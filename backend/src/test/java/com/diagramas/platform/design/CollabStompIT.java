package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Ciclo C4 — CU-17: CP-09 (dos clientes convergen), locks por elemento, roles y aislamiento por empresa en STOMP. */
class CollabStompIT extends AbstractIntegrationTest {

    @LocalServerPort int port;

    private record Client(StompSession session, BlockingQueue<JsonNode> topic, BlockingQueue<JsonNode> errors) {
        void send(String diagramId, String action, Object body) {
            session.send("/app/diagram/" + diagramId + "/" + action, body);
        }

        void op(String diagramId, Map<String, Object> op) {
            send(diagramId, "op", Map.of("op", op));
        }
    }

    private StompSession open(String token) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        return stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
    }

    private StompFrameHandler into(BlockingQueue<JsonNode> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((JsonNode) payload);
            }
        };
    }

    private Client connect(String token, String diagramId) throws Exception {
        StompSession s = open(token);
        BlockingQueue<JsonNode> topic = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> errors = new LinkedBlockingQueue<>();
        s.subscribe("/topic/diagram." + diagramId, into(topic));
        s.subscribe("/user/queue/errors", into(errors));
        Thread.sleep(800); // deja que el broker registre las suscripciones antes de emitir
        return new Client(s, topic, errors);
    }

    private JsonNode next(BlockingQueue<JsonNode> queue, Predicate<JsonNode> match) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode m = queue.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && match.test(m)) return m;
        }
        throw new AssertionError("No llegó el mensaje esperado");
    }

    private JsonNode nextOfType(BlockingQueue<JsonNode> q, String type) throws Exception {
        return next(q, m -> type.equals(m.path("type").asText()));
    }

    private JsonNode nextError(BlockingQueue<JsonNode> q, String code) throws Exception {
        return next(q, m -> code.equals(m.path("code").asText()));
    }

    private record Setup(Tenant t, String diagramId) {}

    private Setup setup() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        String id = createDiagram(t.designerToken(), project, "Colab").get("id").asText();
        JsonNode saved = saveDiagram(t.designerToken(), id, Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "c1", "name", "Cliente", "x", 10, "y", 10, "attributes", List.of(Map.of("name", "n", "type", "String"))),
                Map.of("id", "c2", "name", "Pedido", "x", 200, "y", 10, "attributes", List.of(Map.of("name", "t", "type", "int")))),
                "relationships", List.of()), 1);
        assertThat(saved.has("errors")).as(saved.toString()).isFalse();
        return new Setup(t, id); // versión 2
    }

    private int versionInDb(String id) {
        return jdbc.queryForObject("select version from diagrams where id = ?::uuid", Integer.class, id);
    }

    private String userId(String token) {
        return call(org.springframework.http.HttpMethod.GET, "/api/auth/me", token, null).getBody().get("id").asText();
    }

    @Test
    void cp09_bVeLaClaseQueAgregaASinRefrescar() throws Exception {
        Setup s = setup();
        Client a = connect(s.t().designerToken(), s.diagramId());
        Client b = connect(s.t().designer2Token(), s.diagramId());
        a.send(s.diagramId(), "join", Map.of());
        b.send(s.diagramId(), "join", Map.of());
        JsonNode join = next(b.topic(), m -> "JOIN".equals(m.path("type").asText()) && m.path("participants").size() == 2);
        List<String> names = join.get("participants").findValuesAsText("username");
        assertThat(names).containsExactlyInAnyOrder("designer", "designer2");

        a.op(s.diagramId(), Map.of("op", "ADD_CLASS", "class", Map.of("name", "Factura", "x", 400, "y", 60,
                "attributes", List.of(Map.of("name", "numero", "type", "int")))));

        JsonNode seenByB = nextOfType(b.topic(), "OP");
        assertThat(seenByB.get("userId").asText()).isEqualTo(userId(s.t().designerToken()));
        assertThat(seenByB.get("version").asInt()).isEqualTo(3);
        assertThat(seenByB.get("op").get("op").asText()).isEqualTo("ADD_CLASS");
        assertThat(seenByB.get("op").get("class").get("name").asText()).isEqualTo("Factura");
        assertThat(seenByB.get("op").get("class").get("id").asText()).isNotBlank(); // el servidor difunde el ID asignado
        assertThat(seenByB.get("diagramId").asText()).isEqualTo(s.diagramId());
        assertThat(nextOfType(a.topic(), "OP").get("version").asInt()).isEqualTo(3); // el emisor también recibe el ack

        // y quedó persistido (B, al recargar por GraphQL, ve el mismo estado)
        JsonNode reloaded = getDiagram(s.t().designer2Token(), s.diagramId());
        assertThat(reloaded.get("version").asInt()).isEqualTo(3);
        assertThat(reloaded.get("contentJson").get("classes")).hasSize(3);
        a.session().disconnect();
        b.session().disconnect();
    }

    @Test
    void operacionesConcurrentesSeSerializanConVersionesConsecutivas() throws Exception {
        Setup s = setup();
        Client a = connect(s.t().designerToken(), s.diagramId());
        Client b = connect(s.t().designer2Token(), s.diagramId());
        a.send(s.diagramId(), "join", Map.of());
        b.send(s.diagramId(), "join", Map.of());
        for (int i = 0; i < 5; i++) {
            a.op(s.diagramId(), Map.of("op", "ADD_CLASS", "class", Map.of("name", "A" + i, "x", 10, "y", 10)));
            b.op(s.diagramId(), Map.of("op", "ADD_CLASS", "class", Map.of("name", "B" + i, "x", 20, "y", 20)));
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> versionInDb(s.diagramId()) == 12);
        JsonNode c = getDiagram(s.t().designerToken(), s.diagramId()).get("contentJson");
        assertThat(c.get("classes")).hasSize(12);
        a.session().disconnect();
        b.session().disconnect();
    }

    @Test
    void cp01_relacionDuplicadaPorWebSocket_noModificaNada() throws Exception {
        Setup s = setup();
        Client a = connect(s.t().designerToken(), s.diagramId());
        a.send(s.diagramId(), "join", Map.of());
        Map<String, Object> rel = Map.of("op", "ADD_RELATIONSHIP",
                "relationship", Map.of("type", "ASSOCIATION", "sourceId", "c1", "targetId", "c2"));
        a.op(s.diagramId(), rel);
        nextOfType(a.topic(), "OP");
        assertThat(versionInDb(s.diagramId())).isEqualTo(3);

        a.op(s.diagramId(), rel);
        JsonNode err = nextError(a.errors(), "DUPLICATE_RELATIONSHIP");
        assertThat(err.get("message").asText()).isEqualTo("La conexión entre esas clases ya existe.");
        assertThat(versionInDb(s.diagramId())).isEqualTo(3);
        a.session().disconnect();
    }

    @Test
    void locksPorElemento_elConflictoDevuelveElementLocked() throws Exception {
        Setup s = setup();
        Client a = connect(s.t().designerToken(), s.diagramId());
        Client b = connect(s.t().designer2Token(), s.diagramId());
        a.send(s.diagramId(), "join", Map.of());
        b.send(s.diagramId(), "join", Map.of());

        a.send(s.diagramId(), "lock", Map.of("elementId", "c1"));
        JsonNode lock = next(b.topic(), m -> "LOCK".equals(m.path("type").asText()));
        assertThat(lock.get("elementId").asText()).isEqualTo("c1");
        assertThat(lock.get("username").asText()).isEqualTo("designer"); // la UI muestra quién edita

        int before = versionInDb(s.diagramId());
        b.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 500, "y", 500));
        JsonNode err = nextError(b.errors(), "ELEMENT_LOCKED");
        assertThat(err.get("message").asText()).contains("designer");
        assertThat(versionInDb(s.diagramId())).isEqualTo(before);

        // otro elemento no bloqueado sí se puede editar
        b.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c2", "x", 300, "y", 300));
        assertThat(nextOfType(b.topic(), "OP").get("elementId").asText()).isEqualTo("c2");

        // el dueño edita su propio elemento bloqueado sin problema
        a.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 11, "y", 11));
        nextOfType(b.topic(), "OP");

        // al liberar el lock, B puede editar
        a.send(s.diagramId(), "unlock", Map.of("elementId", "c1"));
        nextOfType(b.topic(), "UNLOCK");
        b.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 500, "y", 500));
        assertThat(nextOfType(b.topic(), "OP").get("op").get("x").asInt()).isEqualTo(500);
        a.session().disconnect();
        b.session().disconnect();
    }

    @Test
    void alDesconectarseSeLiberanSusLocks() throws Exception {
        Setup s = setup();
        Client a = connect(s.t().designerToken(), s.diagramId());
        Client b = connect(s.t().designer2Token(), s.diagramId());
        a.send(s.diagramId(), "join", Map.of());
        b.send(s.diagramId(), "join", Map.of());
        a.send(s.diagramId(), "lock", Map.of("elementId", "c2"));
        nextOfType(b.topic(), "LOCK");

        a.session().disconnect();
        JsonNode unlock = nextOfType(b.topic(), "UNLOCK");
        assertThat(unlock.get("elementId").asText()).isEqualTo("c2");
        JsonNode leave = nextOfType(b.topic(), "LEAVE");
        assertThat(leave.get("participants")).hasSize(1);

        b.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c2", "x", 1, "y", 1));
        assertThat(nextOfType(b.topic(), "OP").get("elementId").asText()).isEqualTo("c2");
        b.session().disconnect();
    }

    @Test
    void developerPuedeObservarPeroNoEditar() throws Exception {
        Setup s = setup();
        Client dev = connect(s.t().developerToken(), s.diagramId());
        Client des = connect(s.t().designerToken(), s.diagramId());
        dev.send(s.diagramId(), "join", Map.of());
        nextOfType(dev.topic(), "JOIN");

        dev.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 5, "y", 5));
        nextError(dev.errors(), "FORBIDDEN");
        dev.send(s.diagramId(), "lock", Map.of("elementId", "c1"));
        nextError(dev.errors(), "FORBIDDEN");

        // pero recibe los cambios del diseñador en tiempo real
        des.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 77, "y", 5));
        assertThat(nextOfType(dev.topic(), "OP").get("op").get("x").asInt()).isEqualTo(77);
        dev.session().disconnect();
        des.session().disconnect();
    }

    @Test
    void aislamientoMultiTenant_noSePuedeSuscribirNiUnirseAUnDiagramaAjeno() throws Exception {
        Setup s = setup();
        Tenant other = newTenant();

        // SUBSCRIBE a un diagrama de otra empresa: el servidor corta la conexión
        StompSession intruder = open(other.designerToken());
        BlockingQueue<JsonNode> spy = new LinkedBlockingQueue<>();
        intruder.subscribe("/topic/diagram." + s.diagramId(), into(spy));
        await().atMost(Duration.ofSeconds(20)).until(() -> !intruder.isConnected());

        // JOIN a un diagrama ajeno: NOT_FOUND (no se revela su existencia)
        Setup own = setup(); // el intruso trabaja en SU diagrama, pero intenta operar sobre el ajeno
        Client b = connect(own.t().designerToken(), own.diagramId());
        b.send(s.diagramId(), "join", Map.of());
        nextError(b.errors(), "NOT_FOUND");
        b.op(s.diagramId(), Map.of("op", "MOVE_CLASS", "classId", "c1", "x", 5, "y", 5));
        nextError(b.errors(), "NOT_FOUND");
        assertThat(spy).isEmpty();
        assertThat(versionInDb(s.diagramId())).isEqualTo(2);
        b.session().disconnect();
    }

    @Test
    void conexionSinTokenOTokenInvalidoSeRechaza() {
        assertThatThrownBy(() -> open(null)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> open("token.invalido.xyz")).isInstanceOf(Exception.class);
    }

}
