package com.diagramas.platform.design.service;

import static com.diagramas.platform.common.util.Json.text;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Único punto de validación y aplicación de operaciones sobre {@code content_json} (7.3).
 * Lo usan el editor manual, el canal WebSocket, la IA (tras normalizar) y el import XMI.
 *
 * <p>Nunca modifica el contenido recibido: trabaja sobre una copia y solo la devuelve si TODO es válido,
 * de modo que un fallo deja el diagrama intacto.
 */
@Component
public class DiagramOperationApplier {

    public static final Set<String> OPERATIONS = Set.of(
            "ADD_CLASS", "UPDATE_CLASS", "MOVE_CLASS", "REMOVE_CLASS",
            "ADD_ATTRIBUTE", "UPDATE_ATTRIBUTE", "REMOVE_ATTRIBUTE",
            "ADD_METHOD", "UPDATE_METHOD", "REMOVE_METHOD",
            "ADD_RELATIONSHIP", "UPDATE_RELATIONSHIP", "REMOVE_RELATIONSHIP");

    public static final Set<String> BASE_TYPES = Set.of(
            "String", "int", "Integer", "long", "Long", "double", "Double", "float",
            "boolean", "Boolean", "UUID", "LocalDate", "LocalDateTime", "BigDecimal");

    public static final Set<String> VISIBILITIES = Set.of("PUBLIC", "PRIVATE", "PROTECTED", "PACKAGE");
    public static final Set<String> STEREOTYPES = Set.of("interface", "abstract", "enum");
    public static final Set<String> RELATIONSHIP_TYPES =
            Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION", "GENERALIZATION", "REALIZATION", "DEPENDENCY");
    public static final Set<String> MESSAGE_KINDS = Set.of("SYNC", "ASYNC", "RETURN", "SELF");

    private static final Pattern MULTIPLICITY = Pattern.compile("^(\\d+|\\*)(\\.\\.(\\d+|\\*))?$");
    private static final Pattern COLLECTION_TYPE = Pattern.compile("^(List|Set)<\\s*([^<>]+?)\\s*>$");
    private static final int MAX_COORD = 10_000;
    private static final int MAX_NAME = 100;

    /** Resultado de aplicar una operación: contenido nuevo + operación normalizada (con IDs asignados). */
    public record Applied(ObjectNode content, ObjectNode operation) {}

    public record AppliedBatch(ObjectNode content, List<ObjectNode> operations) {}

    private record TypeRef(String type, boolean allowVoid, String where) {}

    // ---------------------------------------------------------------- API pública

    /** Valida y aplica una operación. */
    public Applied apply(JsonNode content, JsonNode operation) {
        ObjectNode work = classContentCopy(content);
        ObjectNode op = prepare(work, operation);
        List<TypeRef> pending = new ArrayList<>();
        execute(work, op, pending);
        checkTypes(work, pending);
        return new Applied(work, op);
    }

    /** Aplica varias operaciones de forma atómica (todo o nada). Los tipos se validan al final. */
    public AppliedBatch applyAll(JsonNode content, List<? extends JsonNode> operations) {
        ObjectNode work = classContentCopy(content);
        List<ObjectNode> prepared = new ArrayList<>();
        for (JsonNode operation : operations) {
            ObjectNode op = prepare(work, operation);
            execute(work, op, null);
            prepared.add(op);
        }
        checkAllTypes(work);
        return new AppliedBatch(work, prepared);
    }

    /**
     * Valida la integridad de un {@code content_json} completo (CU-10) y devuelve su forma canónica
     * (IDs asignados, valores por defecto). Reutiliza las mismas reglas que las operaciones.
     */
    public ObjectNode normalizeContent(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw invalidJson();
        }
        String type = text(content, "type");
        if ("SEQUENCE".equals(type)) {
            return normalizeSequence(content);
        }
        if (!"CLASS".equals(type)) {
            throw invalidJson();
        }
        ObjectNode base = Json.object();
        content.fields().forEachRemaining(e -> base.set(e.getKey(), e.getValue()));
        base.put("schemaVersion", 1);
        JsonNode classes = content.get("classes");
        JsonNode relationships = content.get("relationships");
        if ((classes != null && !classes.isArray()) || (relationships != null && !relationships.isArray())) {
            throw invalidJson();
        }
        base.set("classes", Json.array());
        base.set("relationships", Json.array());
        List<ObjectNode> ops = new ArrayList<>();
        if (classes != null) {
            for (JsonNode c : classes) {
                if (!c.isObject()) throw invalidJson();
                ObjectNode op = Json.object();
                op.put("op", "ADD_CLASS");
                op.set("class", c);
                ops.add(op);
            }
        }
        if (relationships != null) {
            for (JsonNode r : relationships) {
                if (!r.isObject()) throw invalidJson();
                ObjectNode op = Json.object();
                op.put("op", "ADD_RELATIONSHIP");
                op.set("relationship", r);
                ops.add(op);
            }
        }
        return applyAll(base, ops).content();
    }

    /** Contenido inicial de un diagrama de clases (CU-06). */
    public static ObjectNode emptyClassContent() {
        ObjectNode c = Json.object();
        c.put("schemaVersion", 1);
        c.put("type", "CLASS");
        c.set("classes", Json.array());
        c.set("relationships", Json.array());
        return c;
    }

    /** Elemento sobre el que se toma el lock en una operación ya normalizada (clase o relación). */
    public String elementIdOf(JsonNode op) {
        return switch (text(op, "op")) {
            case "ADD_CLASS" -> text(op.get("class"), "id");
            case "ADD_RELATIONSHIP" -> text(op.get("relationship"), "id");
            case "UPDATE_RELATIONSHIP", "REMOVE_RELATIONSHIP" -> text(op, "relationshipId");
            default -> text(op, "classId");
        };
    }

    // ---------------------------------------------------------------- preparación

    private ObjectNode classContentCopy(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw invalidJson();
        }
        String type = text(content, "type");
        if (type != null && !"CLASS".equals(type)) {
            throw ApiException.validation("Solo se pueden editar diagramas de clases.");
        }
        if (!(content.get("classes") instanceof ArrayNode) || !(content.get("relationships") instanceof ArrayNode)) {
            throw invalidJson();
        }
        return content.deepCopy();
    }

    /** Copia la operación, valida su forma y le asigna IDs y posición por defecto. */
    private ObjectNode prepare(ObjectNode content, JsonNode operation) {
        if (operation == null || !operation.isObject()) {
            throw ApiException.validation("La operación no es válida.");
        }
        ObjectNode op = operation.deepCopy();
        String type = text(op, "op");
        if (type == null || !OPERATIONS.contains(type)) {
            throw ApiException.validation("Operación no soportada: " + type + ".");
        }
        Set<String> used = collectIds(content);
        switch (type) {
            case "ADD_CLASS" -> {
                ObjectNode cls = requireObject(op, "class", "La clase");
                ensureId(cls, used);
                if (!cls.hasNonNull("x") || !cls.hasNonNull("y")) {
                    int[] pos = LayoutUtil.gridPosition(content.withArray("classes").size());
                    cls.put("x", pos[0]);
                    cls.put("y", pos[1]);
                }
                for (String list : List.of("attributes", "methods")) {
                    JsonNode items = cls.get(list);
                    if (items != null && !items.isNull()) {
                        if (!items.isArray()) throw ApiException.validation("Formato inválido en '" + list + "'.");
                        for (JsonNode item : items) {
                            if (!item.isObject()) throw ApiException.validation("Formato inválido en '" + list + "'.");
                            ensureId((ObjectNode) item, used);
                        }
                    }
                }
            }
            case "ADD_ATTRIBUTE" -> ensureId(requireObject(op, "attribute", "El atributo"), used);
            case "ADD_METHOD" -> ensureId(requireObject(op, "method", "El método"), used);
            case "ADD_RELATIONSHIP" -> ensureId(requireObject(op, "relationship", "La relación"), used);
            default -> { }
        }
        return op;
    }

    private static void ensureId(ObjectNode node, Set<String> used) {
        JsonNode id = node.get("id");
        if (id == null || id.isNull() || id.asText().isBlank()) {
            String fresh;
            do {
                fresh = Json.shortId();
            } while (used.contains(fresh));
            node.put("id", fresh);
            used.add(fresh);
        }
    }

    // ---------------------------------------------------------------- ejecución

    private void execute(ObjectNode c, ObjectNode op, List<TypeRef> pending) {
        switch (text(op, "op")) {
            case "ADD_CLASS" -> addClass(c, op.get("class"), pending);
            case "UPDATE_CLASS" -> updateClass(c, op);
            case "MOVE_CLASS" -> {
                ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
                setCoordinates(cls, op.get("x"), op.get("y"));
            }
            case "REMOVE_CLASS" -> removeClass(c, requireText(op, "classId", "La clase"));
            case "ADD_ATTRIBUTE" -> {
                ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
                ObjectNode attr = buildAttribute(op.get("attribute"), collectIds(c), attributeNames(cls, null), pending);
                cls.withArray("attributes").add(attr);
            }
            case "UPDATE_ATTRIBUTE" -> updateMember(c, op, "attributes", "attributeId", "changes", pending);
            case "REMOVE_ATTRIBUTE" -> removeMember(c, op, "attributes", "attributeId");
            case "ADD_METHOD" -> {
                ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
                cls.withArray("methods").add(buildMethod(op.get("method"), collectIds(c), pending));
            }
            case "UPDATE_METHOD" -> updateMember(c, op, "methods", "methodId", "changes", pending);
            case "REMOVE_METHOD" -> removeMember(c, op, "methods", "methodId");
            case "ADD_RELATIONSHIP" -> {
                ObjectNode rel = buildRelationship(c, (ObjectNode) op.get("relationship"), null);
                assertIdFree(collectIds(c), rel.get("id").asText(), "La relación");
                c.withArray("relationships").add(rel);
            }
            case "UPDATE_RELATIONSHIP" -> updateRelationship(c, op);
            case "REMOVE_RELATIONSHIP" -> removeRelationship(c, requireText(op, "relationshipId", "La relación"));
            default -> throw ApiException.validation("Operación no soportada.");
        }
    }

    // ---- clases

    private void addClass(ObjectNode c, JsonNode in, List<TypeRef> pending) {
        ObjectNode cls = ((ObjectNode) in).deepCopy();
        String name = requireName(text(cls, "name"), "La clase");
        assertUniqueClassName(c, name, null);
        String id = cls.get("id").asText();
        Set<String> used = collectIds(c);
        assertIdFree(used, id, "La clase");
        used.add(id);
        cls.put("name", name);
        putStereotype(cls, cls.get("stereotype"));
        putVisibility(cls, "PUBLIC");
        setCoordinates(cls, cls.get("x"), cls.get("y"));

        ArrayNode attrs = Json.array();
        Set<String> attrNames = new HashSet<>();
        JsonNode inAttrs = cls.get("attributes");
        if (inAttrs != null && inAttrs.isArray()) {
            for (JsonNode a : inAttrs) {
                ObjectNode built = buildAttribute(a, used, attrNames, pending);
                attrs.add(built);
            }
        }
        ArrayNode methods = Json.array();
        JsonNode inMethods = cls.get("methods");
        if (inMethods != null && inMethods.isArray()) {
            for (JsonNode m : inMethods) {
                methods.add(buildMethod(m, used, pending));
            }
        }
        cls.set("attributes", attrs);
        cls.set("methods", methods);
        c.withArray("classes").add(cls);
    }

    private void updateClass(ObjectNode c, ObjectNode op) {
        ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
        ObjectNode changes = requireObject(op, "changes", "Los cambios");
        if (changes.has("name")) {
            String name = requireName(text(changes, "name"), "La clase");
            assertUniqueClassName(c, name, cls.get("id").asText());
            String old = cls.get("name").asText();
            if (!old.equals(name)) {
                renameTypeReferences(c, old, name);
            }
            cls.put("name", name);
        }
        if (changes.has("stereotype")) {
            putStereotype(cls, changes.get("stereotype"));
        }
        if (changes.has("visibility")) {
            putVisibility(cls, changes.get("visibility"), "PUBLIC");
        }
    }

    private void removeClass(ObjectNode c, String classId) {
        ArrayNode classes = c.withArray("classes");
        int idx = indexOf(classes, classId);
        if (idx < 0) throw classNotFound();
        classes.remove(idx);
        ArrayNode rels = c.withArray("relationships");
        for (int i = rels.size() - 1; i >= 0; i--) {
            JsonNode r = rels.get(i);
            if (classId.equals(text(r, "sourceId")) || classId.equals(text(r, "targetId"))) {
                rels.remove(i);
            }
        }
    }

    // ---- atributos y métodos

    private ObjectNode buildAttribute(JsonNode in, Set<String> usedIds, Set<String> names, List<TypeRef> pending) {
        if (in == null || !in.isObject()) throw ApiException.validation("El atributo no es válido.");
        ObjectNode a = ((ObjectNode) in).deepCopy();
        String name = requireName(text(a, "name"), "El atributo");
        if (!names.add(name.toLowerCase(Locale.ROOT))) {
            throw ApiException.validation("Ya existe un atributo llamado '" + name + "' en la clase.");
        }
        String type = text(a, "type");
        if (type == null || type.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DATATYPE, "El tipo de dato del atributo '" + name + "' es obligatorio.");
        }
        String id = text(a, "id");
        if (id == null || id.isBlank()) {
            do { id = Json.shortId(); } while (usedIds.contains(id));
        }
        assertIdFree(usedIds, id, "El atributo");
        usedIds.add(id);
        a.put("id", id);
        a.put("name", name);
        a.put("type", type.trim());
        putVisibility(a, "PRIVATE");
        if (pending != null) pending.add(new TypeRef(type.trim(), false, "el atributo '" + name + "'"));
        return a;
    }

    private ObjectNode buildMethod(JsonNode in, Set<String> usedIds, List<TypeRef> pending) {
        if (in == null || !in.isObject()) throw ApiException.validation("El método no es válido.");
        ObjectNode m = ((ObjectNode) in).deepCopy();
        String name = requireName(text(m, "name"), "El método");
        String id = text(m, "id");
        if (id == null || id.isBlank()) {
            do { id = Json.shortId(); } while (usedIds.contains(id));
        }
        assertIdFree(usedIds, id, "El método");
        usedIds.add(id);
        String returnType = text(m, "returnType");
        returnType = returnType == null || returnType.isBlank() ? "void" : returnType.trim();
        m.put("id", id);
        m.put("name", name);
        m.put("returnType", returnType);
        putVisibility(m, "PUBLIC");
        if (pending != null) pending.add(new TypeRef(returnType, true, "el retorno del método '" + name + "'"));

        ArrayNode params = Json.array();
        Set<String> paramNames = new HashSet<>();
        JsonNode inParams = m.get("parameters");
        if (inParams != null && !inParams.isNull()) {
            if (!inParams.isArray()) throw ApiException.validation("Los parámetros del método no son válidos.");
            for (JsonNode p : inParams) {
                if (!p.isObject()) throw ApiException.validation("Los parámetros del método no son válidos.");
                ObjectNode param = ((ObjectNode) p).deepCopy();
                String pName = requireName(text(param, "name"), "El parámetro");
                if (!paramNames.add(pName.toLowerCase(Locale.ROOT))) {
                    throw ApiException.validation("El parámetro '" + pName + "' está repetido en el método '" + name + "'.");
                }
                String pType = text(param, "type");
                if (pType == null || pType.isBlank()) {
                    throw new ApiException(ErrorCode.INVALID_DATATYPE, "El tipo del parámetro '" + pName + "' es obligatorio.");
                }
                param.put("name", pName);
                param.put("type", pType.trim());
                if (pending != null) pending.add(new TypeRef(pType.trim(), false, "el parámetro '" + pName + "'"));
                params.add(param);
            }
        }
        m.set("parameters", params);
        return m;
    }

    private void updateMember(ObjectNode c, ObjectNode op, String listField, String idField, String changesField,
                              List<TypeRef> pending) {
        ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
        String memberId = requireText(op, idField, "El elemento");
        ArrayNode list = cls.withArray(listField);
        int idx = indexOf(list, memberId);
        if (idx < 0) throw ApiException.notFound("El " + (listField.equals("attributes") ? "atributo" : "método") + " no existe en la clase.");
        ObjectNode changes = requireObject(op, changesField, "Los cambios");
        ObjectNode merged = ((ObjectNode) list.get(idx)).deepCopy();
        changes.fields().forEachRemaining(e -> {
            if (!"id".equals(e.getKey())) merged.set(e.getKey(), e.getValue());
        });
        Set<String> used = collectIds(c);
        used.remove(memberId);
        ObjectNode rebuilt;
        if (listField.equals("attributes")) {
            rebuilt = buildAttribute(merged, used, attributeNames(cls, memberId), pending);
        } else {
            rebuilt = buildMethod(merged, used, pending);
        }
        list.set(idx, rebuilt);
    }

    private void removeMember(ObjectNode c, ObjectNode op, String listField, String idField) {
        ObjectNode cls = findClass(c, requireText(op, "classId", "La clase"));
        ArrayNode list = cls.withArray(listField);
        int idx = indexOf(list, requireText(op, idField, "El elemento"));
        if (idx < 0) throw ApiException.notFound("El " + (listField.equals("attributes") ? "atributo" : "método") + " no existe en la clase.");
        list.remove(idx);
    }

    // ---- relaciones

    private ObjectNode buildRelationship(ObjectNode c, ObjectNode in, String excludeId) {
        ObjectNode rel = in.deepCopy();
        String type = text(rel, "type");
        type = type == null ? null : type.trim().toUpperCase(Locale.ROOT);
        if (type == null || !RELATIONSHIP_TYPES.contains(type)) {
            throw ApiException.validation("El tipo de relación no es válido.");
        }
        String sourceId = text(rel, "sourceId");
        String targetId = text(rel, "targetId");
        if (sourceId == null || targetId == null || indexOf(c.withArray("classes"), sourceId) < 0
                || indexOf(c.withArray("classes"), targetId) < 0) {
            throw new ApiException(ErrorCode.INVALID_RELATIONSHIP, "El origen y el destino de la relación deben existir en el diagrama.");
        }
        if (sourceId.equals(targetId) && (type.equals("GENERALIZATION") || type.equals("REALIZATION"))) {
            throw new ApiException(ErrorCode.INVALID_RELATIONSHIP, "Una clase no puede heredar de sí misma.");
        }
        for (JsonNode other : c.withArray("relationships")) {
            if (!text(other, "id").equals(excludeId)
                    && sourceId.equals(text(other, "sourceId")) && targetId.equals(text(other, "targetId"))) {
                throw new ApiException(ErrorCode.DUPLICATE_RELATIONSHIP, "La conexión entre esas clases ya existe.");
            }
        }
        if (type.equals("GENERALIZATION") && createsInheritanceCycle(c, sourceId, targetId, excludeId)) {
            throw new ApiException(ErrorCode.INVALID_RELATIONSHIP, "La herencia circular no está permitida.");
        }
        for (String field : List.of("sourceMultiplicity", "targetMultiplicity")) {
            String m = text(rel, field);
            if (m == null || m.isBlank()) {
                rel.putNull(field);
            } else if (!MULTIPLICITY.matcher(m.trim()).matches()) {
                throw ApiException.validation("Multiplicidad inválida: '" + m + "'. Use por ejemplo 1, 0..1, *, 1..*.");
            } else {
                rel.put(field, m.trim());
            }
        }
        for (String field : List.of("sourceRole", "targetRole", "name")) {
            String v = text(rel, field);
            if (v != null && v.length() > MAX_NAME) {
                throw ApiException.validation("El campo '" + field + "' excede " + MAX_NAME + " caracteres.");
            }
        }
        rel.put("type", type);
        return rel;
    }

    private void updateRelationship(ObjectNode c, ObjectNode op) {
        String relId = requireText(op, "relationshipId", "La relación");
        ArrayNode rels = c.withArray("relationships");
        int idx = indexOf(rels, relId);
        if (idx < 0) throw ApiException.notFound("La relación no existe en el diagrama.");
        ObjectNode changes = requireObject(op, "changes", "Los cambios");
        ObjectNode merged = ((ObjectNode) rels.get(idx)).deepCopy();
        changes.fields().forEachRemaining(e -> {
            if (!"id".equals(e.getKey())) merged.set(e.getKey(), e.getValue());
        });
        rels.set(idx, buildRelationship(c, merged, relId));
    }

    private void removeRelationship(ObjectNode c, String relId) {
        ArrayNode rels = c.withArray("relationships");
        int idx = indexOf(rels, relId);
        if (idx < 0) throw ApiException.notFound("La relación no existe en el diagrama.");
        rels.remove(idx);
    }

    /** ¿Agregar {@code source → target} (source hereda de target) genera un ciclo de herencia? */
    private boolean createsInheritanceCycle(ObjectNode c, String source, String target, String excludeId) {
        Set<String> visited = new HashSet<>();
        List<String> stack = new ArrayList<>(List.of(target));
        while (!stack.isEmpty()) {
            String current = stack.remove(stack.size() - 1);
            if (current.equals(source)) return true;
            if (!visited.add(current)) continue;
            for (JsonNode r : c.withArray("relationships")) {
                if ("GENERALIZATION".equals(text(r, "type"))
                        && !text(r, "id").equals(excludeId)
                        && current.equals(text(r, "sourceId"))) {
                    stack.add(text(r, "targetId"));
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- tipos de datos

    private void checkTypes(ObjectNode c, List<TypeRef> pending) {
        Set<String> classNames = classNames(c);
        for (TypeRef t : pending) {
            if (!isValidType(t.type(), classNames, t.allowVoid())) {
                throw invalidType(t.type(), t.where());
            }
        }
    }

    private void checkAllTypes(ObjectNode c) {
        Set<String> classNames = classNames(c);
        for (JsonNode cls : c.withArray("classes")) {
            for (JsonNode a : cls.withArray("attributes")) {
                if (!isValidType(text(a, "type"), classNames, false)) {
                    throw invalidType(text(a, "type"), "el atributo '" + text(a, "name") + "' de '" + text(cls, "name") + "'");
                }
            }
            for (JsonNode m : cls.withArray("methods")) {
                if (!isValidType(text(m, "returnType"), classNames, true)) {
                    throw invalidType(text(m, "returnType"), "el retorno del método '" + text(m, "name") + "'");
                }
                for (JsonNode p : m.withArray("parameters")) {
                    if (!isValidType(text(p, "type"), classNames, false)) {
                        throw invalidType(text(p, "type"), "el parámetro '" + text(p, "name") + "'");
                    }
                }
            }
        }
    }

    public static boolean isValidType(String type, Set<String> classNames, boolean allowVoid) {
        if (type == null || type.isBlank()) return false;
        String t = type.trim();
        if (allowVoid && t.equals("void")) return true;
        Matcher m = COLLECTION_TYPE.matcher(t);
        if (m.matches()) {
            t = m.group(2);
        }
        return BASE_TYPES.contains(t) || classNames.contains(t);
    }

    private static ApiException invalidType(String type, String where) {
        return new ApiException(ErrorCode.INVALID_DATATYPE,
                "El tipo de dato '" + type + "' no es válido en " + where + ".",
                Map.of("type", String.valueOf(type)));
    }

    /** Al renombrar una clase se actualizan los tipos que la referencian. */
    private void renameTypeReferences(ObjectNode c, String oldName, String newName) {
        Pattern p = Pattern.compile("(?<![\\w])" + Pattern.quote(oldName) + "(?![\\w])");
        String replacement = Matcher.quoteReplacement(newName);
        for (JsonNode cls : c.withArray("classes")) {
            for (JsonNode a : cls.withArray("attributes")) {
                ((ObjectNode) a).put("type", p.matcher(text(a, "type")).replaceAll(replacement));
            }
            for (JsonNode m : cls.withArray("methods")) {
                ((ObjectNode) m).put("returnType", p.matcher(text(m, "returnType")).replaceAll(replacement));
                for (JsonNode param : m.withArray("parameters")) {
                    ((ObjectNode) param).put("type", p.matcher(text(param, "type")).replaceAll(replacement));
                }
            }
        }
    }

    // ---------------------------------------------------------------- diagrama de secuencia

    private ObjectNode normalizeSequence(JsonNode content) {
        ObjectNode out = Json.object();
        out.put("schemaVersion", 1);
        out.put("type", "SEQUENCE");
        JsonNode lifelines = content.get("lifelines");
        JsonNode messages = content.get("messages");
        if ((lifelines != null && !lifelines.isArray()) || (messages != null && !messages.isArray())) {
            throw invalidJson();
        }
        Set<String> ids = new HashSet<>();
        ArrayNode outLifelines = Json.array();
        if (lifelines != null) {
            for (JsonNode l : lifelines) {
                if (!l.isObject()) throw invalidJson();
                ObjectNode ll = ((ObjectNode) l).deepCopy();
                ll.put("name", requireName(text(ll, "name"), "La línea de vida"));
                String id = text(ll, "id");
                if (id == null || id.isBlank()) {
                    do { id = Json.shortId(); } while (ids.contains(id));
                }
                if (!ids.add(id)) throw invalidJson();
                ll.put("id", id);
                outLifelines.add(ll);
            }
        }
        ArrayNode outMessages = Json.array();
        Set<String> messageIds = new HashSet<>();
        int order = 1;
        if (messages != null) {
            for (JsonNode m : messages) {
                if (!m.isObject()) throw invalidJson();
                ObjectNode mm = ((ObjectNode) m).deepCopy();
                String from = text(mm, "fromId");
                String to = text(mm, "toId");
                if (from == null || to == null || !ids.contains(from) || !ids.contains(to)) {
                    throw invalidJson();
                }
                String kind = text(mm, "kind");
                kind = kind == null ? "SYNC" : kind.toUpperCase(Locale.ROOT);
                if (!MESSAGE_KINDS.contains(kind)) throw invalidJson();
                String id = text(mm, "id");
                if (id == null || id.isBlank()) {
                    do { id = Json.shortId(); } while (messageIds.contains(id));
                }
                if (!messageIds.add(id)) throw invalidJson();
                mm.put("id", id);
                mm.put("kind", kind);
                mm.put("name", requireName(text(mm, "name"), "El mensaje"));
                mm.put("order", mm.hasNonNull("order") && mm.get("order").canConvertToInt() ? mm.get("order").asInt() : order);
                order++;
                outMessages.add(mm);
            }
        }
        out.set("lifelines", outLifelines);
        out.set("messages", outMessages);
        return out;
    }

    // ---------------------------------------------------------------- utilidades

    private static ApiException invalidJson() {
        return new ApiException(ErrorCode.INVALID_JSON, "El contenido del diagrama no es un JSON válido.");
    }

    private static ApiException classNotFound() {
        return ApiException.notFound("La clase no existe en el diagrama.");
    }

    private static ObjectNode requireObject(ObjectNode parent, String field, String label) {
        JsonNode n = parent.get(field);
        if (n == null || !n.isObject()) {
            throw ApiException.validation(label + " es obligatorio(a) y debe ser un objeto.");
        }
        return (ObjectNode) n;
    }

    private static String requireText(JsonNode parent, String field, String label) {
        String v = text(parent, field);
        if (v == null || v.isBlank()) {
            throw ApiException.validation(label + " es obligatorio(a) (" + field + ").");
        }
        return v;
    }

    private static String requireName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw ApiException.validation("El nombre es obligatorio (" + label + ").");
        }
        String n = name.trim();
        if (n.length() > MAX_NAME) {
            throw ApiException.validation("El nombre excede " + MAX_NAME + " caracteres (" + label + ").");
        }
        return n;
    }

    private static void assertIdFree(Set<String> used, String id, String label) {
        if (used.contains(id)) {
            throw ApiException.validation(label + " con identificador '" + id + "' ya existe en el diagrama.");
        }
    }

    private static void assertUniqueClassName(ObjectNode c, String name, String excludeId) {
        for (JsonNode cls : c.withArray("classes")) {
            if (!text(cls, "id").equals(excludeId) && text(cls, "name").equalsIgnoreCase(name)) {
                throw new ApiException(ErrorCode.DUPLICATE_CLASS, "Ya existe una clase llamada '" + name + "' en el diagrama.");
            }
        }
    }

    private static void putStereotype(ObjectNode cls, JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            cls.putNull("stereotype");
            return;
        }
        String s = value.asText().trim().toLowerCase(Locale.ROOT);
        if (!STEREOTYPES.contains(s)) {
            throw ApiException.validation("Estereotipo no válido: '" + value.asText() + "'. Use interface, abstract o enum.");
        }
        cls.put("stereotype", s);
    }

    private static void putVisibility(ObjectNode node, String defaultValue) {
        putVisibility(node, node.get("visibility"), defaultValue);
    }

    private static void putVisibility(ObjectNode node, JsonNode value, String defaultValue) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            node.put("visibility", defaultValue);
            return;
        }
        String v = value.asText().trim().toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(v)) {
            throw ApiException.validation("Visibilidad no válida: '" + value.asText() + "'.");
        }
        node.put("visibility", v);
    }

    private static void setCoordinates(ObjectNode cls, JsonNode x, JsonNode y) {
        cls.put("x", coordinate(x));
        cls.put("y", coordinate(y));
    }

    private static long coordinate(JsonNode v) {
        if (v == null || v.isNull() || !v.isNumber()) {
            throw ApiException.validation("Las coordenadas x e y son obligatorias y deben ser numéricas.");
        }
        double d = v.asDouble();
        if (d < 0 || d > MAX_COORD) {
            throw new ApiException(ErrorCode.OUT_OF_BOUNDS,
                    "La posición está fuera de los límites del lienzo (0 a " + MAX_COORD + ").");
        }
        return Math.round(d);
    }

    private static Set<String> attributeNames(ObjectNode cls, String excludeId) {
        Set<String> names = new HashSet<>();
        for (JsonNode a : cls.withArray("attributes")) {
            if (!text(a, "id").equals(excludeId)) names.add(text(a, "name").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static Set<String> classNames(ObjectNode c) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode cls : c.withArray("classes")) names.add(text(cls, "name"));
        return names;
    }

    private static ObjectNode findClass(ObjectNode c, String id) {
        for (JsonNode cls : c.withArray("classes")) {
            if (id.equals(text(cls, "id"))) return (ObjectNode) cls;
        }
        throw classNotFound();
    }

    private static int indexOf(ArrayNode array, String id) {
        for (int i = 0; i < array.size(); i++) {
            if (id.equals(text(array.get(i), "id"))) return i;
        }
        return -1;
    }

    /** Todos los IDs de clases, atributos, métodos y relaciones del contenido. */
    public static Set<String> collectIds(JsonNode content) {
        Set<String> ids = new HashSet<>();
        for (JsonNode cls : content.path("classes")) {
            addId(ids, cls);
            for (JsonNode a : cls.path("attributes")) addId(ids, a);
            for (JsonNode m : cls.path("methods")) addId(ids, m);
        }
        for (JsonNode r : content.path("relationships")) addId(ids, r);
        return ids;
    }

    private static void addId(Set<String> ids, JsonNode node) {
        String id = text(node, "id");
        if (id != null) ids.add(id);
    }
}
