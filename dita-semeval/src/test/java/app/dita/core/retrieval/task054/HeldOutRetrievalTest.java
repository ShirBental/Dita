package app.dita.core.retrieval.task054;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 HELD-OUT retrieval questions. Written 2026-11-04 AFTER the selector was tuned on the 9 frozen acceptance
 * questions and BEFORE this class was first run; the selector was NOT changed in response to these results. They
 * estimate how the selector generalises to other questions on the same conversation; the first run's results are
 * recorded in .avengers/tasks.md (TASK-054) as they were, pass or fail.
 */
class HeldOutRetrievalTest {
    static final Path RES = Path.of("src/test/resources/task054");
    static LocalBgeSmall model;
    static ConversationHarness alice;

    @BeforeAll static void load() throws Exception {
        assumeTrue(LocalBgeSmall.available(), "frozen bge-small files absent: NOT EXECUTED");
        model = new LocalBgeSmall();
        alice = new ConversationHarness("tgt1001000000054", RES.resolve("alice-bob-natural-dialogue.txt"), 302, model, ConversationHarness.FIXED);
    }
    @AfterAll static void close() throws Exception { if (model != null) model.close(); }

    record Need(String name, int... anyOf) {}

    static void check(String label, String question, Need... needs) {
        var run = alice.ask(question);
        Set<String> sel = new LinkedHashSet<>(run.selected());
        StringBuilder rep = new StringBuilder("[TASK-054 held-out] " + label + " | q=\"" + question + "\"\n  selected(" + sel.size() + ", " + run.requestBytes() + " B):\n");
        for (String id : sel) rep.append("    ").append(id.substring(alice.group.length())).append(": ").append(alice.textById.get(id)).append('\n');
        List<String> missing = new ArrayList<>();
        for (Need n : needs) {
            boolean ok = Arrays.stream(n.anyOf()).anyMatch(l -> sel.contains(alice.id(l)));
            rep.append("  need ").append(n.name()).append(ok ? " COVERED" : " MISSING").append('\n');
            if (!ok) missing.add(n.name());
        }
        System.out.println(rep);
        assertTrue(missing.isEmpty(), label + ": missing " + missing + "\n" + rep);
        assertTrue(sel.size() <= 14 && run.requestBytes() <= 12_000, label + ": context not compact");
    }

    @Test void h1BikeShopLocation() { check("H1 bike shop where", "Where is the bike repair shop?", new Need("Elm Lane", 68)); }
    @Test void h2BrakeCost() { check("H2 brake cost", "How much did the brake adjustment cost?", new Need("thirty-five dollars", 70)); }
    @Test void h3StorytellingWhenWhereRegistration() {
        check("H3 storytelling when/where/registration", "When and where is the storytelling session, and do we need to register?",
                new Need("when (Sunday at four)", 102, 119), new Need("where (Willow Library / library desk)", 101, 110),
                new Need("registration required", 106, 119));
    }
    @Test void h4BusStopAndExit() {
        check("H4 bus stop and exit", "Which stop do I get off at for the park, and which exit should I use?",
                new Need("Birch Station", 182, 184), new Need("north exit", 186));
    }
    @Test void h5CafeReservations() { check("H5 cafe reservations", "Does Cedar Cafe take reservations?", new Need("first come, first served", 50)); }
    @Test void h6CoachDiving() { check("H6 coach diving", "Is Coach Willow suitable for advanced diving?", new Need("not advanced diving", 126)); }
    @Test void h7GroomingPrice() { check("H7 grooming price", "How much does the grooming salon charge?", new Need("forty-five dollars, may change", 174)); }
    @Test void h8AnxiousDog() { check("H8 anxious dog", "Would Paws Grooming be good for a large anxious dog?", new Need("separate consultation first", 166)); }
    @Test void h9ChildFareUnknown() { check("H9 child fare unknown", "What is the child fare on the bus?", new Need("does not know the child fare", 192)); }
    @Test void h10SalonWhereParaphrase() { check("H10 salon address paraphrase", "What is the address of the dog groomer?", new Need("Garden Street", 168)); }
}
