package app.dita.muse;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.answer.synthesis.GroundedSynthesis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-053 acceptance (offline, synthetic, RECORDED Muse only). Arm A: the deterministic production QueryService.
 * Arm B: the same QueryService with Muse answer synthesis (recorded, hand-authored stand-in replies). Expected answers
 * (expected.json) were written before any run. Hard assertions are the SAFETY invariants (citation validity, isolation,
 * no consensus, every adversarial reply rejected by the expected rule); accuracy is measured and reported, not asserted,
 * so a weak result is visible instead of hidden. Results: target/task053-results.json.
 */
class Task053AcceptanceTest {
    static final MuseSettings SETTINGS = new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3",
            "MODEL_API_KEY", Duration.ofSeconds(5), 512, 20_000, 50, MuseSettings.ResponseFormat.PROMPT_ONLY);
    static final Set<String> CONSENSUS = Set.of("everyone agrees", "all agree", "consensus", "unanimous");

    static RecordedMuseTransport transport(String resource) throws Exception {
        return RecordedMuseTransport.fromFile(Path.of(Task053AcceptanceTest.class.getResource("/task053/" + resource).toURI()));
    }

    static MuseClient client(RecordedMuseTransport t) { return new MuseClient(SETTINGS, t, k -> "synthetic-test-key-0123456789"); }

    @Test void deterministicVersusGroundedSynthesis() throws Exception {
        var f = new Task053Fixture();
        QueryService a = f.deterministic();
        assertFalse(a.synthesisEnabled(), "synthesis is off unless explicitly attached");
        var rt = transport("recorded-good.json");
        List<GroundedSynthesis.Result> outcomes = new ArrayList<>();
        var synthesis = new GroundedSynthesis(f.store, new MuseAnswerSynthesizer(client(rt)), id -> id, outcomes::add);
        QueryService b = a.withSynthesis(synthesis);

        ObjectNode report = Task053Fixture.JSON.createObjectNode();
        report.put("note", "Arm B uses HAND-AUTHORED recorded replies: it measures the pipeline/validator, not live Muse quality.");
        ArrayNode rows = report.putArray("questions");
        Map<String, int[]> totals = new LinkedHashMap<>();   // criterion -> {passA, passB, applicable}
        for (JsonNode q : Task053Fixture.resource("expected.json").path("questions")) {
            String question = q.path("question").asText();
            GroundedAnswer ansA = a.ask(Task053Fixture.PICNIC, "reader", question);
            int before = outcomes.size();
            GroundedAnswer ansB = b.ask(Task053Fixture.PICNIC, "reader", question);
            String outcomeB = outcomes.size() > before ? outcomes.get(outcomes.size() - 1).outcome().name() : "NOT_CALLED";
            Map<String, Boolean> sa = score(q, ansA, f), sb = score(q, ansB, f);
            ObjectNode row = rows.addObject().put("id", q.path("id").asText()).put("phenomenon", q.path("phenomenon").asText())
                    .put("synthesisOutcomeB", outcomeB);
            row.set("A", answerJson(ansA, sa));
            row.set("B", answerJson(ansB, sb));
            if (outcomes.size() > before) row.putPOJO("violationsB", outcomes.get(outcomes.size() - 1).violations());
            for (var e : sa.entrySet()) {
                int[] t = totals.computeIfAbsent(e.getKey(), k -> new int[3]);
                t[2]++; if (e.getValue()) t[0]++; if (Boolean.TRUE.equals(sb.get(e.getKey()))) t[1]++;
            }
            // SAFETY invariants (both arms): valid citations, isolation, no consensus wording
            for (var ans : List.of(ansA, ansB)) {
                assertTrue(sa.get("citationValidity") && sb.get("citationValidity"), q.path("id").asText() + " citations: " + ans.citations());
                assertTrue(sb.get("isolation") && sa.get("isolation"), q.path("id").asText() + " isolation: " + ans.text());
                assertTrue(CONSENSUS.stream().noneMatch(c -> ans.text().toLowerCase(Locale.ROOT).contains(c)), ans.text());
            }
        }
        ObjectNode agg = report.putObject("totals");
        totals.forEach((k, t) -> agg.putObject(k).put("A", t[0]).put("B", t[1]).put("of", t[2]));
        ObjectNode outs = report.putObject("synthesisOutcomesB");
        synthesis.metrics().forEach((k, v) -> outs.put(k.name(), v));

        // isolation of what LEAVES the process: no other-group or injection content in any Muse request
        for (String body : rt.requests()) {
            assertFalse(body.contains("everyone stay home") || body.contains("\"o1\""), "other group's data sent to Muse");
            assertFalse(body.contains("ignore your previous instructions"), "the injection message was sent as evidence");
            assertFalse(body.contains("Ana") || body.contains("Ben") || body.contains("Carmen"), "display names must be pseudonymised");
        }
        report.put("museRequests", rt.requests().size());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/task053-results.json"), Task053Fixture.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test void adversarialRepliesAreRejectedByTheExpectedRule() throws Exception {
        var f = new Task053Fixture();
        var rt = transport("recorded-adversarial.json");
        List<GroundedSynthesis.Result> outcomes = new ArrayList<>();
        QueryService a = f.deterministic();
        QueryService b = a.withSynthesis(new GroundedSynthesis(f.store, new MuseAnswerSynthesizer(client(rt)), id -> id, outcomes::add));
        JsonNode recs = Task053Fixture.JSON.readTree(Path.of(getClass().getResource("/task053/recorded-adversarial.json").toURI()).toFile()).path("recordings");
        ArrayNode rows = Task053Fixture.JSON.createArrayNode();
        int detected = 0, expectedRejections = 0;
        for (JsonNode r : recs) {
            String question = r.path("requires").get(0).asText();
            GroundedAnswer base = a.ask(Task053Fixture.PICNIC, "reader", question);
            int before = outcomes.size();
            GroundedAnswer got = b.ask(Task053Fixture.PICNIC, "reader", question);
            assertEquals(before + 1, outcomes.size(), r.path("id").asText() + ": synthesis must run once");
            var res = outcomes.get(outcomes.size() - 1);
            String expectOutcome = r.path("expect").path("outcome").asText(), rule = r.path("expect").path("rule").asText();
            rows.addObject().put("id", r.path("id").asText()).put("outcome", res.outcome().name()).putPOJO("violations", res.violations());
            assertEquals(expectOutcome, res.outcome().name(), r.path("id").asText() + " " + res.violations());
            if (!rule.isEmpty() && !rule.equals("KNOWN-GAP")) {
                expectedRejections++;
                assertTrue(res.violations().stream().anyMatch(v -> v.startsWith(rule)), r.path("id").asText() + " expected " + rule + ": " + res.violations());
                assertEquals(base.text(), got.text(), "a rejected draft leaves the deterministic answer unchanged");
                detected++;
            }
            if (expectOutcome.equals("FALLBACK_ERROR")) assertEquals(base.text(), got.text());
        }
        assertEquals(expectedRejections, detected);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/task053-adversarial.json"), Task053Fixture.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
    }

    /** Natasha F7/F8: pseudonym-like input (a member called "P2", "P1" in a quote) and over-long questions are not sent. */
    @Test void ambiguousOrOversizedInputIsNotEligible() throws Exception {
        var f = new Task053Fixture();
        f.store.append(Task053Fixture.PICNIC, new app.dita.core.model.Message("p1", Task053Fixture.PICNIC.groupId(), "P2",
                Task053Fixture.T0.plusSeconds(6100), null, "The lantern parade is at the lake.", app.dita.core.model.SourceKind.SYNTHETIC, false));
        var rt = transport("recorded-good.json");
        List<GroundedSynthesis.Result> outcomes = new ArrayList<>();
        QueryService b = f.deterministic().withSynthesis(new GroundedSynthesis(f.store, new MuseAnswerSynthesizer(client(rt)), id -> id, outcomes::add));
        GroundedAnswer ans = b.ask(Task053Fixture.PICNIC, "reader", "Where is the lantern parade?");
        if (ans.found()) assertEquals(GroundedSynthesis.Outcome.FALLBACK_NOT_ELIGIBLE, outcomes.get(outcomes.size() - 1).outcome());
        b.ask(Task053Fixture.PICNIC, "reader", "Who is bringing the grill? " + "x".repeat(GroundedSynthesis.MAX_QUESTION_CHARS));
        if (!outcomes.isEmpty() && outcomes.get(outcomes.size() - 1).answer().found())
            assertEquals(GroundedSynthesis.Outcome.FALLBACK_NOT_ELIGIBLE, outcomes.get(outcomes.size() - 1).outcome());
        assertEquals(2, outcomes.stream().filter(o -> o.outcome() == GroundedSynthesis.Outcome.FALLBACK_NOT_ELIGIBLE).count(), outcomes.toString());
        assertTrue(rt.requests().stream().noneMatch(body -> body.contains("lantern") || body.contains("xxxxxxxx")), "ineligible input was sent to Muse");
    }

    @Test void nonSyntheticEvidenceNeverLeavesTheProcess() throws Exception {
        var f = new Task053Fixture();
        // a real (non-synthetic) message in the group: the answer citing it must not be sent to Muse
        f.store.append(Task053Fixture.PICNIC, new app.dita.core.model.Message("r1", Task053Fixture.PICNIC.groupId(), "Hana",
                Task053Fixture.T0.plusSeconds(6000), null, "The lantern parade is at the lake.", app.dita.core.model.SourceKind.PLATFORM, false));
        var rt = transport("recorded-good.json");
        List<GroundedSynthesis.Result> outcomes = new ArrayList<>();
        QueryService b = f.deterministic().withSynthesis(new GroundedSynthesis(f.store, new MuseAnswerSynthesizer(client(rt)), id -> id, outcomes::add));
        GroundedAnswer ans = b.ask(Task053Fixture.PICNIC, "reader", "Where is the lantern parade?");
        if (ans.found() && ans.citations().stream().anyMatch(c -> c.messageId().equals("r1"))) {
            assertEquals(GroundedSynthesis.Outcome.FALLBACK_NOT_ELIGIBLE, outcomes.get(outcomes.size() - 1).outcome());
        }
        assertTrue(rt.requests().stream().noneMatch(body -> body.contains("lantern")), "non-synthetic text was sent to Muse");
    }

    /** Pre-registered scoring (expected.json). Every criterion is rule-based on citations and wording. */
    static Map<String, Boolean> score(JsonNode q, GroundedAnswer ans, Task053Fixture f) {
        Map<String, Boolean> s = new LinkedHashMap<>();
        Set<String> cites = ans.citations().stream().map(Citation::messageId).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        String text = ans.text(), lower = text.toLowerCase(Locale.ROOT);
        boolean valid = ans.citations().stream().allMatch(c -> f.store.find(Task053Fixture.PICNIC, c.messageId())
                .filter(m -> !m.deleted() && m.text().contains(c.quote()) && m.authorId().equals(c.authorId())).isPresent());
        s.put("citationValidity", valid);
        s.put("isolation", !cites.contains("o1") && !lower.contains("everyone stay home"));
        boolean acc = true;
        if (q.has("found")) acc &= ans.found() == q.path("found").asBoolean();
        for (JsonNode m : q.path("mustCite")) acc &= cites.contains(m.asText());
        for (JsonNode m : q.path("mustNotCite")) acc &= !cites.contains(m.asText());
        for (JsonNode m : q.path("mustContainAll")) acc &= text.contains(m.asText());
        if (q.has("mustContainAny")) acc &= anyIn(lower, q.path("mustContainAny"));
        for (JsonNode m : q.path("mustNotContain")) acc &= !lower.contains(m.asText().toLowerCase(Locale.ROOT));
        if (q.has("abstainOrOnlyCite")) {
            Set<String> only = new java.util.HashSet<>(); q.path("abstainOrOnlyCite").forEach(x -> only.add(x.asText()));
            acc &= !ans.found() || (only.containsAll(cites) && anyIn(lower, q.path("ifFoundMustContainAny")));
            s.put("abstention", !ans.found() || only.containsAll(cites));
        }
        if (q.path("id").asText().equals("Q7")) s.put("abstention", !ans.found());
        s.put("factualAccuracy", acc);
        boolean attributed = true;
        for (JsonNode sp : iterable(q.path("speakers"))) attributed &= !cites.contains(sp.path("k").asText()) || text.contains(sp.path("v").asText());
        s.put("sourceAttribution", attributed && ans.citations().stream().allMatch(c -> text.contains(c.authorId())));
        if (q.path("conflicting").asBoolean(false))
            s.put("conflictPreservation", ans.conflicting() && cites.containsAll(List.of("m1", "m2")) && CONSENSUS.stream().noneMatch(lower::contains));
        if (q.path("id").asText().equals("Q2")) s.put("supersession", !cites.contains("m3") && !lower.contains("saturday"));
        return s;
    }

    static boolean anyIn(String lower, JsonNode words) {
        for (JsonNode w : words) if (lower.contains(w.asText().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    static List<JsonNode> iterable(JsonNode obj) {
        List<JsonNode> out = new ArrayList<>();
        obj.fields().forEachRemaining(e -> out.add(Task053Fixture.JSON.createObjectNode().put("k", e.getKey()).put("v", e.getValue().asText())));
        return out;
    }

    static ObjectNode answerJson(GroundedAnswer a, Map<String, Boolean> score) {
        ObjectNode o = Task053Fixture.JSON.createObjectNode().put("found", a.found()).put("conflicting", a.conflicting()).put("text", a.text());
        o.putPOJO("cites", a.citations().stream().map(Citation::messageId).toList());
        o.putPOJO("score", score);
        return o;
    }
}
