package com.diagramas.platform.copilot.service;

import static com.diagramas.platform.common.util.Json.text;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramCompleteness;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.diagramas.platform.design.service.DiagramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** CU-20 Generar diagrama de secuencia desde el diagrama de clases (flujo 9.5). */
@Service
public class SequenceService {

    private final DiagramService diagrams;
    private final AiClient ai;
    private final DiagramOperationApplier applier;

    public SequenceService(DiagramService diagrams, AiClient ai, DiagramOperationApplier applier) {
        this.diagrams = diagrams;
        this.ai = ai;
        this.applier = applier;
    }

    public Diagram generate(AuthPrincipal p, UUID sourceDiagramId) {
        // validarDiagramaOrigen
        Diagram source = diagrams.get(p, sourceDiagramId);
        if (!Diagram.TYPE_CLASS.equals(source.getType()) || !DiagramCompleteness.isComplete(source.getContentJson())) {
            throw new ApiException(ErrorCode.DIAGRAM_INCOMPLETE,
                    "El diagrama de clases debe tener clases con atributos o métodos para generar el diagrama de secuencia.");
        }
        // invocarMotorIA → analizarClases
        JsonNode interactions = ai.sequence(source.getContentJson());
        // normalizarSecuencia
        ObjectNode content = normalizeSequence(interactions, source.getContentJson());
        try {
            content = applier.normalizeContent(content);
        } catch (ApiException e) {
            throw new ApiException(ErrorCode.AI_INVALID_RESPONSE, "El asistente devolvió una secuencia inválida.");
        }
        // persistirDiagramaSecuencia
        return diagrams.createWithContent(p, source.getProjectId(), "Secuencia - " + source.getName(),
                Diagram.TYPE_SEQUENCE, content, source.getId());
    }

    ObjectNode normalizeSequence(JsonNode interactions, JsonNode classContent) {
        JsonNode lifelinesIn = interactions.get("lifelines");
        JsonNode messagesIn = interactions.get("messages");
        if (lifelinesIn == null || !lifelinesIn.isArray() || lifelinesIn.isEmpty()
                || messagesIn == null || !messagesIn.isArray() || messagesIn.isEmpty()) {
            throw invalid("La respuesta no contiene líneas de vida y mensajes.");
        }
        Map<String, String> classIdByName = new HashMap<>();
        for (JsonNode c : classContent.path("classes")) {
            classIdByName.put(text(c, "name").toLowerCase(Locale.ROOT), text(c, "id"));
        }

        ArrayNode lifelines = Json.array();
        Map<String, String> lifelineIdByName = new HashMap<>();
        int n = 1;
        for (JsonNode l : lifelinesIn) {
            String name = text(l, "name");
            if (name == null || name.isBlank()) throw invalid("Una línea de vida no tiene nombre.");
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (lifelineIdByName.containsKey(key)) continue;
            ObjectNode ll = Json.object();
            String id = "l" + n++;
            ll.put("id", id);
            ll.put("name", name.trim());
            String className = text(l, "className");
            String classId = className == null ? null : classIdByName.get(className.trim().toLowerCase(Locale.ROOT));
            if (classId == null) classId = classIdByName.get(key);
            if (classId != null) ll.put("classId", classId); else ll.putNull("classId");
            lifelines.add(ll);
            lifelineIdByName.put(key, id);
        }

        List<JsonNode> ordered = new ArrayList<>();
        messagesIn.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(m -> m.path("order").asInt(Integer.MAX_VALUE)));

        ArrayNode messages = Json.array();
        int order = 1;
        for (JsonNode m : ordered) {
            String from = lifelineIdByName.get(String.valueOf(text(m, "from")).trim().toLowerCase(Locale.ROOT));
            String to = lifelineIdByName.get(String.valueOf(text(m, "to")).trim().toLowerCase(Locale.ROOT));
            String name = text(m, "name");
            if (from == null || to == null || name == null || name.isBlank()) {
                throw invalid("Un mensaje referencia una línea de vida inexistente o no tiene nombre.");
            }
            String kind = text(m, "kind") == null ? "SYNC" : text(m, "kind").toUpperCase(Locale.ROOT);
            if (!DiagramOperationApplier.MESSAGE_KINDS.contains(kind)) kind = "SYNC";
            if (kind.equals("SELF")) to = from;
            ObjectNode msg = Json.object();
            msg.put("id", "s" + order);
            msg.put("order", order++);
            msg.put("fromId", from);
            msg.put("toId", to);
            msg.put("name", name.trim());
            msg.put("kind", kind);
            messages.add(msg);
        }

        ObjectNode content = Json.object();
        content.put("schemaVersion", 1);
        content.put("type", "SEQUENCE");
        content.set("lifelines", lifelines);
        content.set("messages", messages);
        return content;
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.AI_INVALID_RESPONSE,
                "El asistente devolvió una secuencia que no se pudo interpretar: " + reason, Map.of("reason", reason));
    }
}
