package com.diagramas.platform.support.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.diagramas.platform.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * El almacenamiento de archivos (XMI) con la configuración de producción: variables
 * {@code AZURE_STORAGE_CONNECTION_STRING} y {@code AZURE_STORAGE_CONTAINER}, aquí contra Azurite, el emulador de
 * Azure Storage que se levanta en Docker. En Azure real, la cadena de conexión sale de la cuenta de almacenamiento
 * (o se usa una identidad administrada en vez de clave de acceso).
 */
class AzureBlobStorageIT {

    private static final String CONTAINER_NAME = "diagramas-prueba";

    // Cuenta y clave públicas y fijas del emulador Azurite; no son un secreto real.
    private static final String CUENTA_EMULADOR = "devstoreaccount1";
    private static final String CLAVE_EMULADOR =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.37.0")
            .withExposedPorts(10000)
            .waitingFor(Wait.forListeningPort());

    private static String connectionString;
    private static BlobServiceClient cliente;

    @BeforeAll
    static void arrancarAzurite() {
        AZURITE.start();
        String endpoint = "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/" + CUENTA_EMULADOR;
        connectionString = "DefaultEndpointsProtocol=http;AccountName=" + CUENTA_EMULADOR + ";AccountKey=" + CLAVE_EMULADOR
                + ";BlobEndpoint=" + endpoint + ";";

        cliente = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
        cliente.createBlobContainer(CONTAINER_NAME);
    }

    @AfterAll
    static void pararAzurite() {
        AZURITE.stop();
    }

    private static AppProperties props(String container, Path localDir) {
        return new AppProperties(
                new AppProperties.Jwt("x", 60),
                "x",
                List.of(),
                new AppProperties.Rabbit("h", 1, "u", "p"),
                new AppProperties.Ai("http://ai", "k", 30),
                new AppProperties.Fcm("", ""),
                new AppProperties.Blob(connectionString, "", container),
                new AppProperties.Storage(localDir.toString()),
                new AppProperties.Collab(false),
                new AppProperties.Codegen("com.generated.app"));
    }

    @Test
    void conContenedorConfiguradoElArchivoQuedaEnElBlob(@TempDir Path dir) {
        StorageService storage = new StorageConfig().storageService(props(CONTAINER_NAME, dir));

        String key = storage.put("xmi/empresa-1/diagrama-1/1.xmi", "<xmi>contenido</xmi>".getBytes(StandardCharsets.UTF_8), "application/xml");

        assertThat(key).isEqualTo("xmi/empresa-1/diagrama-1/1.xmi");
        var blob = cliente.getBlobContainerClient(CONTAINER_NAME).getBlobClient(key);
        assertThat(blob.downloadContent().toString()).isEqualTo("<xmi>contenido</xmi>");
        assertThat(blob.getProperties().getContentType()).isEqualTo("application/xml");
    }

    @Test
    void sinContenedorConfiguradoSeGuardaEnDisco(@TempDir Path dir) {
        StorageService storage = new StorageConfig().storageService(props("", dir));

        storage.put("xmi/a/b.xmi", "x".getBytes(StandardCharsets.UTF_8), "application/xml");

        assertThat(dir.resolve("xmi/a/b.xmi")).exists();
    }

    @Test
    void unContenedorQueNoExisteFallaConUnaExcepcionQueElLlamadorPuedeCapturar(@TempDir Path dir) {
        // XmiService captura RuntimeException y sigue: exportar un XMI no depende de que Azure esté bien configurado.
        StorageService storage = new StorageConfig().storageService(props("no-existe-este-contenedor", dir));

        assertThatThrownBy(() -> storage.put("k", new byte[] {1}, "application/xml")).isInstanceOf(RuntimeException.class);
    }
}
