package app.dita.core.answer.synthesis;

import app.dita.core.answer.ContextCues;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Draft;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 DEC-051: replays Muse's SAVED live response (run-20261104T155040Z-0bb1, call-001) through the current
 * validator, on the EXACT inputs Dita sent (question, evidence, replaced, conflicting from the saved request; the
 * context check recomputed as GroundedSynthesis does: every saved stance is neutral, so there are no conflict groups,
 * which this test asserts). No model call. The original artifacts are read only and hash-checked before and after;
 * results go to build/task054-validator-replay/. Gated on the saved files: SKIPPED = NOT EXECUTED.
 */
class SavedLiveResponseReplayTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN = "run-20261104T155040Z-0bb1";

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("build/telegram-synthesis-trial"))) p = p.getParent();
        return p;
    }

    static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    record Saved(Request request, Draft draft, JsonNode ask) {}

    static Saved load(Path call, Path ask) throws Exception {
        JsonNode body = JSON.readTree(call.resolve("request.json").toFile());
        JsonNode msgs = body.path("messages");
        JsonNode user = JSON.readTree(msgs.get(msgs.size() - 1).path("content").asText());
        List<Evidence> ev = new ArrayList<>();
        Map<String, Set<String>> ctx = new LinkedHashMap<>();
        for (JsonNode e : user.path("evidence")) {
            String stance = e.path("stance").asText();
            assertEquals("neutral", stance, "replay assumes no stance (no conflict groups); found " + stance);
            ev.add(new Evidence(e.path("id").asText(), e.path("speaker").asText(), e.path("date").asText(), e.path("kind").asText(),
                    0, e.path("qualified").asBoolean(), e.path("quote").asText()));
            ctx.put(e.path("id").asText(), ContextCues.stated(e.path("quote").asText()));
        }
        List<String> replaced = new ArrayList<>();
        user.path("replaced").forEach(r -> replaced.add(r.asText()));
        String question = user.path("question").asText();
        var check = new AnswerSynthesizer.ContextCheck(ContextCues.of(question), ctx, List.of());
        Request req = new Request(question, user.path("draft").asText(), ev, replaced, user.path("conflicting").asBoolean(), check);
        JsonNode resp = JSON.readTree(call.resolve("response.json").toFile());
        JsonNode content = JSON.readTree(resp.path("choices").get(0).path("message").path("content").asText());
        List<String> cited = new ArrayList<>();
        content.path("citations").forEach(c -> cited.add(c.asText()));
        Draft d = new Draft(content.path("abstain").asBoolean(), content.path("answer").asText(), cited);
        return new Saved(req, d, JSON.readTree(ask.toFile()));
    }

    @Test void savedPicnicResponseNowPassesAndAdversarialVariantsFail() throws Exception {
        Path root = root();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        Path run = root.resolve("build/telegram-synthesis-trial").resolve(RUN), call = run.resolve("call-001-synthesis"), ask = run.resolve("ask-002.json");
        assumeTrue(Files.isRegularFile(call.resolve("request.json")) && Files.isRegularFile(call.resolve("response.json")) && Files.isRegularFile(ask),
                "saved live call absent: NOT EXECUTED");
        Map<String, String> before = new TreeMap<>();
        for (Path f : List.of(call.resolve("request.json"), call.resolve("response.json"), ask)) before.put(root.relativize(f).toString(), sha(f));

        Saved s = load(call, ask);
        var validator = new SynthesisValidator();
        var verdict = validator.validate(s.request(), s.draft());

        // adversarial variants on the same live inputs
        String a = s.draft().answer();
        Map<String, Draft> variants = new LinkedHashMap<>();
        variants.put("speaker swapped (P2 for P1 on m329)", new Draft(false, a.replace("P1 said to make it", "P2 said to make it"), s.draft().citedIds()));
        variants.put("carry onto the other speaker (m342 without P2)", new Draft(false, a.replace("which P2 noted as", "which is"), s.draft().citedIds()));
        variants.put("dropped qualifier (weather permitting)", new Draft(false, a.replace(", weather permitting", ""), s.draft().citedIds()));
        variants.put("dropped qualifier (unless …)", new Draft(false, a.replace(" unless somewhere else is found", ""), s.draft().citedIds()));
        variants.put("dropped negation (not noon)", new Draft(false, a.replace("Saturday at one, not noon", "Saturday at one"), s.draft().citedIds()));
        variants.put("carry hides the speaker (other members)", new Draft(false, a.replace(", and said rain means", ", and other members said rain means"), s.draft().citedIds()));
        variants.put("invented fact (two o'clock)", new Draft(false, a.replace("Saturday at one, not noon", "Saturday at two, not noon"), s.draft().citedIds()));
        variants.put("polarity change", new Draft(false, a.replace("said rain means postponing", "said rain does not mean postponing"), s.draft().citedIds()));
        variants.put("invented cause", new Draft(false, a.replace("because the library event is Sunday", "because of a football match"), s.draft().citedIds()));
        variants.put("invalid citation", new Draft(false, a.replace("[" + s.draft().citedIds().get(0) + "]", "[tgt1001000000054m999]"), s.draft().citedIds()));
        Map<String, List<String>> variantVerdicts = new LinkedHashMap<>();
        for (var e : variants.entrySet()) variantVerdicts.put(e.getKey(), validator.validate(s.request(), e.getValue()).violations());
        // false premise: the same answer to a question about a barbecue nobody mentioned
        Request barbecue = new Request(s.request().question().replace("picnic", "barbecue"), s.request().draft(), s.request().evidence(),
                s.request().replaced(), s.request().conflicting(), s.request().context());
        variantVerdicts.put("false premise (barbecue)", validator.validate(barbecue,
                new Draft(false, a.replace("the picnic", "the barbecue"), s.draft().citedIds())).violations());

        // write results separately; originals unchanged
        Path out = Files.createDirectories(root.resolve("build/task054-validator-replay").resolve(RUN));
        ObjectNode rep = JSON.createObjectNode();
        rep.put("kind", "validator-replay (no model call)").put("validator", "SynthesisValidator DEC-052").put("source_run", RUN);
        rep.set("original_outcome", s.ask().path("synthesis_outcomes"));
        rep.set("original_violations", s.ask().path("violations"));
        rep.put("replay_ok", verdict.ok());
        rep.set("replay_violations", JSON.valueToTree(verdict.violations()));
        rep.set("adversarial_variants", JSON.valueToTree(variantVerdicts));
        rep.set("source_sha256", JSON.valueToTree(before));
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("replay.json").toFile(), rep);
        System.out.println("[TASK-054 validator replay] " + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rep));

        for (var e : before.entrySet()) assertEquals(e.getValue(), sha(root.resolve(e.getKey())), "original artifact changed: " + e.getKey());
        assertTrue(verdict.ok(), "saved live answer: " + verdict.violations());
        for (var e : variantVerdicts.entrySet()) assertFalse(e.getValue().isEmpty(), "adversarial variant accepted: " + e.getKey());
    }

    /** The earlier, INCOMPLETE picnic answer (run-20261104T131550Z-fd89 call-003; rejected by R3 then) must not start passing. */
    @Test void earlierIncompletePicnicAnswerStillFails() throws Exception {
        Path root = root();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        String run = "run-20261104T131550Z-fd89";
        Path dir = root.resolve("build/telegram-synthesis-trial").resolve(run), call = dir.resolve("call-003-synthesis"), ask = dir.resolve("ask-004.json");
        assumeTrue(Files.isRegularFile(call.resolve("response.json")) && Files.isRegularFile(ask), "saved call absent: NOT EXECUTED");
        String h1 = sha(call.resolve("response.json")), h2 = sha(call.resolve("request.json")), h3 = sha(ask);
        Saved s = load(call, ask);
        var verdict = new SynthesisValidator().validate(s.request(), s.draft());
        Path out = Files.createDirectories(root.resolve("build/task054-validator-replay").resolve(run));
        ObjectNode rep = JSON.createObjectNode();
        rep.put("kind", "validator-replay (no model call)").put("validator", "SynthesisValidator DEC-052").put("source_run", run);
        rep.set("original_violations", s.ask().path("violations"));
        rep.put("replay_ok", verdict.ok());
        rep.set("replay_violations", JSON.valueToTree(verdict.violations()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("replay.json").toFile(), rep);
        System.out.println("[TASK-054 validator replay fd89] " + rep);
        assertEquals(h1, sha(call.resolve("response.json"))); assertEquals(h2, sha(call.resolve("request.json"))); assertEquals(h3, sha(ask));
        assertFalse(verdict.ok(), "the incomplete earlier answer must still be rejected");
    }

    /**
     * Second live rehearsal (run-20261104T161752Z-c886): complete and correctly cited, but it calls P2's restatement
     * (m342, "That is what I have now") "confirmed". R6 rightly rejects that speech-act upgrade; recorded so a future
     * relaxation cannot silently accept it.
     */
    @Test void secondLiveAnswerIsRejectedForConfirmedWording() throws Exception {
        Path root = root();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        String run = "run-20261104T161752Z-c886";
        Path dir = root.resolve("build/telegram-synthesis-trial").resolve(run), call = dir.resolve("call-001-synthesis"), ask = dir.resolve("ask-002.json");
        assumeTrue(Files.isRegularFile(call.resolve("response.json")) && Files.isRegularFile(ask), "saved call absent: NOT EXECUTED");
        String h1 = sha(call.resolve("response.json")), h2 = sha(call.resolve("request.json")), h3 = sha(ask);
        Saved s = load(call, ask);
        var verdict = new SynthesisValidator().validate(s.request(), s.draft());
        Path out = Files.createDirectories(root.resolve("build/task054-validator-replay").resolve(run));
        ObjectNode rep = JSON.createObjectNode();
        rep.put("kind", "validator-replay (no model call)").put("validator", "SynthesisValidator DEC-052").put("source_run", run);
        rep.set("original_violations", s.ask().path("violations"));
        rep.put("replay_ok", verdict.ok());
        rep.set("replay_violations", JSON.valueToTree(verdict.violations()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("replay.json").toFile(), rep);
        System.out.println("[TASK-054 validator replay c886] " + rep);
        assertEquals(h1, sha(call.resolve("response.json"))); assertEquals(h2, sha(call.resolve("request.json"))); assertEquals(h3, sha(ask));
        assertTrue(verdict.violations().stream().anyMatch(x -> x.startsWith("R6") && x.contains("confirmed")), verdict.violations().toString());
    }

    @Test void savedV3AnswerPassesThroughProductionContextResolutionWithoutChangingArtifacts() throws Exception {
        replayContext("run-20261104T164835Z-8567");
    }
    @Test void latestAbbreviatedMarkersPassProductionValidation() throws Exception {
        replayContext("run-20261104T171409Z-0918");
    }
    @Test void liveQuotedThenPreservesSourceWording() throws Exception {
        replayContext("run-20261104T171705Z-19b3");
    }
    @Test void liveReportedSourceThenIsNotNarrativeOrdering() throws Exception {
        // TASK-055 (Fury): since verbatimOrder, the old input (no reference metadata) already passes, so the
        // "old input is rejected" precondition does not apply to this run; the production-path assertions below still do.
        replayContext("run-20261104T171831Z-9ed9", false);
    }
    void replayContext(String runTag) throws Exception { replayContext(runTag, true); }
    void replayContext(String runTag, boolean oldInputRejected) throws Exception {
        Path root = root();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        Path run = root.resolve("build/telegram-synthesis-trial").resolve(runTag);
        Path call = run.resolve("call-001-synthesis"), ask = run.resolve("ask-002.json");
        assumeTrue(Files.isRegularFile(call.resolve("response.json")) && Files.isRegularFile(ask), "saved v3 absent: NOT EXECUTED");
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (Path f : List.of(call.resolve("request.json"), call.resolve("response.json"), ask)) hashes.put(f, sha(f));
        Saved saved = load(call, ask);
        if (oldInputRejected) assertFalse(new SynthesisValidator().validate(saved.request(), saved.draft()).ok(), "old input lacks reference metadata");
        var store = new app.dita.core.store.InMemoryMessageStore();
        var scope = new app.dita.core.model.GroupScope("saved-synthetic-replay");
        var start = java.time.Instant.parse("2026-11-04T13:20:00Z");
        var byNumber = new TreeMap<Integer, Evidence>();
        saved.request().evidence().forEach(e -> byNumber.put(Integer.parseInt(e.id().substring(e.id().lastIndexOf('m') + 1)), e));
        String messagePrefix = saved.request().evidence().get(0).id().replaceFirst("m[0-9]+$", "m");
        // Preserve original chronology, including gaps. m337/m338 are consecutive in the synthetic transcript.
        for (int n = byNumber.firstKey(); n <= byNumber.lastKey(); n++) {
            Evidence e = byNumber.get(n);
            store.append(scope, new app.dita.core.model.Message(messagePrefix + n, scope.groupId(),
                    e == null ? "other" : "author-" + e.speaker().substring(1), start.plusSeconds(n), null,
                    e == null ? "Unselected intervening message." : e.quote(), app.dita.core.model.SourceKind.SYNTHETIC, false));
        }
        var citations = saved.request().evidence().stream().map(e -> new app.dita.core.answer.Citation(e.id(), "author-" + e.speaker().substring(1),
                store.find(scope, e.id()).orElseThrow().sentAt(), e.quote(), app.dita.core.answer.Citation.Evidence.EXPLICIT, 0, e.qualified())).toList();
        var baseline = new app.dita.core.answer.GroundedAnswer(saved.request().question(), saved.request().draft(), citations, false, true);
        AnswerSynthesizer synth = new AnswerSynthesizer() {
            public String version() { return "saved-v3-offline"; }
            public Draft synthesize(Request req) {
                assertEquals(Map.of(messagePrefix + "338", messagePrefix + "337"), req.referenceQuestions());
                return saved.draft();
            }
        };
        var result = new GroundedSynthesis(store, synth, x -> x).apply(scope, baseline);
        assertEquals(GroundedSynthesis.Outcome.SYNTHESIZED, result.outcome(), result.violations().toString());
        assertTrue(result.answer().citations().stream().anyMatch(c -> c.messageId().equals(messagePrefix + "337")), "antecedent is a final source");
        var refs = ReferenceSupport.selectedQuestions(store, scope, saved.request().evidence());
        var req = new Request(saved.request().question(), saved.request().draft(), saved.request().evidence(), List.of(), false,
                saved.request().context(), Map.of(), refs);
        String answer = CitationMarkers.canonicalize(req, saved.draft()).answer();
        for (String bad : List.of(answer.replace("Meadow Park", "Cedar Park"), answer.replace("west gate", "east gate"),
                answer.replace("library room", "school room"), answer.replace("P2 said the library", "P1 said the library"),
                answer.replace("is booked", "is available"), answer.replace("not an option", "an option"),
                answer.replace("unless we find somewhere else", ""), answer.replace("one, not noon", "two, not noon"))) {
            if (bad.equals(answer)) continue;
            assertFalse(new SynthesisValidator().validate(req, new Draft(false, bad, saved.draft().citedIds())).ok(), bad);
        }
        for (var h : hashes.entrySet()) assertEquals(h.getValue(), sha(h.getKey()), "saved live artifact unchanged");
    }
}
