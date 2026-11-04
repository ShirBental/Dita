package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.events.EventPublisher;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Trustworthy-current-state ACCEPTANCE suite (TASK-045, gate rebuilt in TASK-046). Synthetic, deterministic cases (no
 * model) in src/test/resources/trust/*.json.
 *
 * <p><b>Acceptance (TASK-046):</b> EVERY expectation a case declares is enforced; any violation fails the build. There
 * is no switch that turns the acceptance gate off. Diagnostics (recall, citation precision, counts) are written to
 * target/trust-report.md and never decide pass/fail.
 * <ul>
 *   <li>Schema: unknown case or expectation keys, and answer expectations on a case without a question, are violations
 *       (a typo can never make an expectation vacuous). An empty case set is a failure.</li>
 *   <li>Expected failures must be declared with {@code "expectedFailure": {"reason": …, "kinds": [...]}}; only the
 *       declared NON-safety kinds are tolerated, and a case that unexpectedly passes (XPASS) fails the build so the
 *       marker is removed. Safety kinds (false/missing supersession, valid hidden, stale shown, false no-answer,
 *       nondeterminism, schema) can never be expected.</li>
 *   <li>Measurement mode ({@code -Ddita.trust.mode=measure}) is for run-once unseen sets only and is refused on the
 *       acceptance directory.</li>
 * </ul>
 * Determinism (Tony R1): every case is also extracted one message per batch, forward and reversed; relations and validity
 * must equal the single-batch run. {@link TrustSuiteGateTest} proves the gate rejects broken behaviour.
 */
class TrustworthyStateSuiteTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    static final String ACCEPTANCE_DIR = "src/test/resources/trust";

    enum Kind {
        SCHEMA, NONDETERMINISTIC, MISSING_SUPERSESSION, FALSE_SUPERSESSION, FORBIDDEN_SUPERSESSION, NOT_STORED, VALID_HIDDEN,
        EXPECTED_SUPERSEDED_ACTIVE, STALE_CITED, STALE_UNMARKED, EVIDENCE_NOT_CITED, TEXT_MISSING, TEXT_FORBIDDEN, LEAD_MISSING,
        FALSE_NO_ANSWER, XPASS;
        boolean safety() { return !EnumSet.of(EVIDENCE_NOT_CITED, TEXT_MISSING, LEAD_MISSING).contains(this); }
    }

    record Violation(Kind kind, String detail) {
        @Override public String toString() { return kind + " " + detail; }
    }

    /** Diagnostics only (never pass/fail). */
    static final class Metrics {
        int supExp, supFormed, mustCite, mustCiteHit, cited, citedOk;
    }

    record Outcome(String id, String cls, List<Violation> violations, List<Violation> tolerated) {
        boolean accepted() { return violations.isEmpty(); }
    }

    static final Set<String> CASE_KEYS = Set.of("id", "class", "messages", "items", "question", "expect", "names", "expectedFailure");
    static final Set<String> EXPECT_KEYS = Set.of("supersedes", "forbidSupersedes", "active", "superseded", "mustCite", "allowCite",
            "mustNotCite", "staleMarkedIfShown", "textMust", "textMustNot", "leadContains", "noAnswerAllowed");
    static final Set<String> ANSWER_KEYS = Set.of("mustCite", "allowCite", "mustNotCite", "staleMarkedIfShown", "textMust", "textMustNot",
            "leadContains", "noAnswerAllowed");

    /** Test seam for {@link TrustSuiteGateTest}: lets a gate test simulate a broken composer. Identity in the real suite. */
    record Hooks(UnaryOperator<GroundedAnswer> answer) {
        static final Hooks NONE = new Hooks(UnaryOperator.identity());
    }

    @Test void runSuite() throws Exception {
        String dirName = System.getProperty("dita.trust.dir", ACCEPTANCE_DIR);
        boolean measure = "measure".equals(System.getProperty("dita.trust.mode"));
        if (measure && Path.of(dirName).toRealPath().equals(Path.of(ACCEPTANCE_DIR).toRealPath()))     // B46-10: no path aliasing
            fail("measurement mode is refused on the acceptance directory " + ACCEPTANCE_DIR);
        List<JsonNode> cases = load(Path.of(dirName));
        Metrics m = new Metrics();
        List<Outcome> out = new ArrayList<>();
        for (JsonNode c : cases) out.add(evaluate(c, m, Hooks.NONE));
        writeReport(out, m, Path.of(System.getProperty("dita.trust.report", "target/trust-report.md")));
        if (!measure) gate(out);
    }

    static List<JsonNode> load(Path dir) throws Exception {
        List<JsonNode> cases = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                if (!f.toString().endsWith(".json")) continue;
                JsonNode arr = JSON.readTree(Files.readString(f));
                if (!arr.isArray() || arr.isEmpty()) throw new IllegalStateException("case file is not a non-empty array: " + f);
                arr.forEach(cases::add);
            }
        }
        if (cases.isEmpty()) throw new IllegalStateException("no trust cases in " + dir);
        return cases;
    }

    /** Fails with every non-accepted case and its violations. */
    static void gate(List<Outcome> out) {
        List<Outcome> bad = out.stream().filter(o -> !o.accepted()).toList();
        if (bad.isEmpty()) return;
        StringBuilder sb = new StringBuilder(bad.size() + " of " + out.size() + " trust cases violate their declared expectations:\n");
        for (Outcome o : bad) sb.append("  ").append(o.id()).append(": ").append(o.violations()).append('\n');
        fail(sb.toString());
    }

    static Outcome evaluate(JsonNode c, Metrics m, Hooks hooks) {
        List<Violation> v = new ArrayList<>();
        String id = c.path("id").asText("?");
        schema(c, v);
        if (v.stream().anyMatch(x -> x.kind() == Kind.SCHEMA && x.detail().startsWith("fatal"))) return new Outcome(id, c.path("class").asText(""), v, List.of());

        GroupScope g = new GroupScope("g-trust");
        InMemoryMessageStore store = messages(g, c);
        List<Proposal.Item> items = items(c);
        extract(g, store, c, items, false, false);
        String single = state(g, store);
        for (boolean reversed : new boolean[]{false, true}) {
            InMemoryMessageStore other = messages(g, c);
            extract(g, other, c, items, true, reversed);
            if (!state(g, other).equals(single)) v.add(new Violation(Kind.NONDETERMINISTIC, "per-message batches" + (reversed ? " (reversed)" : "")));
        }
        Map<String, KnowledgeItem> byMid = new HashMap<>();
        for (KnowledgeItem k : store.knowledge(g)) byMid.put(k.asserting().get(0).messageId(), k);
        List<String> sup = new ArrayList<>();
        for (KnowledgeRelation r : store.relations(g)) {
            if (r.kind() == KnowledgeRelation.Kind.SUPERSEDES) sup.add(midOf(byMid, r.fromId()) + ">" + midOf(byMid, r.toId()));
        }
        JsonNode e = c.path("expect");
        List<String> expected = pairs(e.path("supersedes"));
        for (String p : expected) {
            m.supExp++;
            if (sup.contains(p)) m.supFormed++; else v.add(new Violation(Kind.MISSING_SUPERSESSION, p));
        }
        for (String p : pairs(e.path("forbidSupersedes"))) if (sup.contains(p)) v.add(new Violation(Kind.FORBIDDEN_SUPERSESSION, p));
        for (String s : sup) if (!expected.contains(s)) v.add(new Violation(Kind.FALSE_SUPERSESSION, s));
        for (JsonNode a : e.path("active")) {
            KnowledgeItem k = byMid.get(a.asText());
            if (k == null) v.add(new Violation(Kind.NOT_STORED, a.asText()));
            else if (k.assertion().validity() != Assertion.Validity.ACTIVE) v.add(new Violation(Kind.VALID_HIDDEN, a.asText()));
        }
        for (JsonNode a : e.path("superseded")) {
            KnowledgeItem k = byMid.get(a.asText());
            if (k == null) v.add(new Violation(Kind.NOT_STORED, a.asText()));
            else if (k.assertion().validity() != Assertion.Validity.SUPERSEDED) v.add(new Violation(Kind.EXPECTED_SUPERSEDED_ACTIVE, a.asText()));
        }
        if (c.hasNonNull("question")) answer(g, store, c, e, m, hooks, v);
        return applyExpectedFailure(c, id, v);
    }

    private static void answer(GroupScope g, InMemoryMessageStore store, JsonNode c, JsonNode e, Metrics m, Hooks hooks, List<Violation> v) {
        Map<String, String> names = new LinkedHashMap<>();
        c.path("names").fields().forEachRemaining(n -> names.put(n.getKey(), n.getValue().asText()));
        java.util.function.Function<String, String> display = id -> names.getOrDefault(id, id);
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
        QueryService q = new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, display,
                new LexicalKnowledgeRetriever(store, display));
        GroundedAnswer ans = hooks.answer().apply(q.ask(g, "u-asker", c.get("question").asText()));
        List<String> ids = ans.citations().stream().map(Citation::messageId).toList();
        String text = ans.text();
        List<String> allowed = new ArrayList<>();
        e.path("mustCite").forEach(x -> allowed.add(x.asText()));
        e.path("allowCite").forEach(x -> allowed.add(x.asText()));
        for (JsonNode x : e.path("mustCite")) {
            m.mustCite++;
            if (ids.contains(x.asText())) m.mustCiteHit++; else v.add(new Violation(Kind.EVIDENCE_NOT_CITED, x.asText()));
        }
        for (JsonNode x : e.path("mustNotCite")) if (ids.contains(x.asText())) v.add(new Violation(Kind.STALE_CITED, x.asText()));
        for (JsonNode x : e.path("staleMarkedIfShown")) {
            String mid = x.asText();
            boolean marked = text.lines().filter(l -> l.contains("[" + mid + "]")).allMatch(l -> l.toLowerCase().contains("earlier"));
            if (ids.contains(mid) && !marked) v.add(new Violation(Kind.STALE_UNMARKED, mid));
        }
        if (!allowed.isEmpty()) { m.cited += ids.size(); m.citedOk += (int) ids.stream().filter(allowed::contains).count(); }
        for (JsonNode t : e.path("textMust")) if (!text.contains(t.asText())) v.add(new Violation(Kind.TEXT_MISSING, "'" + t.asText() + "'"));
        for (JsonNode t : e.path("textMustNot")) if (text.contains(t.asText())) v.add(new Violation(Kind.TEXT_FORBIDDEN, "'" + t.asText() + "'"));
        if (e.hasNonNull("leadContains")) {
            String lead = text.lines().filter(l -> l.startsWith("- ")).findFirst().orElse("");
            if (!lead.contains(e.get("leadContains").asText())) v.add(new Violation(Kind.LEAD_MISSING, "'" + e.get("leadContains").asText() + "'"));
        }
        String low = text.toLowerCase();
        if ((low.contains("no answer") || low.contains("no reply")) && !e.path("noAnswerAllowed").asBoolean(false))
            v.add(new Violation(Kind.FALSE_NO_ANSWER, ""));
    }

    private static void schema(JsonNode c, List<Violation> v) {
        for (String k : List.of("id", "class", "messages", "items", "expect"))
            if (!c.hasNonNull(k)) v.add(new Violation(Kind.SCHEMA, "fatal: missing '" + k + "'"));
        c.fieldNames().forEachRemaining(k -> { if (!CASE_KEYS.contains(k)) v.add(new Violation(Kind.SCHEMA, "unknown case key '" + k + "'")); });
        JsonNode e = c.path("expect");
        // B46-10: every expectation must have its declared TYPE (a plain string where a list is expected checked nothing)
        e.fields().forEachRemaining(f -> {
            String k = f.getKey(); JsonNode val = f.getValue();
            boolean ok = switch (k) {
                case "supersedes", "forbidSupersedes" -> val.isArray() && java.util.stream.StreamSupport.stream(val.spliterator(), false)
                        .allMatch(p -> p.isArray() && p.size() == 2 && p.get(0).isTextual() && p.get(1).isTextual());
                case "leadContains" -> val.isTextual() && !val.asText().isBlank();
                case "noAnswerAllowed" -> val.isBoolean();
                default -> val.isArray() && java.util.stream.StreamSupport.stream(val.spliterator(), false).allMatch(x -> x.isTextual() && !x.asText().isBlank());
            };
            if (EXPECT_KEYS.contains(k) && !ok) v.add(new Violation(Kind.SCHEMA, "fatal: expectation '" + k + "' has the wrong type"));
        });
        // B46-10: results are keyed by evidence message, so one item per message (a second one would never be checked)
        java.util.Set<String> mids = new java.util.HashSet<>();
        for (JsonNode it : c.path("items")) if (!mids.add(it.path("mid").asText())) v.add(new Violation(Kind.SCHEMA, "fatal: two items cite message " + it.path("mid").asText()));
        e.fieldNames().forEachRemaining(k -> {
            if (!EXPECT_KEYS.contains(k)) v.add(new Violation(Kind.SCHEMA, "unknown expectation '" + k + "'"));
            else if (ANSWER_KEYS.contains(k) && !c.hasNonNull("question")) v.add(new Violation(Kind.SCHEMA, "'" + k + "' needs a question"));
        });
        if (c.path("class").asText("").contains("expected to fail") && !c.hasNonNull("expectedFailure"))
            v.add(new Violation(Kind.SCHEMA, "class says 'expected to fail' but no expectedFailure marker"));
        JsonNode xf = c.path("expectedFailure");
        if (!xf.isMissingNode() && !xf.isNull()) {
            if (xf.path("reason").asText("").isBlank() || !xf.path("kinds").isArray() || xf.path("kinds").isEmpty())
                v.add(new Violation(Kind.SCHEMA, "expectedFailure needs a reason and a non-empty kinds list"));
            for (JsonNode k : xf.path("kinds")) {
                Kind kind;
                try { kind = Kind.valueOf(k.asText()); } catch (IllegalArgumentException ex) { v.add(new Violation(Kind.SCHEMA, "unknown kind " + k.asText())); continue; }
                if (kind.safety()) v.add(new Violation(Kind.SCHEMA, "safety kind " + kind + " can never be an expected failure"));
            }
        }
    }

    /** Declared non-safety kinds are tolerated; anything else stays a violation; a clean run under the marker is XPASS. */
    private static Outcome applyExpectedFailure(JsonNode c, String id, List<Violation> v) {
        JsonNode xf = c.path("expectedFailure");
        String cls = c.path("class").asText("");
        if (xf.isMissingNode() || xf.isNull() || v.stream().anyMatch(x -> x.kind() == Kind.SCHEMA)) return new Outcome(id, cls, v, List.of());
        Set<Kind> declared = EnumSet.noneOf(Kind.class);
        xf.path("kinds").forEach(k -> declared.add(Kind.valueOf(k.asText())));
        List<Violation> tolerated = v.stream().filter(x -> declared.contains(x.kind()) && !x.kind().safety()).toList();
        List<Violation> rest = new ArrayList<>(v);
        rest.removeAll(tolerated);
        if (v.isEmpty()) rest.add(new Violation(Kind.XPASS, "passes; remove its expectedFailure marker"));
        return new Outcome(id, cls, rest, tolerated);
    }

    private static List<String> pairs(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : arr) out.add(p.get(0).asText() + ">" + p.get(1).asText());
        return out;
    }

    static List<Proposal.Item> items(JsonNode c) {
        List<Proposal.Item> items = new ArrayList<>();
        for (JsonNode it : c.path("items")) {
            List<Proposal.Qualifier> qs = new ArrayList<>();
            it.path("qualifiers").forEach(q -> qs.add(new Proposal.Qualifier(q.get("kind").asText(), q.get("mid").asText(), q.get("quote").asText())));
            items.add(new Proposal.Item(it.get("status").asText(), it.get("certainty").asText(), it.get("subjectId").asText(),
                    it.get("subject").asText(), it.get("predicate").asText(), null,
                    it.hasNonNull("attributedTo") ? it.get("attributedTo").asText() : null,
                    List.of(new Proposal.Quote(it.get("mid").asText(), it.get("quote").asText())), qs, List.of()));
        }
        return items;
    }

    static void writeReport(List<Outcome> out, Metrics m, Path path) throws Exception {
        long ok = out.stream().filter(Outcome::accepted).count();
        Map<Kind, Integer> counts = new java.util.EnumMap<>(Kind.class);
        out.forEach(o -> o.violations().forEach(x -> counts.merge(x.kind(), 1, Integer::sum)));
        StringBuilder r = new StringBuilder("# Trustworthy-current-state suite (TASK-046 gate)\n\n");
        r.append(String.format("ACCEPTANCE: cases %d · accepted %d · rejected %d · tolerated expected failures %d · violations by kind %s%n%n",
                out.size(), ok, out.size() - ok, out.stream().filter(o -> !o.tolerated().isEmpty()).count(), counts));
        r.append(String.format("DIAGNOSTICS (never pass/fail): expected supersessions formed %d/%d · evidence recall %d/%d · citation precision %d/%d%n%n",
                m.supFormed, m.supExp, m.mustCiteHit, m.mustCite, m.citedOk, m.cited));
        r.append("| case | class | accepted | violations | tolerated (expected failure) |\n|---|---|---|---|---|\n");
        for (Outcome o : out) r.append("| ").append(o.id()).append(" | ").append(o.cls()).append(" | ").append(o.accepted() ? "yes" : "NO").append(" | ")
                .append(String.join("; ", o.violations().stream().map(Violation::toString).toList())).append(" | ")
                .append(String.join("; ", o.tolerated().stream().map(Violation::toString).toList())).append(" |\n");
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, r.toString(), StandardCharsets.UTF_8);
        System.out.println(r.toString().lines().limit(5).reduce("", (a, b) -> a + b + "\n"));
    }

    static InMemoryMessageStore messages(GroupScope g, JsonNode c) {
        InMemoryMessageStore store = new InMemoryMessageStore();
        for (JsonNode m : c.path("messages")) {
            String reply = m.hasNonNull("replyTo") ? m.get("replyTo").asText() : null;
            store.append(g, new Message(m.get("id").asText(), g.groupId(), m.get("author").asText(),
                    T0.plusSeconds(60L * m.get("minute").asLong()), reply, m.get("text").asText(), SourceKind.SYNTHETIC, false));
        }
        return store;
    }

    /** Extracts the case's items: one batch, or one message per batch (forward or reversed); the proposer only proposes
     *  the items whose evidence is in the batch (as a real model would). */
    static void extract(GroupScope g, InMemoryMessageStore store, JsonNode c, List<Proposal.Item> items, boolean perMessage, boolean reversed) {
        Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, ctx) -> {
            java.util.Set<String> ids = new java.util.HashSet<>();
            t.forEach(m -> ids.add(m.id()));
            return new Proposal(items.stream().filter(i -> ids.contains(i.evidence().get(0).messageId())).toList(), "trust");
        }, EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
        List<String> mids = new ArrayList<>();
        c.path("messages").forEach(m -> mids.add(m.get("id").asText()));
        if (reversed) java.util.Collections.reverse(mids);
        List<List<String>> batches = perMessage ? mids.stream().map(List::of).toList() : List.of(mids);
        for (List<String> bm : batches) {
            for (String id : bm) x.enqueue(g, store.find(g, id).orElseThrow());
            for (var batch : x.collect(g)) {
                x.approve(g, batch.id(), "trust-suite");
                var rep = x.process(g, batch.id()).orElseThrow();
                assertFalse(rep.status() == ExtractionBatch.Status.FAILED, "extraction failed in a trust case: " + rep.error());
            }
        }
    }

    /** Canonical relation + validity state, keyed by evidence message id. */
    static String state(GroupScope g, InMemoryMessageStore store) {
        Map<String, KnowledgeItem> byMid = new HashMap<>();
        for (KnowledgeItem k : store.knowledge(g)) byMid.put(k.asserting().get(0).messageId(), k);
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (KnowledgeRelation r : store.relations(g)) out.add(r.kind() + ":" + midOf(byMid, r.fromId()) + ">" + midOf(byMid, r.toId()));
        byMid.forEach((mid, k) -> out.add(mid + "=" + k.assertion().validity()));
        return String.join(",", out);
    }

    static String midOf(Map<String, KnowledgeItem> byMid, String knowledgeId) {
        return byMid.entrySet().stream().filter(x -> x.getValue().id().equals(knowledgeId)).map(Map.Entry::getKey).findFirst().orElse(knowledgeId);
    }
}
