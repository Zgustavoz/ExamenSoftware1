package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * El diagrama de ejemplo «Tienda en línea» pasa por el camino real: se guarda (con la validación del servidor),
 * se genera el código y se descarga el ZIP. Deja el proyecto generado en {@code target/tienda-generada} para
 * poder arrancarlo y probarlo con Postman.
 */
class TiendaEnLineaIT extends AbstractIntegrationTest {

    private static final String GEN = "mutation($d: ID!, $l: String!) { generateBackendCode(diagramId: $d, language: $l) "
            + "{ id status resultJson } }";

    @Test
    void elDiagramaEsValidoYGeneraUnBackendConApiParaCadaEntidad() throws Exception {
        JsonNode diagrama;
        try (var in = getClass().getResourceAsStream("/diagramas/tienda-en-linea.json")) {
            diagrama = new ObjectMapper().readTree(in);
        }
        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "Tienda");
        String id = createDiagram(t.designerToken(), project, "Tienda en línea").get("id").asText();

        // El servidor valida el diagrama con las mismas reglas que el editor (tipos, relaciones, multiplicidades…).
        JsonNode saved = saveDiagram(t.designerToken(), id, diagrama, 1);
        assertThat(saved.has("errors")).as(saved.toString()).isFalse();

        // Está completo: genera código sin DIAGRAM_INCOMPLETE.
        JsonNode gen = graphql(t.designerToken(), GEN, Map.of("d", id, "l", "JAVA"));
        assertThat(gen.has("errors")).as(gen.toString()).isFalse();
        JsonNode task = gen.get("data").get("generateBackendCode");
        assertThat(task.get("status").asText()).isEqualTo("COMPLETED");

        ResponseEntity<byte[]> zip = rest.exchange(
                "/api/generated-code/tasks/" + task.get("id").asText() + "/download", HttpMethod.GET,
                new HttpEntity<>(bearer(t.developerToken())), byte[].class);
        assertThat(zip.getStatusCode().is2xxSuccessful()).isTrue();

        Path destino = Path.of("target", "tienda-generada");
        List<String> archivos = extraer(zip.getBody(), destino);

        String base = "src/main/java/com/generated/app/";
        for (String entidad : List.of("Cliente", "Pedido", "Producto", "Pago")) {
            assertThat(archivos).contains(
                    base + "model/" + entidad + ".java",
                    base + "repository/" + entidad + "Repository.java",
                    base + "web/" + entidad + "Controller.java");
        }
        assertThat(archivos).contains("pom.xml", "src/main/resources/application.properties");

        // Cada relación se serializa por un solo extremo: si no, con datos enlazados el JSON de la API no termina
        // nunca (un cliente con sus pedidos, cada pedido con su cliente…) y llega truncado.
        String cliente = Files.readString(destino.resolve(base + "model/Cliente.java"));
        assertThat(cliente).containsPattern("@OneToMany\\(mappedBy = \"cliente\"\\)\\s+@JsonIgnore\\s+private List<Pedido> pedidos;");
        String pedido = Files.readString(destino.resolve(base + "model/Pedido.java"));
        assertThat(pedido).containsPattern("@ManyToOne\\s+private Cliente cliente;");
        assertThat(pedido).containsPattern("@ManyToMany\\s+private List<Producto> productos;");
        String pago = Files.readString(destino.resolve(base + "model/Pago.java"));
        assertThat(pago).containsPattern("@OneToOne\\(mappedBy = \"pago\"\\)\\s+@JsonIgnore\\s+private Pedido pedido;");
    }

    private static List<String> extraer(byte[] zip, Path destino) throws Exception {
        List<String> nombres = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                Path fichero = destino.resolve(e.getName()).normalize();
                assertThat(fichero.startsWith(destino.normalize())).as("zip-slip: " + e.getName()).isTrue();
                Files.createDirectories(fichero.getParent());
                Files.write(fichero, in.readAllBytes());
                nombres.add(e.getName());
            }
        }
        return nombres;
    }
}
