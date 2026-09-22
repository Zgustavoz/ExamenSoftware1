package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** CU-21 Gestionar tareas: creación manual, secuencia de estados, permisos y avisos. */
class TaskIT extends AbstractIntegrationTest {

    private static final String CREAR =
            "mutation($i: NewTaskInput!) { createTask(input: $i) { id type title status assignedTo createdBy diagramId startedAt completedAt } }";
    private static final String EDITAR =
            "mutation($id: ID!, $i: EditTaskInput!) { updateTask(id: $id, input: $i) { id title description assignedTo assignedToName createdByName } }";
    private static final String ELIMINAR = "mutation($id: ID!) { deleteTask(id: $id) }";
    private static final String CAMBIAR =
            "mutation($id: ID!, $s: String!) { updateTaskStatus(id: $id, status: $s) { id status startedAt completedAt } }";

    private record Ctx(Tenant t, String diagramId, String designerId, String designer2Id, String developerId) {}

    private String userId(String token) {
        return call(HttpMethod.GET, "/api/auth/me", token, null).getBody().get("id").asText();
    }

    private Ctx ctx() {
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        String diagramId = createDiagram(t.designerToken(), project, "Modelo").get("id").asText();
        return new Ctx(t, diagramId, userId(t.designerToken()), userId(t.designer2Token()), userId(t.developerToken()));
    }

    private JsonNode crear(Ctx c, String token, Map<String, Object> cambios) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(Map.of(
                "diagramId", c.diagramId(), "assignedTo", c.designer2Id(), "title", "Revisar el modelo"));
        input.putAll(cambios);
        return graphql(token, CREAR, Map.of("i", input));
    }

    private List<String> notificacionesDe(String token, String tipo) {
        JsonNode page = call(HttpMethod.GET, "/api/notifications", token, null).getBody();
        return page.get("content").findValues("type").stream().map(JsonNode::asText).filter(tipo::equals).toList();
    }

    @Test
    void crearUnaTareaLaDejaPendienteYAvisaAQuienSeLeAsigna() {
        Ctx c = ctx();

        JsonNode r = crear(c, c.t().designerToken(), Map.of("description", "Revisar nombres de clases"));

        assertThat(r.has("errors")).as(r.toString()).isFalse();
        JsonNode task = r.get("data").get("createTask");
        assertThat(task.get("type").asText()).isEqualTo("MANUAL");
        assertThat(task.get("status").asText()).isEqualTo("PENDING");
        assertThat(task.get("assignedTo").asText()).isEqualTo(c.designer2Id());
        assertThat(task.get("createdBy").asText()).isEqualTo(c.designerId());
        assertThat(task.get("startedAt").isNull()).isTrue();
        assertThat(task.get("completedAt").isNull()).isTrue();

        // El aviso le llega al asignado, no a quien la creó.
        assertThat(notificacionesDe(c.t().designer2Token(), "TASK_ASSIGNED")).hasSize(1);
        assertThat(notificacionesDe(c.t().designerToken(), "TASK_ASSIGNED")).isEmpty();
    }

    @Test
    void laSecuenciaDeEstadosGuardaLasFechasYAvisaAQuienLaCreo() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        JsonNode enCurso = graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"));
        assertThat(enCurso.get("data").get("updateTaskStatus").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(enCurso.get("data").get("updateTaskStatus").get("startedAt").isNull()).isFalse();

        JsonNode completada = graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "COMPLETED"));
        assertThat(completada.get("data").get("updateTaskStatus").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completada.get("data").get("updateTaskStatus").get("completedAt").isNull()).isFalse();

        // Quien creó la tarea recibe un aviso por cada cambio de estado.
        assertThat(notificacionesDe(c.t().designerToken(), "TASK_STATUS_CHANGED")).hasSize(2);
    }

    @Test
    void saltarseUnEstadoORetrocederSeRechaza() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        // De pendiente no se puede saltar a completada.
        JsonNode salto = graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "COMPLETED"));
        assertThat(errorCode(salto)).isEqualTo("INVALID_STATE_TRANSITION");

        graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"));
        // Ni volver atrás.
        assertThat(errorCode(graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "PENDING"))))
                .isEqualTo("INVALID_STATE_TRANSITION");
        // Ni inventarse un estado.
        assertThat(errorCode(graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "CANCELADA"))))
                .isEqualTo("INVALID_STATE_TRANSITION");

        graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "COMPLETED"));
        // Una tarea completada ya no se mueve más.
        assertThat(errorCode(graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"))))
                .isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void lasTareasAutomaticasNoSeCambianAMano() {
        Ctx c = ctx();
        saveDiagram(c.t().designerToken(), c.diagramId(), Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "c1", "name", "Cliente", "x", 10, "y", 10,
                        "attributes", List.of(Map.of("name", "nombre", "type", "String")))), "relationships", List.of()), 1);
        String automatica = graphql(c.t().designerToken(),
                "mutation($d: ID!) { generateBackendCode(diagramId: $d, language: \"JAVA\") { id status } }",
                Map.of("d", c.diagramId())).get("data").get("generateBackendCode").get("id").asText();

        JsonNode r = graphql(c.t().designerToken(), CAMBIAR, Map.of("id", automatica, "s", "IN_PROGRESS"));

        assertThat(errorCode(r)).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void soloElAsignadoElCreadorOElAdministradorCambianElEstado() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        // El desarrollador no tiene nada que ver con esta tarea.
        assertThat(errorCode(graphql(c.t().developerToken(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"))))
                .isEqualTo("FORBIDDEN");

        // El administrador de la empresa sí puede.
        assertThat(graphql(c.t().adminToken(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"))
                .get("data").get("updateTaskStatus").get("status").asText()).isEqualTo("IN_PROGRESS");
        // Y quien la creó, también.
        assertThat(graphql(c.t().designerToken(), CAMBIAR, Map.of("id", id, "s", "COMPLETED"))
                .get("data").get("updateTaskStatus").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void noSePuedeAsignarAUnUsuarioInactivoNiDeOtraEmpresa() {
        Ctx c = ctx();
        Tenant otra = newTenant();

        assertThat(errorCode(crear(c, c.t().designerToken(), Map.of("assignedTo", userId(otra.designerToken())))))
                .isEqualTo("USER_NOT_ELIGIBLE");

        // Se desactiva al asignado y deja de ser elegible.
        JsonNode usuarios = call(HttpMethod.GET, "/api/users", c.t().adminToken(), null).getBody();
        String developerId = null;
        for (JsonNode u : usuarios) if (u.get("username").asText().equals("developer")) developerId = u.get("id").asText();
        call(HttpMethod.PATCH, "/api/users/" + developerId + "/status", c.t().adminToken(), Map.of("active", false));

        assertThat(errorCode(crear(c, c.t().designerToken(), Map.of("assignedTo", developerId))))
                .isEqualTo("USER_NOT_ELIGIBLE");
    }

    @Test
    void elTituloEsObligatorioYElDiagramaDebeSerDeLaEmpresa() {
        Ctx c = ctx();

        assertThat(errorCode(crear(c, c.t().designerToken(), Map.of("title", "   ")))).isEqualTo("VALIDATION_ERROR");

        Tenant otra = newTenant();
        String diagramaAjeno = createDiagram(otra.designerToken(), createProject(otra.designerToken(), "Suyo"), "D")
                .get("id").asText();
        assertThat(errorCode(crear(c, c.t().designerToken(), Map.of("diagramId", diagramaAjeno)))).isEqualTo("NOT_FOUND");
    }

    @Test
    void misTareasTraeLoAsignadoYLoAutomaticoQueLance() {
        Ctx c = ctx();
        crear(c, c.t().designerToken(), Map.of("title", "Para designer2"));
        crear(c, c.t().designerToken(), Map.of("assignedTo", c.designerId(), "title", "Para mí mismo"));

        JsonNode mias = graphql(c.t().designerToken(), "{ myTasks { title assignedTo } }", Map.of())
                .get("data").get("myTasks");
        assertThat(mias.findValues("title").stream().map(JsonNode::asText)).containsExactly("Para mí mismo");

        JsonNode suyas = graphql(c.t().designer2Token(), "{ myTasks { title } }", Map.of()).get("data").get("myTasks");
        assertThat(suyas.findValues("title").stream().map(JsonNode::asText)).containsExactly("Para designer2");

        // Y las que repartí aparecen en «creadas por mí».
        JsonNode creadas = graphql(c.t().designerToken(), "{ tasksCreatedByMe { title } }", Map.of())
                .get("data").get("tasksCreatedByMe");
        assertThat(creadas).hasSize(2);
    }

    @Test
    void sePuedeFiltrarPorEstadoYPorDiagrama() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of("title", "Primera")).get("data").get("createTask").get("id").asText();
        crear(c, c.t().designerToken(), Map.of("title", "Segunda"));
        graphql(c.t().designer2Token(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"));

        JsonNode pendientes = graphql(c.t().designer2Token(), "query($s: String) { myTasks(status: $s) { title } }",
                Map.of("s", "PENDING")).get("data").get("myTasks");
        assertThat(pendientes.findValues("title").stream().map(JsonNode::asText)).containsExactly("Segunda");

        JsonNode delDiagrama = graphql(c.t().designerToken(),
                "query($d: ID!, $s: String) { tasks(diagramId: $d, status: $s) { title } }",
                Map.of("d", c.diagramId(), "s", "IN_PROGRESS")).get("data").get("tasks");
        assertThat(delDiagrama.findValues("title").stream().map(JsonNode::asText)).containsExactly("Primera");
    }

    @Test
    void lasTareasDeOtraEmpresaNoSeVenNiSeTocan() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();
        Tenant otra = newTenant();

        assertThat(errorCode(graphql(otra.adminToken(), CAMBIAR, Map.of("id", id, "s", "IN_PROGRESS"))))
                .isEqualTo("NOT_FOUND");
        assertThat(graphql(otra.designerToken(), "{ myTasks { id } }", Map.of()).get("data").get("myTasks")).isEmpty();
        assertThat(graphql(otra.designerToken(), "{ tasks { id } }", Map.of()).get("data").get("tasks")).isEmpty();
    }

    @Test
    void unaTareaInexistenteEsNotFound() {
        Ctx c = ctx();
        JsonNode r = graphql(c.t().designerToken(), CAMBIAR, Map.of("id", UUID.randomUUID().toString(), "s", "IN_PROGRESS"));
        assertThat(errorCode(r)).isEqualTo("NOT_FOUND");
    }

    @Test
    void editarCambiaElTituloYReasignaAvisandoAQuienLaRecibe() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        JsonNode r = graphql(c.t().designerToken(), EDITAR, Map.of(
                "id", id,
                "i", Map.of("assignedTo", c.developerId(), "title", "Revisar el modelo (v2)",
                        "description", "Con multiplicidades")));

        assertThat(r.has("errors")).as(r.toString()).isFalse();
        JsonNode task = r.get("data").get("updateTask");
        assertThat(task.get("title").asText()).isEqualTo("Revisar el modelo (v2)");
        assertThat(task.get("description").asText()).isEqualTo("Con multiplicidades");
        assertThat(task.get("assignedTo").asText()).isEqualTo(c.developerId());
        // Los nombres se resuelven para que la interfaz no tenga que buscarlos.
        assertThat(task.get("assignedToName").isNull()).isFalse();
        assertThat(task.get("createdByName").isNull()).isFalse();

        // Solo se avisa a quien la recibe ahora.
        assertThat(notificacionesDe(c.t().developerToken(), "TASK_ASSIGNED")).hasSize(1);
    }

    @Test
    void editarValidaElTituloYQueElAsignadoSeaDeLaEmpresa() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        JsonNode sinTitulo = graphql(c.t().designerToken(), EDITAR, Map.of(
                "id", id, "i", Map.of("assignedTo", c.designer2Id(), "title", "   ")));
        assertThat(errorCode(sinTitulo)).isEqualTo("VALIDATION_ERROR");

        JsonNode ajeno = graphql(c.t().designerToken(), EDITAR, Map.of(
                "id", id, "i", Map.of("assignedTo", UUID.randomUUID().toString(), "title", "Ajena")));
        assertThat(errorCode(ajeno)).isEqualTo("USER_NOT_ELIGIBLE");
    }

    @Test
    void editarYEliminarSonDeQuienLaEncargoNoDeQuienLaTieneAsignada() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        // designer2 la tiene asignada: puede avanzar el estado, pero no gestionarla.
        JsonNode edita = graphql(c.t().designer2Token(), EDITAR, Map.of(
                "id", id, "i", Map.of("assignedTo", c.designer2Id(), "title", "Mía ahora")));
        assertThat(errorCode(edita)).isEqualTo("FORBIDDEN");

        JsonNode elimina = graphql(c.t().designer2Token(), ELIMINAR, Map.of("id", id));
        assertThat(errorCode(elimina)).isEqualTo("FORBIDDEN");

        // El administrador de la empresa sí puede.
        JsonNode admin = graphql(c.t().adminToken(), EDITAR, Map.of(
                "id", id, "i", Map.of("assignedTo", c.designer2Id(), "title", "Corregida")));
        assertThat(admin.has("errors")).as(admin.toString()).isFalse();
    }

    @Test
    void eliminarLaQuitaDeLaListaYNoAplicaALasAutomaticas() {
        Ctx c = ctx();
        String id = crear(c, c.t().designerToken(), Map.of()).get("data").get("createTask").get("id").asText();

        JsonNode r = graphql(c.t().designerToken(), ELIMINAR, Map.of("id", id));
        assertThat(r.has("errors")).as(r.toString()).isFalse();
        assertThat(r.get("data").get("deleteTask").asText()).isEqualTo(id);

        JsonNode quedan = graphql(c.t().designer2Token(), "query { myTasks { id } }", Map.of());
        assertThat(quedan.get("data").get("myTasks")).isEmpty();

        // Una tarea automática no se borra a mano.
        saveDiagram(c.t().designerToken(), c.diagramId(), Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "c1", "name", "Cliente", "x", 10, "y", 10,
                        "attributes", List.of(Map.of("name", "nombre", "type", "String")))), "relationships", List.of()), 1);
        String auto = graphql(c.t().designerToken(),
                "mutation($d: ID!) { generateBackendCode(diagramId: $d, language: \"JAVA\") { id } }",
                Map.of("d", c.diagramId())).get("data").get("generateBackendCode").get("id").asText();
        assertThat(errorCode(graphql(c.t().designerToken(), ELIMINAR, Map.of("id", auto))))
                .isEqualTo("INVALID_STATE_TRANSITION");
    }
}
