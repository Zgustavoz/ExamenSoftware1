package com.diagramas.platform.support.storage;

import com.diagramas.platform.common.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Con {@code S3_BUCKET} usa S3 (o MinIO/LocalStack vía {@code S3_ENDPOINT}); si no, guarda en disco local. */
@Configuration
public class StorageConfig {

    @Bean
    StorageService storageService(AppProperties props) {
        String bucket = props.s3().bucket();
        if (bucket == null || bucket.isBlank()) {
            return new LocalStorageService(Path.of(props.storage().localDir()));
        }
        var builder = S3Client.builder().region(Region.of(props.s3().region()));
        String endpoint = props.s3().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return new S3StorageService(builder.build(), bucket);
    }

    static class S3StorageService implements StorageService {
        private final S3Client client;
        private final String bucket;

        S3StorageService(S3Client client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        @Override
        public String put(String key, byte[] content, String contentType) {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(content));
            return key;
        }
    }

    static class LocalStorageService implements StorageService {
        private final Path root;

        LocalStorageService(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public String put(String key, byte[] content, String contentType) {
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Clave de almacenamiento inválida");
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo guardar el archivo", e);
            }
            return key;
        }
    }
}
