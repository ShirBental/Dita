package app.dita.muse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Minimal Muse chat client for Dita's reasoning tasks. NOT a generic LLM framework: one request shape, one model,
 * structured JSON output only.
 * <p>Per the user-verified docs: Bearer auth; OpenAI-compatible chat completions with {@code messages}; the response
 * text is {@code choices[0].message.content}. Only that shape is accepted; anything else fails (no guessing).
 * Token limit: {@code max_completion_tokens} (user-verified docs). Still UNVERIFIED: {@code temperature},
 * {@code response_format} support (default PROMPT_ONLY: the schema is in the prompt and Dita validates strictly),
 * and the {@code usage.prompt_tokens}/{@code completion_tokens} fields (reported as null if absent).
 */
public final class MuseClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Successful call. {@code usage} holds every numeric usage field (flattened, e.g. completion_tokens_details.reasoning_tokens);
     * {@code shape} is the structure-only description of the response (no text).
     */
    public record Result(String content, String model, Integer promptTokens, Integer completionTokens,
                         Integer reasoningTokens, long latencyMs, String requestId, String finishReason,
                         int httpStatus, java.util.Map<String, Integer> usage, String shape) {}

    private final MuseSettings settings;
    private final MuseTransport transport;
    private final Function<String, String> env;
    /** N-39: the call cap is per process, across all client instances. */
    private static final AtomicInteger PROCESS_CALLS = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public MuseClient(MuseSettings settings, MuseTransport transport, Function<String, String> env) {
        this.settings = settings; this.transport = transport; this.env = env;
    }

    public MuseSettings settings() { return settings; }
    public int callsMade() { return calls.get(); }
    static void resetProcessCountForTests() { PROCESS_CALLS.set(0); }

    /** Connectivity check: one tiny request, 8 output tokens, no schema. */
    /**
     * Live finding (2026-10-28): muse-spark-1.3 is a reasoning model. With a 64-token limit it used all 64 completion
     * tokens on reasoning (finish_reason=length, content=null). Connectivity budget: 128 (user decision).
     */
    public Result ping() { return ping(128); }

    /** Headroom matters: a reasoning model spends output tokens before any visible content. */
    public Result ping(int maxTokens) {
        return call("You are a connectivity check.", "Reply with the single word: OK", null, null, maxTokens);
    }

    /** One structured-output call. {@code schema} is a JSON Schema for the expected object. */
    public Result complete(String system, String user, String schemaName, ObjectNode schema) {
        return call(system, user, schemaName, schema, settings.maxOutputTokens());
    }

    private Result call(String system, String user, String schemaName, ObjectNode schema, int maxTokens) {
        String key = env.apply(settings.apiKeyEnvVar());
        if (key == null || key.isBlank()) throw new MuseException(MuseException.Kind.CONFIG, 0, "environment variable " + settings.apiKeyEnvVar() + " is not set");
        if (system.length() + user.length() > settings.maxInputChars())
            throw new MuseException(MuseException.Kind.BUDGET, 0, "input exceeds maxInputChars=" + settings.maxInputChars());
        if (PROCESS_CALLS.incrementAndGet() > settings.maxCallsPerProcess()) {
            PROCESS_CALLS.decrementAndGet();
            throw new MuseException(MuseException.Kind.BUDGET, 0, "maxCallsPerProcess=" + settings.maxCallsPerProcess() + " reached");
        }
        calls.incrementAndGet();

        String body = requestBody(system, user, schemaName, schema, maxTokens);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + key);
        long t0 = System.nanoTime();
        MuseTransport.Reply r;
        try {
            r = transport.post(settings.chatCompletionsUrl(), headers, body, settings.timeout());
        } catch (MuseException e) {
            throw e;                                                           // TIMEOUT from the transport
        } catch (Exception e) {
            throw new MuseException(MuseException.Kind.TRANSPORT, 0, "no HTTP response: " + e.getClass().getSimpleName());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int st = r.status();
        if (st < 200 || st >= 300) {
            MuseException.Kind k = st == 401 || st == 403 ? MuseException.Kind.AUTH : st == 429 ? MuseException.Kind.RATE_LIMIT
                    : st >= 500 ? MuseException.Kind.SERVER : MuseException.Kind.CLIENT;
            throw apiError(k, st, r.body(), key);
        }
        return parse(r.body(), st, ms, r.headers().getOrDefault("x-request-id", ""));
    }

    /**
     * Non-2xx: parse the OpenAI-style error envelope {"error":{"message","type","code","param"}}. Only type and code
     * (sanitised) plus a redacted, truncated message are kept. A non-JSON body is described by length only.
     */
    static MuseException apiError(MuseException.Kind k, int status, String body, String key) {
        String type = null, code = null, msg;
        try {
            JsonNode e = JSON.readTree(body == null ? "" : body).path("error");
            if (e.isObject()) {
                type = token(e.path("type").asText(null));
                code = token(e.path("code").isNumber() ? e.path("code").asText() : e.path("code").asText(null));
                msg = redact(e.path("message").asText(""), key);
            } else {
                msg = "error body without an error envelope (" + (body == null ? 0 : body.length()) + " chars)";
            }
        } catch (Exception ex) {
            msg = "non-JSON error body (" + (body == null ? 0 : body.length()) + " chars)";
        }
        String text = (type != null ? "type=" + type + " " : "") + (code != null ? "code=" + code + " " : "") + msg;
        return new MuseException(k, status, text.strip(), new MuseException.Details(type, code, null, java.util.Map.of(), null));
    }

    private static String token(String s) { return s != null && s.matches("[A-Za-z0-9_.:-]{1,64}") ? s : null; }

    String requestBody(String system, String user, String schemaName, ObjectNode schema, int maxTokens) {
        ObjectNode b = JSON.createObjectNode();
        b.put("model", settings.model());
        ArrayNode msgs = b.putArray("messages");
        String sys = system;
        if (schema != null && settings.responseFormat() != MuseSettings.ResponseFormat.JSON_SCHEMA) {
            sys = system + "\nThe JSON object MUST conform to this JSON Schema:\n" + schema;          // schema in the prompt
        }
        msgs.addObject().put("role", "system").put("content", sys);
        msgs.addObject().put("role", "user").put("content", user);
        b.put("temperature", 0);
        b.put("max_completion_tokens", maxTokens);                                  // documented output cap (includes reasoning)
        b.put("reasoning_effort", settings.reasoningEffort().wire());               // always explicit; default is model-dependent
        if (schema == null) {
            try { return JSON.writeValueAsString(b); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        switch (settings.responseFormat()) {
            case JSON_SCHEMA -> {
                ObjectNode rf = b.putObject("response_format").put("type", "json_schema");
                rf.putObject("json_schema").put("name", schemaName).put("strict", true).set("schema", schema);
            }
            case JSON_OBJECT -> b.putObject("response_format").put("type", "json_object");
            case PROMPT_ONLY -> { }
        }
        try { return JSON.writeValueAsString(b); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Result parse(String body, int httpStatus, long ms, String requestId) {
        JsonNode n;
        try { n = JSON.readTree(body); }
        catch (Exception e) {
            throw new MuseException(MuseException.Kind.RESPONSE_FORMAT, httpStatus, "2xx body is not JSON (" + (body == null ? 0 : body.length()) + " chars)");
        }
        if (n == null || !n.isObject()) throw new MuseException(MuseException.Kind.RESPONSE_FORMAT, httpStatus, "2xx body is not a JSON object");
        String content = null, finish = null;
        Integer pt = null, ct = null, rt = null;
        // Documented shape only: choices[0].message.content (string, or an array of text parts).
        if (n.path("choices").isArray() && n.path("choices").size() > 0) {
            JsonNode m = n.path("choices").get(0).path("message").path("content");
            content = m.isTextual() ? m.asText() : textOfParts(m);
            finish = n.path("choices").get(0).path("finish_reason").asText(null);
            if (n.path("usage").has("prompt_tokens")) pt = n.path("usage").path("prompt_tokens").asInt();
            if (n.path("usage").has("completion_tokens")) ct = n.path("usage").path("completion_tokens").asInt();
            JsonNode rtn = n.path("usage").path("completion_tokens_details").path("reasoning_tokens");
            if (rtn.isNumber()) rt = rtn.asInt();
        }
        String fr = finish != null && finish.matches("[a-z_]{1,32}") ? finish : null;
        java.util.Map<String, Integer> usage = usage(n.path("usage"));
        String shape = shape(n);
        if (!n.path("choices").isArray() || n.path("choices").size() == 0) {
            throw new MuseException(MuseException.Kind.RESPONSE_FORMAT, httpStatus, "2xx response has no choices; shape=" + shape,
                    new MuseException.Details(null, null, fr, usage, shape));
        }
        if (content == null || content.isBlank()) {
            String why = "length".equals(fr) ? "content empty: completion budget exhausted (finish_reason=length)" : "content empty (finish_reason=" + fr + ")";
            throw new MuseException(MuseException.Kind.EMPTY_CONTENT, httpStatus, why + "; shape=" + shape,
                    new MuseException.Details(null, null, fr, usage, shape));
        }
        return new Result(content, n.path("model").asText(null), pt, ct, rt, ms, requestId, fr, httpStatus, usage, shape);
    }

    /** Every numeric usage field, nested objects flattened with dots. */
    static java.util.Map<String, Integer> usage(JsonNode u) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (!u.isObject()) return out;
        u.fieldNames().forEachRemaining(f -> {
            JsonNode v = u.get(f);
            if (!f.matches("[A-Za-z0-9_.-]{1,64}")) return;
            if (v.isNumber()) out.put(f, v.asInt());
            else if (v.isObject()) v.fieldNames().forEachRemaining(g -> {
                if (g.matches("[A-Za-z0-9_.-]{1,64}") && v.get(g).isNumber()) out.put(f + "." + g, v.get(g).asInt());
            });
        });
        return out;
    }

    /**
     * Structure-only description of a response for diagnostics: keys, types, lengths, finish_reason and usage numbers.
     * Never includes string values (no model text, nothing that could echo a credential).
     */
    static String shape(JsonNode n) {
        StringBuilder sb = new StringBuilder("{top=").append(keys(n));
        JsonNode ch = n.path("choices");
        sb.append(", choices=").append(ch.isArray() ? "array[" + ch.size() + "]" : ch.getNodeType());
        if (ch.isArray() && ch.size() > 0) {
            JsonNode c0 = ch.get(0), msg = c0.path("message");
            sb.append(", choice0=").append(keys(c0)).append(", message=").append(keys(msg));
            for (java.util.Iterator<String> it = msg.fieldNames(); it.hasNext(); ) {
                String f = it.next(); JsonNode v = msg.get(f);
                sb.append(", message.").append(f).append('=').append(v.getNodeType())
                  .append(v.isTextual() ? "(len " + v.asText().length() + ")" : v.isArray() ? "[" + v.size() + "]" : "");
            }
            String fr = c0.path("finish_reason").asText(null);
            if (fr != null && fr.matches("[a-z_]{1,32}")) sb.append(", finish_reason=").append(fr);
        }
        JsonNode u = n.path("usage");
        if (u.isObject()) {
            sb.append(", usage={");
            for (java.util.Iterator<String> it = u.fieldNames(); it.hasNext(); ) {
                String f = it.next(); JsonNode v = u.get(f);
                sb.append(f).append('=').append(v.isNumber() ? v.asText() : v.isObject() ? numbers(v) : v.getNodeType()).append(it.hasNext() ? ", " : "");
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    /** Nested usage objects: numeric fields only (e.g. reasoning_tokens=64). */
    private static String numbers(JsonNode n) {
        java.util.List<String> k = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(f -> {
            if (f.matches("[A-Za-z0-9_.-]{1,64}")) k.add(f + (n.get(f).isNumber() ? "=" + n.get(f).asText() : ""));
        });
        return k.toString();
    }

    private static String keys(JsonNode n) {
        if (!n.isObject()) return String.valueOf(n.getNodeType());
        java.util.List<String> k = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(f -> { if (f.matches("[A-Za-z0-9_.-]{1,64}")) k.add(f); });
        return k.toString();
    }

    private static String textOfParts(JsonNode parts) {
        if (!parts.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        parts.forEach(p -> { if (p.has("text")) sb.append(p.path("text").asText()); });
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Error bodies: key removed, truncated to 300 chars. */
    static String redact(String body, String key) {
        if (body == null) return "";
        String s = key == null || key.isEmpty() ? body : body.replace(key, "<redacted>");
        s = s.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer <redacted>");
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
