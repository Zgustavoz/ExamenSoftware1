package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ciclo C2: CU-06, CU-10 (versionado, conflicto), CU-11 y CP-02 (persistencia del guardado). */
class DesignIT extends AbstractIntegrationTest {

    static Map<String, Object> content(int x) {
        return Map.of("schemaVersion", 1, "type", "CLASS",
                "classes", List.of(Map.of("id", "c1", "name", "Cliente", "visibility", "PUBLIC", "x", x, "y", 80,
                        "attributes", List.of(Map.of("id", "a1", "name", "nombre", "type", "String")),
                        "methods", List.of())),
                "relationships", List.of());
    }

    @Test
    void cu06_crearDiagrama() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        JsonNode d = createDiagram(t.designerToken(), project, "Ventas");
        assertThat(d.get("type").asText()).isEqualTo("CLASS");
        assertThat(d.get("version").asInt()).isEqualTo(1);
        assertThat(d.get("contentJson").get("classes")).isEmpty();
        assertThat(d.get("contentJson").get("relationships")).isEmpty();
        assertThat(d.get("contentJson").get("schemaVersion").asInt()).isEqualTo(1);

        // nombre duplicado en el proyecto (sin distinguir mayúsculas)
        JsonNode dup = graphql(t.designerToken(), "mutation($p: ID!) { createDiagram(projectId: $p, name: \"VENTAS\") { id } }", Map.of("p", project));
        assertThat(errorCode(dup)).isEqualTo("DUPLICATE_DIAGRAM");
        // el mismo nombre en otro proyecto sí se permite
        String other = createProject(t.designerToken(), "P2");
        assertThat(createDiagram(t.designerToken(), other, "Ventas").get("id").asText()).isNotBlank();
    }

    @Test
    void cu06_soloDesigner() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        for (String token : List.of(t.developerToken(), t.adminToken())) {
            JsonNode r = graphql(token, "mutation($p: ID!) { createDiagram(projectId: $p, name: \"x\") { id } }", Map.of("p", project));
            assertThat(errorCode(r)).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void graphqlExigeJwt() {
        assertThat(call(org.springframework.http.HttpMethod.POST, "/graphql", null, Map.of("query", "{ __typename }")).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void cu11_todosLosRolesConsultan_inexistenteEsNotFound() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        JsonNode d = createDiagram(t.designerToken(), project, "D");
        for (String token : List.of(t.designerToken(), t.developerToken(), t.adminToken())) {
            assertThat(getDiagram(token, d.get("id").asText()).get("name").asText()).isEqualTo("D");
            JsonNode list = graphql(token, "query($p: ID!) { diagrams(projectId: $p) { id name } }", Map.of("p", project));
            assertThat(list.get("data").get("diagrams")).hasSize(1);
        }
        JsonNode missing = graphql(t.designerToken(), "query { diagram(id: \"00000000-0000-0000-0000-000000000000\") { id } }", Map.of());
        assertThat(errorCode(missing)).isEqualTo("NOT_FOUND");
    }

    @Test
    void cp02_guardadoPersisteYReloadMuestraElCambio() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        JsonNode d = createDiagram(t.designerToken(), project, "D");
        String id = d.get("id").asText();

        JsonNode saved1 = saveDiagram(t.designerToken(), id, content(120), 1).get("data").get("saveDiagram");
        assertThat(saved1.get("version").asInt()).isEqualTo(2);

        // «mover una clase» y guardar (lo que hace el autoguardado)
        JsonNode saved2 = saveDiagram(t.designerToken(), id, content(555), 2).get("data").get("saveDiagram");
        assertThat(saved2.get("version").asInt()).isEqualTo(3);

        JsonNode reloaded = getDiagram(t.designerToken(), id);
        assertThat(reloaded.get("version").asInt()).isEqualTo(3);
        assertThat(reloaded.get("contentJson").get("classes").get(0).get("x").asInt()).isEqualTo(555);
        // y persiste realmente en diagrams.content_json
        String x = jdbc.queryForObject("select content_json->'classes'->0->>'x' from diagrams where id = ?::uuid", String.class, id);
        assertThat(x).isEqualTo("555");
    }

    @Test
    void cu10_conflictoDeVersionDevuelveElDiagramaActual() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        String id = createDiagram(t.designerToken(), project, "D").get("id").asText();
        saveDiagram(t.designerToken(), id, content(10), 1); // → versión 2

        JsonNode stale = saveDiagram(t.designer2Token(), id, content(999), 1); // otro dispositivo con base vieja
        assertThat(errorCode(stale)).isEqualTo("VERSION_CONFLICT");
        JsonNode details = stale.get("errors").get(0).get("extensions").get("details");
        assertThat(details.get("currentVersion").asInt()).isEqualTo(2);
        assertThat(details.get("contentJson").get("classes").get(0).get("x").asInt()).isEqualTo(10);
        // no se perdió ni se pisó nada
        assertThat(getDiagram(t.designerToken(), id).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void cu10_jsonInvalidoYReglasDeIntegridadNoModificanNada() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        String id = createDiagram(t.designerToken(), project, "D").get("id").asText();

        assertThat(errorCode(saveDiagram(t.designerToken(), id, "hola", 1))).isEqualTo("INVALID_JSON");
        assertThat(errorCode(saveDiagram(t.designerToken(), id, Map.of("type", "CLASS", "classes", "no-es-lista"), 1))).isEqualTo("INVALID_JSON");
        assertThat(errorCode(saveDiagram(t.designerToken(), id, Map.of("type", "CLASS", "classes", List.of(
                Map.of("name", "A", "x", 1, "y", 1), Map.of("name", "a", "x", 2, "y", 2)), "relationships", List.of()), 1)))
                .isEqualTo("DUPLICATE_CLASS");
        assertThat(errorCode(saveDiagram(t.designerToken(), id, Map.of("type", "CLASS", "classes", List.of(
                Map.of("name", "A", "x", 99999, "y", 1)), "relationships", List.of()), 1))).isEqualTo("OUT_OF_BOUNDS");
        assertThat(errorCode(saveDiagram(t.designerToken(), id, Map.of("type", "CLASS", "classes", List.of(
                Map.of("name", "A", "x", 1, "y", 1, "attributes", List.of(Map.of("name", "n", "type", "Foo")))), "relationships", List.of()), 1)))
                .isEqualTo("INVALID_DATATYPE");
        // el diagrama sigue en la versión 1
        assertThat(getDiagram(t.designerToken(), id).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void guardarExigeRolDesigner() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        String id = createDiagram(t.designerToken(), project, "D").get("id").asText();
        assertThat(errorCode(saveDiagram(t.developerToken(), id, content(1), 1))).isEqualTo("FORBIDDEN");
    }

    @Test
    void cp01_relacionDuplicadaAlGuardar() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P1");
        String id = createDiagram(t.designerToken(), project, "D").get("id").asText();
        Map<String, Object> c = Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "a", "name", "A", "x", 1, "y", 1), Map.of("id", "b", "name", "B", "x", 2, "y", 2)),
                "relationships", List.of(
                        Map.of("id", "r1", "type", "ASSOCIATION", "sourceId", "a", "targetId", "b"),
                        Map.of("id", "r2", "type", "ASSOCIATION", "sourceId", "a", "targetId", "b")));
        assertThat(errorCode(saveDiagram(t.designerToken(), id, c, 1))).isEqualTo("DUPLICATE_RELATIONSHIP");
    }
}
