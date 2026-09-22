package com.diagramas.platform.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.common.config.AppProperties;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.copilot.service.AiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** CP-04 (ai-service apagado): conexión rechazada → AI_UNAVAILABLE, sin excepciones internas. */
class AiClientTest {

    @Test
    void servicioApagadoEsAiUnavailable() {
        AppProperties props = new AppProperties(null, "", List.of(), null, new AppProperties.Ai("http://127.0.0.1:1", "k", 2),
                null, null, null, null, null);
        AiClient client = new AiClient(props);
        ObjectMapper m = new ObjectMapper();
        try {
            client.interpret("hola", "TEXTO", m.createObjectNode(), m.createArrayNode());
            throw new AssertionError("debió fallar");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
            assertThat(e.getMessage()).contains("no está disponible");
        }
    }

    /**
     * uvicorn (servidor del ai-service) responde 400 a las peticiones con «Upgrade: h2c», que es lo que el
     * cliente HTTP de Java envía por defecto. El stub de las pruebas de integración lo ignora, así que este
     * servidor imita el comportamiento estricto de uvicorn.
     */
    @Test
    void noPideSubirAHttp2ComoHaceElClientePorDefecto() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        HttpServer strict = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        strict.createContext("/v1/interpret", ex -> {
            upgrade.set(ex.getRequestHeaders().getFirst("Upgrade"));
            boolean rejected = ex.getRequestHeaders().containsKey("Upgrade") || ex.getRequestHeaders().containsKey("Http2-settings");
            byte[] body = (rejected ? "{\"code\":\"BAD\"}" : "{\"explanation\":\"ok\",\"operations\":[]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(rejected ? 400 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        strict.start();
        try {
            AppProperties props = new AppProperties(null, "", List.of(), null,
                    new AppProperties.Ai("http://127.0.0.1:" + strict.getAddress().getPort(), "k", 5), null, null, null, null, null);
            ObjectMapper m = new ObjectMapper();

            var response = new AiClient(props).interpret("hola", "TEXTO", m.createObjectNode(), m.createArrayNode());

            assertThat(upgrade.get()).as("no debe enviarse Upgrade: h2c").isNull();
            assertThat(response.get("explanation").asText()).isEqualTo("ok");
        } finally {
            strict.stop(0);
        }
    }
}
