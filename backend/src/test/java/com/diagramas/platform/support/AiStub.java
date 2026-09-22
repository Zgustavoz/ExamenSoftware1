package com.diagramas.platform.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Simula el ai-service (HTTP interno) para las pruebas de integración: sin llamadas reales a ningún LLM. */
public final class AiStub {

    public record Response(int status, String body, long delayMillis) {
        public static Response ok(String json) {
            return new Response(200, json, 0);
        }

        public static Response status(int status) {
            return new Response(status, "{\"code\":\"ERR\"}", 0);
        }

        public static Response slow(long millis, String json) {
            return new Response(200, json, millis);
        }
    }

    public static final String KEY = "test-internal-key";

    private final HttpServer server;
    private final AtomicReference<Response> interpret = new AtomicReference<>(Response.status(503));
    private final AtomicReference<Response> sequence = new AtomicReference<>(Response.status(503));
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    public AiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/interpret", ex -> handle(ex, interpret));
        server.createContext("/v1/sequence", ex -> handle(ex, sequence));
        server.start();
    }

    private void handle(com.sun.net.httpserver.HttpExchange ex, AtomicReference<Response> ref) throws IOException {
        lastKey.set(ex.getRequestHeaders().getFirst("X-Internal-Key"));
        lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Response r = ref.get();
        try {
            if (r.delayMillis() > 0) Thread.sleep(r.delayMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(r.status(), body.length);
            ex.getResponseBody().write(body);
        } catch (IOException ignored) {
            // el cliente ya cerró (timeout)
        } finally {
            ex.close();
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void interpretReturns(Response r) {
        interpret.set(r);
    }

    public void sequenceReturns(Response r) {
        sequence.set(r);
    }

    public String lastKey() {
        return lastKey.get();
    }

    public String lastBody() {
        return lastBody.get();
    }
}
