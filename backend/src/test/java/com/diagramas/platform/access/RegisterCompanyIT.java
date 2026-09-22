package com.diagramas.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** CU-22 Registrar empresa: alta pública de una empresa con su primer administrador. */
class RegisterCompanyIT extends AbstractIntegrationTest {

    private static Map<String, Object> payload(String slug, Map<String, Object> cambios) {
        Map<String, Object> admin = new java.util.LinkedHashMap<>(Map.of(
                "fullName", "Dueño " + slug,
                "username", "jefe",
                "email", "jefe@" + slug + ".test",
                "password", USER_PASSWORD));
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "companyName", "Empresa " + slug, "companySlug", slug, "admin", admin));
        cambios.forEach((k, v) -> {
            if (k.startsWith("admin.")) admin.put(k.substring(6), v);
            else body.put(k, v);
        });
        return body;
    }

    private static String nuevoSlug() {
        return "reg" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void registraLaEmpresaYDejaLaSesionIniciadaComoAdministrador() {
        String slug = nuevoSlug();

        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/register-company", null, payload(slug, Map.of()));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = r.getBody();
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("user").get("username").asText()).isEqualTo("jefe");
        assertThat(body.get("user").get("roles").get(0).asText()).isEqualTo("COMPANY_ADMIN");
        assertThat(body.toString()).doesNotContain(USER_PASSWORD); // la contraseña nunca vuelve

        // El token sirve de verdad: con él ya se administran los usuarios de la empresa nueva (CU-03).
        String token = body.get("token").asText();
        assertThat(call(HttpMethod.GET, "/api/users", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Y también se puede iniciar sesión con esas credenciales.
        assertThat(login(slug, "jefe", USER_PASSWORD)).isNotBlank();
    }

    @Test
    void laEmpresaNaceActivaYConUnSoloUsuario() {
        String slug = nuevoSlug();
        call(HttpMethod.POST, "/api/auth/register-company", null, payload(slug, Map.of()));

        assertThat(jdbc.queryForObject("select is_active from companies where slug = ?", Boolean.class, slug)).isTrue();
        Integer usuarios = jdbc.queryForObject(
                "select count(*) from users u join companies c on c.id = u.company_id where c.slug = ?", Integer.class, slug);
        assertThat(usuarios).isEqualTo(1);
    }

    @Test
    void elSlugRepetidoSeRechaza() {
        String slug = nuevoSlug();
        call(HttpMethod.POST, "/api/auth/register-company", null, payload(slug, Map.of()));

        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/register-company", null,
                payload(slug, Map.of("companyName", "Otra empresa distinta")));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asText()).isEqualTo("DUPLICATE_COMPANY");
    }

    @Test
    void elNombreRepetidoSeRechaza() {
        String slug = nuevoSlug();
        call(HttpMethod.POST, "/api/auth/register-company", null, payload(slug, Map.of()));

        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/register-company", null,
                payload(nuevoSlug(), Map.of("companyName", "Empresa " + slug)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asText()).isEqualTo("DUPLICATE_COMPANY");
    }

    @Test
    void unRegistroRechazadoNoDejaLaEmpresaCreada() {
        String slug = nuevoSlug();
        // La contraseña corta hace fallar la validación después de haber escrito el nombre de la empresa.
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/register-company", null,
                payload(slug, Map.of("admin.password", "corta")));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("select count(*) from companies where slug = ?", Integer.class, slug)).isZero();
    }

    @Test
    void losDatosInvalidosSeRechazanConMensajeEnEspanol() {
        record Caso(String descripcion, Map<String, Object> cambios) {}
        for (Caso caso : java.util.List.of(
                new Caso("slug con mayúsculas y espacios", Map.of("companySlug", "Con Mayusculas")),
                new Caso("nombre vacío", Map.of("companyName", "")),
                new Caso("correo inválido", Map.of("admin.email", "no-es-un-correo")),
                new Caso("contraseña de menos de 8", Map.of("admin.password", "1234567")),
                new Caso("usuario vacío", Map.of("admin.username", "")))) {
            ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/api/auth/register-company", null,
                    payload(nuevoSlug(), caso.cambios()));

            assertThat(r.getStatusCode()).as(caso.descripcion()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(r.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(r.getBody().get("message").asText()).isNotBlank();
        }
    }

    @Test
    void laEmpresaRegistradaQuedaAisladaDeLasDemas() {
        String slug = nuevoSlug();
        String token = call(HttpMethod.POST, "/api/auth/register-company", null, payload(slug, Map.of()))
                .getBody().get("token").asText();

        // Un proyecto de otra empresa no es visible desde la recién registrada.
        Tenant otra = newTenant();
        String proyectoAjeno = createProject(otra.designerToken(), "Secreto de la otra empresa");

        ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/api/projects/" + proyectoAjeno, token, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Y su administrador no hereda permisos de plataforma.
        assertThat(call(HttpMethod.GET, "/api/companies", token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
