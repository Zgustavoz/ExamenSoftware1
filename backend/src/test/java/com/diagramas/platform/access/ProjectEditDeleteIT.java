package com.diagramas.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** CU-23 Editar y eliminar proyecto, con sus permisos y la limpieza en cascada. */
class ProjectEditDeleteIT extends AbstractIntegrationTest {

    private int contar(String tabla, String columna, String id) {
        return jdbc.queryForObject("select count(*) from " + tabla + " where " + columna + " = ?::uuid", Integer.class, id);
    }

    @Test
    void elPropietarioEditaSuProyecto() {
        Tenant t = newTenant();
        String id = createProject(t.designerToken(), "Ventas");

        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, "/api/projects/" + id, t.designerToken(),
                Map.of("name", "Ventas y postventa", "description", "Actualizado"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("name").asText()).isEqualTo("Ventas y postventa");
        assertThat(r.getBody().get("description").asText()).isEqualTo("Actualizado");
        assertThat(call(HttpMethod.GET, "/api/projects/" + id, t.designerToken(), null).getBody().get("name").asText())
                .isEqualTo("Ventas y postventa");
    }

    @Test
    void elAdministradorDeLaEmpresaTambienPuedeEditarYEliminar() {
        Tenant t = newTenant();
        String id = createProject(t.designerToken(), "De otro");

        assertThat(call(HttpMethod.PUT, "/api/projects/" + id, t.adminToken(), Map.of("name", "Renombrado por el admin"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.DELETE, "/api/projects/" + id, t.adminToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void otroDisenadorQueNoEsElPropietarioNoPuede() {
        Tenant t = newTenant();
        String id = createProject(t.designerToken(), "Solo mío");

        ResponseEntity<JsonNode> edicion =
                call(HttpMethod.PUT, "/api/projects/" + id, t.designer2Token(), Map.of("name", "Robado"));
        assertThat(edicion.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(edicion.getBody().get("code").asText()).isEqualTo("FORBIDDEN");

        assertThat(call(HttpMethod.DELETE, "/api/projects/" + id, t.designer2Token(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Y sigue existiendo.
        assertThat(call(HttpMethod.GET, "/api/projects/" + id, t.designerToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void elNombreRepetidoSeRechazaPeroConservarElPropioNo() {
        Tenant t = newTenant();
        createProject(t.designerToken(), "Inventario");
        String id = createProject(t.designerToken(), "Ventas");

        ResponseEntity<JsonNode> repetido =
                call(HttpMethod.PUT, "/api/projects/" + id, t.designerToken(), Map.of("name", "INVENTARIO"));
        assertThat(repetido.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repetido.getBody().get("code").asText()).isEqualTo("DUPLICATE_PROJECT");

        // Guardar el proyecto con su propio nombre (por ejemplo, solo cambiando la descripción) sí vale.
        assertThat(call(HttpMethod.PUT, "/api/projects/" + id, t.designerToken(),
                Map.of("name", "Ventas", "description", "Otra descripción")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void alEliminarElProyectoNoQuedanDiagramasTareasNiCodigoHuerfanos() {
        Tenant t = newTenant();
        String projectId = createProject(t.designerToken(), "Con contenido");
        String diagramId = createDiagram(t.designerToken(), projectId, "Modelo").get("id").asText();
        saveDiagram(t.designerToken(), diagramId, Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "c1", "name", "Cliente", "x", 10, "y", 10,
                        "attributes", List.of(Map.of("name", "nombre", "type", "String")))),
                "relationships", List.of()), 1);
        JsonNode tarea = graphql(t.designerToken(),
                "mutation($d: ID!) { generateBackendCode(diagramId: $d, language: \"JAVA\") { id } }",
                Map.of("d", diagramId));
        String taskId = tarea.get("data").get("generateBackendCode").get("id").asText();

        assertThat(contar("diagrams", "project_id", projectId)).isEqualTo(1);
        assertThat(contar("generated_code", "task_id", taskId)).isPositive();

        assertThat(call(HttpMethod.DELETE, "/api/projects/" + projectId, t.designerToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(contar("projects", "id", projectId)).isZero();
        assertThat(contar("diagrams", "project_id", projectId)).isZero();
        assertThat(contar("diagrams", "id", diagramId)).isZero();
        assertThat(contar("tasks", "diagram_id", diagramId)).isZero();
        assertThat(contar("tasks", "id", taskId)).isZero();
        assertThat(contar("generated_code", "diagram_id", diagramId)).isZero();
        assertThat(contar("ai_chats", "diagram_id", diagramId)).isZero();
    }

    @Test
    void noSePuedeTocarUnProyectoDeOtraEmpresa() {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String id = createProject(a.designerToken(), "De la empresa A");

        // El administrador de B tiene el rol, pero el proyecto no es suyo: no debe ni saber que existe.
        assertThat(call(HttpMethod.PUT, "/api/projects/" + id, b.adminToken(), Map.of("name", "Robado"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/projects/" + id, b.adminToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(contar("projects", "id", id)).isEqualTo(1);
    }

    @Test
    void unProyectoInexistenteEsNotFound() {
        Tenant t = newTenant();
        String id = UUID.randomUUID().toString();

        assertThat(call(HttpMethod.PUT, "/api/projects/" + id, t.adminToken(), Map.of("name", "X")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/projects/" + id, t.adminToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void elNombreVacioSeRechaza() {
        Tenant t = newTenant();
        String id = createProject(t.designerToken(), "Proyecto");

        ResponseEntity<JsonNode> r = call(HttpMethod.PUT, "/api/projects/" + id, t.designerToken(), Map.of("name", " "));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
}
