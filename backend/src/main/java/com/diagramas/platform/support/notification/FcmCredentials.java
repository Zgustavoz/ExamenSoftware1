package com.diagramas.platform.support.notification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * De dónde salen las credenciales de Firebase (la clave de la cuenta de servicio).
 *
 * <p>En producción no hay un archivo que montar: toda la clave va **en una sola línea** en la variable
 * {@code FCM_CREDENTIALS_JSON}, en cualquiera de estas dos formas:
 *
 * <ul>
 *   <li><b>base64</b> (recomendada): no tiene comillas, llaves ni {@code \n}, así que sobrevive intacta a
 *       {@code docker compose}, a un archivo {@code .env} y a la consola de AWS.
 *   <li><b>JSON</b> tal cual, en una línea. Ojo: el {@code \n} que lleva la clave privada solo se conserva si
 *       la variable llega al proceso sin que nadie lo interprete.
 * </ul>
 *
 * <p>Si no hay variable, se usa el archivo de {@code FCM_CREDENTIALS_PATH} (desarrollo).
 */
final class FcmCredentials {

    private FcmCredentials() {}

    /**
     * @return el JSON de la cuenta de servicio, o vacío si no hay ninguna credencial configurada
     * @throws IllegalArgumentException si la variable está definida pero no es un JSON ni un base64 de un JSON
     */
    static Optional<byte[]> resolve(String inline, String path) {
        String value = unquote(inline);
        if (!value.isEmpty()) {
            return Optional.of(fromInline(value));
        }
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(Path.of(path)));
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer el archivo de credenciales de Firebase: " + e.getMessage(), e);
        }
    }

    private static byte[] fromInline(String value) {
        if (value.startsWith("{")) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        try {
            // El decodificador MIME ignora espacios y saltos de línea que un editor pueda haber metido.
            byte[] decoded = Base64.getMimeDecoder().decode(value);
            if (new String(decoded, StandardCharsets.UTF_8).stripLeading().startsWith("{")) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // cae al mensaje de abajo
        }
        // Sin citar el valor: es un secreto.
        throw new IllegalArgumentException("FCM_CREDENTIALS_JSON no es un JSON ni un base64 de un JSON válido.");
    }

    /** Un {@code .env} con comillas alrededor del valor no debe romper la lectura. */
    private static String unquote(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.length() >= 2
                && ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\"")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }
}
