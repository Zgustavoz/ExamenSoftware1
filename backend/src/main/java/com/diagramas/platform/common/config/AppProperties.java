package com.diagramas.platform.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración de la aplicación; todos los valores provienen de variables de entorno / .env. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        String seedAdminPassword,
        List<String> corsAllowedOrigins,
        Rabbit rabbit,
        Ai ai,
        Fcm fcm,
        S3 s3,
        Storage storage,
        Collab collab,
        Codegen codegen) {

    public record Jwt(String secret, long expirationMinutes) {}

    public record Rabbit(String host, int stompPort, String user, String password) {}

    public record Ai(String url, String internalKey, int timeoutSeconds) {}

    public record Fcm(String credentialsPath, String credentialsJson) {}

    public record S3(String bucket, String region, String endpoint) {}

    public record Storage(String localDir) {}

    public record Collab(boolean distributed) {}

    public record Codegen(String basePackage) {}
}
