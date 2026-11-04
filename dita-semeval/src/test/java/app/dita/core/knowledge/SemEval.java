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
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * TASK-046 end-to-end evaluation core (PREREGISTRATION.md §2), EXPERIMENT ONLY. For one case and one arm: a throwaway
 * in-memory store, the real EvidenceValidator/ExtractionService on the case's proposed items, the arm's relation proposals
 * through the deterministic gate, ValidityReconciler, then Dita's real answer composition. Scores E1–E4 against gold.
 * Builders run this on DEV only; the sealed run is executed once, reporting aggregates.
 */
final class SemEval {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    /** The case being evaluated (recorded Muse arms look their outputs up by case). */
    static final ThreadLocal<String> CURRENT_CASE = new ThreadLocal<>();
    static final Map<String, Integer> STRENGTH = Map.of("DECISION", 3, "CONFIRMED_COMMITMENT", 2, "OFFER", 1);

    record Outcome(String caseId, String arm, boolean atRisk, boolean e1Fail, boolean lure, boolean e2Fail, boolean replacesTarget,
                   boolean e3Success, boolean e4Fail, boolean e4Omission, int pairs, int uncertain, int gateRejected,
                   long relationNanos, long caseNanos, List<String> notes) {
        ObjectNode json() {
            ObjectNode o = JSON.createObjectNode();
            o.put("case", caseId).put("arm", arm).put("atRisk", atRisk).put("e1Fail", e1Fail).put("lure", lure).put("e2Fail", e2Fail)
                    .put("replacesTarget", replacesTarget).put("e3Success", e3Success).put("e4Fail", e4Fail).put("e4Omission", e4Omission)
                    .put("pairs", pairs).put("uncertain", uncertain).put("gateRejected", gateRejected)
                    .put("relationMicros", relationNanos / 1000).put("caseMicros", caseNanos / 1000);
            o.putArray("notes").addAll(notes.stream().map(JSON.getNodeFactory()::textNode).toList());
            return o;
        }
    }

    /** How an arm changes the stored statuses after validation (downgrade-only, Tony §2). Identity for most arms. */
    interface StatusArm { Optional<Assertion.Status> downgrade(GroupScope g, InMemoryMessageStore store, KnowledgeItem k) throws Exception; }
    static final StatusArm NO_STATUS_ARM = (g, s, k) -> Optional.empty();

    static List<JsonNode> load(Path dir) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String n = f.getFileName().toString();
                if (n.startsWith("cases-") && n.endsWith(".json")) JSON.readTree(Files.readString(f)).forEach(out::add);
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("no cases in " + dir);
        return out;
    }

    /** Proposed extraction items for a case (the fixture for A1–A5; A6 supplies its own). */
    static List<Proposal.Item> fixtureItems(JsonNode c) {
        List<Proposal.Item> items = new ArrayList<>();
        for (JsonNode it : c.path("items")) {
            List<Proposal.Qualifier> qs = new ArrayList<>();
            it.path("qualifiers").forEach(q -> qs.add(new Proposal.Qualifier(q.path("kind").asText(), q.path("mid").asText(), q.path("quote").asText())));
            items.add(new Proposal.Item(it.path("status").asText(), it.path("certainty").asText("ASSERTED"), it.path("subjectId").asText(),
                    it.path("subject").asText(), it.path("predicate").asText(), null,
                    it.hasNonNull("attributedTo") ? it.get("attributedTo").asText() : null,
                    List.of(new Proposal.Quote(it.path("mid").asText(), it.path("quote").asText())), qs, List.of()));
        }
        return items;
    }

    static InMemoryMessageStore store(GroupScope g, JsonNode c) {
        InMemoryMessageStore s = new InMemoryMessageStore();
        for (JsonNode m : c.path("messages")) {
            String reply = m.hasNonNull("replyTo") ? m.get("replyTo").asText() : null;
            s.append(g, new Message(m.get("id").asText(), g.groupId(), m.get("author").asText(), T0.plusSeconds(60L * m.get("minute").asLong()),
                    reply, m.get("text").asText(), SourceKind.SYNTHETIC, false));
        }
        return s;
    }

    static void extract(GroupScope g, InMemoryMessageStore s, JsonNode c, List<Proposal.Item> items) {
        Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
        ExtractionService x = new ExtractionService(s, new InMemoryExtractionQueue(), (sc, b, t, ctx) -> new Proposal(items, "semeval"),
                EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
        c.path("messages").forEach(m -> x.enqueue(g, s.find(g, m.get("id").asText()).orElseThrow()));
        for (var b : x.collect(g)) { x.approve(g, b.id(), "semeval"); x.process(g, b.id()); }
    }

    static String mid(KnowledgeItem k) { return k.asserting().get(0).messageId(); }

    /** Runs one case through one arm and scores it against {@code gold} (the authorGold structure). */
    static Outcome run(JsonNode c, JsonNode gold, RelationArms.Arm arm, StatusArm statusArm, List<Proposal.Item> items, double minConfidence) throws Exception {
        long t0 = System.nanoTime();
        CURRENT_CASE.set(c.path("id").asText());
        List<String> notes = new ArrayList<>();
        GroupScope g = new GroupScope("g-semeval");
        InMemoryMessageStore s = store(g, c);
        extract(g, s, c, items);
        for (KnowledgeItem k : s.knowledge(g)) {
            Optional<Assertion.Status> d = statusArm.downgrade(g, s, k);
            if (d.isPresent() && STRENGTH.getOrDefault(d.get().name(), 0) < STRENGTH.getOrDefault(k.assertion().status().name(), 0))
                s.replaceKnowledge(g, k.withAssertion(downgraded(k.assertion(), d.get())));
        }
        long r0 = System.nanoTime();
        List<RelationArms.Proposal> ps = RelationArms.apply(g, s, arm, minConfidence);
        long relNanos = System.nanoTime() - r0;
        int unc = (int) ps.stream().filter(p -> p.replaces() == RelationArms.Tri.UNCERTAIN || p.sameProperty() == RelationArms.Tri.UNCERTAIN).count();
        int rejected = (int) ps.stream().filter(p -> p.replaces() == RelationArms.Tri.YES && RelationArms.gate(g, s, p, minConfidence).isPresent()).count();

        Map<String, KnowledgeItem> byMid = new HashMap<>();
        for (KnowledgeItem k : s.knowledge(g)) byMid.put(mid(k), k);
        java.util.function.Predicate<String> current = m -> byMid.containsKey(m) && byMid.get(m).assertion().validity() == Assertion.Validity.ACTIVE
                && !byMid.get(m).statementRedacted();

        // E1: at-risk = gold-current claims on the earlier side of a candidate pair (from the PROPOSED items' structure)
        Set<String> goldCurrent = texts(gold.path("current"));
        Set<String> earlierSides = new java.util.HashSet<>();
        Map<String, JsonNode> itemByMid = new HashMap<>();
        c.path("items").forEach(it -> itemByMid.put(it.path("mid").asText(), it));
        for (JsonNode a : c.path("items")) for (JsonNode b : c.path("items")) {
            if (a == b) continue;
            Message ma = s.find(g, a.path("mid").asText()).orElseThrow(), mb = s.find(g, b.path("mid").asText()).orElseThrow();
            if (ma.authorId().equals(mb.authorId()) && a.path("subjectId").asText().equals(b.path("subjectId").asText()) && mb.sentAt().isAfter(ma.sentAt()))
                earlierSides.add(a.path("mid").asText());
        }
        Set<String> atRisk = new java.util.TreeSet<>(goldCurrent); atRisk.retainAll(earlierSides);
        boolean e1Fail = false;
        for (String m : atRisk) if (!current.test(m)) { e1Fail = true; notes.add("E1 " + m + (byMid.containsKey(m) ? " retired" : " not stored")); }

        // E2: lure = a proposed item whose fixture status is stronger than gold; fail if stored stronger (or rendered as such)
        boolean lure = false, e2Fail = false;
        for (var en : gold.path("status").properties()) {
            JsonNode it = itemByMid.get(en.getKey());
            int goldS = STRENGTH.getOrDefault(en.getValue().asText(), 0);
            if (it == null || STRENGTH.getOrDefault(it.path("status").asText(), 0) <= goldS) continue;
            lure = true;
            KnowledgeItem k = byMid.get(en.getKey());
            if (k != null && STRENGTH.getOrDefault(k.assertion().status().name(), 0) > goldS) { e2Fail = true; notes.add("E2 " + en.getKey() + " stored " + k.assertion().status()); }
        }

        // E3: every gold replaces-yes pair: earlier non-current, later current
        boolean replacesTarget = false, e3 = true;
        for (JsonNode p : gold.path("pairs")) {
            if (!"yes".equals(p.path("replaces").asText())) continue;
            replacesTarget = true;
            String later = p.path("later").asText(), earlier = p.path("earlier").asText();
            if (current.test(earlier) || !current.test(later)) { e3 = false; notes.add("E3 miss " + later + ">" + earlier); }
        }

        // E4: the user-facing answer after validation
        Map<String, String> names = new LinkedHashMap<>();
        c.path("names").properties().forEach(n -> names.put(n.getKey(), n.getValue().asText()));
        Function<String, String> display = id -> names.getOrDefault(id, id);
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (sc, mb) -> Optional.empty(), s);
        QueryService q = new QueryService(s, Retrievers.build(s, RetrievalSettings.DEFAULTS), access, 8, 200, display, new LexicalKnowledgeRetriever(s, display));
        GroundedAnswer ans = q.ask(g, "u-asker", c.path("question").asText());
        JsonNode a = gold.path("answer");
        boolean e4 = false, omission = false;
        List<String> lines = ans.text().lines().toList();
        for (String stale : texts(a.path("mustNotStateAsCurrent")))
            for (String l : lines) if (contains(l, stale) && !marked(l)) { e4 = true; notes.add("E4a stale '" + stale + "'"); break; }
        for (String verb : texts(a.path("mustNotLabel")))
            if (contains(ans.text(), verb)) { e4 = true; notes.add("E4b label '" + verb + "'"); }
        Set<String> goldNotCurrent = texts(gold.path("notCurrent"));
        for (Citation ct : ans.citations()) {
            if (!goldNotCurrent.contains(ct.messageId())) continue;
            boolean onlyMarked = lines.stream().filter(l -> l.contains("[" + ct.messageId() + "]") || l.contains("msg " + ct.messageId())).allMatch(SemEval::marked);
            if (!onlyMarked) { e4 = true; notes.add("E4c cites " + ct.messageId()); }
        }
        for (String must : texts(a.path("mustState"))) if (!contains(ans.text(), must)) { omission = true; notes.add("E4o missing '" + must + "'"); }
        return new Outcome(c.path("id").asText(), arm.id(), !atRisk.isEmpty(), e1Fail, lure, e2Fail, replacesTarget, replacesTarget && e3, e4, omission,
                ps.size(), unc, rejected, relNanos, System.nanoTime() - t0, notes);
    }

    static Assertion downgraded(Assertion a, Assertion.Status to) {
        String pred = to == Assertion.Status.OFFER ? "offers" : "states";
        return new Assertion(to, a.certainty(), a.speakerId(), a.attributedTo(), a.subjectId(), a.subject(), pred, a.qualifiers(), a.entities(),
                a.statedAt(), a.validity(), a.proposalVersion(), a.batchId());
    }

    static boolean marked(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("earlier") || l.contains("no longer current") || l.contains("possibly updated");
    }

    static boolean contains(String text, String part) {
        return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    static Set<String> texts(JsonNode arr) {
        Set<String> out = new java.util.LinkedHashSet<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }
}
