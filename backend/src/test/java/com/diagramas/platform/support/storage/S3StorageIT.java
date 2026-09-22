package com.diagramas.platform.support.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramas.platform.common.config.AppProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * El almacenamiento de archivos (XMI) con la configuración de producción: variables {@code S3_BUCKET},
 * {@code AWS_REGION} y, aquí, {@code S3_ENDPOINT} apuntando a MinIO, un S3 compatible que se levanta en Docker.
 * En AWS real se omite {@code S3_ENDPOINT} y las credenciales salen del rol de la instancia.
 */
class S3StorageIT {

    private static final String BUCKET = "diagramas-prueba";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static String endpoint;
    private static S3Client cliente;
    private static String usuarioAnterior;
    private static String claveAnterior;

    @BeforeAll
    static void arrancarMinio() {
        MINIO.start();
        endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);

        // La cadena de credenciales por omisión del SDK: en AWS sale del rol de la instancia, aquí de propiedades.
        usuarioAnterior = System.getProperty("aws.accessKeyId");
        claveAnterior = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "minioadmin");
        System.setProperty("aws.secretAccessKey", "minioadmin");

        cliente = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        cliente.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void pararMinio() {
        restaurar("aws.accessKeyId", usuarioAnterior);
        restaurar("aws.secretAccessKey", claveAnterior);
        if (cliente != null) cliente.close();
        MINIO.stop();
    }

    private static void restaurar(String propiedad, String valor) {
        if (valor == null) System.clearProperty(propiedad);
        else System.setProperty(propiedad, valor);
    }

    private static AppProperties props(String bucket, String endpoint, Path localDir) {
        return new AppProperties(
                new AppProperties.Jwt("x", 60),
                "x",
                List.of(),
                new AppProperties.Rabbit("h", 1, "u", "p"),
                new AppProperties.Ai("http://ai", "k", 30),
                new AppProperties.Fcm("", ""),
                new AppProperties.S3(bucket, "us-east-1", endpoint),
                new AppProperties.Storage(localDir.toString()),
                new AppProperties.Collab(false),
                new AppProperties.Codegen("com.generated.app"));
    }

    @Test
    void conS3ConfiguradoElArchivoQuedaEnElBucket(@TempDir Path dir) {
        StorageService storage = new StorageConfig().storageService(props(BUCKET, endpoint, dir));

        String key = storage.put("xmi/empresa-1/diagrama-1/1.xmi", "<xmi>contenido</xmi>".getBytes(StandardCharsets.UTF_8), "application/xml");

        assertThat(key).isEqualTo("xmi/empresa-1/diagrama-1/1.xmi");
        var objeto = cliente.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(new String(objeto.asByteArray(), StandardCharsets.UTF_8)).isEqualTo("<xmi>contenido</xmi>");
        assertThat(objeto.response().contentType()).isEqualTo("application/xml");
    }

    @Test
    void sinBucketConfiguradoSeGuardaEnDisco(@TempDir Path dir) {
        StorageService storage = new StorageConfig().storageService(props("", "", dir));

        storage.put("xmi/a/b.xmi", "x".getBytes(StandardCharsets.UTF_8), "application/xml");

        assertThat(dir.resolve("xmi/a/b.xmi")).exists();
    }

    @Test
    void unBucketQueNoExisteFallaConUnaExcepcionQueElLlamadorPuedeCapturar(@TempDir Path dir) {
        // XmiService captura RuntimeException y sigue: exportar un XMI no depende de que S3 esté bien configurado.
        StorageService storage = new StorageConfig().storageService(props("no-existe-este-bucket", endpoint, dir));

        assertThatThrownBy(() -> storage.put("k", new byte[] {1}, "application/xml")).isInstanceOf(RuntimeException.class);
    }
}
