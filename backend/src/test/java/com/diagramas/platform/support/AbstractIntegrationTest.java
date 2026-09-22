package com.diagramas.platform.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Base de las pruebas de integración: PostgreSQL real (Testcontainers) + ai-service simulado. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String ADMIN_PASSWORD = "Admin#12345";
    protected static final String USER_PASSWORD = "Password#123";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final AiStub AI = new AiStub();
    private static final Path STORAGE;

    static {
        POSTGRES.start();
        try {
            STORAGE = Files.createTempDirectory("storage-it");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.jwt.secret", () -> "test-secret-test-secret-test-secret-1234");
        r.add("app.jwt.expiration-minutes", () -> "60");
        r.add("app.seed-admin-password", () -> ADMIN_PASSWORD);
        r.add("app.ai.url", AI::url);
        r.add("app.ai.internal-key", () -> AiStub.KEY);
        r.add("app.ai.timeout-seconds", () -> "2");
        r.add("app.collab.distributed", () -> "false");
        r.add("app.storage.local-dir", STORAGE::toString);
        r.add("app.cors-allowed-origins", () -> "http://localhost:4200");
        r.add("app.bcrypt-strength", () -> "4"); // acelera las pruebas; producción usa 10
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;

    // ------------------------------------------------------------------ REST

    protected HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, bearer(token)), JsonNode.class);
    }

    protected String login(String slug, String username, String password) {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("companySlug", slug, "username", username, "password", password));
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Login falló: " + r.getStatusCode() + " " + r.getBody());
        }
        return r.getBody().get("token").asText();
    }

    protected String adminToken() {
        return login("platform", "admin", ADMIN_PASSWORD);
    }

    /** Empresa con un usuario por rol. Devuelve slug, tokens y ids. */
    public record Tenant(String slug, UUID companyId, String adminToken, String designerToken, String developerToken,
                         String designer2Token) {}

    protected Tenant newTenant() {
        String slug = "t" + UUID.randomUUID().toString().substring(0, 8);
        String sa = adminToken();
        JsonNode company = call(HttpMethod.POST, "/api/companies", sa, Map.of("name", "Empresa " + slug, "slug", slug)).getBody();
        String companyId = company.get("id").asText();
        call(HttpMethod.POST, "/api/companies/" + companyId + "/admins", sa,
                Map.of("username", "boss", "email", "boss@" + slug + ".test", "password", USER_PASSWORD, "fullName", "Jefe"));
        String boss = login(slug, "boss", USER_PASSWORD);
        createUser(boss, "designer", "DESIGNER");
        createUser(boss, "designer2", "DESIGNER");
        createUser(boss, "developer", "DEVELOPER");
        return new Tenant(slug, UUID.fromString(companyId), boss, login(slug, "designer", USER_PASSWORD),
                login(slug, "developer", USER_PASSWORD), login(slug, "designer2", USER_PASSWORD));
    }

    protected JsonNode createUser(String adminToken, String username, String role) {
        return call(HttpMethod.POST, "/api/users", adminToken, Map.of("username", username, "email", username + "@x.test",
                "password", USER_PASSWORD, "fullName", username, "roles", List.of(role))).getBody();
    }

    protected String createProject(String token, String name) {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/projects", token, Map.of("name", name, "description", "d"));
        return r.getBody().get("id").asText();
    }

    // ------------------------------------------------------------------ GraphQL

    protected JsonNode graphql(String token, String query, Map<String, Object> variables) {
        return call(HttpMethod.POST, "/graphql", token, Map.of("query", query, "variables", variables)).getBody();
    }

    /** Devuelve extensions.code del primer error, o null si no hay errores. */
    protected static String errorCode(JsonNode gql) {
        JsonNode errors = gql.get("errors");
        return errors == null || errors.isEmpty() ? null : errors.get(0).path("extensions").path("code").asText();
    }

    protected static final String DIAGRAM_FIELDS = "id projectId name type version contentJson sourceDiagramId";

    protected JsonNode createDiagram(String token, String projectId, String name) {
        JsonNode r = graphql(token, "mutation($p: ID!, $n: String!) { createDiagram(projectId: $p, name: $n) { " + DIAGRAM_FIELDS + " } }",
                Map.of("p", projectId, "n", name));
        if (r.has("errors")) throw new IllegalStateException(r.toString());
        return r.get("data").get("createDiagram");
    }

    protected JsonNode getDiagram(String token, String id) {
        return graphql(token, "query($id: ID!) { diagram(id: $id) { " + DIAGRAM_FIELDS + " } }", Map.of("id", id))
                .get("data").get("diagram");
    }

    protected JsonNode saveDiagram(String token, String id, Object content, int baseVersion) {
        return graphql(token, "mutation($id: ID!, $c: JSON!, $v: Int!) { saveDiagram(id: $id, contentJson: $c, baseVersion: $v) { "
                + DIAGRAM_FIELDS + " } }", Map.of("id", id, "c", content, "v", baseVersion));
    }

    @BeforeAll
    static void containersReady() {
        // El arranque del contenedor ocurre en el inicializador estático; este método fuerza la carga de la clase.
    }
}
