package app.dita.eval.cost;

import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict reader/writer for the recorded-usage JSONL format {@value UsageRecord#SCHEMA} (N-55). The loader FAILS on:
 * any field not in {@link #FIELDS} (for example {@code verbatim_evidence}, {@code content}, {@code request_id}); any
 * missing field (nullable fields must be present as JSON null); a wrong schema tag; a non-integer number where an
 * integer is expected; and any string that is not an allowlisted enum value or short identifier. Error messages name
 * the line and field, never the offending value.
 */
public final class UsageJsonl {
    /** The complete allowlist, in canonical output order. */
    public static final List<String> FIELDS = List.of("schema", "call_kind", "arm", "run_label", "model", "reasoning_effort",
            "max_completion_tokens", "prompt_tokens", "completion_tokens", "reasoning_tokens", "cached_tokens", "latency_ms",
            "finish_reason", "http_status", "request_bytes", "request_sha256", "request_id_sha256", "provenance");
    private static final Set<String> FIELD_SET = Set.copyOf(FIELDS);

    private UsageJsonl() {}

    public static List<UsageRecord> parse(String jsonl) {
        List<UsageRecord> out = new ArrayList<>();
        int n = 0;
        for (String line : jsonl.split("\n", -1)) {
            n++;
            if (line.isBlank()) continue;
            JsonNode r;
            try { r = Json.parse(line); } catch (IllegalArgumentException e) { throw err(n, "not valid JSON"); }
            if (!r.isObject()) throw err(n, "not a JSON object");
            Set<String> seen = new LinkedHashSet<>();
            for (Iterator<String> it = r.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (!FIELD_SET.contains(f)) throw err(n, "field not in the numbers-only allowlist: " + safeName(f));
                seen.add(f);
            }
            for (String f : FIELDS) if (!seen.contains(f)) throw err(n, "missing field " + f + " (use null for unknown)");
            if (!UsageRecord.SCHEMA.equals(text(r, "schema", n, false))) throw err(n, "schema must be " + UsageRecord.SCHEMA);
            try {
                out.add(new UsageRecord(
                        UsageRecord.CallKind.valueOf(text(r, "call_kind", n, false)),
                        text(r, "arm", n, true),
                        text(r, "run_label", n, false),
                        text(r, "model", n, false),
                        text(r, "reasoning_effort", n, true),
                        Math.toIntExact(integer(r, "max_completion_tokens", n, false)),
                        integer(r, "prompt_tokens", n, false),
                        integer(r, "completion_tokens", n, false),
                        integer(r, "reasoning_tokens", n, true),
                        integer(r, "cached_tokens", n, true),
                        integer(r, "latency_ms", n, true),
                        text(r, "finish_reason", n, true),
                        r.get("http_status").isNull() ? null : Math.toIntExact(integer(r, "http_status", n, false)),
                        integer(r, "request_bytes", n, true),
                        text(r, "request_sha256", n, true),
                        text(r, "request_id_sha256", n, true),
                        Provenance.valueOf(text(r, "provenance", n, false))));
            } catch (IllegalArgumentException | ArithmeticException e) {
                if (e instanceof UsageFormatException u) throw u;
                throw err(n, "invalid value: " + e.getClass().getSimpleName() + (e.getMessage() != null && !e.getMessage().startsWith("No enum") ? " (" + e.getMessage() + ")" : ""));
            }
        }
        return out;
    }

    public static List<UsageRecord> load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static ObjectNode toJson(UsageRecord u) {
        ObjectNode o = Json.object();
        o.put("schema", UsageRecord.SCHEMA);
        o.put("call_kind", u.callKind().name());
        putText(o, "arm", u.arm());
        o.put("run_label", u.runLabel());
        o.put("model", u.model());
        putText(o, "reasoning_effort", u.reasoningEffort());
        o.put("max_completion_tokens", u.maxCompletionTokens());
        o.put("prompt_tokens", u.promptTokens());
        o.put("completion_tokens", u.completionTokens());
        putLong(o, "reasoning_tokens", u.reasoningTokens());
        putLong(o, "cached_tokens", u.cachedTokens());
        putLong(o, "latency_ms", u.latencyMs());
        putText(o, "finish_reason", u.finishReason());
        if (u.httpStatus() == null) o.putNull("http_status"); else o.put("http_status", u.httpStatus());
        putLong(o, "request_bytes", u.requestBytes());
        putText(o, "request_sha256", u.requestSha256());
        putText(o, "request_id_sha256", u.requestIdSha256());
        o.put("provenance", u.provenance().name());
        return o;
    }

    public static String render(List<UsageRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (UsageRecord u : records) sb.append(Json.compact(toJson(u))).append('\n');
        return sb.toString();
    }

    private static void putText(ObjectNode o, String f, String v) { if (v == null) o.putNull(f); else o.put(f, v); }
    private static void putLong(ObjectNode o, String f, Long v) { if (v == null) o.putNull(f); else o.put(f, v); }

    private static String text(JsonNode r, String f, int n, boolean nullable) {
        JsonNode v = r.get(f);
        if (v.isNull()) { if (nullable) return null; throw err(n, f + " must not be null"); }
        if (!v.isTextual()) throw err(n, f + " must be a string");
        return v.asText();
    }

    private static Long integer(JsonNode r, String f, int n, boolean nullable) {
        JsonNode v = r.get(f);
        if (v.isNull()) { if (nullable) return null; throw err(n, f + " must not be null"); }
        if (!v.isIntegralNumber() || !v.canConvertToLong()) throw err(n, f + " must be an integer");
        return v.asLong();
    }

    /** Field names are echoed only if they look like identifiers, so an error can never print arbitrary text. */
    private static String safeName(String f) { return f.matches("[A-Za-z0-9_]{1,40}") ? f : "<non-identifier field name>"; }

    private static UsageFormatException err(int line, String what) {
        return new UsageFormatException("usage line " + line + ": " + what);
    }

    public static final class UsageFormatException extends IllegalArgumentException {
        public UsageFormatException(String m) { super(m); }
    }
}
