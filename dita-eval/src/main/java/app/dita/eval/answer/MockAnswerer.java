package app.dita.eval.answer;

import app.dita.core.text.Tokenizer;
import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Deterministic offline stand-in used ONLY to exercise the plumbing (request rendering, guard, parsing, scoring).
 * It cites the context message with the most question-token overlap (latest wins ties), or says UNKNOWN. Its scores
 * are not results and must never be reported as such.
 */
public final class MockAnswerer implements ModelAnswerer {
    @Override public String name() { return "mock-deterministic"; }
    @Override public boolean isMock() { return true; }

    @Override
    public Reply answer(ModelRequest request) {
        Set<String> q = Tokenizer.tokenSet(request.question());
        JsonNode best = null;
        long bestScore = 0;
        for (JsonNode m : Json.parse(request.contextJson())) {
            long s = Tokenizer.tokenSet(m.get("text").asText()).stream().filter(q::contains).count();
            if (s >= bestScore && s > 0) { bestScore = s; best = m; }
        }
        ObjectNode out = Json.object();
        if (best == null) {
            out.put("behavior", "UNKNOWN").put("answer", "").set("slots", Json.object());
            out.set("claims", Json.array());
        } else {
            String text = best.get("text").asText();
            out.put("behavior", "ANSWER").put("answer", text).set("slots", Json.object());
            ObjectNode c = out.putArray("claims").addObject();
            c.put("speaker", best.get("author").asText()).putNull("attributed_to");
            c.putArray("message_ids").add(best.get("id").asText());
            c.putArray("quotes").add(text.substring(0, Math.min(40, text.length())));
            c.put("stance", "neutral").putNull("qualifier");
            c.put("is_current", true).put("commitment", "none");
        }
        out.putNull("clarify_question");
        return new Reply(Json.compact(out), "stop", null, null);
    }
}
