package app.dita.muse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TASK-051: serves RECORDED Muse chat-completion replies and never opens a network connection. For synthetic replay runs
 * that must exercise the real Muse client (request building, pseudonymisation, budget, response parsing) without a live
 * call. A recorded reply is NOT a live Muse integration; every report must say so.
 * <p>Recording file (JSON): {@code {"recordings":[{"requires":["text the request must contain", ...], "content": <object or string>}]}}.
 * Each request is answered by the first UNUSED recording whose {@code requires} strings all occur in the request body.
 * No match is a hard failure (the batch fails; nothing is invented and nothing is sent anywhere).
 */
public final class RecordedMuseTransport implements MuseTransport {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Recording(List<String> requires, String content) {}

    private final List<Recording> recordings;
    private final boolean[] used;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    RecordedMuseTransport(List<Recording> recordings) {
        this.recordings = List.copyOf(recordings);
        this.used = new boolean[recordings.size()];
    }

    public static RecordedMuseTransport fromFile(Path file) {
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            List<Recording> out = new ArrayList<>();
            for (JsonNode r : root.path("recordings")) {
                List<String> req = new ArrayList<>();
                r.path("requires").forEach(n -> req.add(n.asText()));
                JsonNode c = r.path("content");
                if (c.isMissingNode() || c.isNull()) throw new IllegalArgumentException("recording without content");
                out.add(new Recording(req, c.isTextual() ? c.asText() : JSON.writeValueAsString(c)));
            }
            if (out.isEmpty()) throw new IllegalArgumentException("no recordings");
            return new RecordedMuseTransport(out);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("unreadable or invalid Muse recording file (path and content not shown)");   // L-1
        }
    }

    /** Request bodies received (test and operator evidence; synthetic data only). */
    public List<String> requests() { return List.copyOf(requests); }

    @Override public synchronized Reply post(URI url, Map<String, String> headers, String body, Duration timeout) {
        requests.add(body);
        for (int i = 0; i < recordings.size(); i++) {
            if (used[i]) continue;
            Recording r = recordings.get(i);
            if (r.requires().stream().allMatch(body::contains)) {
                used[i] = true;
                ObjectNode env = JSON.createObjectNode().put("id", "recorded-" + (i + 1)).put("model", "recorded");
                ObjectNode choice = env.putArray("choices").addObject();
                choice.putObject("message").put("role", "assistant").put("content", r.content());
                choice.put("finish_reason", "stop");
                env.putObject("usage").put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0);
                return new Reply(200, env.toString(), Map.of("x-request-id", "recorded-" + (i + 1)));
            }
        }
        throw new MuseException(MuseException.Kind.CLIENT, 0, "no unused recorded reply matches this request (recorded transport; no network call)");
    }
}
