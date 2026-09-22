package com.diagramas.platform.support.storage;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.diagramas.platform.common.config.AppProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Con {@code AZURE_STORAGE_CONTAINER} usa Azure Blob Storage; si no, guarda en disco local. Con
 * {@code AZURE_STORAGE_CONNECTION_STRING} (Azurite en desarrollo) autentica por clave; si no, con
 * {@code AZURE_STORAGE_ACCOUNT_URL} usa Managed Identity (sin credenciales estáticas, recomendado en Azure). */
@Configuration
public class StorageConfig {

    @Bean
    StorageService storageService(AppProperties props) {
        String container = props.blob().container();
        if (container == null || container.isBlank()) {
            return new LocalStorageService(Path.of(props.storage().localDir()));
        }
        var builder = new BlobServiceClientBuilder();
        String connectionString = props.blob().connectionString();
        if (connectionString != null && !connectionString.isBlank()) {
            builder.connectionString(connectionString);
        } else {
            builder.endpoint(props.blob().accountUrl()).credential(new DefaultAzureCredentialBuilder().build());
        }
        BlobContainerClient client = builder.buildClient().getBlobContainerClient(container);
        return new AzureBlobStorageService(client);
    }

    static class AzureBlobStorageService implements StorageService {
        private final BlobContainerClient container;

        AzureBlobStorageService(BlobContainerClient container) {
            this.container = container;
        }

        @Override
        public String put(String key, byte[] content, String contentType) {
            var blob = container.getBlobClient(key);
            blob.upload(new ByteArrayInputStream(content), content.length, true);
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
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
