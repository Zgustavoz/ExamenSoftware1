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

/** Ciclo C1: CU-01 … CU-05, autorización por rol y aislamiento multi-tenant. */
class AccessIT extends AbstractIntegrationTest {

    @Test
    void healthEsPublico() {
        ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/actuator/health", null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void semillaCreaSoftwareAdmin() {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "platform", "username", "admin", "password", ADMIN_PASSWORD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("user").get("roles").get(0).asText()).isEqualTo("SOFTWARE_ADMIN");
        // El hash nunca viaja en la respuesta
        assertThat(r.getBody().toString()).doesNotContain("password");
    }

    @Test
    void cu01_credencialesIncorrectas() {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "platform", "username", "admin", "password", "mala"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(r.getBody().get("message").asText()).isNotBlank();
    }

    @Test
    void cu01_empresaInexistenteNoRevelaNada() {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", "no-existe", "username", "admin", "password", ADMIN_PASSWORD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void cu01_usuarioInactivoYEmpresaDeshabilitada() {
        Tenant t = newTenant();
        JsonNode users = call(HttpMethod.GET, "/api/users", t.adminToken(), null).getBody();
        String developerId = null;
        for (JsonNode u : users) if (u.get("username").asText().equals("developer")) developerId = u.get("id").asText();

        assertThat(call(HttpMethod.PATCH, "/api/users/" + developerId + "/status", t.adminToken(), Map.of("active", false))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> inactive = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", t.slug(), "username", "developer", "password", USER_PASSWORD));
        assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inactive.getBody().get("code").asText()).isEqualTo("USER_INACTIVE");

        call(HttpMethod.PATCH, "/api/companies/" + t.companyId() + "/status", adminToken(), Map.of("active", false));
        ResponseEntity<JsonNode> disabled = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", t.slug(), "username", "designer", "password", USER_PASSWORD));
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(disabled.getBody().get("code").asText()).isEqualTo("COMPANY_DISABLED");
    }

    @Test
    void jwtLlevaCompanyIdYRoles() {
        Tenant t = newTenant();
        JsonNode me = call(HttpMethod.GET, "/api/auth/me", t.designerToken(), null).getBody();
        assertThat(me.get("companyId").asText()).isEqualTo(t.companyId().toString());
        assertThat(me.get("roles").get(0).asText()).isEqualTo("DESIGNER");
        // Payload del JWT
        String payload = new String(java.util.Base64.getUrlDecoder().decode(t.designerToken().split("\\.")[1]));
        assertThat(payload).contains("company_id").contains("DESIGNER");
    }

    @Test
    void sinTokenOTokenInvalido_401() {
        assertThat(call(HttpMethod.GET, "/api/projects", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> bad = call(HttpMethod.GET, "/api/projects", "esto.no.es.jwt", null);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody().get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void cu02_soloSoftwareAdminGestionaEmpresas() {
        Tenant t = newTenant();
        for (String token : List.of(t.adminToken(), t.designerToken(), t.developerToken())) {
            ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/api/companies", token, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(call(HttpMethod.GET, "/api/companies", adminToken(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cu02_empresaDuplicadaPorNombreOSlug() {
        String sa = adminToken();
        String slug = "dup" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(call(HttpMethod.POST, "/api/companies", sa, Map.of("name", "Dup " + slug, "slug", slug)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> sameSlug = call(HttpMethod.POST, "/api/companies", sa, Map.of("name", "Otro " + slug, "slug", slug));
        assertThat(sameSlug.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameSlug.getBody().get("code").asText()).isEqualTo("DUPLICATE_COMPANY");
        ResponseEntity<JsonNode> sameName = call(HttpMethod.POST, "/api/companies", sa, Map.of("name", ("DUP " + slug), "slug", slug + "x"));
        assertThat(sameName.getBody().get("code").asText()).isEqualTo("DUPLICATE_COMPANY");
        // datos inválidos
        ResponseEntity<JsonNode> invalid = call(HttpMethod.POST, "/api/companies", sa, Map.of("name", "", "slug", "Con Espacios"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(invalid.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void cu03_usuariosSoloDeSuEmpresa_duplicadosYRoles() {
        Tenant a = newTenant();
        Tenant b = newTenant();
        // usuario duplicado (mismo username en la misma empresa)
        ResponseEntity<JsonNode> dup = call(HttpMethod.POST, "/api/users", a.adminToken(), Map.of("username", "designer",
                "email", "otro@x.test", "password", USER_PASSWORD, "roles", List.of("DESIGNER")));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("DUPLICATE_USER");
        // el mismo username SÍ se permite en otra empresa
        assertThat(createUserStatus(b.adminToken(), "designer3")).isEqualTo(HttpStatus.CREATED);
        // nunca SOFTWARE_ADMIN
        ResponseEntity<JsonNode> sa = call(HttpMethod.POST, "/api/users", a.adminToken(), Map.of("username", "hacker",
                "email", "h@x.test", "password", USER_PASSWORD, "roles", List.of("SOFTWARE_ADMIN")));
        assertThat(sa.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // datos incompletos
        ResponseEntity<JsonNode> incomplete = call(HttpMethod.POST, "/api/users", a.adminToken(), Map.of("username", "x"));
        assertThat(incomplete.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        // un diseñador no administra usuarios
        assertThat(call(HttpMethod.GET, "/api/users", a.designerToken(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // el listado solo trae usuarios de la propia empresa
        JsonNode listA = call(HttpMethod.GET, "/api/users", a.adminToken(), null).getBody();
        for (JsonNode u : listA) assertThat(u.get("companyId").asText()).isEqualTo(a.companyId().toString());
        // no se puede editar un usuario de otra empresa
        String foreignId = call(HttpMethod.GET, "/api/users", b.adminToken(), null).getBody().get(0).get("id").asText();
        ResponseEntity<JsonNode> cross = call(HttpMethod.PATCH, "/api/users/" + foreignId + "/status", a.adminToken(), Map.of("active", false));
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cu03_desactivarNoBorra() {
        Tenant t = newTenant();
        JsonNode created = createUser(t.adminToken(), "temporal", "DEVELOPER");
        String id = created.get("id").asText();
        call(HttpMethod.PATCH, "/api/users/" + id + "/status", t.adminToken(), Map.of("active", false));
        Integer n = jdbc.queryForObject("select count(*) from users where id = ?::uuid and is_active = false", Integer.class, id);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void cu04_cu05_proyectos() {
        Tenant t = newTenant();
        // sin proyectos: respuesta vacía, no error
        JsonNode empty = call(HttpMethod.GET, "/api/projects", t.designerToken(), null).getBody();
        assertThat(empty.get("content")).isEmpty();

        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/api/projects", t.designerToken(),
                Map.of("name", "Sistema Ventas", "description", "Módulo de ventas"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("ownerId")).isNotNull();

        ResponseEntity<JsonNode> dup = call(HttpMethod.POST, "/api/projects", t.adminToken(),
                Map.of("name", "SISTEMA ventas", "description", "x"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("DUPLICATE_PROJECT");

        // un DEVELOPER no crea proyectos, pero sí los consulta
        assertThat(call(HttpMethod.POST, "/api/projects", t.developerToken(), Map.of("name", "P", "description", "d")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        createProject(t.designerToken(), "Inventario");
        JsonNode filtered = call(HttpMethod.GET, "/api/projects?q=ventas", t.developerToken(), null).getBody();
        assertThat(filtered.get("content")).hasSize(1);
        assertThat(filtered.get("content").get(0).get("name").asText()).isEqualTo("Sistema Ventas");
        assertThat(call(HttpMethod.GET, "/api/projects", t.developerToken(), null).getBody().get("totalElements").asInt()).isEqualTo(2);

        // datos incompletos
        assertThat(call(HttpMethod.POST, "/api/projects", t.designerToken(), Map.of("description", "sin nombre"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aislamientoMultiTenant_recursoDeOtraEmpresaEs404() {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String projectA = createProject(a.designerToken(), "Secreto de A");

        ResponseEntity<JsonNode> cross = call(HttpMethod.GET, "/api/projects/" + projectA, b.designerToken(), null);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cross.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        // el listado de B no ve el proyecto de A
        assertThat(call(HttpMethod.GET, "/api/projects?q=Secreto", b.designerToken(), null).getBody().get("content")).isEmpty();

        // GraphQL: el diagrama de A no es visible para B
        JsonNode diagram = createDiagram(a.designerToken(), projectA, "D1");
        JsonNode read = graphql(b.designerToken(), "query($id: ID!) { diagram(id: $id) { id } }", Map.of("id", diagram.get("id").asText()));
        assertThat(errorCode(read)).isEqualTo("NOT_FOUND");
        JsonNode list = graphql(b.designerToken(), "query($p: ID!) { diagrams(projectId: $p) { id } }", Map.of("p", projectA));
        assertThat(errorCode(list)).isEqualTo("NOT_FOUND");
        // y B tampoco puede crear diagramas en un proyecto de A
        JsonNode create = graphql(b.designerToken(), "mutation($p: ID!) { createDiagram(projectId: $p, name: \"x\") { id } }", Map.of("p", projectA));
        assertThat(errorCode(create)).isEqualTo("NOT_FOUND");
    }

    @Test
    void cu02_softwareAdminCreaCompanyAdmin() {
        String sa = adminToken();
        String slug = "adm" + UUID.randomUUID().toString().substring(0, 6);
        String companyId = call(HttpMethod.POST, "/api/companies", sa, Map.of("name", "Co " + slug, "slug", slug)).getBody().get("id").asText();
        ResponseEntity<JsonNode> admin = call(HttpMethod.POST, "/api/companies/" + companyId + "/admins", sa,
                Map.of("username", "root", "email", "root@x.test", "password", USER_PASSWORD));
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(admin.getBody().get("roles").get(0).asText()).isEqualTo("COMPANY_ADMIN");
        assertThat(login(slug, "root", USER_PASSWORD)).isNotBlank();
    }

    private HttpStatus createUserStatus(String token, String username) {
        return (HttpStatus) call(HttpMethod.POST, "/api/users", token, Map.of("username", username, "email", username + "@x.test",
                "password", USER_PASSWORD, "roles", List.of("DESIGNER"))).getStatusCode();
    }
}
