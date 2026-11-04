package app.dita.core.retrieval.task054;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.synthesis.AnswerSynthesizer;
import app.dita.core.answer.synthesis.GroundedSynthesis;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.GraphExpandingRetriever;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.InMemoryMessageStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline replay of a stored trial conversation through the PRODUCTION answer path (IngestionPipeline with
 * RuleLinkInferencer, HybridRetriever fusion with the production weights, GraphExpandingRetriever, QueryService
 * messages-only mode, ExtractiveAnswerComposer, AnswerVerifier, GroundedSynthesis). Only the two Vespa channels are
 * emulated (EmulatedChannels) and Muse is replaced by a CAPTURING FAKE that records the exact request and abstains:
 * this measures what context Dita would send, never what Muse would answer.
 */
final class ConversationHarness {
    record Line(int n, String speaker, String text) {}
    record Run(String question, List<Hit> candidates, AnswerSynthesizer.Request request, String outcomeText, int requestBytes) {
        List<String> selected() { return request == null ? List.of() : request.evidence().stream().map(AnswerSynthesizer.Evidence::id).toList(); }
    }

    static final Pattern LINE = Pattern.compile("^(\\d+)\\. (\\w+): (.*)$");
    static List<Line> parse(Path p) throws Exception {
        List<Line> out = new ArrayList<>();
        for (String l : Files.readAllLines(p)) { Matcher m = LINE.matcher(l.strip()); if (m.matches()) out.add(new Line(Integer.parseInt(m.group(1)), m.group(2), m.group(3))); }
        return out;
    }

    final GroupScope scope;
    final String group;
    final int idOffset;
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final Map<String, String> textById = new LinkedHashMap<>();
    final LocalBgeSmall model;
    final Retriever retriever;
    final List<AnswerSynthesizer.Request> captured = new ArrayList<>();
    final QueryService queries;

    /**
     * Which stack the acceptance classes run: the DEC-050 fix (default) or, with env DITA_TASK054_STACK=before, the stack
     * as deployed in run-20261104T131550Z-fd89 (for the before/after comparison; same frozen questions).
     */
    static final boolean FIXED = !"before".equals(System.getenv("DITA_TASK054_STACK"));

    /** {@code idOffset}: stored Telegram id = line number + offset (the observed trial mapping). */
    ConversationHarness(String group, Path conversation, int idOffset, LocalBgeSmall model) throws Exception {
        this(group, conversation, idOffset, model, true);
    }

    /** {@code fixed=false}: the stack exactly as deployed in run-20261104T131550Z-fd89 (raw question to BM25, top-8 cut). */
    ConversationHarness(String group, Path conversation, int idOffset, LocalBgeSmall model, boolean fixed) throws Exception {
        this.group = group; this.scope = new GroupScope(group); this.idOffset = idOffset; this.model = model;
        var pipeline = new IngestionPipeline(store, new RuleLinkInferencer());
        Instant t0 = Instant.parse("2026-11-04T13:16:00Z");
        Map<String, String> authors = new HashMap<>();
        for (Line l : parse(conversation)) {
            String author = authors.computeIfAbsent(l.speaker(), s -> "author-" + (authors.size() + 1));
            String id = id(l.n());
            pipeline.ingest(scope, new Message(id, group, author, t0.plusSeconds(l.n() * 15L), null, l.text(), SourceKind.SYNTHETIC, false));
            textById.put(id, l.text());
        }
        // production wiring (PostgresRuntimeConfiguration.retriever, dense.messages=true; application.yml fusion values)
        Retriever rawBm25 = EmulatedChannels.bm25(store), dense = EmulatedChannels.dense(store, model, 0.60);
        Retriever bm25 = fixed ? app.dita.core.retrieval.QuestionNeeds.contentWordsOnly(rawBm25) : rawBm25;   // DEC-050 wiring
        Retriever seeds = new HybridRetriever(List.of(bm25, dense), List.of(1.0, 0.5), 60, 3, true);
        retriever = new HybridRetriever(List.of(bm25, dense, new GraphExpandingRetriever(seeds, store, 2, 0.8, 0.5)),
                List.of(1.0, 0.5, 1.0), 60, 3, true);
        AnswerSynthesizer capture = new AnswerSynthesizer() {                  // FAKE: records the request, abstains
            public String version() { return "fake-capture"; }
            public AnswerSynthesizer.Draft synthesize(AnswerSynthesizer.Request r) { captured.add(r); return new AnswerSynthesizer.Draft(true, "", List.of()); }
        };
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store);
        queries = new QueryService(store, retriever, access, 8, 200, id -> id)
                .withSynthesis(new GroundedSynthesis(store, capture, id -> id)).messagesOnly(m -> true)
                .withContextSelection(fixed ? new app.dita.core.retrieval.ConversationContextSelector() : null);
    }

    String id(int line) { return group + "m" + (line + idOffset); }

    Run ask(String question) {
        int before = captured.size();
        var answer = queries.ask(scope, "author-1", question);
        var req = captured.size() > before ? captured.get(captured.size() - 1) : null;
        var cands = queries.relevantMessages(scope, "author-1", question, queries.retrievalDepth());
        int bytes = req == null ? 0 : app.dita.muse.MuseRequestBytes.userContent(req).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return new Run(question, cands, req, answer.text(), bytes);
    }
}
