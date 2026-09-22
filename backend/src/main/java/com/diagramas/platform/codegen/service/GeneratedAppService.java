package com.diagramas.platform.codegen.service;

import com.diagramas.platform.codegen.generator.JavaSpringBootGenerator;
import com.diagramas.platform.codegen.service.GeneratedAppCommand.Call;
import com.diagramas.platform.codegen.service.GeneratedAppCommand.Entity;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.copilot.service.AiClient;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Ejecuta una orden hablada contra el backend generado a partir de un diagrama (demostración del móvil).
 *
 * <p>La orden se traduce a una llamada HTTP con el asistente; si no está configurado o falla, se usa el
 * intérprete local de {@link GeneratedAppCommand}, de modo que la demostración no dependa del LLM. La
 * llamada resultante se ejecuta contra la aplicación generada y se devuelve lo que respondió.
 */
@Service
public class GeneratedAppService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedAppService.class);

    /** Lo que se devuelve al cliente móvil. */
    public record CommandResult(
            String explanation, String method, String path, int status, boolean usedAi, JsonNode response) {}

    private final DiagramService diagrams;
    private final AiClient ai;
    private final RestClient client;
    private final String targetUrl;

    public GeneratedAppService(
            DiagramService diagrams,
            AiClient ai,
            @Value("${app.generated-app.url:http://host.docker.internal:8090}") String targetUrl) {
        this.diagrams = diagrams;
        this.ai = ai;
        this.targetUrl = targetUrl;
        this.client = RestClient.builder()
                .baseUrl(targetUrl)
                .requestFactory(factory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(15));
        return f;
    }

    @Transactional(readOnly = true)
    public CommandResult execute(AuthPrincipal p, UUID diagramId, String instruction) {
        String orden = instruction == null ? "" : instruction.trim();
        if (orden.isEmpty()) {
            throw ApiException.validation("Diga o escriba qué quiere registrar.");
        }

        Diagram diagram = diagrams.get(p, diagramId);
        List<Entity> entities = GeneratedAppCommand.entitiesOf(
                diagram.getContentJson(), name -> "/api/" + JavaSpringBootGenerator.resourcePath(pascal(name)));
        if (entities.isEmpty()) {
            throw new ApiException(
                    ErrorCode.DIAGRAM_INCOMPLETE, "El diagrama no tiene clases que el backend generado exponga.");
        }

        boolean usedAi = true;
        Call call = askAi(orden, entities);
        if (call == null) {
            usedAi = false;
            call = GeneratedAppCommand.parse(orden, entities);
        }
        if (call == null) {
            throw ApiException.validation(
                    "No entendí la orden. Pruebe con «registra un " + entities.get(0).name().toLowerCase()
                            + " con " + primerCampo(entities.get(0)) + " ...».");
        }
        assertPathIsKnown(call, entities);
        return send(call, usedAi);
    }

    /** Pregunta al asistente; devuelve `null` si no está disponible, para que actúe el respaldo local. */
    private Call askAi(String instruction, List<Entity> entities) {
        try {
            ArrayNode json = Json.array();
            for (Entity e : entities) {
                json.add(GeneratedAppCommand.toJson(e));
            }
            JsonNode r = ai.command(instruction, json);
            Map<String, Object> body = new LinkedHashMap<>();
            JsonNode node = r.path("body");
            node.fields().forEachRemaining(e -> body.put(e.getKey(), Json.plain(e.getValue())));
            return new Call(
                    Json.text(r, "explanation"), Json.text(r, "method"), Json.text(r, "path"), body);
        } catch (ApiException e) {
            // El asistente no está configurado o falló: se sigue con el intérprete local.
            log.info("El asistente no pudo traducir la orden ({}); se usa el intérprete local", e.code());
            return null;
        }
    }

    /** La ruta tiene que ser una de las que expone el backend generado: nada de rutas inventadas. */
    private static void assertPathIsKnown(Call call, List<Entity> entities) {
        boolean conocida = entities.stream().anyMatch(e -> call.path().equals(e.path())
                || call.path().startsWith(e.path() + "/"));
        if (!conocida) {
            throw new ApiException(
                    ErrorCode.AI_INVALID_RESPONSE, "La orden apunta a un recurso que el backend generado no expone.");
        }
    }

    private CommandResult send(Call call, boolean usedAi) {
        HttpMethod method = HttpMethod.valueOf(call.method());
        boolean conCuerpo = method == HttpMethod.POST || method == HttpMethod.PUT;
        try {
            RestClient.RequestHeadersSpec<?> spec = conCuerpo
                    ? client.method(method).uri(call.path()).contentType(MediaType.APPLICATION_JSON).body(call.body())
                    : client.method(method).uri(call.path());
            var response = spec.accept(MediaType.APPLICATION_JSON).retrieve().toEntity(JsonNode.class);
            return new CommandResult(
                    call.explanation(), call.method(), call.path(), response.getStatusCode().value(), usedAi,
                    response.getBody());
        } catch (RestClientResponseException e) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "El backend generado rechazó la petición (" + e.getStatusCode().value() + ").",
                    Map.of("path", call.path(), "status", e.getStatusCode().value()));
        } catch (Exception e) {
            log.warn("No se pudo contactar con el backend generado en {}", targetUrl);
            throw new ApiException(
                    ErrorCode.AI_UNAVAILABLE,
                    "No se pudo contactar con el backend generado. Compruebe que esté en marcha.",
                    Map.of("url", targetUrl));
        }
    }

    private static String primerCampo(Entity entity) {
        return entity.fields().isEmpty() ? "nombre" : entity.fields().get(0).name();
    }

    /** El generador usa el nombre de la clase en PascalCase para derivar la ruta. */
    private static String pascal(String name) {
        String limpio = name.trim().replaceAll("[^A-Za-z0-9]", " ");
        StringBuilder sb = new StringBuilder();
        for (String parte : limpio.split("\\s+")) {
            if (!parte.isEmpty()) sb.append(Character.toUpperCase(parte.charAt(0))).append(parte.substring(1));
        }
        return sb.isEmpty() ? name : sb.toString();
    }

    /** Las entidades que el backend generado expone, para que el cliente sepa qué puede pedir. */
    @Transactional(readOnly = true)
    public List<Entity> entities(AuthPrincipal p, UUID diagramId) {
        Diagram diagram = diagrams.get(p, diagramId);
        return GeneratedAppCommand.entitiesOf(
                diagram.getContentJson(), name -> "/api/" + JavaSpringBootGenerator.resourcePath(pascal(name)));
    }

    public String targetUrl() {
        return targetUrl;
    }

    /** Para armar la respuesta al cliente sin exponer detalles internos. */
    public static ObjectNode describe(CommandResult r) {
        ObjectNode node = Json.object();
        node.put("explanation", r.explanation());
        node.put("method", r.method());
        node.put("path", r.path());
        node.put("status", r.status());
        node.put("usedAi", r.usedAi());
        node.set("response", r.response() == null ? Json.object() : r.response());
        return node;
    }
}
