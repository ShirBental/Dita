package app.dita.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared JSON helpers. Compact output is deterministic: field order is insertion order. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {}

    public static JsonNode parse(String s) {
        try { return MAPPER.readTree(s); }
        catch (Exception e) { throw new IllegalArgumentException("invalid JSON: " + e.getClass().getSimpleName()); }
    }

    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }

    public static String compact(JsonNode n) {
        try { return MAPPER.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String pretty(JsonNode n) {
        try { return PRETTY.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static ArrayNode strings(Collection<String> values) {
        ArrayNode a = array();
        values.forEach(a::add);
        return a;
    }

    public static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) return out;
        if (!n.isArray()) throw new IllegalArgumentException("expected array");
        n.forEach(x -> {
            if (!x.isTextual()) throw new IllegalArgumentException("expected array of strings");
            out.add(x.asText());
        });
        return out;
    }

    public static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
