package com.diagramas.platform.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lo que el backend exige para arrancar en producción. */
class ProductionSettingsTest {

    private static final String JWT = "un-secreto-de-jwt-que-tiene-mas-de-32-bytes";

    /** Una configuración correcta; cada prueba estropea una sola cosa. */
    private static AppProperties props(String jwt, String seed, String aiKey, boolean distributed, String rabbitPassword, List<String> cors) {
        return new AppProperties(
                new AppProperties.Jwt(jwt, 60),
                seed,
                cors,
                new AppProperties.Rabbit("rabbitmq", 61613, "diagramas", rabbitPassword),
                new AppProperties.Ai("http://ai-service:8000", aiKey, 30),
                new AppProperties.Fcm("", ""),
                new AppProperties.S3("", "us-east-1", ""),
                new AppProperties.Storage("./data/storage"),
                new AppProperties.Collab(distributed),
                new AppProperties.Codegen("com.generated.app"));
    }

    private static AppProperties buena() {
        return props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "otra-clave-larga", List.of("https://diagramas.ejemplo.com"));
    }

    @Test
    void unaConfiguracionCorrectaNoTieneProblemas() {
        assertThat(ProductionSettings.problems(buena(), "clave-de-la-base")).isEmpty();
    }

    @Test
    void unSecretoJwtCortoSeRechaza() {
        var p = props("corto", "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "otra-clave-larga", List.of("https://x.com"));

        assertThat(ProductionSettings.problems(p, "db")).singleElement().asString().contains("JWT_SECRET");
    }

    @Test
    void laContrasenaDelAdministradorTieneQueSerLarga() {
        var p = props(JWT, "corta", "clave-interna-de-ia-larga", true, "otra-clave-larga", List.of("https://x.com"));

        assertThat(ProductionSettings.problems(p, "db")).singleElement().asString().contains("SEED_ADMIN_PASSWORD");
    }

    @Test
    void laContrasenaDeLaBaseNoPuedeEstarVacia() {
        assertThat(ProductionSettings.problems(buena(), "")).singleElement().asString().contains("DB_PASSWORD");
        assertThat(ProductionSettings.problems(buena(), null)).singleElement().asString().contains("DB_PASSWORD");
    }

    @Test
    void laClaveInternaDeLaIaNoPuedeEstarVaciaNiSerCorta() {
        var p = props(JWT, "una-contrasena-larga-1", "", true, "otra-clave-larga", List.of("https://x.com"));

        assertThat(ProductionSettings.problems(p, "db")).singleElement().asString().contains("AI_INTERNAL_KEY");
    }

    @Test
    void conColaboracionDistribuidaRabbitNoPuedeUsarLaContrasenaPorOmision() {
        var guest = props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "guest", List.of("https://x.com"));
        var vacia = props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "", List.of("https://x.com"));
        var local = props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", false, "guest", List.of("https://x.com"));

        assertThat(ProductionSettings.problems(guest, "db")).singleElement().asString().contains("RABBIT_PASSWORD");
        assertThat(ProductionSettings.problems(vacia, "db")).singleElement().asString().contains("RABBIT_PASSWORD");
        // Sin colaboración distribuida no se usa RabbitMQ: no se exige nada.
        assertThat(ProductionSettings.problems(local, "db")).isEmpty();
    }

    @Test
    void corsNoPuedeSerUnComodinNiEstarVacio() {
        var comodin = props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "otra-clave-larga", List.of("*"));
        var vacio = props(JWT, "una-contrasena-larga-1", "clave-interna-de-ia-larga", true, "otra-clave-larga", List.of());

        assertThat(ProductionSettings.problems(comodin, "db")).singleElement().asString().contains("CORS_ALLOWED_ORIGINS");
        assertThat(ProductionSettings.problems(vacio, "db")).singleElement().asString().contains("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void sinNadaConfiguradoLoDiceTodoDeUnaVez() {
        var p = props("", "", "", true, "guest", List.of());

        assertThat(ProductionSettings.problems(p, "")).hasSize(6);
    }
}
