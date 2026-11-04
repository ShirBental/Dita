package app.dita.core.knowledge;

import app.dita.core.answer.GroundedAnswer;
import app.dita.core.knowledge.TrustworthyStateSuiteTest.Hooks;
import app.dita.core.knowledge.TrustworthyStateSuiteTest.Kind;
import app.dita.core.knowledge.TrustworthyStateSuiteTest.Metrics;
import app.dita.core.knowledge.TrustworthyStateSuiteTest.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046 Priority 1: proves the trust gate catches broken behaviour. Each test feeds the real evaluator a case (or a
 * simulated broken composer) and asserts that the gate rejects it. A gate that passes these is not vacuous.
 */
class TrustSuiteGateTest {
    static final String CORRECTION = """
        {"id":"G-1","class":"gate:correction","messages":[{"id":"a1","author":"u-ana","minute":0,"text":"Dinner is at 7 PM."},
         {"id":"a2","author":"u-ana","minute":5,"text":"Sorry, I got that wrong earlier. Dinner is at 8 PM."}],
         "items":[{"status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"dinner","subject":"Dinner","predicate":"states","mid":"a1","quote":"Dinner is at 7 PM."},
                  {"status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"dinner","subject":"Dinner","predicate":"states","mid":"a2","quote":"Dinner is at 8 PM."}],
         "question":"What time is dinner?","expect":{"supersedes":[["a2","a1"]],"active":["a2"],"mustCite":["a2"],"mustNotCite":["a1"],"textMustNot":["7 PM"]}}
        """;

    static ObjectNode parse(String s) throws Exception { return (ObjectNode) TrustworthyStateSuiteTest.JSON.readTree(s); }

    static Outcome eval(JsonNode c) { return TrustworthyStateSuiteTest.evaluate(c, new Metrics(), Hooks.NONE); }

    static boolean has(Outcome o, Kind k) { return o.violations().stream().anyMatch(v -> v.kind() == k); }

    @Test void aCorrectCaseIsAcceptedAndPassesTheGate() throws Exception {
        Outcome o = eval(parse(CORRECTION));
        assertTrue(o.accepted(), o.violations().toString());
        assertDoesNotThrow(() -> TrustworthyStateSuiteTest.gate(List.of(o)));
    }

    @Test void aForbiddenTextFailureIsAnAcceptanceFailure() throws Exception {
        // the old gate only asserted FALSE/VALID/STALE reasons; a stale value in the TEXT was reported and ignored
        ObjectNode c = parse(CORRECTION);
        ((ObjectNode) c.get("expect")).putArray("textMustNot").add("8 PM");
        Outcome o = eval(c);
        assertTrue(has(o, Kind.TEXT_FORBIDDEN), o.violations().toString());
        assertThrows(AssertionFailedError.class, () -> TrustworthyStateSuiteTest.gate(List.of(o)));
    }

    @Test void missingEvidenceAndMissingLeadFailTheGate() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ObjectNode e = (ObjectNode) c.get("expect");
        e.putArray("mustCite").add("a2").add("zz");
        e.put("leadContains", "nonexistent words");
        Outcome o = eval(c);
        assertTrue(has(o, Kind.EVIDENCE_NOT_CITED) && has(o, Kind.LEAD_MISSING), o.violations().toString());
        assertThrows(AssertionFailedError.class, () -> TrustworthyStateSuiteTest.gate(List.of(o)));
    }

    @Test void aSimulatedBrokenComposerThatShowsTheStaleClaimIsRejected() throws Exception {
        // broken behaviour: the composer re-quotes the superseded message and cites it
        Hooks broken = new Hooks(a -> {
            var cites = new java.util.ArrayList<>(a.citations());
            cites.add(new app.dita.core.answer.Citation("a1", "u-ana", TrustworthyStateSuiteTest.T0, "Dinner is at 7 PM.",
                    app.dita.core.answer.Citation.Evidence.EXPLICIT, 0, false));
            return new GroundedAnswer(a.question(), "- u-ana said about Dinner: \"Dinner is at 7 PM.\" [a1]\n" + a.text(), cites,
                    a.conflicting(), a.found(), a.knowledgeIds());
        });
        Outcome o = TrustworthyStateSuiteTest.evaluate(parse(CORRECTION), new Metrics(), broken);
        assertTrue(has(o, Kind.STALE_CITED) && has(o, Kind.TEXT_FORBIDDEN), o.violations().toString());
        assertThrows(AssertionFailedError.class, () -> TrustworthyStateSuiteTest.gate(List.of(o)));
    }

    @Test void aSimulatedComposerThatAnswersNothingFailsOnMissingEvidence() throws Exception {
        Hooks empty = new Hooks(a -> new GroundedAnswer(a.question(), "UNKNOWN", List.of(), false, false, List.of()));
        Outcome o = TrustworthyStateSuiteTest.evaluate(parse(CORRECTION), new Metrics(), empty);
        assertTrue(has(o, Kind.EVIDENCE_NOT_CITED), o.violations().toString());
        assertTrue(!o.accepted());
    }

    @Test void aMissingSupersessionAndAForbiddenOneAreBothCaught() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ObjectNode e = (ObjectNode) c.get("expect");
        e.putArray("supersedes");                                  // the run forms a2>a1: now unexpected
        e.putArray("forbidSupersedes").addArray().add("a2").add("a1");
        Outcome o = eval(c);
        assertTrue(has(o, Kind.FALSE_SUPERSESSION) && has(o, Kind.FORBIDDEN_SUPERSESSION), o.violations().toString());
        ObjectNode d = parse(CORRECTION);
        ((ObjectNode) d.get("expect")).putArray("supersedes").addArray().add("a1").add("a2");
        assertTrue(has(eval(d), Kind.MISSING_SUPERSESSION));
    }

    @Test void expectingAnActiveClaimThatIsSupersededIsValidHidden() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ((ObjectNode) c.get("expect")).putArray("active").add("a1");
        assertTrue(has(eval(c), Kind.VALID_HIDDEN));
        ObjectNode d = parse(CORRECTION);
        ((ObjectNode) d.get("expect")).putArray("superseded").add("a2");
        assertTrue(has(eval(d), Kind.EXPECTED_SUPERSEDED_ACTIVE));
    }

    @Test void typosAndVacuousExpectationsAreSchemaFailures() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ((ObjectNode) c.get("expect")).putArray("textMustnot").add("7 PM");        // typo: was silently ignored
        assertTrue(has(eval(c), Kind.SCHEMA));
        ObjectNode d = parse(CORRECTION);
        d.remove("question");                                                       // answer expectations without a question
        Outcome o = eval(d);
        assertTrue(has(o, Kind.SCHEMA), o.violations().toString());
        ObjectNode x = parse(CORRECTION);
        x.put("expected", true);                                                    // unknown case key
        assertTrue(has(eval(x), Kind.SCHEMA));
    }

    @Test void wronglyTypedExpectationsAreSchemaFailures() throws Exception {
        for (String bad : List.of("{\"active\":\"a2\"}", "{\"textMust\":\"x\"}", "{\"supersedes\":\"a2>a1\"}",
                "{\"leadContains\":[\"x\"]}", "{\"supersedes\":[[\"a2\"]]}", "{\"noAnswerAllowed\":\"yes\"}")) {
            ObjectNode c = parse(CORRECTION);
            JsonNode patch = TrustworthyStateSuiteTest.JSON.readTree(bad);
            ((ObjectNode) c.get("expect")).setAll((ObjectNode) patch);
            assertTrue(has(eval(c), Kind.SCHEMA), bad);
        }
    }

    @Test void twoItemsOnOneMessageAreRejected() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ((com.fasterxml.jackson.databind.node.ArrayNode) c.get("items")).add(c.get("items").get(0).deepCopy());
        assertTrue(has(eval(c), Kind.SCHEMA));
    }

    @Test void anExpectedFailureThatPassesIsXpassAndFails() throws Exception {
        ObjectNode c = parse(CORRECTION);
        c.putObject("expectedFailure").put("reason", "test").putArray("kinds").add("EVIDENCE_NOT_CITED");
        Outcome o = eval(c);
        assertTrue(has(o, Kind.XPASS), o.violations().toString());
        assertThrows(AssertionFailedError.class, () -> TrustworthyStateSuiteTest.gate(List.of(o)));
    }

    @Test void anExpectedFailureToleratesOnlyItsDeclaredNonSafetyKinds() throws Exception {
        ObjectNode c = parse(CORRECTION);
        ((ObjectNode) c.get("expect")).putArray("mustCite").add("a2").add("zz");
        c.putObject("expectedFailure").put("reason", "test").putArray("kinds").add("EVIDENCE_NOT_CITED");
        Outcome tolerated = eval(c);
        assertTrue(tolerated.accepted(), tolerated.violations().toString());
        assertEquals(1, tolerated.tolerated().size());
        // a safety violation in the same case is never excused
        ((ObjectNode) c.get("expect")).putArray("mustNotCite").add("a2");
        Outcome unsafe = eval(c);
        assertTrue(has(unsafe, Kind.STALE_CITED) && !unsafe.accepted(), unsafe.violations().toString());
        // and a safety kind cannot be declared as expected
        ObjectNode d = parse(CORRECTION);
        d.putObject("expectedFailure").put("reason", "test").putArray("kinds").add("STALE_CITED");
        assertTrue(has(eval(d), Kind.SCHEMA));
    }

    @Test void aClassLabelAloneNoLongerExcusesAFailure() throws Exception {
        ObjectNode c = parse(CORRECTION);
        c.put("class", "retrieval:paraphrase (expected to fail)");
        ((ObjectNode) c.get("expect")).putArray("mustCite").add("zz");
        Outcome o = eval(c);
        assertTrue(has(o, Kind.SCHEMA) && has(o, Kind.EVIDENCE_NOT_CITED), o.violations().toString());
    }

    @Test void anEmptyCaseDirectoryIsAFailureNotAPass() throws Exception {
        Files.createDirectories(Path.of("target"));
        Path dir = Files.createTempDirectory(Path.of("target"), "trust-empty");
        assertThrows(IllegalStateException.class, () -> TrustworthyStateSuiteTest.load(dir));
        Files.writeString(dir.resolve("x.json"), "[]");
        assertThrows(IllegalStateException.class, () -> TrustworthyStateSuiteTest.load(dir));
    }

    @Test void measurementModeIsRefusedOnTheAcceptanceDirectory() {
        String old = System.getProperty("dita.trust.mode");
        System.setProperty("dita.trust.mode", "measure");
        try {
            assertThrows(AssertionFailedError.class, () -> new TrustworthyStateSuiteTest().runSuite());
            System.setProperty("dita.trust.dir", Path.of(TrustworthyStateSuiteTest.ACCEPTANCE_DIR).toAbsolutePath() + "/../trust");   // alias
            assertThrows(AssertionFailedError.class, () -> new TrustworthyStateSuiteTest().runSuite());
        } finally {
            if (old == null) System.clearProperty("dita.trust.mode"); else System.setProperty("dita.trust.mode", old);
            System.clearProperty("dita.trust.dir");
        }
    }
}
