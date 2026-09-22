package com.diagramas.platform.codegen.generator;

import static com.diagramas.platform.common.util.Json.text;

import com.diagramas.platform.common.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Genera entidades JPA (Java 21 / Spring Boot 3) + el esqueleto mínimo para compilar (D-04).
 * Determinista: misma entrada → misma salida (sin fechas, sin aleatoriedad, colecciones ordenadas).
 */
@Component
public class JavaSpringBootGenerator implements CodeGenerator {

    static final String SPRING_BOOT_VERSION = "3.5.16";

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_");
    private static final Pattern COLLECTION = Pattern.compile("^(List|Set)<\\s*([^<>]+?)\\s*>$");

    private final String basePackage;

    @Autowired
    public JavaSpringBootGenerator(AppProperties props) {
        this(props.codegen().basePackage());
    }

    public JavaSpringBootGenerator(String basePackage) {
        this.basePackage = basePackage == null || basePackage.isBlank() ? "com.generated.app" : basePackage.trim();
    }

    @Override
    public String language() {
        return "JAVA";
    }

    // ------------------------------------------------------------------ modelo interno

    private record Field(List<String> annotations, String visibility, String type, String name, boolean id) {}

    private static final class Cls {
        final String id;
        final String origName;
        final String javaName;
        final String stereotype;
        final JsonNode node;
        final List<Field> fields = new ArrayList<>();
        final Set<String> usedNames = new HashSet<>();
        final Set<String> implementsNames = new LinkedHashSet<>();
        final Set<String> dependencies = new TreeSet<>();
        String extendsName;
        boolean hasChildren;
        boolean isSubclass;

        Cls(JsonNode node, String javaName) {
            this.node = node;
            this.id = text(node, "id");
            this.origName = text(node, "name");
            this.javaName = javaName;
            this.stereotype = text(node, "stereotype");
        }

        boolean isInterface() { return "interface".equals(stereotype); }
        boolean isEnum() { return "enum".equals(stereotype); }
        boolean isEntity() { return !isInterface() && !isEnum(); }

        String uniqueName(String base) {
            String candidate = base;
            int n = 2;
            while (usedNames.contains(candidate)) {
                candidate = base + n++;
            }
            usedNames.add(candidate);
            return candidate;
        }
    }

    // ------------------------------------------------------------------ generación

    @Override
    public List<GeneratedFile> generate(JsonNode content) {
        List<Cls> classes = new ArrayList<>();
        Map<String, Cls> byId = new HashMap<>();
        Map<String, String> javaNames = new HashMap<>();
        Set<String> takenNames = new HashSet<>();
        for (JsonNode c : content.path("classes")) {
            String base = pascal(text(c, "name"));
            String javaName = base;
            int n = 2;
            while (!takenNames.add(javaName)) {
                javaName = base + n++;
            }
            Cls cls = new Cls(c, javaName);
            classes.add(cls);
            byId.put(cls.id, cls);
            javaNames.put(cls.origName, javaName);
        }

        applyInheritance(content, byId);

        for (Cls cls : classes) {
            buildAttributeFields(cls, byId, javaNames);
        }
        for (JsonNode r : content.path("relationships")) {
            applyRelationship(r, byId);
        }

        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile("pom.xml", pom()));
        files.add(new GeneratedFile(sourcePath(basePackage, "Application"), application()));
        files.add(new GeneratedFile("src/main/resources/application.properties", properties()));
        Map<String, Cls> byJavaName = new LinkedHashMap<>();
        for (Cls cls : classes) {
            byJavaName.put(cls.javaName, cls);
        }
        for (Cls cls : classes) {
            files.add(new GeneratedFile(sourcePath(basePackage + ".model", cls.javaName), render(cls, javaNames)));
            // Una interfaz, un enum o una clase abstracta no se instancian: no tienen API propia.
            if (!cls.isEntity() || "abstract".equals(cls.stereotype)) continue;
            String idType = idTypeOf(cls, byJavaName);
            files.add(new GeneratedFile(
                    sourcePath(basePackage + ".repository", cls.javaName + "Repository"), repository(cls, idType)));
            files.add(new GeneratedFile(
                    sourcePath(basePackage + ".web", cls.javaName + "Controller"), controller(cls, idType)));
        }
        files.sort(Comparator.comparing(GeneratedFile::path));
        return files;
    }

    private void applyInheritance(JsonNode content, Map<String, Cls> byId) {
        for (JsonNode r : content.path("relationships")) {
            String type = text(r, "type");
            Cls src = byId.get(text(r, "sourceId"));
            Cls tgt = byId.get(text(r, "targetId"));
            if (src == null || tgt == null || tgt.isEnum() || src.isEnum()) continue;
            if ("GENERALIZATION".equals(type)) {
                if (tgt.isInterface()) {
                    src.implementsNames.add(tgt.javaName);
                } else if (!src.isInterface() && src.extendsName == null) {
                    src.extendsName = tgt.javaName;
                    src.isSubclass = true;
                    tgt.hasChildren = true;
                }
            } else if ("REALIZATION".equals(type) && tgt.isInterface()) {
                src.implementsNames.add(tgt.javaName);
            } else if ("DEPENDENCY".equals(type)) {
                src.dependencies.add(tgt.javaName);
            }
        }
    }

    private void buildAttributeFields(Cls cls, Map<String, Cls> byId, Map<String, String> javaNames) {
        if (!cls.isEntity()) return;
        boolean declaresId = false;
        for (JsonNode a : cls.node.path("attributes")) {
            if ("id".equalsIgnoreCase(camel(text(a, "name")))) declaresId = true;
        }
        if (!cls.isSubclass && !declaresId) {
            cls.usedNames.add("id");
            cls.fields.add(new Field(
                    List.of("@Id", "@GeneratedValue(strategy = GenerationType.UUID)"), "private", "UUID", "id", true));
        }
        for (JsonNode a : cls.node.path("attributes")) {
            String name = cls.uniqueName(camel(text(a, "name")));
            String type = text(a, "type");
            List<String> ann = new ArrayList<>();
            boolean isId = false;
            if (name.equals("id") && declaresId) {
                isId = true;
                ann.add("@Id");
                if (type.equals("UUID")) ann.add("@GeneratedValue(strategy = GenerationType.UUID)");
                else if (type.equals("Long") || type.equals("long")) ann.add("@GeneratedValue(strategy = GenerationType.IDENTITY)");
            } else {
                ann.addAll(annotationsFor(type, byId));
            }
            cls.fields.add(new Field(ann, visibility(text(a, "visibility")), mapType(type, javaNames), name, isId));
        }
    }

    /** Anotaciones JPA para un atributo cuyo tipo puede referenciar otra clase del diagrama. */
    private List<String> annotationsFor(String type, Map<String, Cls> byId) {
        Matcher m = COLLECTION.matcher(type.trim());
        boolean collection = m.matches();
        String base = collection ? m.group(2) : type.trim();
        Cls target = byId.values().stream().filter(c -> c.origName.equals(base)).findFirst().orElse(null);
        if (target == null) {
            return collection ? List.of("@ElementCollection") : List.of();
        }
        if (target.isEnum()) {
            return collection ? List.of("@ElementCollection", "@Enumerated(EnumType.STRING)") : List.of("@Enumerated(EnumType.STRING)");
        }
        if (target.isInterface()) {
            return List.of("@Transient");
        }
        return collection ? List.of("@OneToMany") : List.of("@ManyToOne");
    }

    private void applyRelationship(JsonNode r, Map<String, Cls> byId) {
        String type = text(r, "type");
        if (!List.of("ASSOCIATION", "AGGREGATION", "COMPOSITION").contains(type)) return;
        Cls s = byId.get(text(r, "sourceId"));
        Cls t = byId.get(text(r, "targetId"));
        if (s == null || t == null || !s.isEntity() || !t.isEntity()) return;

        boolean sMany = many(text(r, "sourceMultiplicity"));
        boolean tMany = many(text(r, "targetMultiplicity"));
        boolean composite = type.equals("COMPOSITION");
        // Nombre del rol en el extremo destino = campo dentro del origen, y viceversa.
        String tRole = s.uniqueName(roleOrDefault(text(r, "targetRole"), t, tMany));
        String sRole = t.uniqueName(roleOrDefault(text(r, "sourceRole"), s, sMany));
        String all = "cascade = CascadeType.ALL";
        String allOrphan = "cascade = CascadeType.ALL, orphanRemoval = true";

        if (tMany && !sMany) {            // 1 origen : * destinos
            s.fields.add(field("@OneToMany(mappedBy = \"" + sRole + "\"" + (composite ? ", " + allOrphan : "") + ")",
                    "List<" + t.javaName + ">", tRole));
            t.fields.add(field("@ManyToOne", s.javaName, sRole));
        } else if (!tMany && sMany) {     // * orígenes : 1 destino
            s.fields.add(field(composite ? "@ManyToOne(" + all + ")" : "@ManyToOne", t.javaName, tRole));
            t.fields.add(field("@OneToMany(mappedBy = \"" + tRole + "\")", "List<" + s.javaName + ">", sRole));
        } else if (!tMany) {              // 1 : 1
            s.fields.add(field(composite ? "@OneToOne(" + allOrphan + ")" : "@OneToOne", t.javaName, tRole));
            t.fields.add(field("@OneToOne(mappedBy = \"" + tRole + "\")", s.javaName, sRole));
        } else {                          // * : *
            s.fields.add(field(composite ? "@ManyToMany(" + all + ")" : "@ManyToMany", "List<" + t.javaName + ">", tRole));
            t.fields.add(field("@ManyToMany(mappedBy = \"" + tRole + "\")", "List<" + s.javaName + ">", sRole));
        }
    }

    /**
     * El extremo inverso de una relación (`mappedBy`) no se serializa a JSON: sale del otro extremo. Si se
     * serializara, un cliente contendría sus pedidos, cada pedido a su cliente, y así sin fin: la respuesta de
     * la API llegaba truncada en cuanto había datos enlazados.
     */
    private static Field field(String annotation, String type, String name) {
        List<String> annotations = annotation.contains("mappedBy")
                ? List.of(annotation, "@JsonIgnore")
                : List.of(annotation);
        return new Field(annotations, "private", type, name, false);
    }

    private static String roleOrDefault(String role, Cls other, boolean many) {
        if (role != null && !role.isBlank()) return camel(role);
        return camel(other.javaName) + (many ? "s" : "");
    }

    /** Límite superior de la multiplicidad > 1 (o *). Ausente se asume 1. */
    static boolean many(String multiplicity) {
        if (multiplicity == null || multiplicity.isBlank()) return false;
        String m = multiplicity.trim();
        int i = m.indexOf("..");
        String upper = i >= 0 ? m.substring(i + 2) : m;
        return upper.equals("*") || Integer.parseInt(upper) > 1;
    }

    // ------------------------------------------------------------------ render

    private String render(Cls cls, Map<String, String> javaNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".model;\n\n");
        sb.append("import com.fasterxml.jackson.annotation.JsonIgnore;\n");
        sb.append("import jakarta.persistence.CascadeType;\n");
        sb.append("import jakarta.persistence.ElementCollection;\n");
        sb.append("import jakarta.persistence.Entity;\n");
        sb.append("import jakarta.persistence.EnumType;\n");
        sb.append("import jakarta.persistence.Enumerated;\n");
        sb.append("import jakarta.persistence.GeneratedValue;\n");
        sb.append("import jakarta.persistence.GenerationType;\n");
        sb.append("import jakarta.persistence.Id;\n");
        sb.append("import jakarta.persistence.Inheritance;\n");
        sb.append("import jakarta.persistence.InheritanceType;\n");
        sb.append("import jakarta.persistence.ManyToMany;\n");
        sb.append("import jakarta.persistence.ManyToOne;\n");
        sb.append("import jakarta.persistence.OneToMany;\n");
        sb.append("import jakarta.persistence.OneToOne;\n");
        sb.append("import jakarta.persistence.Table;\n");
        sb.append("import jakarta.persistence.Transient;\n");
        sb.append("import java.math.BigDecimal;\n");
        sb.append("import java.time.LocalDate;\n");
        sb.append("import java.time.LocalDateTime;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Set;\n");
        sb.append("import java.util.UUID;\n");
        for (String dep : cls.dependencies) {
            sb.append("import ").append(basePackage).append(".model.").append(dep).append(";\n");
        }
        sb.append('\n');

        if (cls.isEnum()) {
            renderEnum(sb, cls);
        } else if (cls.isInterface()) {
            renderInterface(sb, cls, javaNames);
        } else {
            renderEntity(sb, cls, javaNames);
        }
        return sb.toString();
    }

    private void renderEnum(StringBuilder sb, Cls cls) {
        sb.append("public enum ").append(cls.javaName).append(" {\n");
        Set<String> constants = new LinkedHashSet<>();
        for (JsonNode a : cls.node.path("attributes")) {
            String c = constant(text(a, "name"));
            String candidate = c;
            int n = 2;
            while (!constants.add(candidate)) candidate = c + "_" + n++;
        }
        sb.append("    ").append(String.join(",\n    ", constants)).append(";\n}\n");
    }

    private void renderInterface(StringBuilder sb, Cls cls, Map<String, String> javaNames) {
        sb.append("public interface ").append(cls.javaName);
        if (!cls.implementsNames.isEmpty()) sb.append(" extends ").append(String.join(", ", cls.implementsNames));
        sb.append(" {\n");
        for (JsonNode m : cls.node.path("methods")) {
            sb.append("\n    ").append(signature(m, javaNames, "")).append(";\n");
        }
        sb.append("}\n");
    }

    private void renderEntity(StringBuilder sb, Cls cls, Map<String, String> javaNames) {
        sb.append("@Entity\n");
        sb.append("@Table(name = \"t_").append(snake(cls.javaName)).append("\")\n");
        if (cls.hasChildren && !cls.isSubclass) {
            sb.append("@Inheritance(strategy = InheritanceType.JOINED)\n");
        }
        sb.append("public ");
        if ("abstract".equals(cls.stereotype)) sb.append("abstract ");
        sb.append("class ").append(cls.javaName);
        if (cls.extendsName != null) sb.append(" extends ").append(cls.extendsName);
        if (!cls.implementsNames.isEmpty()) sb.append(" implements ").append(String.join(", ", cls.implementsNames));
        sb.append(" {\n");

        for (Field f : cls.fields) {
            sb.append('\n');
            for (String a : f.annotations()) sb.append("    ").append(a).append('\n');
            sb.append("    ").append(f.visibility()).append(' ').append(f.type()).append(' ').append(f.name()).append(";\n");
        }

        Set<String> accessors = new HashSet<>();
        for (Field f : cls.fields) {
            String cap = capitalize(f.name());
            String getter = (f.type().equals("boolean") ? "is" : "get") + cap;
            String setter = "set" + cap;
            accessors.add(getter + "/0");
            accessors.add(setter + "/1");
            sb.append("\n    public ").append(f.type()).append(' ').append(getter).append("() {\n")
                    .append("        return this.").append(f.name()).append(";\n    }\n");
            sb.append("\n    public void ").append(setter).append('(').append(f.type()).append(' ').append(f.name()).append(") {\n")
                    .append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n    }\n");
        }

        for (JsonNode m : cls.node.path("methods")) {
            String name = camel(text(m, "name"));
            if (accessors.contains(name + "/" + m.path("parameters").size())) {
                continue; // ya existe el accessor generado con esa firma
            }
            sb.append("\n    ").append(signature(m, javaNames, visibilityPrefix(text(m, "visibility")))).append(" {\n")
                    .append("        throw new UnsupportedOperationException(\"TODO\");\n    }\n");
        }
        sb.append("}\n");
    }

    private String signature(JsonNode m, Map<String, String> javaNames, String modifier) {
        String ret = mapType(text(m, "returnType") == null ? "void" : text(m, "returnType"), javaNames);
        List<String> params = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode p : m.path("parameters")) {
            String base = camel(text(p, "name"));
            String n = base;
            int i = 2;
            while (!names.add(n)) n = base + i++;
            params.add(mapType(text(p, "type"), javaNames) + " " + n);
        }
        return modifier + ret + " " + camel(text(m, "name")) + "(" + String.join(", ", params) + ")";
    }

    private static String visibilityPrefix(String v) {
        return switch (v == null ? "PUBLIC" : v) {
            case "PRIVATE" -> "private ";
            case "PROTECTED" -> "protected ";
            case "PACKAGE" -> "";
            default -> "public ";
        };
    }

    private static String visibility(String v) {
        return visibilityPrefix(v == null ? "PRIVATE" : v).trim().isEmpty() ? "" : visibilityPrefix(v == null ? "PRIVATE" : v).trim();
    }

    // ------------------------------------------------------------------ archivos de esqueleto

    private String pom() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>
                    <groupId>%s</groupId>
                    <artifactId>generated-app</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.h2database</groupId>
                            <artifactId>h2</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(SPRING_BOOT_VERSION, basePackage);
    }

    private String application() {
        return """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {

                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """.formatted(basePackage);
    }

    /**
     * Por omisión el proyecto arranca contra H2 en un archivo local, para que el ZIP se pueda ejecutar sin
     * instalar nada. Apuntando `DB_URL` a PostgreSQL funciona igual.
     */
    private String properties() {
        return """
                spring.datasource.url=${DB_URL:jdbc:h2:file:./data/generated;AUTO_SERVER=TRUE}
                spring.datasource.username=${DB_USER:sa}
                spring.datasource.password=${DB_PASSWORD:}
                spring.jpa.hibernate.ddl-auto=update
                server.port=${SERVER_PORT:8090}
                """;
    }

    // ------------------------------------------------------------------ API REST generada

    /**
     * Tipo del identificador de una entidad. Una subclase no declara el suyo: lo hereda de la raíz de la
     * jerarquía, así que hay que subir hasta encontrarlo.
     */
    private static String idTypeOf(Cls cls, Map<String, Cls> byJavaName) {
        for (Field f : cls.fields) {
            if (f.id()) return f.type();
        }
        Cls parent = cls.extendsName == null ? null : byJavaName.get(cls.extendsName);
        return parent == null ? "UUID" : idTypeOf(parent, byJavaName);
    }

    /** Ruta del recurso: el nombre de la clase en minúsculas y en plural. `Cliente` → `clientes`. */
    public static String resourcePath(String javaName) {
        String base = javaName.toLowerCase(Locale.ROOT);
        return base.endsWith("s") ? base : base + "s";
    }

    private String repository(Cls cls, String idType) {
        return """
                package %s.repository;

                import %s.model.%s;
                %simport org.springframework.data.jpa.repository.JpaRepository;

                public interface %sRepository extends JpaRepository<%s, %s> {}
                """
                .formatted(basePackage, basePackage, cls.javaName,
                        "UUID".equals(idType) ? "import java.util.UUID;\n" : "",
                        cls.javaName, cls.javaName, idType);
    }

    /** CRUD sobre la entidad: es lo que convierte el diagrama en una API que se puede probar. */
    private String controller(Cls cls, String idType) {
        String name = cls.javaName;
        return """
                package %s.web;

                import %s.model.%s;
                import %s.repository.%sRepository;
                import java.util.List;
                %simport org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.DeleteMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.PutMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.ResponseStatus;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/%s")
                public class %sController {

                    private final %sRepository repository;

                    public %sController(%sRepository repository) {
                        this.repository = repository;
                    }

                    @GetMapping
                    public List<%s> list() {
                        return repository.findAll();
                    }

                    @GetMapping("/{id}")
                    public ResponseEntity<%s> get(@PathVariable %s id) {
                        return repository.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
                    }

                    @PostMapping
                    @ResponseStatus(HttpStatus.CREATED)
                    public %s create(@RequestBody %s body) {
                        return repository.save(body);
                    }

                    @PutMapping("/{id}")
                    public ResponseEntity<%s> update(@PathVariable %s id, @RequestBody %s body) {
                        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
                        return ResponseEntity.ok(repository.save(body));
                    }

                    @DeleteMapping("/{id}")
                    @ResponseStatus(HttpStatus.NO_CONTENT)
                    public void delete(@PathVariable %s id) {
                        repository.deleteById(id);
                    }
                }
                """
                .formatted(basePackage, basePackage, name, basePackage, name,
                        "UUID".equals(idType) ? "import java.util.UUID;\n" : "",
                        resourcePath(name), name, name, name, name,
                        name, name, idType, name, name, name, idType, name, idType);
    }

    private static String sourcePath(String pkg, String className) {
        return "src/main/java/" + pkg.replace('.', '/') + "/" + className + ".java";
    }

    // ------------------------------------------------------------------ identificadores

    private static String mapType(String type, Map<String, String> javaNames) {
        String t = type == null ? "String" : type.trim();
        Matcher m = COLLECTION.matcher(t);
        if (m.matches()) {
            String inner = m.group(2);
            return m.group(1) + "<" + javaNames.getOrDefault(inner, inner) + ">";
        }
        return javaNames.getOrDefault(t, t);
    }

    public static String pascal(String raw) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                sb.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            } else {
                upper = true;
            }
        }
        String s = sb.toString();
        if (s.isEmpty()) return "Unnamed";
        return Character.isJavaIdentifierStart(s.charAt(0)) ? s : "_" + s;
    }

    public static String camel(String raw) {
        String p = pascal(raw);
        boolean allUpper = p.chars().filter(Character::isLetter).allMatch(Character::isUpperCase) && p.length() > 1;
        String s = allUpper ? p.toLowerCase(Locale.ROOT) : Character.toLowerCase(p.charAt(0)) + p.substring(1);
        return KEYWORDS.contains(s) ? s + "_" : s;
    }

    private static String constant(String raw) {
        String s = (raw == null ? "" : raw).replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9_]", "_");
        s = s.toUpperCase(Locale.ROOT);
        if (s.isEmpty()) s = "VALUE";
        return Character.isJavaIdentifierStart(s.charAt(0)) ? s : "_" + s;
    }

    private static String snake(String javaName) {
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
