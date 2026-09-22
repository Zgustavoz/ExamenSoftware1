package com.diagramas.platform.copilot.service;

import static com.diagramas.platform.common.util.Json.text;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code normalizarRespuesta} (9.2): traduce las operaciones de la IA (que referencian clases POR NOMBRE)
 * a operaciones por {@code id} del vocabulario 7.3, asigna IDs a lo nuevo y corrige mayúsculas/sinónimos
 * de tipos. Las visibilidades por defecto y la posición en grilla las completa el applier.
 * La salida se revalida siempre con {@link DiagramOperationApplier}: el backend nunca confía en la IA.
 */
@Component
public class OperationNormalizer {

    private static final Pattern COLLECTION = Pattern.compile("^(List|Set)<\\s*([^<>]+?)\\s*>$");
    private static final Map<String, String> TYPE_SYNONYMS = new HashMap<>();

    static {
        for (String t : DiagramOperationApplier.BASE_TYPES) {
            TYPE_SYNONYMS.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
        }
        TYPE_SYNONYMS.put("str", "String");
        TYPE_SYNONYMS.put("text", "String");
        TYPE_SYNONYMS.put("bool", "boolean");
        TYPE_SYNONYMS.put("date", "LocalDate");
        TYPE_SYNONYMS.put("datetime", "LocalDateTime");
        TYPE_SYNONYMS.put("timestamp", "LocalDateTime");
        TYPE_SYNONYMS.put("decimal", "BigDecimal");
        TYPE_SYNONYMS.put("money", "BigDecimal");
    }

    public List<ObjectNode> normalize(JsonNode aiOperations, JsonNode content) {
        if (aiOperations == null || !aiOperations.isArray()) {
            throw invalid("La respuesta no contiene una lista de operaciones.");
        }
        Context ctx = new Context(content);
        // Primero se registran las clases nuevas, para que los tipos puedan referenciarlas en cualquier orden.
        for (JsonNode op : aiOperations) {
            if (op.isObject() && "ADD_CLASS".equals(text(op, "op")) && op.get("class") != null && op.get("class").isObject()) {
                String name = text(op.get("class"), "name");
                if (name != null && !name.isBlank() && !ctx.classIds.containsKey(name.trim().toLowerCase(Locale.ROOT))) {
                    String id = ctx.freshId();
                    ctx.classIds.put(name.trim().toLowerCase(Locale.ROOT), id);
                    ctx.canonical.put(name.trim().toLowerCase(Locale.ROOT), name.trim());
                    ctx.preassigned.put(name.trim().toLowerCase(Locale.ROOT), id);
                }
            }
        }
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode op : aiOperations) {
            if (!op.isObject()) {
                throw invalid("Una operación no es un objeto JSON.");
            }
            result.add(translate((ObjectNode) op, ctx));
        }
        return result;
    }

    // ------------------------------------------------------------------

    private ObjectNode translate(ObjectNode in, Context ctx) {
        String type = text(in, "op");
        if (type == null || !DiagramOperationApplier.OPERATIONS.contains(type)) {
            throw invalid("Operación desconocida: " + type + ".");
        }
        ObjectNode out = Json.object();
        out.put("op", type);
        switch (type) {
            case "ADD_CLASS" -> {
                ObjectNode cls = obj(in, "class").deepCopy();
                String name = required(text(cls, "name"), "nombre de la clase");
                cls.put("name", name.trim());
                String key = name.trim().toLowerCase(Locale.ROOT);
                String id = ctx.preassigned.remove(key);
                if (id == null) {
                    id = ctx.freshId(); // duplicado: el applier lo rechazará con DUPLICATE_CLASS
                }
                cls.put("id", id);
                fixMemberTypes(cls, ctx);
                out.set("class", cls);
            }
            case "UPDATE_CLASS" -> {
                String id = classId(in, ctx);
                ObjectNode changes = obj(in, "changes").deepCopy();
                String newName = text(changes, "name");
                if (newName != null && !newName.isBlank()) {
                    String oldKey = ctx.nameOf(id);
                    if (oldKey != null) ctx.classIds.remove(oldKey);
                    ctx.classIds.put(newName.trim().toLowerCase(Locale.ROOT), id);
                    ctx.canonical.put(newName.trim().toLowerCase(Locale.ROOT), newName.trim());
                }
                out.put("classId", id);
                out.set("changes", changes);
            }
            case "MOVE_CLASS" -> {
                out.put("classId", classId(in, ctx));
                out.set("x", in.get("x"));
                out.set("y", in.get("y"));
            }
            case "REMOVE_CLASS" -> out.put("classId", classId(in, ctx));
            case "ADD_ATTRIBUTE" -> {
                out.put("classId", classId(in, ctx));
                ObjectNode attr = obj(in, "attribute").deepCopy();
                fixTypeField(attr, "type", ctx);
                out.set("attribute", attr);
            }
            case "UPDATE_ATTRIBUTE" -> {
                String cid = classId(in, ctx);
                out.put("classId", cid);
                out.put("attributeId", memberId(ctx, cid, "attributes", in, "attributeName", "attributeId"));
                ObjectNode changes = obj(in, "changes").deepCopy();
                if (changes.has("type")) fixTypeField(changes, "type", ctx);
                out.set("changes", changes);
            }
            case "REMOVE_ATTRIBUTE" -> {
                String cid = classId(in, ctx);
                out.put("classId", cid);
                out.put("attributeId", memberId(ctx, cid, "attributes", in, "attributeName", "attributeId"));
            }
            case "ADD_METHOD" -> {
                out.put("classId", classId(in, ctx));
                ObjectNode method = obj(in, "method").deepCopy();
                fixMethodTypes(method, ctx);
                out.set("method", method);
            }
            case "UPDATE_METHOD" -> {
                String cid = classId(in, ctx);
                out.put("classId", cid);
                out.put("methodId", memberId(ctx, cid, "methods", in, "methodName", "methodId"));
                ObjectNode changes = obj(in, "changes").deepCopy();
                fixMethodTypes(changes, ctx);
                out.set("changes", changes);
            }
            case "REMOVE_METHOD" -> {
                String cid = classId(in, ctx);
                out.put("classId", cid);
                out.put("methodId", memberId(ctx, cid, "methods", in, "methodName", "methodId"));
            }
            case "ADD_RELATIONSHIP" -> {
                ObjectNode rel = obj(in, "relationship").deepCopy();
                String source = ctx.resolveClass(text(rel, "source") != null ? text(rel, "source") : text(rel, "sourceId"));
                String target = ctx.resolveClass(text(rel, "target") != null ? text(rel, "target") : text(rel, "targetId"));
                rel.remove("source");
                rel.remove("target");
                rel.put("sourceId", source);
                rel.put("targetId", target);
                String id = ctx.freshId();
                rel.put("id", id);
                ctx.relationships.put(source + "|" + target, id);
                out.set("relationship", rel);
            }
            case "UPDATE_RELATIONSHIP" -> {
                out.put("relationshipId", relationshipId(in, ctx));
                ObjectNode changes = obj(in, "changes").deepCopy();
                if (changes.has("source")) {
                    changes.put("sourceId", ctx.resolveClass(text(changes, "source")));
                    changes.remove("source");
                }
                if (changes.has("target")) {
                    changes.put("targetId", ctx.resolveClass(text(changes, "target")));
                    changes.remove("target");
                }
                out.set("changes", changes);
            }
            case "REMOVE_RELATIONSHIP" -> out.put("relationshipId", relationshipId(in, ctx));
            default -> throw invalid("Operación desconocida: " + type + ".");
        }
        return out;
    }

    // ---- resolución de referencias por nombre

    private String classId(ObjectNode in, Context ctx) {
        String byId = text(in, "classId");
        if (byId != null && ctx.knownIds.contains(byId)) return byId;
        return ctx.resolveClass(text(in, "className"));
    }

    private String memberId(Context ctx, String classId, String list, ObjectNode in, String nameField, String idField) {
        String byId = text(in, idField);
        JsonNode cls = ctx.classNode(classId);
        if (cls != null) {
            for (JsonNode m : cls.path(list)) {
                if (byId != null && byId.equals(text(m, "id"))) return byId;
            }
            String name = text(in, nameField);
            if (name != null) {
                for (JsonNode m : cls.path(list)) {
                    if (name.trim().equalsIgnoreCase(text(m, "name"))) return text(m, "id");
                }
            }
        }
        throw invalid("No se encontró " + (list.equals("attributes") ? "el atributo" : "el método")
                + " '" + text(in, nameField) + "' en la clase indicada.");
    }

    private String relationshipId(ObjectNode in, Context ctx) {
        String byId = text(in, "relationshipId");
        if (byId != null && ctx.relationships.containsValue(byId)) return byId;
        String source = ctx.resolveClass(text(in, "source"));
        String target = ctx.resolveClass(text(in, "target"));
        String id = ctx.relationships.get(source + "|" + target);
        if (id == null) {
            throw invalid("No existe una relación entre '" + text(in, "source") + "' y '" + text(in, "target") + "'.");
        }
        return id;
    }

    // ---- tipos

    private void fixMemberTypes(ObjectNode cls, Context ctx) {
        JsonNode attrs = cls.get("attributes");
        if (attrs != null && attrs.isArray()) {
            for (JsonNode a : attrs) {
                if (a.isObject()) fixTypeField((ObjectNode) a, "type", ctx);
            }
        }
        JsonNode methods = cls.get("methods");
        if (methods != null && methods.isArray()) {
            for (JsonNode m : methods) {
                if (m.isObject()) fixMethodTypes((ObjectNode) m, ctx);
            }
        }
    }

    private void fixMethodTypes(ObjectNode method, Context ctx) {
        if (method.has("returnType")) fixTypeField(method, "returnType", ctx);
        JsonNode params = method.get("parameters");
        if (params != null && params.isArray()) {
            for (JsonNode p : params) {
                if (p.isObject()) fixTypeField((ObjectNode) p, "type", ctx);
            }
        }
    }

    private void fixTypeField(ObjectNode node, String field, Context ctx) {
        String t = text(node, field);
        if (t != null) node.put(field, fixType(t, ctx));
    }

    private String fixType(String raw, Context ctx) {
        String t = raw.trim();
        Matcher m = COLLECTION.matcher(t);
        if (m.matches()) {
            String wrapper = m.group(1);
            return wrapper + "<" + fixSimple(m.group(2), ctx) + ">";
        }
        return fixSimple(t, ctx);
    }

    private String fixSimple(String t, Context ctx) {
        if (DiagramOperationApplier.BASE_TYPES.contains(t) || t.equals("void")) return t;
        String lower = t.toLowerCase(Locale.ROOT);
        String cls = ctx.canonical.get(lower);
        if (cls != null) return cls;
        return TYPE_SYNONYMS.getOrDefault(lower, t);
    }

    // ---- utilidades

    private static ObjectNode obj(ObjectNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || !n.isObject()) throw invalid("Falta el campo '" + field + "' en una operación.");
        return (ObjectNode) n;
    }

    private static String required(String v, String label) {
        if (v == null || v.isBlank()) throw invalid("Falta el " + label + ".");
        return v;
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.AI_INVALID_RESPONSE,
                "El asistente propuso cambios que no se pudieron aplicar: " + reason,
                Map.of("reason", reason));
    }

    /** Estado de resolución de nombres durante la normalización de un lote. */
    private static final class Context {
        final JsonNode content;
        final Map<String, String> classIds = new HashMap<>();     // nombre (minúsculas) → id
        final Map<String, String> canonical = new HashMap<>();    // nombre (minúsculas) → nombre real
        final Map<String, String> preassigned = new HashMap<>();  // clases nuevas del lote → id
        final Map<String, String> relationships = new HashMap<>(); // "src|tgt" → id
        final Set<String> knownIds = new HashSet<>();
        final Set<String> used = new HashSet<>();

        Context(JsonNode content) {
            this.content = content;
            for (JsonNode c : content.path("classes")) {
                String name = text(c, "name");
                classIds.put(name.toLowerCase(Locale.ROOT), text(c, "id"));
                canonical.put(name.toLowerCase(Locale.ROOT), name);
                knownIds.add(text(c, "id"));
            }
            for (JsonNode r : content.path("relationships")) {
                relationships.put(text(r, "sourceId") + "|" + text(r, "targetId"), text(r, "id"));
            }
            used.addAll(DiagramOperationApplier.collectIds(content));
        }

        String freshId() {
            String id;
            do {
                id = Json.shortId();
            } while (used.contains(id));
            used.add(id);
            knownIds.add(id);
            return id;
        }

        String resolveClass(String name) {
            if (name == null || name.isBlank()) throw invalid("Falta el nombre de una clase.");
            String id = classIds.get(name.trim().toLowerCase(Locale.ROOT));
            if (id == null) throw invalid("La clase '" + name + "' no existe en el diagrama.");
            return id;
        }

        String nameOf(String classId) {
            return classIds.entrySet().stream()
                    .filter(e -> e.getValue().equals(classId))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }

        JsonNode classNode(String id) {
            for (JsonNode c : content.path("classes")) {
                if (id.equals(text(c, "id"))) return c;
            }
            return null;
        }
    }
}
