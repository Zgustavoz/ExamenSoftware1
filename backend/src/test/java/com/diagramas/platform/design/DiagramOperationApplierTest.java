package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.diagramas.platform.design.service.DiagramOperationApplier.Applied;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reglas 7.3: nombre único, tipos, relaciones duplicadas (CP-01), herencia circular, límites, versionado atómico. */
class DiagramOperationApplierTest {

    private final ObjectMapper m = new ObjectMapper();
    private final DiagramOperationApplier applier = new DiagramOperationApplier();
    private ObjectNode content;

    @BeforeEach
    void setUp() {
        content = DiagramOperationApplier.emptyClassContent();
    }

    private JsonNode json(String s) {
        try {
            return m.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode add(String name) {
        Applied a = applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"" + name + "\",\"x\":10,\"y\":20}}"));
        content = a.content();
        return a.operation();
    }

    private String idOf(String name) {
        for (JsonNode c : content.get("classes")) if (c.get("name").asText().equals(name)) return c.get("id").asText();
        throw new IllegalStateException(name);
    }

    private ObjectNode relate(String type, String from, String to) {
        Applied a = applier.apply(content, json("{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"" + type
                + "\",\"sourceId\":\"" + idOf(from) + "\",\"targetId\":\"" + idOf(to) + "\"}}"));
        content = a.content();
        return a.operation();
    }

    private ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void agregarClaseAsignaIdYAplicaPorDefecto() {
        ObjectNode op = add("Cliente");
        JsonNode cls = content.get("classes").get(0);
        assertThat(cls.get("id").asText()).hasSize(8);
        assertThat(cls.get("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(cls.get("attributes")).isEmpty();
        assertThat(op.get("class").get("id").asText()).isEqualTo(cls.get("id").asText()); // la op normalizada lleva el id
    }

    @Test
    void noModificaElContenidoOriginal() {
        add("A");
        ObjectNode before = content.deepCopy();
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"a\",\"x\":1,\"y\":1}}"))))
                .isEqualTo(ErrorCode.DUPLICATE_CLASS);
        assertThat(content).isEqualTo(before);
    }

    @Test
    void nombreDeClaseDuplicadoSinDistinguirMayusculas() {
        add("Cliente");
        assertThat(codeOf(() -> add("CLIENTE"))).isEqualTo(ErrorCode.DUPLICATE_CLASS);
    }

    @Test
    void cp01_relacionDuplicadaSeBloquea() {
        add("A");
        add("B");
        relate("ASSOCIATION", "A", "B");
        assertThat(codeOf(() -> relate("ASSOCIATION", "A", "B"))).isEqualTo(ErrorCode.DUPLICATE_RELATIONSHIP);
        assertThat(codeOf(() -> relate("COMPOSITION", "A", "B"))).isEqualTo(ErrorCode.DUPLICATE_RELATIONSHIP);
        assertThat(content.get("relationships")).hasSize(1);
        // el sentido inverso es otra pareja (origen, destino)
        relate("ASSOCIATION", "B", "A");
        assertThat(content.get("relationships")).hasSize(2);
    }

    @Test
    void herenciaCircularYAutoHerenciaSonInvalidas() {
        add("A");
        add("B");
        add("C");
        relate("GENERALIZATION", "A", "B");
        relate("GENERALIZATION", "B", "C");
        assertThat(codeOf(() -> relate("GENERALIZATION", "C", "A"))).isEqualTo(ErrorCode.INVALID_RELATIONSHIP);
        assertThat(codeOf(() -> relate("GENERALIZATION", "A", "A"))).isEqualTo(ErrorCode.INVALID_RELATIONSHIP);
        assertThat(codeOf(() -> relate("REALIZATION", "B", "B"))).isEqualTo(ErrorCode.INVALID_RELATIONSHIP);
    }

    @Test
    void relacionConExtremoInexistente() {
        add("A");
        assertThat(codeOf(() -> applier.apply(content, json(
                "{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"ASSOCIATION\",\"sourceId\":\"" + idOf("A") + "\",\"targetId\":\"zzz\"}}"))))
                .isEqualTo(ErrorCode.INVALID_RELATIONSHIP);
    }

    @Test
    void multiplicidadYTipoDeRelacionInvalidos() {
        add("A");
        add("B");
        String base = "{\"op\":\"ADD_RELATIONSHIP\",\"relationship\":{\"type\":\"%s\",\"sourceId\":\"" + idOf("A")
                + "\",\"targetId\":\"" + idOf("B") + "\",\"targetMultiplicity\":\"%s\"}}";
        assertThat(codeOf(() -> applier.apply(content, json(base.formatted("ASSOCIATION", "muchos"))))).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(codeOf(() -> applier.apply(content, json(base.formatted("FRIENDSHIP", "1"))))).isEqualTo(ErrorCode.VALIDATION_ERROR);
        for (String ok : List.of("1", "0..1", "*", "0..*", "1..*", "2..5")) {
            assertThat(codeOf(() -> applier.apply(content, json(base.formatted("ASSOCIATION", ok))))).isNull();
        }
    }

    @Test
    void tiposDeDatoPermitidos() {
        add("Cliente");
        String cid = idOf("Cliente");
        for (String ok : List.of("String", "int", "Integer", "long", "Long", "double", "Double", "float", "boolean", "Boolean",
                "UUID", "LocalDate", "LocalDateTime", "BigDecimal", "Cliente", "List<String>", "Set<Cliente>", "List<UUID>")) {
            assertThat(codeOf(() -> applier.apply(content, attr(cid, "x", ok)))).as(ok).isNull();
        }
        for (String bad : List.of("string", "Date", "Foo", "List<Foo>", "Map<String,String>", "void", "List<void>", "")) {
            assertThat(codeOf(() -> applier.apply(content, attr(cid, "x", bad)))).as(bad).isEqualTo(ErrorCode.INVALID_DATATYPE);
        }
    }

    @Test
    void voidSoloComoRetornoDeMetodo() {
        add("A");
        String cid = idOf("A");
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_METHOD\",\"classId\":\"" + cid
                + "\",\"method\":{\"name\":\"run\",\"returnType\":\"void\"}}")))).isNull();
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_METHOD\",\"classId\":\"" + cid
                + "\",\"method\":{\"name\":\"run\",\"returnType\":\"Foo\"}}")))).isEqualTo(ErrorCode.INVALID_DATATYPE);
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_METHOD\",\"classId\":\"" + cid
                + "\",\"method\":{\"name\":\"run\",\"parameters\":[{\"name\":\"p\",\"type\":\"void\"}]}}")))).isEqualTo(ErrorCode.INVALID_DATATYPE);
    }

    @Test
    void visibilidadesPorDefecto() {
        add("A");
        String cid = idOf("A");
        content = applier.apply(content, attr(cid, "nombre", "String")).content();
        content = applier.apply(content, json("{\"op\":\"ADD_METHOD\",\"classId\":\"" + cid + "\",\"method\":{\"name\":\"m\"}}")).content();
        JsonNode cls = content.get("classes").get(0);
        assertThat(cls.get("attributes").get(0).get("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(cls.get("methods").get(0).get("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(cls.get("methods").get(0).get("returnType").asText()).isEqualTo("void");
    }

    @Test
    void limitesDelLienzo() {
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\",\"x\":-1,\"y\":0}}"))))
                .isEqualTo(ErrorCode.OUT_OF_BOUNDS);
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\",\"x\":0,\"y\":10001}}"))))
                .isEqualTo(ErrorCode.OUT_OF_BOUNDS);
        add("B");
        String id = idOf("B");
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"MOVE_CLASS\",\"classId\":\"" + id + "\",\"x\":10001,\"y\":5}"))))
                .isEqualTo(ErrorCode.OUT_OF_BOUNDS);
        content = applier.apply(content, json("{\"op\":\"MOVE_CLASS\",\"classId\":\"" + id + "\",\"x\":300,\"y\":400}")).content();
        assertThat(content.get("classes").get(0).get("x").asInt()).isEqualTo(300);
    }

    @Test
    void sinCoordenadasSePosicionaEnGrilla() {
        content = applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"A\"}}")).content();
        content = applier.apply(content, json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"B\"}}")).content();
        assertThat(content.get("classes").get(0).get("x").asInt()).isNotEqualTo(content.get("classes").get(1).get("x").asInt());
    }

    @Test
    void eliminarClaseEliminaSusRelaciones() {
        add("A");
        add("B");
        relate("ASSOCIATION", "A", "B");
        content = applier.apply(content, json("{\"op\":\"REMOVE_CLASS\",\"classId\":\"" + idOf("A") + "\"}")).content();
        assertThat(content.get("classes")).hasSize(1);
        assertThat(content.get("relationships")).isEmpty();
    }

    @Test
    void renombrarClaseActualizaLosTiposQueLaReferencian() {
        add("Cliente");
        add("Pedido");
        content = applier.apply(content, attr(idOf("Pedido"), "cliente", "Cliente")).content();
        content = applier.apply(content, json("{\"op\":\"UPDATE_CLASS\",\"classId\":\"" + idOf("Cliente") + "\",\"changes\":{\"name\":\"Comprador\"}}")).content();
        assertThat(content.get("classes").get(1).get("attributes").get(0).get("type").asText()).isEqualTo("Comprador");
    }

    @Test
    void operacionInvalidaOAtributoDuplicado() {
        add("A");
        String cid = idOf("A");
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"EXPLOTAR\"}")))).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(codeOf(() -> applier.apply(content, json("{\"op\":\"REMOVE_CLASS\",\"classId\":\"nope\"}")))).isEqualTo(ErrorCode.NOT_FOUND);
        content = applier.apply(content, attr(cid, "nombre", "String")).content();
        assertThat(codeOf(() -> applier.apply(content, attr(cid, "Nombre", "String")))).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void loteEsAtomico_siUnaOperacionFallaNadaSeAplica() {
        add("A");
        ObjectNode before = content.deepCopy();
        List<JsonNode> ops = List.of(
                json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"B\"}}"),
                json("{\"op\":\"ADD_CLASS\",\"class\":{\"name\":\"a\"}}"));
        assertThatThrownBy(() -> applier.applyAll(content, ops)).isInstanceOf(ApiException.class);
        assertThat(content).isEqualTo(before);
    }

    @Test
    void loteAceptaTiposQueReferencianClasesDeOtroPasoDelLote() {
        List<JsonNode> ops = List.of(
                json("{\"op\":\"ADD_CLASS\",\"class\":{\"id\":\"c1\",\"name\":\"Pedido\",\"attributes\":[{\"name\":\"cliente\",\"type\":\"Cliente\"}]}}"),
                json("{\"op\":\"ADD_CLASS\",\"class\":{\"id\":\"c2\",\"name\":\"Cliente\",\"attributes\":[{\"name\":\"nombre\",\"type\":\"String\"}]}}"));
        assertThat(applier.applyAll(content, ops).content().get("classes")).hasSize(2);
    }

    // ---- normalizeContent (CU-10)

    @Test
    void normalizeContentAsignaIdsYRechazaJsonInvalido() {
        ObjectNode ok = (ObjectNode) json("{\"schemaVersion\":1,\"type\":\"CLASS\",\"classes\":[{\"name\":\"A\",\"x\":1,\"y\":1,\"attributes\":[{\"name\":\"n\",\"type\":\"String\"}]}],\"relationships\":[]}");
        ObjectNode n = applier.normalizeContent(ok);
        assertThat(n.get("classes").get(0).get("id").asText()).isNotBlank();
        assertThat(n.get("classes").get(0).get("attributes").get(0).get("visibility").asText()).isEqualTo("PRIVATE");

        assertThat(codeOf(() -> applier.normalizeContent(json("[1,2]")))).isEqualTo(ErrorCode.INVALID_JSON);
        assertThat(codeOf(() -> applier.normalizeContent(json("{\"type\":\"CLASS\",\"classes\":\"x\"}")))).isEqualTo(ErrorCode.INVALID_JSON);
        assertThat(codeOf(() -> applier.normalizeContent(json("{\"type\":\"OTRO\"}")))).isEqualTo(ErrorCode.INVALID_JSON);
        assertThat(codeOf(() -> applier.normalizeContent(json(
                "{\"type\":\"CLASS\",\"classes\":[{\"name\":\"A\",\"x\":1,\"y\":1},{\"name\":\"a\",\"x\":2,\"y\":2}],\"relationships\":[]}"))))
                .isEqualTo(ErrorCode.DUPLICATE_CLASS);
    }

    @Test
    void normalizeContentConservaCamposExtraDelCliente() {
        ObjectNode ok = (ObjectNode) json("{\"type\":\"CLASS\",\"viewport\":{\"zoom\":2},\"classes\":[{\"name\":\"A\",\"x\":1,\"y\":1,\"width\":150}],\"relationships\":[]}");
        ObjectNode n = applier.normalizeContent(ok);
        assertThat(n.get("viewport").get("zoom").asInt()).isEqualTo(2);
        assertThat(n.get("classes").get(0).get("width").asInt()).isEqualTo(150);
    }

    @Test
    void diagramaDeSecuenciaValidaReferencias() {
        ObjectNode ok = (ObjectNode) json("{\"type\":\"SEQUENCE\",\"lifelines\":[{\"id\":\"l1\",\"name\":\"A\"},{\"id\":\"l2\",\"name\":\"B\"}],"
                + "\"messages\":[{\"id\":\"s1\",\"order\":1,\"fromId\":\"l1\",\"toId\":\"l2\",\"name\":\"hola\",\"kind\":\"SYNC\"}]}");
        assertThat(applier.normalizeContent(ok).get("messages")).hasSize(1);
        ObjectNode bad = (ObjectNode) json("{\"type\":\"SEQUENCE\",\"lifelines\":[{\"id\":\"l1\",\"name\":\"A\"}],"
                + "\"messages\":[{\"fromId\":\"l1\",\"toId\":\"zz\",\"name\":\"x\"}]}");
        assertThat(codeOf(() -> applier.normalizeContent(bad))).isEqualTo(ErrorCode.INVALID_JSON);
    }

    private JsonNode attr(String classId, String name, String type) {
        ObjectNode op = m.createObjectNode();
        op.put("op", "ADD_ATTRIBUTE");
        op.put("classId", classId);
        ObjectNode a = op.putObject("attribute");
        a.put("name", name);
        a.put("type", type);
        return op;
    }
}
