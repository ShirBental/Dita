package app.dita.eval.capability;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.retrieval.Retrievers;
import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.arms.StoreReplay;
import app.dita.eval.runner.Prompt;
import app.dita.eval.runner.RunnerFileAccess;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TASK-044 Dita Memory Capability Test, runner side. For every prompt it replays the corpus (and its isolation decoy)
 * into a fresh store exactly up to {@code as_of} through Dita's production ingestion + deletion code, then asks Dita's
 * production {@link QueryService} (the same object {@code /ask} uses) and records what Dita actually answered.
 *
 * <p>No model is called. The runner reads prompts/ and corpora/ only (never sealed gold) and writes only results/.
 * The conversion to the benchmark output schema adds nothing Dita did not produce (see {@link #toOutput}).
 */
public final class DitaProductRunner {
    public static final String ARM = "D-product";
    /** Simpler-retrieval baseline: the same answer path with Dita's conversation graph switched off (BM25 only). */
    public static final String BASELINE_ARM = "BM25-only";
    public static final RetrievalSettings BM25_ONLY = new RetrievalSettings(true, 1.2, 0.75, false, 0, 0.8, 0.5, 60, 3, 1.0, 1.0,
            RetrievalSettings.DEFAULTS.defaultK(), RetrievalSettings.DEFAULTS.maxK());
    /** Production defaults (application.yml): retrieval.default-k 8, memory.answers.max-quote-chars 200, FULL_GROUP_HISTORY. */
    public static final int K = RetrievalSettings.DEFAULTS.defaultK(), MAX_QUOTE = 200;
    public static final String REQUESTER = "capability-eval";

    public record Row(String qid, String backend, String content, GroundedAnswer answer, long askNanos) {}

    private DitaProductRunner() {}

    public static List<Row> run(RunnerFileAccess files, String split) { return run(files, split, RetrievalSettings.DEFAULTS); }

    public static List<Row> run(RunnerFileAccess files, String split, RetrievalSettings retrieval) {
        List<Row> rows = new ArrayList<>();
        for (Prompt p : files.prompts(split)) {
            StoreReplay.Replayed r = StoreReplay.replay(files.corpus(p.corpusId()), files.decoyFor(p.corpusId()).orElse(null), p.asOf());
            var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (scope, member) -> Optional.empty(), r.store());
            QueryService q = new QueryService(r.store(), Retrievers.build(r.store(), retrieval), access, K, MAX_QUOTE);
            long t0 = System.nanoTime();
            GroundedAnswer a = q.ask(r.scope(), REQUESTER, p.question());
            long dt = System.nanoTime() - t0;
            if (!a.citations().isEmpty() && r.decoyScope() != null) {
                for (Citation c : a.citations()) {
                    if (r.store().find(r.decoyScope(), c.messageId()).isPresent() && r.store().find(r.scope(), c.messageId()).isEmpty()) {
                        throw new IllegalStateException("isolation: " + p.qid() + " cited a decoy-group message");
                    }
                }
            }
            rows.add(new Row(p.qid(), StoreReplay.BACKEND, Json.compact(toOutput(a)), a, dt));
        }
        return rows;
    }

    /**
     * Dita's answer in the DESIGN §1 output schema, without embellishment:
     * <ul>
     *   <li>behavior: not found → UNKNOWN; conflicting → CONFLICT; else ANSWER. Dita's /ask has no CLARIFY path.</li>
     *   <li>one claim per citation: speaker = the cited message's author, the verbatim quote, stance from Dita's polarity.</li>
     *   <li>qualifier = the quote only when Dita flagged the citation as qualified; null otherwise.</li>
     *   <li>is_current = true: Dita presents every cited message as current (it has no supersession marker today).</li>
     *   <li>commitment = "none": Dita does not classify offers vs confirmations today.</li>
     * </ul>
     */
    public static ObjectNode toOutput(GroundedAnswer a) {
        ObjectNode o = Json.object();
        o.put("behavior", !a.found() ? "UNKNOWN" : a.conflicting() ? "CONFLICT" : "ANSWER");
        o.put("answer", a.text());
        o.putObject("slots");
        ArrayNode claims = o.putArray("claims");
        for (Citation c : a.citations()) {
            ObjectNode cl = claims.addObject();
            cl.put("speaker", c.authorId());
            cl.putNull("attributed_to");
            cl.set("message_ids", Json.strings(List.of(c.messageId())));
            cl.set("quotes", Json.strings(List.of(c.quote())));
            cl.put("stance", c.polarity() > 0 ? "positive" : c.polarity() < 0 ? "negative" : "neutral");
            if (c.qualified()) cl.put("qualifier", c.quote()); else cl.putNull("qualifier");
            cl.put("is_current", true);
            cl.put("commitment", "none");
        }
        o.putNull("clarify_question");
        return o;
    }

    public static String toJsonl(List<Row> rows, String split) { return toJsonl(rows, split, ARM); }

    public static String toJsonl(List<Row> rows, String split, String arm) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            ObjectNode o = Json.object();
            o.put("qid", r.qid()).put("arm", arm).put("split", split).put("backend", r.backend());
            o.put("content", r.content()).put("finish_reason", "stop");
            o.put("knowledge_items_used", r.answer().knowledgeIds().size());
            o.put("ask_ms", r.askNanos() / 1e6);
            sb.append(Json.compact(o)).append('\n');
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String split = args.length > 0 ? args[0] : "dev";
        RunnerFileAccess files = new RunnerFileAccess(BenchPaths.locate());
        List<Row> rows = run(files, split);
        files.writeResult("capability-" + split + "-answers.jsonl", toJsonl(rows, split));
        System.out.println("capability runner: " + rows.size() + " answers written (no gold read)");
    }
}
