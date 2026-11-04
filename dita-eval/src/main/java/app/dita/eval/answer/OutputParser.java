package app.dita.eval.answer;

import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, arm-independent parser for the DESIGN §1 output schema. A reply with {@code finish_reason=length}, invalid
 * JSON or a schema violation is a format failure, which the scorer counts as wrong, never as dropped. A single
 * surrounding Markdown code fence is tolerated, identically for every arm.
 */
public final class OutputParser {
    public static final List<String> BEHAVIORS = List.of("ANSWER", "UNKNOWN", "CONFLICT", "CLARIFY");
    public static final List<String> COMMITMENTS = List.of("offer", "confirmed", "none");
    private static final Set<String> TOP = Set.of("behavior", "answer", "slots", "claims", "clarify_question");
    private static final Set<String> CLAIM = Set.of("speaker", "attributed_to", "message_ids", "quotes", "stance", "qualifier", "is_current", "commitment");

    public record Claim(String speaker, String attributedTo, List<String> messageIds, List<String> quotes, String stance,
                        String qualifier, Boolean isCurrent, String commitment) {}

    public record Output(String behavior, String answer, Map<String, String> slots, List<Claim> claims, String clarifyQuestion) {}

    /** {@code output} is null iff {@code ok} is false; {@code error} names the failure class without echoing content. */
    public record Parsed(boolean ok, String error, Output output) {
        static Parsed fail(String e) { return new Parsed(false, e, null); }
    }

    private OutputParser() {}

    public static Parsed parse(String content, String finishReason) {
        if ("length".equals(finishReason)) return Parsed.fail("finish_reason=length");
        if (content == null || content.isBlank()) return Parsed.fail("empty content");
        String s = content.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl < 0 || !s.endsWith("```")) return Parsed.fail("unterminated code fence");
            s = s.substring(nl + 1, s.length() - 3).strip();
        }
        JsonNode n;
        try { n = Json.parse(s); } catch (IllegalArgumentException e) { return Parsed.fail("invalid JSON"); }
        if (!n.isObject()) return Parsed.fail("not a JSON object");
        for (var it = n.fieldNames(); it.hasNext(); ) { String f = it.next(); if (!TOP.contains(f)) return Parsed.fail("unknown field " + f); }
        for (String f : TOP) if (!n.has(f)) return Parsed.fail("missing " + f);
        String behavior = n.get("behavior").asText("");
        if (!BEHAVIORS.contains(behavior)) return Parsed.fail("bad behavior");
        if (!n.get("answer").isTextual()) return Parsed.fail("answer must be a string");
        if (!n.get("slots").isObject()) return Parsed.fail("slots must be an object");
        Map<String, String> slots = new LinkedHashMap<>();
        for (var it = n.get("slots").fields(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().isTextual()) return Parsed.fail("slot values must be strings");
            slots.put(e.getKey(), e.getValue().asText());
        }
        if (!n.get("claims").isArray()) return Parsed.fail("claims must be an array");
        List<Claim> claims = new ArrayList<>();
        for (JsonNode c : n.get("claims")) {
            if (!c.isObject()) return Parsed.fail("claim must be an object");
            for (var it = c.fieldNames(); it.hasNext(); ) { String f = it.next(); if (!CLAIM.contains(f)) return Parsed.fail("unknown claim field " + f); }
            for (String f : CLAIM) if (!c.has(f)) return Parsed.fail("claim missing " + f);
            if (!c.get("speaker").isTextual() || !c.get("stance").isTextual()) return Parsed.fail("claim speaker/stance must be strings");
            if (!(c.get("attributed_to").isNull() || c.get("attributed_to").isTextual())) return Parsed.fail("attributed_to type");
            if (!(c.get("qualifier").isNull() || c.get("qualifier").isTextual())) return Parsed.fail("qualifier type");
            if (!c.get("is_current").isBoolean()) return Parsed.fail("is_current must be boolean");
            String commitment = c.get("commitment").asText("");
            if (!COMMITMENTS.contains(commitment)) return Parsed.fail("bad commitment");
            List<String> ids, quotes;
            try { ids = Json.stringList(c.get("message_ids")); quotes = Json.stringList(c.get("quotes")); }
            catch (IllegalArgumentException e) { return Parsed.fail("message_ids/quotes must be string arrays"); }
            claims.add(new Claim(c.get("speaker").asText(), Json.textOrNull(c, "attributed_to"), ids, quotes,
                    c.get("stance").asText(), Json.textOrNull(c, "qualifier"), c.get("is_current").asBoolean(), commitment));
        }
        JsonNode cq = n.get("clarify_question");
        if (!(cq.isNull() || cq.isTextual())) return Parsed.fail("clarify_question type");
        return new Parsed(true, null, new Output(behavior, n.get("answer").asText(), slots, claims, cq.isNull() ? null : cq.asText()));
    }
}
