package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Ciclo C3: CU-14 (CP-05, CP-06), CU-15 (ZIP, zip-slip) y notificaciones CODE_READY / TASK_ASSIGNED (CU-19). */
class CodegenIT extends AbstractIntegrationTest {

    private static final String GEN = "mutation($d: ID!, $l: String!) { generateBackendCode(diagramId: $d, language: $l) "
            + "{ id diagramId type status title resultJson startedAt completedAt } }";

    private static Map<String, Object> validDiagram() {
        return Map.of("schemaVersion", 1, "type", "CLASS", "classes", List.of(
                Map.of("id", "c1", "name", "Persona", "stereotype", "abstract", "x", 10, "y", 10,
                        "attributes", List.of(Map.of("name", "nombre", "type", "String")), "methods", List.of()),
                Map.of("id", "c2", "name", "Cliente", "x", 300, "y", 10,
                        "attributes", List.of(Map.of("name", "email", "type", "String")),
                        "methods", List.of(Map.of("name", "comprar", "returnType", "void"))),
                Map.of("id", "c3", "name", "Pedido", "x", 10, "y", 300,
                        "attributes", List.of(Map.of("name", "total", "type", "BigDecimal")), "methods", List.of()),
                Map.of("id", "c4", "name", "Linea", "x", 300, "y", 300,
                        "attributes", List.of(Map.of("name", "cantidad", "type", "int")), "methods", List.of())),
                "relationships", List.of(
                        Map.of("id", "r1", "type", "GENERALIZATION", "sourceId", "c2", "targetId", "c1"),
                        Map.of("id", "r2", "type", "ASSOCIATION", "sourceId", "c2", "targetId", "c3", "sourceMultiplicity", "1", "targetMultiplicity", "0..*"),
                        Map.of("id", "r3", "type", "COMPOSITION", "sourceId", "c3", "targetId", "c4", "sourceMultiplicity", "1", "targetMultiplicity", "1..*")));
    }

    private record Ctx(Tenant t, String diagramId) {}

    private Ctx completeDiagram() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        String id = createDiagram(t.designerToken(), project, "Ventas").get("id").asText();
        JsonNode saved = saveDiagram(t.designerToken(), id, validDiagram(), 1);
        assertThat(saved.has("errors")).as(saved.toString()).isFalse();
        return new Ctx(t, id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void cp05_generaTareaYCodigoYPermiteDescarga() throws Exception {
        Ctx c = completeDiagram();
        JsonNode r = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"));
        assertThat(r.has("errors")).as(r.toString()).isFalse();
        JsonNode task = r.get("data").get("generateBackendCode");
        assertThat(task.get("type").asText()).isEqualTo("CODE_GENERATION");
        assertThat(task.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(task.get("startedAt").isNull()).isFalse();
        assertThat(task.get("completedAt").isNull()).isFalse();
        String taskId = task.get("id").asText();
        int files = task.get("resultJson").get("fileCount").asInt();
        assertThat(files).isGreaterThanOrEqualTo(7); // pom + Application + properties + 4 entidades

        // registros en tasks y generated_code (uno por archivo, todos con el mismo task_id)
        assertThat(count("select count(*) from tasks where id = ?::uuid and status = 'COMPLETED'", taskId)).isEqualTo(1);
        assertThat(count("select count(*) from generated_code where task_id = ?::uuid and status = 'SUCCESS' and language = 'JAVA'", taskId))
                .isEqualTo(files);

        // CU-15: un DEVELOPER lista el historial y descarga el ZIP
        JsonNode history = call(HttpMethod.GET, "/api/generated-code?diagramId=" + c.diagramId(), c.t().developerToken(), null).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("taskId").asText()).isEqualTo(taskId);
        assertThat(history.get(0).get("files")).hasSize(files);

        ResponseEntity<byte[]> zip = rest.exchange("/api/generated-code/tasks/" + taskId + "/download", HttpMethod.GET,
                new HttpEntity<>(bearer(c.t().developerToken())), byte[].class);
        assertThat(zip.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(zip.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(zip.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        List<String> names = zipEntries(zip.getBody());
        assertThat(names).contains("pom.xml", "src/main/java/com/generated/app/model/Cliente.java");
        assertThat(names).hasSize(files);
    }

    @Test
    void cu14_tambienLoPuedeGenerarUnDeveloper_peroNoElAdminDeEmpresa() {
        Ctx c = completeDiagram();
        JsonNode dev = graphql(c.t().developerToken(), GEN, Map.of("d", c.diagramId(), "l", "java"));
        assertThat(dev.has("errors")).as(dev.toString()).isFalse();
        JsonNode admin = graphql(c.t().adminToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"));
        assertThat(errorCode(admin)).isEqualTo("FORBIDDEN");
    }

    @Test
    void cp06_diagramaIncompletoSeRechazaSinCrearTarea() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        String empty = createDiagram(t.designerToken(), project, "Vacio").get("id").asText();
        JsonNode r = graphql(t.designerToken(), GEN, Map.of("d", empty, "l", "JAVA"));
        assertThat(errorCode(r)).isEqualTo("DIAGRAM_INCOMPLETE");
        assertThat(r.get("errors").get(0).get("message").asText())
                .isEqualTo("El diagrama debe tener clases con atributos y métodos para poder generar código");

        // una clase vacía entre las demás también lo vuelve incompleto
        String half = createDiagram(t.designerToken(), project, "Mitad").get("id").asText();
        saveDiagram(t.designerToken(), half, Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "a", "name", "Llena", "x", 1, "y", 1, "attributes", List.of(Map.of("name", "n", "type", "int"))),
                Map.of("id", "b", "name", "Vacia", "x", 2, "y", 2)), "relationships", List.of()), 1);
        assertThat(errorCode(graphql(t.designerToken(), GEN, Map.of("d", half, "l", "JAVA")))).isEqualTo("DIAGRAM_INCOMPLETE");

        assertThat(count("select count(*) from tasks where diagram_id in (?::uuid, ?::uuid)", empty, half)).isZero();
        assertThat(count("select count(*) from generated_code where diagram_id in (?::uuid, ?::uuid)", empty, half)).isZero();
    }

    @Test
    void diagramaInexistenteOLenguajeNoSoportadoNoCreanTarea() {
        Ctx c = completeDiagram();
        long before = count("select count(*) from tasks");
        JsonNode missing = graphql(c.t().designerToken(), GEN, Map.of("d", UUID.randomUUID().toString(), "l", "JAVA"));
        assertThat(errorCode(missing)).isEqualTo("NOT_FOUND");
        JsonNode cobol = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "COBOL"));
        assertThat(errorCode(cobol)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("select count(*) from tasks")).isEqualTo((int) before);
    }

    @Test
    void aislamientoMultiTenant_generacionYDescarga() {
        Ctx c = completeDiagram();
        Tenant other = newTenant();
        assertThat(errorCode(graphql(other.designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA")))).isEqualTo("NOT_FOUND");

        String taskId = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"))
                .get("data").get("generateBackendCode").get("id").asText();
        ResponseEntity<JsonNode> foreign = call(HttpMethod.GET, "/api/generated-code/tasks/" + taskId + "/download", other.developerToken(), null);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/generated-code?diagramId=" + c.diagramId(), other.developerToken(), null).getStatusCode().value())
                .isEqualTo(404);
        // las tareas tampoco se ven entre empresas
        JsonNode t = graphql(other.designerToken(), "query($id: ID!) { task(id: $id) { id } }", Map.of("id", taskId));
        assertThat(errorCode(t)).isEqualTo("NOT_FOUND");
    }

    @Test
    void cu15_soloDeveloperDescarga_yCodigoInexistenteEs404() {
        Ctx c = completeDiagram();
        String taskId = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"))
                .get("data").get("generateBackendCode").get("id").asText();
        assertThat(call(HttpMethod.GET, "/api/generated-code/tasks/" + taskId + "/download", c.t().designerToken(), null)
                .getStatusCode().value()).isEqualTo(403);
        ResponseEntity<JsonNode> missing = call(HttpMethod.GET, "/api/generated-code/tasks/" + UUID.randomUUID() + "/download",
                c.t().developerToken(), null);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void zipSlip_lasRutasNoPuedenSalirDelDirectorio() throws Exception {
        Ctx c = completeDiagram();
        String taskId = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"))
                .get("data").get("generateBackendCode").get("id").asText();
        // Simula datos maliciosos ya guardados en la BD
        for (String evil : List.of("../../evil.txt", "/etc/passwd", "..\\..\\windows\\evil.bat", "a/../../b.txt", "C:\\temp\\x.txt")) {
            jdbc.update("insert into generated_code (diagram_id, task_id, language, code_content, file_name) values (?::uuid, ?::uuid, 'JAVA', 'x', ?)",
                    c.diagramId(), taskId, evil);
        }
        ResponseEntity<byte[]> zip = rest.exchange("/api/generated-code/tasks/" + taskId + "/download", HttpMethod.GET,
                new HttpEntity<>(bearer(c.t().developerToken())), byte[].class);
        assertThat(zip.getStatusCode().is2xxSuccessful()).isTrue();
        List<String> names = zipEntries(zip.getBody());
        assertThat(names).isNotEmpty();
        for (String n : names) {
            assertThat(n).doesNotContain("..").doesNotStartWith("/").doesNotContain("\\").doesNotContain(":");
        }
        assertThat(names).contains("evil.txt", "etc/passwd", "windows/evil.bat", "a/b.txt", "C_/temp/x.txt");
    }

    @Test
    void cu19_notificacionesTaskAssignedYCodeReady() {
        Ctx c = completeDiagram();
        String taskId = graphql(c.t().designerToken(), GEN, Map.of("d", c.diagramId(), "l", "JAVA"))
                .get("data").get("generateBackendCode").get("id").asText();

        JsonNode page = call(HttpMethod.GET, "/api/notifications", c.t().designerToken(), null).getBody();
        List<String> types = new ArrayList<>();
        page.get("content").forEach(n -> types.add(n.get("type").asText()));
        assertThat(types).contains("TASK_ASSIGNED", "CODE_READY");
        JsonNode ready = null;
        for (JsonNode n : page.get("content")) if (n.get("type").asText().equals("CODE_READY")) ready = n;
        assertThat(ready.get("read").asBoolean()).isFalse();
        assertThat(ready.get("payload").get("taskId").asText()).isEqualTo(taskId);

        // marcar como leída
        JsonNode read = call(HttpMethod.PATCH, "/api/notifications/" + ready.get("id").asText() + "/read", c.t().designerToken(), null).getBody();
        assertThat(read.get("read").asBoolean()).isTrue();
        JsonNode unread = call(HttpMethod.GET, "/api/notifications?unreadOnly=true", c.t().designerToken(), null).getBody();
        for (JsonNode n : unread.get("content")) assertThat(n.get("id").asText()).isNotEqualTo(ready.get("id").asText());

        // las notificaciones son privadas del usuario y de su empresa
        assertThat(call(HttpMethod.GET, "/api/notifications", c.t().developerToken(), null).getBody().get("content")).isEmpty();
        assertThat(call(HttpMethod.PATCH, "/api/notifications/" + ready.get("id").asText() + "/read", c.t().developerToken(), null)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void cu19_registrarTokenFcm() {
        Tenant t = newTenant();
        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, "/api/me/fcm-token", t.designerToken(), Map.of("token", "token-fcm-123"));
        assertThat(r.getStatusCode().value()).isEqualTo(204);
        Map<String, Object> row = jdbc.queryForMap("select fcm_token, fcm_updated_at from users where company_id = ?::uuid and username = 'designer'",
                t.companyId().toString());
        assertThat(row.get("fcm_token")).isEqualTo("token-fcm-123");
        assertThat(row.get("fcm_updated_at")).isNotNull();
        assertThat(call(HttpMethod.PUT, "/api/me/fcm-token", t.designerToken(), Map.of("token", " ")).getStatusCode().value()).isEqualTo(422);
    }

    private static List<String> zipEntries(byte[] bytes) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }
}
