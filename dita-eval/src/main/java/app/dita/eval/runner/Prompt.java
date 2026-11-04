package app.dita.eval.runner;

import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** A question as the runner sees it: {qid, corpus_id, as_of, question} and nothing else (N-50). */
public record Prompt(String qid, String corpusId, Instant asOf, String question) {
    public static final Set<String> FIELDS = Set.of("qid", "corpus_id", "as_of", "question");

    /** Strict: any extra field (e.g. a label that leaked into prompts/) is rejected. */
    public static List<Prompt> parseAll(String jsonl) {
        List<Prompt> out = new ArrayList<>();
        int lineNo = 0;
        for (String line : jsonl.split("\n")) {
            lineNo++;
            if (line.isBlank()) continue;
            JsonNode n = Json.parse(line);
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (!FIELDS.contains(f)) throw new IllegalArgumentException("prompts line " + lineNo + ": field not allowed in prompts/: " + f);
            }
            for (String f : FIELDS) if (!n.hasNonNull(f)) throw new IllegalArgumentException("prompts line " + lineNo + ": missing " + f);
            out.add(new Prompt(n.get("qid").asText(), n.get("corpus_id").asText(), Instant.parse(n.get("as_of").asText()),
                    n.get("question").asText()));
        }
        if (out.stream().map(Prompt::qid).distinct().count() != out.size()) throw new IllegalArgumentException("duplicate qid in prompts");
        return out;
    }
}
