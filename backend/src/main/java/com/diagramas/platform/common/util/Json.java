package com.diagramas.platform.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Utilidades JSON compartidas. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** Convierte un nodo a objetos simples (Map/List/String/Number) aptos para el escalar GraphQL JSON. */
    public static Object plain(JsonNode node) {
        return node == null ? null : MAPPER.convertValue(node, Object.class);
    }

    public static JsonNode fromPlain(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** ID corto (8 hex) para elementos de diagrama. */
    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
