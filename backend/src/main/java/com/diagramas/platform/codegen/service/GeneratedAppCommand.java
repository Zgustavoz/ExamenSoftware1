package com.diagramas.platform.codegen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.diagramas.platform.common.util.Json;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduce una orden en español a la llamada HTTP que la cumple, sin usar la IA.
 *
 * <p>Es el respaldo de {@code /v1/command}: si el asistente no está configurado o falla, la demostración
 * del backend generado sigue funcionando. Entiende órdenes del estilo
 * «registra un cliente con nombre Juan y email juan@x.com» o «lista los clientes».
 */
public final class GeneratedAppCommand {

    /** Entidad del backend generado, tal como la expone su API REST. */
    public record Entity(String name, String path, List<Field> fields) {
        public record Field(String name, String type) {}
    }

    /** Llamada resultante. `body` va vacío en las consultas. */
    public record Call(String explanation, String method, String path, Map<String, Object> body) {}

    private static final Pattern CREAR = Pattern.compile(
            "\\b(registra|registrar|crea|crear|agrega|agregar|anade|anadir|nuevo|nueva|guarda|guardar)\\b");
    private static final Pattern LISTAR = Pattern.compile("\\b(lista|listar|muestra|mostrar|ver|consulta|consultar)\\b");
    /** «con nombre Juan», «nombre: Juan», «y email juan@x.com». */
    private static final Pattern ASIGNACION = Pattern.compile(
            "\\b(?:con\\s+|y\\s+)?([\\p{L}\\p{N}_]+)\\s*(?::|\\s)\\s*(.+?)"
                    + "(?=\\s+(?:y|con)\\s+[\\p{L}\\p{N}_]+\\s*[:\\s]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private GeneratedAppCommand() {}

    /** Quita acentos y pasa a minúsculas, para comparar lo que se dictó con los nombres del diagrama. */
    static String normalize(String text) {
        if (text == null) return "";
        String sinTildes = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Interpreta la orden. Devuelve {@code null} si no reconoce ni la acción ni la entidad: en ese caso el
     * llamador responde que no entendió, en vez de inventarse una llamada.
     */
    public static Call parse(String instruction, List<Entity> entities) {
        String texto = normalize(instruction);
        if (texto.isEmpty() || entities.isEmpty()) return null;

        Entity entidad = findEntity(texto, entities);
        if (entidad == null) return null;

        boolean crear = CREAR.matcher(texto).find();
        boolean listar = LISTAR.matcher(texto).find();
        if (!crear && !listar) return null;
        if (listar && !crear) {
            return new Call("Consulté " + entidad.name() + ".", "GET", entidad.path(), Map.of());
        }

        Map<String, Object> body = extractFields(instruction, entidad);
        if (body.isEmpty()) return null;
        return new Call("Registré un " + entidad.name() + ".", "POST", entidad.path(), body);
    }

    /** La entidad nombrada en la orden; acepta el singular y el plural sencillo. */
    private static Entity findEntity(String texto, List<Entity> entities) {
        Entity mejor = null;
        int posicion = Integer.MAX_VALUE;
        for (Entity e : entities) {
            String nombre = normalize(e.name());
            for (String variante : List.of(nombre, nombre + "s", nombre + "es")) {
                int i = texto.indexOf(variante);
                if (i >= 0 && i < posicion) {
                    posicion = i;
                    mejor = e;
                }
            }
        }
        return mejor;
    }

    /**
     * Saca los pares campo/valor del texto **original**: el nombre del campo se compara sin acentos ni
     * mayúsculas, pero el valor se guarda tal como se dictó, para que «Juan Pérez» no acabe en minúsculas.
     * Lo que no sea un campo de la entidad se descarta, para no enviar basura al backend generado.
     */
    private static Map<String, Object> extractFields(String original, Entity entidad) {
        Map<String, Entity.Field> porNombre = new LinkedHashMap<>();
        for (Entity.Field f : entidad.fields()) {
            porNombre.put(normalize(f.name()), f);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        Matcher m = ASIGNACION.matcher(original);
        while (m.find()) {
            Entity.Field campo = porNombre.get(normalize(m.group(1)));
            if (campo == null) continue;
            String valor = m.group(2).trim().replaceAll("[.,;]+$", "");
            if (!valor.isEmpty()) body.put(campo.name(), convert(valor, campo.type()));
        }
        return body;
    }

    /** Ajusta el valor al tipo del atributo; si no encaja, se manda como texto y decide el backend generado. */
    private static Object convert(String valor, String type) {
        String t = type == null ? "String" : type.trim();
        try {
            return switch (t) {
                case "int", "Integer" -> Integer.valueOf(valor);
                case "long", "Long" -> Long.valueOf(valor);
                case "double", "Double", "float", "BigDecimal" -> Double.valueOf(valor.replace(',', '.'));
                case "boolean", "Boolean" -> valor.startsWith("s") || valor.startsWith("t") || "1".equals(valor);
                default -> valor;
            };
        } catch (NumberFormatException e) {
            return valor;
        }
    }

    /** Las entidades del diagrama que el generador expone como recurso REST. */
    public static List<Entity> entitiesOf(JsonNode content, java.util.function.Function<String, String> pathOf) {
        List<Entity> entities = new ArrayList<>();
        for (JsonNode c : content.path("classes")) {
            String estereotipo = Json.text(c, "stereotype");
            // Interfaces, enums y abstractas no se instancian: el generador no les da controlador.
            if ("interface".equals(estereotipo) || "enum".equals(estereotipo) || "abstract".equals(estereotipo)) {
                continue;
            }
            String nombre = Json.text(c, "name");
            if (nombre == null || nombre.isBlank()) continue;

            List<Entity.Field> campos = new ArrayList<>();
            for (JsonNode a : c.path("attributes")) {
                String campo = Json.text(a, "name");
                if (campo != null && !campo.isBlank() && !"id".equalsIgnoreCase(campo)) {
                    campos.add(new Entity.Field(campo, Json.text(a, "type")));
                }
            }
            entities.add(new Entity(nombre, pathOf.apply(nombre), campos));
        }
        return entities;
    }

    /** Las entidades en el formato que espera el `ai-service`. */
    public static ObjectNode toJson(Entity entity) {
        ObjectNode node = Json.object();
        node.put("name", entity.name());
        node.put("path", entity.path());
        var campos = node.putArray("fields");
        for (Entity.Field f : entity.fields()) {
            ObjectNode campo = campos.addObject();
            campo.put("name", f.name());
            campo.put("type", f.type() == null ? "String" : f.type());
        }
        return node;
    }
}
