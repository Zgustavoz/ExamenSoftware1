package com.diagramas.platform.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.diagramas.platform.support.AiStub;
import com.diagramas.platform.support.AiStub.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ciclo C3: CU-12 (CP-03, CP-04), CU-13 y CU-20, con el ai-service simulado. */
class CopilotIT extends AbstractIntegrationTest {

    private static final String SEND = "mutation($d: ID!, $i: String!, $t: InputType!) { sendAiInstruction(diagramId: $d, instruction: $i, inputType: $t) "
            + "{ explanation operations diagram { id version contentJson } } }";

    private static final String CLIENTE_OK = """
            {"explanation":"Agregué la clase Cliente con nombre y email",
             "operations":[{"op":"ADD_CLASS","class":{"name":"Cliente",
               "attributes":[{"name":"nombre","type":"String"},{"name":"email","type":"string"}]}}]}""";

    private record Ctx(Tenant t, String project, String diagramId) {}

    private Ctx ctx() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        return new Ctx(t, project, createDiagram(t.designerToken(), project, "D").get("id").asText());
    }

    private JsonNode send(Ctx c, String instruction) {
        return graphql(c.t().designerToken(), SEND, Map.of("d", c.diagramId(), "i", instruction, "t", "TEXTO"));
    }

    @Test
    void cp03_generaClaseDesdeTextoNormalizadaYPersistida() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok(CLIENTE_OK));

        JsonNode r = send(c, "agrega una clase Cliente con nombre y email");
        assertThat(r.has("errors")).as(r.toString()).isFalse();
        JsonNode result = r.get("data").get("sendAiInstruction");
        assertThat(result.get("explanation").asText()).contains("Cliente");
        JsonNode cls = result.get("diagram").get("contentJson").get("classes").get(0);
        assertThat(cls.get("name").asText()).isEqualTo("Cliente");
        assertThat(cls.get("id").asText()).isNotBlank();                       // IDs asignados
        assertThat(cls.get("attributes")).hasSize(2);
        assertThat(cls.get("attributes").get(0).get("visibility").asText()).isEqualTo("PRIVATE"); // por defecto
        assertThat(cls.get("attributes").get(1).get("type").asText()).isEqualTo("String");         // «string» normalizado
        assertThat(cls.has("x") && cls.has("y")).isTrue();                     // posición en grilla
        assertThat(result.get("diagram").get("version").asInt()).isEqualTo(2);

        // persistido en BD y con la clave interna enviada al ai-service
        assertThat(getDiagram(c.t().designerToken(), c.diagramId()).get("contentJson").get("classes")).hasSize(1);
        assertThat(AI.lastKey()).isEqualTo(AiStub.KEY);
        assertThat(result.get("operations").get(0).get("op").asText()).isEqualTo("ADD_CLASS");
    }

    @Test
    void cp03_confirmarEsIdempotente() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok(CLIENTE_OK));
        send(c, "agrega Cliente");
        String q = "mutation($d: ID!) { confirmAiChanges(diagramId: $d) { id version contentJson } }";
        JsonNode a = graphql(c.t().designerToken(), q, Map.of("d", c.diagramId())).get("data").get("confirmAiChanges");
        JsonNode b = graphql(c.t().designerToken(), q, Map.of("d", c.diagramId())).get("data").get("confirmAiChanges");
        assertThat(a).isEqualTo(b);
        assertThat(a.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void referenciasPorNombreSeResuelvenAId_relacionesYAtributos() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok("""
                {"explanation":"ok","operations":[
                  {"op":"ADD_CLASS","class":{"name":"Cliente","attributes":[{"name":"nombre","type":"String"}]}},
                  {"op":"ADD_CLASS","class":{"name":"Pedido","attributes":[{"name":"cliente","type":"cliente"},{"name":"total","type":"decimal"}]}},
                  {"op":"ADD_RELATIONSHIP","relationship":{"type":"ASSOCIATION","source":"cliente","target":"PEDIDO","sourceMultiplicity":"1","targetMultiplicity":"0..*"}},
                  {"op":"ADD_METHOD","className":"Pedido","method":{"name":"calcular","returnType":"decimal"}}
                ]}"""));
        JsonNode content = send(c, "crea cliente y pedido").get("data").get("sendAiInstruction").get("diagram").get("contentJson");
        JsonNode cliente = content.get("classes").get(0);
        JsonNode pedido = content.get("classes").get(1);
        assertThat(pedido.get("attributes").get(0).get("type").asText()).isEqualTo("Cliente");    // mayúsculas corregidas
        assertThat(pedido.get("attributes").get(1).get("type").asText()).isEqualTo("BigDecimal"); // sinónimo
        assertThat(pedido.get("methods").get(0).get("returnType").asText()).isEqualTo("BigDecimal");
        JsonNode rel = content.get("relationships").get(0);
        assertThat(rel.get("sourceId").asText()).isEqualTo(cliente.get("id").asText());
        assertThat(rel.get("targetId").asText()).isEqualTo(pedido.get("id").asText());

        // segunda instrucción: modificar y eliminar por nombre
        AI.interpretReturns(Response.ok("""
                {"explanation":"ok","operations":[
                  {"op":"UPDATE_ATTRIBUTE","className":"Cliente","attributeName":"nombre","changes":{"name":"nombreCompleto"}},
                  {"op":"REMOVE_RELATIONSHIP","source":"Cliente","target":"Pedido"},
                  {"op":"MOVE_CLASS","className":"Pedido","x":500,"y":40}
                ]}"""));
        JsonNode after = send(c, "renombra").get("data").get("sendAiInstruction").get("diagram").get("contentJson");
        assertThat(after.get("classes").get(0).get("attributes").get(0).get("name").asText()).isEqualTo("nombreCompleto");
        assertThat(after.get("relationships")).isEmpty();
        assertThat(after.get("classes").get(1).get("x").asInt()).isEqualTo(500);
    }

    // ---------------------------------------------------------------- CP-04 y ramas ALT

    private void assertDiagramIntact(Ctx c, int version) {
        JsonNode d = getDiagram(c.t().designerToken(), c.diagramId());
        assertThat(d.get("version").asInt()).isEqualTo(version);
        assertThat(d.get("contentJson").get("classes")).isEmpty();
    }

    @Test
    void cp04_servicioIaCaido() {
        Ctx c = ctx();
        AI.interpretReturns(Response.status(503));
        JsonNode r = send(c, "agrega Cliente");
        assertThat(errorCode(r)).isEqualTo("AI_UNAVAILABLE");
        assertThat(r.get("errors").get(0).get("message").asText()).contains("no está disponible");
        assertDiagramIntact(c, 1);
    }

    @Test
    void cp04_timeout() {
        Ctx c = ctx();
        AI.interpretReturns(Response.slow(3500, CLIENTE_OK)); // el cliente espera 2 s
        JsonNode r = send(c, "agrega Cliente");
        assertThat(errorCode(r)).isEqualTo("AI_TIMEOUT");
        assertDiagramIntact(c, 1);
    }

    @Test
    void cp04_iaDevuelve504Timeout_o502Invalida() {
        Ctx c = ctx();
        AI.interpretReturns(Response.status(504));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_TIMEOUT");
        AI.interpretReturns(Response.status(502));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        assertDiagramIntact(c, 1);
    }

    @Test
    void cp04_respuestaNoJson() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok("no es json {"));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        assertDiagramIntact(c, 1);
    }

    @Test
    void respuestaIaInvalida_noTocaElDiagrama() {
        Ctx c = ctx();
        // sin lista de operaciones
        AI.interpretReturns(Response.ok("{\"explanation\":\"hola\"}"));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        // operación desconocida
        AI.interpretReturns(Response.ok("{\"explanation\":\"x\",\"operations\":[{\"op\":\"HACKEAR\"}]}"));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        // relación con una clase inexistente
        AI.interpretReturns(Response.ok("{\"explanation\":\"x\",\"operations\":[{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\"}},"
                + "{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"ASSOCIATION\",\"source\":\"A\",\"target\":\"Fantasma\"}}]}"));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        // tipo de dato inválido: el backend revalida y rechaza TODO el lote
        AI.interpretReturns(Response.ok("{\"explanation\":\"x\",\"operations\":[{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\","
                + "\"attributes\":[{\"name\":\"n\",\"type\":\"Inventado\"}]}}]}"));
        JsonNode r = send(c, "x");
        assertThat(errorCode(r)).isEqualTo("AI_INVALID_RESPONSE");
        assertThat(r.get("errors").get(0).get("extensions").get("details").get("reason").asText()).isEqualTo("INVALID_DATATYPE");
        // herencia circular propuesta por la IA
        AI.interpretReturns(Response.ok("{\"explanation\":\"x\",\"operations\":[{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\"}},{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"B\"}},"
                + "{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"GENERALIZATION\",\"source\":\"A\",\"target\":\"B\"}},"
                + "{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"GENERALIZATION\",\"source\":\"B\",\"target\":\"A\"}}]}"));
        assertThat(errorCode(send(c, "x"))).isEqualTo("AI_INVALID_RESPONSE");
        assertDiagramIntact(c, 1);
    }

    @Test
    void instruccionVaciaNoLlamaALaIa() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok(CLIENTE_OK));
        String before = AI.lastBody();
        JsonNode r = send(c, "   ");
        assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        assertThat(AI.lastBody()).isEqualTo(before);
        assertDiagramIntact(c, 1);
    }

    @Test
    void soloDesignerUsaElAsistente() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok(CLIENTE_OK));
        JsonNode r = graphql(c.t().developerToken(), SEND, Map.of("d", c.diagramId(), "i", "hola", "t", "VOZ"));
        assertThat(errorCode(r)).isEqualTo("FORBIDDEN");
    }

    @Test
    void aislamientoMultiTenantEnElAsistente() {
        Ctx c = ctx();
        Tenant other = newTenant();
        AI.interpretReturns(Response.ok(CLIENTE_OK));
        JsonNode r = graphql(other.designerToken(), SEND, Map.of("d", c.diagramId(), "i", "hola", "t", "TEXTO"));
        assertThat(errorCode(r)).isEqualTo("NOT_FOUND");
    }

    // ---------------------------------------------------------------- CU-13

    @Test
    void cu13_historialConContextoYPrivadoPorUsuario() {
        Ctx c = ctx();
        AI.interpretReturns(Response.ok(CLIENTE_OK));
        send(c, "primera instruccion");
        AI.interpretReturns(Response.ok("{\"explanation\":\"listo\",\"operations\":[]}"));
        send(c, "segunda instruccion");
        // la segunda llamada al ai-service incluye el historial previo como contexto
        assertThat(AI.lastBody()).contains("primera instruccion").contains("Agregué la clase Cliente");

        String q = "query($d: ID!) { aiChats(diagramId: $d) { id messages } }";
        JsonNode chats = graphql(c.t().designerToken(), q, Map.of("d", c.diagramId())).get("data").get("aiChats");
        assertThat(chats).hasSize(1);
        JsonNode messages = chats.get(0).get("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("primera instruccion");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("listo");
        assertThat(messages.get(0).get("timestamp").asText()).isNotBlank();

        // otro diseñador de la misma empresa no ve la conversación ajena
        JsonNode mine = graphql(c.t().designer2Token(), q, Map.of("d", c.diagramId())).get("data").get("aiChats");
        assertThat(mine).isEmpty();
        // diagrama inexistente
        JsonNode missing = graphql(c.t().designerToken(), q, Map.of("d", "00000000-0000-0000-0000-000000000000"));
        assertThat(errorCode(missing)).isEqualTo("NOT_FOUND");
    }

    @Test
    void mensajeDelUsuarioQuedaRegistradoAunqueLaIaFalle() {
        Ctx c = ctx();
        AI.interpretReturns(Response.status(503));
        send(c, "esto falla");
        JsonNode chats = graphql(c.t().designerToken(), "query($d: ID!) { aiChats(diagramId: $d) { messages } }",
                Map.of("d", c.diagramId())).get("data").get("aiChats");
        assertThat(chats.get(0).get("messages")).hasSize(1);
        assertThat(chats.get(0).get("messages").get(0).get("content").asText()).isEqualTo("esto falla");
    }

    // ---------------------------------------------------------------- CU-20

    private void completeClassDiagram(Ctx c) {
        saveDiagram(c.t().designerToken(), c.diagramId(), Map.of("schemaVersion", 1, "type", "CLASS",
                "classes", List.of(
                        Map.of("id", "c1", "name", "Cliente", "x", 10, "y", 10,
                                "attributes", List.of(Map.of("name", "nombre", "type", "String")),
                                "methods", List.of(Map.of("name", "crearPedido", "returnType", "Pedido"))),
                        Map.of("id", "c2", "name", "Pedido", "x", 300, "y", 10,
                                "attributes", List.of(Map.of("name", "total", "type", "BigDecimal")), "methods", List.of())),
                "relationships", List.of()), 1);
    }

    private static final String SEQ = "mutation($s: ID!) { generateSequenceDiagram(sourceDiagramId: $s) { id type sourceDiagramId contentJson name } }";

    @Test
    void cu20_generaDiagramaDeSecuenciaDesdeClases() {
        Ctx c = ctx();
        completeClassDiagram(c);
        AI.sequenceReturns(Response.ok("""
                {"lifelines":[{"name":"Cliente","className":"Cliente"},{"name":"Pedido","className":"Pedido"}],
                 "messages":[{"order":2,"from":"Pedido","to":"Cliente","name":"pedidoCreado","kind":"RETURN"},
                             {"order":1,"from":"cliente","to":"Pedido","name":"crearPedido","kind":"SYNC"}]}"""));
        JsonNode r = graphql(c.t().designerToken(), SEQ, Map.of("s", c.diagramId()));
        assertThat(r.has("errors")).as(r.toString()).isFalse();
        JsonNode seq = r.get("data").get("generateSequenceDiagram");
        assertThat(seq.get("type").asText()).isEqualTo("SEQUENCE");
        assertThat(seq.get("sourceDiagramId").asText()).isEqualTo(c.diagramId());
        JsonNode content = seq.get("contentJson");
        assertThat(content.get("lifelines")).hasSize(2);
        assertThat(content.get("lifelines").get(0).get("classId").asText()).isEqualTo("c1");
        JsonNode m1 = content.get("messages").get(0);
        assertThat(m1.get("name").asText()).isEqualTo("crearPedido");     // ordenados por «order»
        assertThat(m1.get("order").asInt()).isEqualTo(1);
        assertThat(m1.get("fromId").asText()).isEqualTo(content.get("lifelines").get(0).get("id").asText());
        // el diagrama de secuencia es consultable (CU-11)
        assertThat(getDiagram(c.t().developerToken(), seq.get("id").asText()).get("type").asText()).isEqualTo("SEQUENCE");
    }

    @Test
    void cu20_diagramaIncompletoOFalloDeIa() {
        Ctx c = ctx(); // diagrama vacío
        AI.sequenceReturns(Response.ok("{\"lifelines\":[],\"messages\":[]}"));
        assertThat(errorCode(graphql(c.t().designerToken(), SEQ, Map.of("s", c.diagramId())))).isEqualTo("DIAGRAM_INCOMPLETE");

        completeClassDiagram(c);
        AI.sequenceReturns(Response.status(503));
        assertThat(errorCode(graphql(c.t().designerToken(), SEQ, Map.of("s", c.diagramId())))).isEqualTo("AI_UNAVAILABLE");
        AI.sequenceReturns(Response.ok("{\"lifelines\":[{\"name\":\"A\"}],\"messages\":[{\"order\":1,\"from\":\"A\",\"to\":\"Z\",\"name\":\"x\"}]}"));
        assertThat(errorCode(graphql(c.t().designerToken(), SEQ, Map.of("s", c.diagramId())))).isEqualTo("AI_INVALID_RESPONSE");
        Integer sequences = jdbc.queryForObject("select count(*) from diagrams where source_diagram_id = ?::uuid", Integer.class, c.diagramId());
        assertThat(sequences).isZero();
    }
}
