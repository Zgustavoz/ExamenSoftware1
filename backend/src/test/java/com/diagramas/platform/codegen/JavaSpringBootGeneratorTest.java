package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.codegen.generator.CodeGenerator.GeneratedFile;
import com.diagramas.platform.codegen.generator.JavaSpringBootGenerator;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** CP-05: el código generado compila (herencia, asociación 1-*, composición, *-*, 1-1, realización, enum, interfaz). */
class JavaSpringBootGeneratorTest {

    private static final String DIAGRAM = """
            {"schemaVersion":1,"type":"CLASS","classes":[
              {"id":"persona","name":"Persona","stereotype":"abstract","visibility":"PUBLIC","x":10,"y":10,
               "attributes":[{"name":"nombre","type":"String"},{"name":"fecha nacimiento","type":"LocalDate"},{"name":"default","type":"int"}],
               "methods":[{"name":"getNombre","returnType":"String","parameters":[]},
                          {"name":"saludar","returnType":"void","parameters":[{"name":"saludo","type":"String"},{"name":"veces","type":"int"}]}]},
              {"id":"cliente","name":"Cliente","x":300,"y":10,
               "attributes":[{"name":"email","type":"String"},{"name":"activo","type":"boolean"},{"name":"estado","type":"Estado"}],
               "methods":[{"name":"auditar","returnType":"void","parameters":[]}]},
              {"id":"pedido","name":"Pedido","x":10,"y":300,
               "attributes":[{"name":"total","type":"BigDecimal"},{"name":"fecha","type":"LocalDateTime"},{"name":"notas","type":"List<String>"}],
               "methods":[{"name":"calcularTotal","returnType":"BigDecimal","parameters":[]}]},
              {"id":"linea","name":"Linea Pedido","x":300,"y":300,
               "attributes":[{"name":"cantidad","type":"int"},{"name":"precio","type":"Double"}],"methods":[]},
              {"id":"etiqueta","name":"Etiqueta","x":600,"y":300,"attributes":[{"name":"texto","type":"String"}],"methods":[]},
              {"id":"perfil","name":"Perfil","x":600,"y":10,"attributes":[{"name":"bio","type":"String"}],"methods":[]},
              {"id":"estado","name":"Estado","stereotype":"enum","x":900,"y":10,"attributes":[{"name":"activo","type":"String"},{"name":"inactivo","type":"String"}],"methods":[]},
              {"id":"auditable","name":"Auditable","stereotype":"interface","x":900,"y":300,"attributes":[],
               "methods":[{"name":"auditar","returnType":"void","parameters":[]}]}
            ],
            "relationships":[
              {"id":"r1","type":"GENERALIZATION","sourceId":"cliente","targetId":"persona"},
              {"id":"r2","type":"ASSOCIATION","sourceId":"cliente","targetId":"pedido","sourceMultiplicity":"1","targetMultiplicity":"0..*","sourceRole":"cliente","targetRole":"pedidos"},
              {"id":"r3","type":"COMPOSITION","sourceId":"pedido","targetId":"linea","sourceMultiplicity":"1","targetMultiplicity":"1..*"},
              {"id":"r4","type":"ASSOCIATION","sourceId":"pedido","targetId":"etiqueta","sourceMultiplicity":"*","targetMultiplicity":"*"},
              {"id":"r5","type":"ASSOCIATION","sourceId":"cliente","targetId":"perfil","sourceMultiplicity":"1","targetMultiplicity":"1"},
              {"id":"r6","type":"REALIZATION","sourceId":"cliente","targetId":"auditable"},
              {"id":"r7","type":"AGGREGATION","sourceId":"etiqueta","targetId":"perfil","sourceMultiplicity":"1","targetMultiplicity":"*"},
              {"id":"r8","type":"DEPENDENCY","sourceId":"pedido","targetId":"perfil"}
            ]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaSpringBootGenerator generator = new JavaSpringBootGenerator("com.generated.app");

    private JsonNode diagram() throws IOException {
        return new DiagramOperationApplier().normalizeContent(mapper.readTree(DIAGRAM));
    }

    private String file(List<GeneratedFile> files, String suffix) {
        return files.stream().filter(f -> f.path().endsWith(suffix)).findFirst().orElseThrow().content();
    }

    @Test
    void generaEsqueletoYUnaEntidadPorClase() throws IOException {
        List<GeneratedFile> files = generator.generate(diagram());
        List<String> paths = files.stream().map(GeneratedFile::path).toList();
        assertThat(paths).contains("pom.xml", "src/main/resources/application.properties",
                "src/main/java/com/generated/app/Application.java",
                "src/main/java/com/generated/app/model/Persona.java",
                "src/main/java/com/generated/app/model/LineaPedido.java",
                "src/main/java/com/generated/app/model/Estado.java");
        assertThat(paths).doesNotHaveDuplicates();
        assertThat(file(files, "pom.xml")).contains("spring-boot-starter-data-jpa").contains("<java.version>21</java.version>");
    }

    @Test
    void mapeoDeElementosDelDiagrama() throws IOException {
        List<GeneratedFile> files = generator.generate(diagram());
        String persona = file(files, "model/Persona.java");
        assertThat(persona).contains("@Entity").contains("public abstract class Persona")
                .contains("@Inheritance(strategy = InheritanceType.JOINED)")
                .contains("@GeneratedValue(strategy = GenerationType.UUID)")
                .contains("private LocalDate fechaNacimiento;")
                .contains("private int default_;");
        // getNombre() del diagrama coincide con el accessor: no se duplica
        assertThat(persona.split("String getNombre\\(\\)", -1)).hasSize(2);
        assertThat(persona).contains("public void saludar(String saludo, int veces)")
                .contains("throw new UnsupportedOperationException(\"TODO\")");

        String cliente = file(files, "model/Cliente.java");
        assertThat(cliente).contains("extends Persona").contains("implements Auditable")
                .contains("@OneToMany(mappedBy = \"cliente\")").contains("private List<Pedido> pedidos;")
                .contains("@OneToOne").contains("@Enumerated(EnumType.STRING)").contains("private Estado estado;")
                .doesNotContain("@Inheritance"); // la subclase no repite la estrategia
        assertThat(cliente).doesNotContain("private UUID id;"); // el id lo hereda de Persona

        String pedido = file(files, "model/Pedido.java");
        assertThat(pedido).contains("@ManyToOne").contains("private Cliente cliente;")
                .contains("@OneToMany(mappedBy = \"pedido\", cascade = CascadeType.ALL, orphanRemoval = true)")
                .contains("private List<LineaPedido> lineaPedidos;")
                .contains("@ManyToMany").contains("@ElementCollection").contains("import com.generated.app.model.Perfil;");

        String perfil = file(files, "model/Perfil.java");
        assertThat(perfil).contains("@OneToOne(mappedBy = ").contains("@ManyToOne"); // agregación: sin cascade
        assertThat(perfil).doesNotContain("cascade");

        assertThat(file(files, "model/Estado.java")).contains("public enum Estado").contains("ACTIVO,").contains("INACTIVO;");
        assertThat(file(files, "model/Auditable.java")).contains("public interface Auditable").contains("void auditar();");
    }

    @Test
    void generacionDeterminista() throws IOException {
        JsonNode d = diagram();
        assertThat(generator.generate(d)).isEqualTo(generator.generate(d));
        assertThat(new JavaSpringBootGenerator("com.generated.app").generate(d)).isEqualTo(generator.generate(d));
    }

    @Test
    void elCodigoGeneradoCompila() throws Exception {
        List<GeneratedFile> files = generator.generate(diagram());
        Path out = Files.createTempDirectory("compiled");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("se necesita un JDK, no un JRE").isNotNull();

        List<JavaFileObject> units = new ArrayList<>();
        for (GeneratedFile f : files) {
            if (f.path().endsWith(".java")) {
                units.add(new SimpleJavaFileObject(URI.create("string:///" + f.path()), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return f.content();
                    }
                });
            }
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok = compiler.getTask(null, null, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path"), "-d", out.toString(), "--release", "21"),
                null, units).call();
        String errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getSource().getName() + ":" + d.getLineNumber() + " " + d.getMessage(null))
                .collect(Collectors.joining("\n"));
        assertThat(ok).as("errores de compilación:\n" + errors).isTrue();
        assertThat(Files.exists(out.resolve("com/generated/app/model/Cliente.class"))).isTrue();
    }

    @Test
    void multiplicidadesYPlaceholders() {
        assertThat(JavaSpringBootGenerator.pascal("linea pedido-x")).isEqualTo("LineaPedidoX");
        assertThat(JavaSpringBootGenerator.camel("URL")).isEqualTo("url");
        assertThat(JavaSpringBootGenerator.camel("class")).isEqualTo("class_");
        assertThat(JavaSpringBootGenerator.camel("1abc")).isEqualTo("_1abc");
    }
}
