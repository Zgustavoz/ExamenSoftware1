package com.diagramas.platform.copilot.service;

import com.diagramas.platform.common.config.AppProperties;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente HTTP interno hacia {@code ai-service}. Traduce fallos a AI_TIMEOUT / AI_UNAVAILABLE /
 * AI_INVALID_RESPONSE. Nunca registra instrucciones ni diagramas.
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final RestClient client;

    public AiClient(AppProperties props) {
        // HTTP/1.1 explícito: por defecto el cliente de Java intenta subir a HTTP/2 con «Upgrade: h2c» y
        // uvicorn (el servidor del ai-service) rechaza esa petición con un 400 sin llegar a atenderla.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(props.ai().timeoutSeconds()));
        this.client = RestClient.builder()
                .baseUrl(props.ai().url())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Key", props.ai().internalKey() == null ? "" : props.ai().internalKey())
                .build();
    }

    /** POST /v1/interpret → {explanation, operations[]} */
    public JsonNode interpret(String instruction, String inputType, JsonNode contentJson, JsonNode history) {
        ObjectNode body = Json.object();
        body.put("instruction", instruction);
        body.put("inputType", inputType);
        body.set("contentJson", contentJson);
        body.set("history", history);
        return post("/v1/interpret", body);
    }

    /** POST /v1/sequence → {lifelines[], messages[]} */
    public JsonNode sequence(JsonNode contentJson) {
        ObjectNode body = Json.object();
        body.set("contentJson", contentJson);
        return post("/v1/sequence", body);
    }

    /** POST /v1/command → {explanation, method, path, body}: la llamada que cumple una orden hablada. */
    public JsonNode command(String instruction, JsonNode entities) {
        ObjectNode body = Json.object();
        body.put("instruction", instruction);
        body.set("entities", entities);
        return post("/v1/command", body);
    }

    private JsonNode post(String path, JsonNode body) {
        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.isObject()) {
                throw invalid();
            }
            return response;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("ai-service respondió HTTP {} en {}", status, path);
            if (status == 504) throw new ApiException(ErrorCode.AI_TIMEOUT, "El asistente tardó demasiado en responder. Intente nuevamente.");
            if (status == 502 || status == 422) throw invalid();
            throw unavailable();
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                log.warn("Timeout llamando a ai-service en {}", path);
                throw new ApiException(ErrorCode.AI_TIMEOUT, "El asistente tardó demasiado en responder. Intente nuevamente.");
            }
            log.warn("ai-service inalcanzable en {}", path);
            throw unavailable();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // cuerpo no JSON u otro fallo de deserialización
            throw invalid();
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            if (t instanceof IOException io && io.getMessage() != null && io.getMessage().toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.AI_INVALID_RESPONSE, "El asistente devolvió una respuesta que no se pudo interpretar.");
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.AI_UNAVAILABLE, "El asistente no está disponible en este momento. Su diagrama no fue modificado.");
    }
}
