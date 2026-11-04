package app.dita.core.retrieval.task054;

import app.dita.core.text.Tokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 retrieval ACCEPTANCE on the natural 200-message Alice-Bob conversation (and a second, differently-populated
 * planning conversation). FROZEN 2026-11-04 BEFORE any ranking change: questions and required evidence come from the
 * conversation text, not from the implementation. A pass means the OUTBOUND synthesis context (exactly what Dita would
 * send to Muse) contains enough ORIGINAL messages for every part of the question; it says nothing about Muse's answer.
 * <p>
 * Stack: production answer path + production fusion, with the two Vespa channels EMULATED offline (BM25 "any" without
 * stopwords; frozen bge-small run locally with Vespa's embedder settings). Muse = capturing FAKE (abstains). Gated on the
 * local model files (build/task054-semantic/package/models, hash-checked): SKIPPED = NOT EXECUTED, never a pass.
 */
class NaturalConversationRetrievalAcceptanceTest {
    static final Path RES = Path.of("src/test/resources/task054");
    static { if (System.getenv("DITA_CTX_DEBUG") != null) System.setProperty("dita.ctx.debug", "true"); }   // diagnostics only
    static LocalBgeSmall model;
    static ConversationHarness alice, harbor;

    @BeforeAll static void load() throws Exception {
        assumeTrue(LocalBgeSmall.available(), "frozen bge-small files absent: NOT EXECUTED");
        model = new LocalBgeSmall();
        alice = new ConversationHarness("tgt1001000000054", RES.resolve("alice-bob-natural-dialogue.txt"), 302, model, ConversationHarness.FIXED);
        harbor = new ConversationHarness("tgtharbor", RES.resolve("harbor-workshop-dialogue.txt"), 0, model, ConversationHarness.FIXED);
    }
    @AfterAll static void close() throws Exception { if (model != null) model.close(); }

    /** One information need: satisfied when the selected context contains ANY of these lines. */
    record Need(String name, int... anyOf) {}
    /** A correction rule: if the earlier (superseded) line is sent, its correction must be sent too. */
    record Correction(int superseded, int correction) {}

    static final int MAX_MESSAGES = 14;          // evidence budget (bounded context; checked for every question)
    static final int MAX_BYTES = 12_000;

    ConversationHarness.Run check(ConversationHarness h, String label, String question, List<Need> needs, List<Correction> corrections) {
        var run = h.ask(question);
        Set<String> sel = new LinkedHashSet<>(run.selected());
        StringBuilder rep = new StringBuilder("[TASK-054 retrieval] " + label + " | q=\"" + question + "\"\n");
        rep.append("  candidates(ranked): ");
        run.candidates().forEach(c -> rep.append(c.messageId().substring(h.group.length())).append('(').append(c.via()).append(") "));
        rep.append("\n  selected(").append(sel.size()).append(", ").append(run.requestBytes()).append(" B):\n");
        for (String id : sel) rep.append("    ").append(id.substring(h.group.length())).append(": ").append(h.textById.get(id)).append('\n');
        List<String> missing = new ArrayList<>();
        for (Need n : needs) {
            boolean ok = Arrays.stream(n.anyOf()).anyMatch(l -> sel.contains(h.id(l)));
            rep.append("  need ").append(n.name()).append(ok ? " COVERED" : " MISSING").append(" (any of ");
            Arrays.stream(n.anyOf()).forEach(l -> rep.append('m').append(l + h.idOffset).append(' '));
            rep.append(")\n");
            if (!ok) missing.add(n.name());
        }
        for (Correction c : corrections)
            if (sel.contains(h.id(c.superseded())) && !sel.contains(h.id(c.correction()))) missing.add("correction of m" + (c.superseded() + h.idOffset));
        System.out.println(rep);
        assertTrue(missing.isEmpty(), label + ": insufficient evidence, missing " + missing + "\n" + rep);
        assertTrue(sel.size() <= MAX_MESSAGES, label + ": context not compact (" + sel.size() + " messages)");
        assertTrue(run.requestBytes() <= MAX_BYTES, label + ": request too large (" + run.requestBytes() + " B)");
        return run;
    }

    @Test void q1PicnicTimePlaceAndRainContingencyTogether() {
        check(alice, "Q1 picnic when/where/rain", "When and where is the picnic, and what happens if it rains?", List.of(
                new Need("when (final: Saturday at one)", 27, 40, 198),
                new Need("where (Meadow Park, west gate)", 25),
                new Need("library backup unavailable", 36),
                new Need("rain means postponing", 37, 200),
                new Need("final confirmation", 40, 198, 200)),
                List.of(new Correction(25, 27)));
    }

    @Test void q2FinalTimeAfterTheNoonToOneCorrection() {
        check(alice, "Q2 final time", "What time is the picnic now?", List.of(new Need("Saturday at one", 27, 40, 198)),
                List.of(new Correction(25, 27)));
    }

    @Test void q3LibraryAsRainBackup() {
        check(alice, "Q3 library backup", "Is the library room available as a rain backup?", List.of(new Need("it is booked", 36)), List.of());
    }

    @Test void q4GreenBackpackStillMissing() {
        check(alice, "Q4 backpack", "Is the green backpack still missing?", List.of(new Need("found in the car / not missing", 94, 99)), List.of());
    }

    @Test void q5CurrentSwimmingClassTime() {
        check(alice, "Q5 swimming time", "What time is the swimming class?", List.of(new Need("ten-thirty (current)", 134)),
                List.of(new Correction(133, 134)));
    }

    @Test void q6DoctorBabiesVersusTeenagers() {
        check(alice, "Q6 doctor", "Is Dr. Cedar recommended for babies or for teenagers?", List.of(
                new Need("babies: positive", 3, 7), new Need("teenagers: look elsewhere", 6)), List.of());
    }

    @Test void q7AGenuinelyUnansweredQuestion() {
        // the conversation says only that Bob went around three on a Friday and that this is NOT the schedule
        check(alice, "Q7 unanswered (hours)", "What are the opening hours of the lost-property desk?",
                List.of(new Need("the explicit 'do not assume' statement", 92)), List.of());
        // nothing at all about it: no Muse call
        var none = alice.ask("What is the wifi password?");
        System.out.println("[TASK-054 retrieval] Q7b wifi selected=" + none.selected());
        assertNull(none.request(), "no Muse request when nothing relevant exists");
    }

    @Test void q8SimilarPlanningConversationWithDifferentEntities() {
        check(harbor, "Q8 workshop when/where/kiln", "When and where is the pottery workshop, and what happens if the kiln is broken?", List.of(
                new Need("when (final: Thursday at half past eleven)", 11, 24, 44),
                new Need("where (Harbor Arts Centre, studio B)", 9),
                new Need("backup venue unavailable", 20),
                new Need("broken kiln means shape now, fire next week", 21, 44),
                new Need("final confirmation", 24, 44)),
                List.of(new Correction(9, 11)));
    }

    @Test void q9ParaphraseNeedsTheSemanticChannel() {
        String q = "Where is the outdoor lunch taking place, and at what hour?";
        for (int l : new int[]{25, 27, 40}) {
            Set<String> shared = new HashSet<>(Tokenizer.normalizedSet(q)); shared.retainAll(Tokenizer.normalizedSet(alice.textById.get(alice.id(l))));
            assertTrue(shared.isEmpty(), "paraphrase must share no content word with line " + l + ": " + shared);
        }
        var run = check(alice, "Q9 paraphrase", q, List.of(new Need("where", 25), new Need("when (final)", 27, 40, 198)), List.of(new Correction(25, 27)));
        assertTrue(run.candidates().stream().anyMatch(c -> c.via().contains("vespa:dense")), "the semantic channel contributed");
    }
}
