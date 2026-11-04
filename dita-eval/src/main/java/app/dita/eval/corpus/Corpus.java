package app.dita.eval.corpus;

import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A parsed synthetic corpus, messages in chronological order (sent_at, then id). Parsing is strict. */
public record Corpus(String id, List<CorpusMessage> messages) {
    public static final Set<String> FIELDS = Set.of("id", "author", "sent_at", "reply_to", "forwarded", "edited", "text", "deleted_at");
    public static final String ID_PATTERN = "[a-z0-9-]+";

    public Corpus {
        if (!id.matches(ID_PATTERN)) throw new IllegalArgumentException("bad corpus id");
        messages = messages.stream().sorted(Comparator.comparing(CorpusMessage::sentAt).thenComparing(CorpusMessage::id)).toList();
        Map<String, CorpusMessage> seen = new LinkedHashMap<>();
        for (CorpusMessage m : messages) {
            if (seen.put(m.id(), m) != null) throw new IllegalArgumentException("duplicate message id " + m.id() + " in " + id);
            if (m.replyTo() != null) {
                CorpusMessage target = seen.get(m.replyTo());
                if (target == null) throw new IllegalArgumentException("reply_to must point to an earlier message: " + m.id());
            }
        }
    }

    public static Corpus parse(String id, String jsonl) {
        List<CorpusMessage> out = new ArrayList<>();
        int lineNo = 0;
        for (String line : jsonl.split("\n")) {
            lineNo++;
            if (line.isBlank()) continue;
            JsonNode n = Json.parse(line);
            if (!n.isObject()) throw new IllegalArgumentException(id + ":" + lineNo + " not an object");
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (!FIELDS.contains(f)) throw new IllegalArgumentException(id + ":" + lineNo + " unknown field " + f);
            }
            for (String req : List.of("id", "author", "sent_at", "text")) {
                if (!n.hasNonNull(req)) throw new IllegalArgumentException(id + ":" + lineNo + " missing " + req);
            }
            out.add(new CorpusMessage(n.get("id").asText(), n.get("author").asText(), Instant.parse(n.get("sent_at").asText()),
                    Json.textOrNull(n, "reply_to"), n.path("forwarded").asBoolean(false), n.path("edited").asBoolean(false),
                    n.get("text").asText(),
                    n.hasNonNull("deleted_at") ? Instant.parse(n.get("deleted_at").asText()) : null));
        }
        return new Corpus(id, out);
    }

    public Optional<CorpusMessage> find(String messageId) {
        return messages.stream().filter(m -> m.id().equals(messageId)).findFirst();
    }

    public List<CorpusMessage> visible(Instant asOf) { return messages.stream().filter(m -> m.visibleAt(asOf)).toList(); }
}
