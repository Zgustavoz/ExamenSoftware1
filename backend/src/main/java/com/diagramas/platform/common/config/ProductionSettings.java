package com.diagramas.platform.common.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprobaciones de configuración para producción. Un servidor público arrancado con la contraseña de
 * ejemplo o con una clave vacía es peor que un servidor que no arranca: aquí se detecta al iniciar y se
 * dice de una vez todo lo que hay que arreglar.
 */
public final class ProductionSettings {

    static final int MIN_JWT_SECRET_BYTES = 32;
    static final int MIN_SEED_PASSWORD = 12;
    static final int MIN_AI_KEY = 16;

    private ProductionSettings() {}

    /** @return los problemas encontrados, en español; vacío si la configuración es aceptable */
    public static List<String> problems(AppProperties props, String dbPassword) {
        List<String> problems = new ArrayList<>();

        String jwt = props.jwt() == null ? null : props.jwt().secret();
        if (jwt == null || jwt.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("JWT_SECRET debe tener al menos " + MIN_JWT_SECRET_BYTES + " caracteres (genere uno con: openssl rand -base64 48).");
        }

        String seed = props.seedAdminPassword();
        if (seed == null || seed.length() < MIN_SEED_PASSWORD) {
            problems.add("SEED_ADMIN_PASSWORD debe tener al menos " + MIN_SEED_PASSWORD + " caracteres: es la contraseña del administrador de la plataforma.");
        }

        if (dbPassword == null || dbPassword.isBlank()) {
            problems.add("DB_PASSWORD no puede estar vacío.");
        }

        String aiKey = props.ai() == null ? null : props.ai().internalKey();
        if (aiKey == null || aiKey.length() < MIN_AI_KEY) {
            problems.add("AI_INTERNAL_KEY debe tener al menos " + MIN_AI_KEY + " caracteres: protege el servicio de IA.");
        }

        if (props.collab() != null && props.collab().distributed()) {
            String rabbit = props.rabbit() == null ? null : props.rabbit().password();
            if (rabbit == null || rabbit.isBlank() || rabbit.equals("guest")) {
                problems.add("RABBIT_PASSWORD no puede estar vacío ni ser «guest» con la colaboración distribuida.");
            }
        }

        List<String> origins = props.corsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            problems.add("CORS_ALLOWED_ORIGINS debe indicar el origen público de la aplicación (por ejemplo https://diagramas.ejemplo.com).");
        } else if (origins.stream().anyMatch(o -> o.trim().equals("*"))) {
            problems.add("CORS_ALLOWED_ORIGINS no puede ser «*»: con sesión por JWT hay que indicar los orígenes exactos.");
        }

        return problems;
    }
}
