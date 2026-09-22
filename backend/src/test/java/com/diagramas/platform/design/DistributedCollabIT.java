package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Modo de producción (CU-17 / 10.5): colaboración distribuida con Redis y el broker relay de RabbitMQ.
 * El resto de la suite corre con la implementación local, así que sin esta prueba un fallo de arranque
 * del relay —como faltar reactor-netty-http— no se detectaría hasta el despliegue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DistributedCollabIT {

    private static final String ADMIN_PASSWORD = "Admin#12345";
    private static final int STOMP_PORT = 61613;

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine")
            .withCopyToContainer(
                    Transferable.of("[rabbitmq_management,rabbitmq_stomp].".getBytes(StandardCharsets.UTF_8)),
                    "/etc/rabbitmq/enabled_plugins")
            .withExposedPorts(5672, 15672, STOMP_PORT);

    static {
        POSTGRES.start();
        REDIS.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.jwt.secret", () -> "test-secret-test-secret-test-secret-1234");
        r.add("app.seed-admin-password", () -> ADMIN_PASSWORD);
        r.add("app.bcrypt-strength", () -> "4");
        r.add("app.cors-allowed-origins", () -> "http://localhost:4200");
        // Lo que se quiere ejercitar: Redis para el estado y RabbitMQ como broker relay.
        r.add("app.collab.distributed", () -> "true");
        r.add("app.rabbit.host", RABBIT::getHost);
        r.add("app.rabbit.stomp-port", () -> RABBIT.getMappedPort(STOMP_PORT));
        r.add("app.rabbit.user", RABBIT::getAdminUsername);
        r.add("app.rabbit.password", RABBIT::getAdminPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers(token)), JsonNode.class).getBody();
    }

    private JsonNode graphql(String token, String query, Map<String, Object> variables) {
        return call(HttpMethod.POST, "/graphql", token, Map.of("query", query, "variables", variables));
    }

    @Test
    void elRelayArrancaYDosClientesConvergenAtravesDeRabbitMQ() throws Exception {
        // El contexto arrancó: el broker relay se conectó a RabbitMQ y el health de Redis está activo.
        assertThat(call(HttpMethod.GET, "/actuator/health", null, null).get("status").asText()).isEqualTo("UP");

        String admin = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "platform", "username", "admin", "password", ADMIN_PASSWORD))
                .get("token").asText();
        String companyId = call(HttpMethod.POST, "/api/companies", admin, Map.of("name", "Distribuida", "slug", "dist"))
                .get("id").asText();
        call(HttpMethod.POST, "/api/companies/" + companyId + "/admins", admin,
                Map.of("username", "boss", "email", "boss@dist.test", "password", "Password#123"));
        String boss = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "dist", "username", "boss", "password", "Password#123")).get("token").asText();
        for (String name : List.of("d1", "d2")) {
            call(HttpMethod.POST, "/api/users", boss, Map.of("username", name, "email", name + "@dist.test",
                    "password", "Password#123", "roles", List.of("DESIGNER")));
        }
        String t1 = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "dist", "username", "d1", "password", "Password#123")).get("token").asText();
        String t2 = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "dist", "username", "d2", "password", "Password#123")).get("token").asText();

        String projectId = call(HttpMethod.POST, "/api/projects", t1, Map.of("name", "P", "description", "d"))
                .get("id").asText();
        String diagramId = graphql(t1, "mutation($p: ID!) { createDiagram(projectId: $p, name: \"Colab\") { id } }",
                Map.of("p", projectId)).get("data").get("createDiagram").get("id").asText();

        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        StompSession a = connect(t1);
        StompSession b = connect(t2);
        b.subscribe("/topic/diagram." + diagramId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        Thread.sleep(1500); // el relay necesita propagar la suscripción a RabbitMQ

        a.send("/app/diagram/" + diagramId + "/join", Map.of());
        a.send("/app/diagram/" + diagramId + "/op", Map.of("op", Map.of(
                "op", "ADD_CLASS",
                "class", Map.of("name", "Cliente", "x", 10, "y", 10,
                        "attributes", List.of(Map.of("name", "nombre", "type", "String"))))));

        JsonNode op = poll(received, "OP");
        assertThat(op.get("op").get("class").get("name").asText()).isEqualTo("Cliente");
        assertThat(op.get("version").asInt()).isEqualTo(2);

        a.disconnect();
        b.disconnect();
    }

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders h = new StompHeaders();
        h.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), h,
                new StompSessionHandlerAdapter() {}).get(15, TimeUnit.SECONDS);
    }

    private JsonNode poll(BlockingQueue<JsonNode> queue, String type) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode m = queue.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.path("type").asText())) return m;
        }
        throw new AssertionError("No llegó ningún mensaje " + type + " a través del broker relay");
    }
}
