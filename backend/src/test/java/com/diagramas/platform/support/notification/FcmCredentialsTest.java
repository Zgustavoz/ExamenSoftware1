package com.diagramas.platform.support.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** La clave de Firebase en una sola línea (variable de entorno) o en un archivo. */
class FcmCredentialsTest {

    /** Una cuenta de servicio de mentira, con el salto de línea escapado que lleva la clave privada real. */
    private static final String JSON = "{\"type\":\"service_account\",\"project_id\":\"demo\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nABC\\n-----END PRIVATE KEY-----\\n\"}";

    private static String texto(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aceptaElJsonTalCualEnUnaLinea() {
        var r = FcmCredentials.resolve(JSON, "");

        assertThat(r).isPresent();
        assertThat(texto(r.get())).isEqualTo(JSON);
    }

    @Test
    void aceptaElJsonCodificadoEnBase64() {
        String base64 = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        var r = FcmCredentials.resolve(base64, "");

        assertThat(texto(r.orElseThrow())).isEqualTo(JSON);
    }

    @Test
    void elBase64SobreviveASaltosDeLineaMetidosPorUnEditor() {
        String base64 = Base64.getMimeEncoder(20, "\n".getBytes()).encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(texto(FcmCredentials.resolve(base64, null).orElseThrow())).isEqualTo(JSON);
    }

    @Test
    void ignoraLasComillasQueUnArchivoEnvPuedePonerAlrededor() {
        String base64 = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(texto(FcmCredentials.resolve("'" + base64 + "'", "").orElseThrow())).isEqualTo(JSON);
        assertThat(texto(FcmCredentials.resolve("\"" + base64 + "\"", "").orElseThrow())).isEqualTo(JSON);
        assertThat(texto(FcmCredentials.resolve("'" + JSON + "'", "").orElseThrow())).isEqualTo(JSON);
    }

    @Test
    void laVariableTienePrioridadSobreElArchivo(@TempDir Path dir) throws IOException {
        Path archivo = Files.writeString(dir.resolve("otra.json"), "{\"project_id\":\"del-archivo\"}");

        var r = FcmCredentials.resolve(JSON, archivo.toString());

        assertThat(texto(r.orElseThrow())).isEqualTo(JSON);
    }

    @Test
    void sinVariableUsaElArchivo(@TempDir Path dir) throws IOException {
        Path archivo = Files.writeString(dir.resolve("clave.json"), JSON);

        assertThat(texto(FcmCredentials.resolve("", archivo.toString()).orElseThrow())).isEqualTo(JSON);
    }

    @Test
    void sinNadaConfiguradoNoHayCredenciales(@TempDir Path dir) {
        assertThat(FcmCredentials.resolve("", "")).isEmpty();
        assertThat(FcmCredentials.resolve(null, null)).isEmpty();
        assertThat(FcmCredentials.resolve("   ", dir.resolve("no-existe.json").toString())).isEmpty();
    }

    @Test
    void unValorQueNoEsJsonNiBase64SeRechazaSinMostrarElSecreto() {
        assertThatThrownBy(() -> FcmCredentials.resolve("esto-no-es-una-clave-valida!!", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FCM_CREDENTIALS_JSON")
                .hasMessageNotContaining("esto-no-es");
    }

    @Test
    void unBase64QueNoContieneUnJsonSeRechaza() {
        String base64 = Base64.getEncoder().encodeToString("hola mundo".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> FcmCredentials.resolve(base64, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es un JSON ni un base64");
    }
}
