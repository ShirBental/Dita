package app.dita.app;

import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 controlled synthesis trial through the PRODUCTION answer path: stored knowledge → Dita retrieval/selection →
 * deterministic draft → GroundedSynthesis request → Muse → SynthesisValidator → QueryService verifier + final gate.
 * Run only via {@code bash scripts/muse-live.sh synthesis ...}. Telegram OFF. Knowledge is re-established from the saved
 * live EXTRACTION response (build/controlled-muse-cedar, read-only).
 * <ul>
 *   <li>PREVIEW: captures each question's exact synthesis request; nothing is sent (the transport answers 599, so Dita
 *       falls back to its deterministic answer, recorded as a fallback).</li>
 *   <li>LIVE: ONE question (dita.synth.question), only with DITA_SYNTH_APPROVED=yes and the SHA-256 of that question's
 *       reviewed preview; the request must match it byte for byte. Attempt marker first, no retry, response saved
 *       separately (CREATE_NEW).</li>
 *   <li>RECORDED: replays saved synthesis responses offline; requests must still match the previews.</li>
 * </ul>
 */
class ControlledSynthesisTrialTest {
    static final String MODE = System.getProperty("dita.synth.mode", "PREVIEW");
    static final String BACKEND = System.getProperty("dita.synth.backend", "POSTGRES");
    /** Synthesis instruction version. V1 artifacts stay in the original directory; V2 gets its own (no reuse of v1 approvals). */
    static final app.dita.muse.MuseAnswerSynthesizer.Prompt PROMPT = app.dita.muse.MuseAnswerSynthesizer.Prompt.valueOf(System.getProperty("dita.synth.prompt", "V2"));
    static final String TRIAL_DIR = PROMPT == app.dita.muse.MuseAnswerSynthesizer.Prompt.V1 ? "build/controlled-synthesis-cedar" : "build/controlled-synthesis-cedar-v2";
    static final Path OUT = SynthesisTrialHarness.ROOT.resolve(TRIAL_DIR).resolve(dir(BACKEND));
    /** V1 trial asked q1-q3 only; V2 adds q4 (unsupported population). */
    static List<String> questionIds(SynthesisTrialHarness.Fixture fx) {
        return fx.questions().keySet().stream().filter(q -> PROMPT != app.dita.muse.MuseAnswerSynthesizer.Prompt.V1 || !q.equals("q4")).toList();
    }
    static String dir(String backend) { return backend.equals("IN_MEMORY") ? "inmemory" : "postgres"; }
    /** Standard-mode Muse Spark pricing, user-supplied 2026-10-28 (historical; not verified current): reasoning bills as output. */
    static final double IN_PER_MTOK = 1.25, OUT_PER_MTOK = 4.25;
    static final String PRICING = "user-supplied 2026-10-28 standard-mode Muse Spark prices: $1.25/M input, $4.25/M output (reasoning billed as output); historical, not verified current, not a billing cap";

    @Test void productionSynthesisTrial() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.synth.run"));
        assertTrue(Set.of("PREVIEW", "LIVE", "RECORDED").contains(MODE), MODE);
        assertTrue(SynthesisTrialHarness.extractionArtifactsPresent(), "saved Cedar extraction (request.json + recording.json) required");
        boolean live = MODE.equals("LIVE"), preview = MODE.equals("PREVIEW");
        var fixture = SynthesisTrialHarness.cedar();
        List<String> ids = questionIds(fixture);
        String liveQ = System.getProperty("dita.synth.question", "");
        List<String> asked = new ArrayList<>();
        if (live) {
            assertTrue(ids.contains(liveQ), "LIVE needs exactly one dita.synth.question " + ids);
            assertEquals("yes", System.getenv("DITA_SYNTH_APPROVED"));
            Path q = OUT.resolve(liveQ);
            assertFalse(Files.exists(q.resolve("attempted")), "Already attempted: use RECORDED; never retry automatically");
            assertFalse(Files.exists(q.resolve("synthesis-recording.json")), "A recording exists: use RECORDED");
            assertEquals(System.getenv("DITA_SYNTH_REQUEST_SHA256"), SynthesisTrialHarness.sha(Files.readAllBytes(q.resolve("synthesis-request.json"))));
            asked.add(liveQ);
        } else if (preview) {
            asked.addAll(ids);
        } else {
            for (String qid : ids)
                if ((liveQ.isEmpty() || liveQ.equals(qid)) && Files.isRegularFile(OUT.resolve(qid).resolve("synthesis-recording.json"))) asked.add(qid);
            assertFalse(asked.isEmpty(), "RECORDED: no saved synthesis response under " + OUT);
        }
        byte[] extractionBefore = extractionDigest();

        Map<String, Map<String, Object>> transportInfo = new HashMap<>();
        SynthesisTrialHarness.SynthesisReply reply = (qid, body, url, headers, timeout) -> {
            Path q = Files.createDirectories(OUT.resolve(qid));
            Path req = q.resolve("synthesis-request.json");
            if (preview) {
                if (Files.exists(q.resolve("attempted"))) assertEquals(Files.readString(req), body, "attempted request must not change; preview kept");
                else Files.writeString(req, body);
                return new MuseTransport.Reply(599, "{}", Map.of());                       // nothing leaves the machine
            }
            assertEquals(Files.readString(req), body, "synthesis request must equal the reviewed preview byte for byte");
            if (!live) return new MuseTransport.Reply(200, Files.readString(q.resolve("synthesis-recording.json")), Map.of());
            assertEquals(liveQ, qid, "only the approved question may be sent");
            assertEquals(System.getenv("DITA_SYNTH_REQUEST_SHA256"), SynthesisTrialHarness.sha(SynthesisTrialHarness.utf8(body)));
            Files.writeString(q.resolve("attempted"), Instant.now() + " sha256=" + System.getenv("DITA_SYNTH_REQUEST_SHA256") + "\n", StandardOpenOption.CREATE_NEW);
            long t0 = System.nanoTime();
            MuseTransport.Reply r;
            try { r = MuseTransport.http().post(url, headers, body, timeout); }
            catch (Exception e) {
                Files.writeString(q.resolve("synthesis-http.json"), SynthesisTrialHarness.JSON.writeValueAsString(Map.of(
                        "error", e.getClass().getSimpleName(), "latency_ms", (System.nanoTime() - t0) / 1_000_000)), StandardOpenOption.CREATE_NEW);
                throw e;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("http_status", r.status()); info.put("latency_ms", ms); info.put("x_request_id", r.headers().getOrDefault("x-request-id", ""));
            transportInfo.put(qid, info);
            Files.writeString(q.resolve("synthesis-http.json"), SynthesisTrialHarness.JSON.writeValueAsString(info), StandardOpenOption.CREATE_NEW);
            if (r.status() == 200) Files.writeString(q.resolve("synthesis-recording.json"), r.body(), StandardOpenOption.CREATE_NEW);
            return r;
        };
        int maxCalls = 1 + asked.size();                       // extraction replay + one synthesis per asked question
        var env = (java.util.function.Function<String, String>) n -> n.equals("MODEL_API_KEY") && !live ? "synthetic-no-live-key" : System.getenv(n);
        List<Map<String, Object>> summary = new ArrayList<>();
        try (var h = SynthesisTrialHarness.start(BACKEND, maxCalls, env, PROMPT, fixture, reply)) {
            for (String qid : asked) {
                var run = h.ask(qid);
                Map<String, Object> res = result(h, run, transportInfo.get(qid));
                Path file = OUT.resolve(qid).resolve("result-" + MODE.toLowerCase(Locale.ROOT) + ".json");
                String json = SynthesisTrialHarness.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(res);
                if (live) Files.writeString(file, json, StandardOpenOption.CREATE_NEW); else Files.writeString(file, json);
                summary.add(res);
            }
        }
        assertArrayEquals(extractionBefore, extractionDigest(), "saved extraction artifacts must be unchanged");
        if (preview) Files.writeString(OUT.resolve("review.md"), review(summary));
        for (var r : summary) {
            @SuppressWarnings("unchecked") var syn = (Map<String, Object>) r.get("synthesis");
            System.out.println("[TASK-054 " + MODE + "] " + r.get("qid") + " outcome=" + syn.get("outcome")
                    + " synthesized=" + r.get("synthesis_succeeded") + " final_sources=" + r.get("final_sources")
                    + " quality_pass=" + ((Map<?, ?>) r.get("quality")).get("pass"));
        }
        // The trial records; it does not judge. Pass = the pipeline ran and every artefact was written. Quality and the
        // synthesis outcome are evidence for the report, never relaxed or asserted into a pass here.
    }

    static byte[] extractionDigest() throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        for (String f : List.of("request.json", "recording.json", "attempted", "result-live.json")) {
            Path p = SynthesisTrialHarness.EXTRACTION_DIR.resolve(f);
            md.update(f.getBytes());
            if (Files.exists(p)) md.update(Files.readAllBytes(p));
        }
        return md.digest();
    }

    static Map<String, Object> result(SynthesisTrialHarness h, SynthesisTrialHarness.QuestionRun run, Map<String, Object> transport) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("qid", run.qid()); r.put("prompt", PROMPT.version); r.put("mode", MODE); r.put("backend", h.backend); r.put("group", h.prefix);
        r.put("question", run.question());
        var t = run.selection();
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("candidate_knowledge_ids", t.candidateIds()); sel.put("selected_knowledge_ids", t.selectedIds());
        sel.put("excluded", t.excluded()); sel.put("outcome", t.outcome()); sel.put("responsive", t.responsive());
        Map<String, String> statements = new LinkedHashMap<>();
        for (var k : h.store().knowledge(h.group)) statements.put(k.id(), k.statement() + " | qualifiers=" + k.assertion().qualifiers());
        sel.put("stored_knowledge", statements);
        r.put("selection", sel);
        r.put("deterministic_baseline", SynthesisTrialHarness.answer(run.baseline()));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("synthesis_requests_in_this_ask", run.requests().size());
        if (!run.requests().isEmpty()) {
            String body = run.requests().get(0);
            JsonNode b = SynthesisTrialHarness.JSON.readTree(body);
            JsonNode u = SynthesisTrialHarness.userPayload(body);
            byte[] bytes = SynthesisTrialHarness.utf8(body);
            req.put("file", TRIAL_DIR + "/" + dir(h.backend) + "/" + run.qid() + "/synthesis-request.json");
            req.put("sha256", SynthesisTrialHarness.sha(bytes)); req.put("bytes", bytes.length);
            req.put("destination", SynthesisTrialHarness.URL); req.put("model", b.path("model").asText());
            req.put("max_completion_tokens", b.path("max_completion_tokens").asInt());
            req.put("reasoning_effort", b.path("reasoning_effort").asText()); req.put("temperature", b.path("temperature").asDouble());
            req.put("response_format_param", b.has("response_format") ? b.get("response_format").toString() : "none (schema in prompt)");
            String system = b.path("messages").get(0).path("content").asText();
            req.put("system_prompt_chars", system.length());
            req.put("system_prompt", system);
            req.put("system_prompt_sha256", SynthesisTrialHarness.sha(SynthesisTrialHarness.utf8(system)));
            req.put("question_sent", u.path("question").asText());
            req.put("contains_question", u.path("question").asText().equals(run.question()));
            req.put("draft_sent", u.path("draft").asText());
            req.put("evidence_sent", SynthesisTrialHarness.JSON.convertValue(u.path("evidence"), List.class));
            req.put("replaced_sent", SynthesisTrialHarness.JSON.convertValue(u.path("replaced"), List.class));
            req.put("conflicting_sent", u.path("conflicting").asBoolean());
            req.put("conversation_messages_in_request", h.conversationInRequest(body));
            // Read-only comparison with the saved v1 request for the same question and backend (if any)
            Path v1 = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar").resolve(dir(h.backend)).resolve(run.qid()).resolve("synthesis-request.json");
            if (PROMPT != app.dita.muse.MuseAnswerSynthesizer.Prompt.V1 && Files.isRegularFile(v1)) {
                String old = Files.readString(v1);
                JsonNode ob = SynthesisTrialHarness.JSON.readTree(old);
                Map<String, Object> cmp = new LinkedHashMap<>();
                cmp.put("v1_request", "build/controlled-synthesis-cedar/" + dir(h.backend) + "/" + run.qid() + "/synthesis-request.json");
                cmp.put("v1_sha256", SynthesisTrialHarness.sha(SynthesisTrialHarness.utf8(old)));
                cmp.put("user_content_identical", ob.path("messages").get(1).path("content").asText().equals(b.path("messages").get(1).path("content").asText()));
                cmp.put("system_prompt_differs", !ob.path("messages").get(0).path("content").asText().equals(system));
                var params = new java.util.TreeSet<String>(); ob.fieldNames().forEachRemaining(params::add); params.remove("messages");
                cmp.put("other_parameters_identical", params.stream().allMatch(k -> ob.get(k).equals(b.get(k))) && b.size() == ob.size());
                req.put("comparison_with_v1", cmp);
            }
            int estIn = (int) Math.ceil(bytes.length / 3.0);
            req.put("estimate", Map.of("prompt_tokens_upper_estimate", estIn, "basis", "ceil(bytes/3); the saved extraction call measured 652 prompt tokens for 3,128 bytes",
                    "max_output_tokens_billable", b.path("max_completion_tokens").asInt(),
                    "max_cost_usd", round(estIn * IN_PER_MTOK / 1e6 + b.path("max_completion_tokens").asInt() * OUT_PER_MTOK / 1e6),
                    "pricing_basis", PRICING));
        }
        r.put("request", req);

        if (!MODE.equals("PREVIEW")) {
            Path rec = OUT.resolve(run.qid()).resolve("synthesis-recording.json");
            Map<String, Object> muse = new LinkedHashMap<>();
            if (transport != null) muse.putAll(transport);
            if (Files.isRegularFile(rec)) {
                JsonNode resp = SynthesisTrialHarness.JSON.readTree(Files.readString(rec));
                muse.put("model", resp.path("model").asText(null));
                JsonNode c0 = resp.path("choices").path(0);
                muse.put("finish_reason", c0.path("finish_reason").asText(null));
                muse.put("draft_raw", c0.path("message").path("content").asText(null));
                muse.put("usage", SynthesisTrialHarness.JSON.convertValue(resp.path("usage"), Map.class));
                JsonNode usage = resp.path("usage");
                if (usage.has("prompt_tokens") && usage.has("completion_tokens")) {
                    int pt = usage.path("prompt_tokens").asInt(), ct = usage.path("completion_tokens").asInt();
                    r.put("cost", Map.of("prompt_tokens", pt, "completion_tokens", ct,
                            "usd", round(pt * IN_PER_MTOK / 1e6 + ct * OUT_PER_MTOK / 1e6), "pricing_basis", PRICING));
                } else r.put("cost", Map.of("usd", "unknown: usage not reported"));
            } else muse.put("recording", "none (no 200 response saved)");
            r.put("muse", muse);
        }
        Map<String, Object> syn = new LinkedHashMap<>();
        syn.put("results_in_this_ask", run.synthesis().size());
        if (!run.synthesis().isEmpty()) {
            var s = run.synthesis().get(0);
            syn.put("outcome", s.outcome().name()); syn.put("violations", s.violations()); syn.put("version", s.synthesizerVersion());
            syn.put("validated_answer_text", s.answer().text());
        } else syn.put("outcome", "NOT_INVOKED");
        r.put("synthesis", syn);
        r.put("final_answer", SynthesisTrialHarness.answer(run.answer()));
        r.put("final_sources", SynthesisTrialHarness.sources(run.answer()));
        boolean synthesized = run.synthesized();
        r.put("synthesis_succeeded", synthesized && !MODE.equals("PREVIEW"));
        r.put("deterministic_fallback", !synthesized && run.answer().found() && run.answer().text().equals(run.baseline().text()));
        r.put("quality", h.quality(run.qid(), run.answer()));
        r.put("quality_note", synthesized ? "quality judged on the validated Muse answer Dita returned"
                : "final answer is NOT a Muse synthesis; quality here describes the deterministic/abstain result only");
        if (MODE.equals("RECORDED")) r.put("recorded_note", "replayed saved response: not a new live call");
        return r;
    }

    static double round(double d) { return Math.round(d * 1e6) / 1e6; }

    @SuppressWarnings("unchecked")
    static String review(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("# TASK-054 synthesis preview: review before any live call\n\n");
        sb.append("Prompt: ").append(PROMPT.version).append(". Backend: ").append(BACKEND).append(". Telegram OFF. Synthetic data only. Nothing was sent: the transport answered 599 and Dita fell back.\n");
        sb.append("Knowledge: replayed from the saved live EXTRACTION response (build/controlled-muse-cedar/recording.json); not a synthesis response.\n");
        sb.append("Conversation stored: m1, m2 (synthetic1, extracted), m3, m4 (synthetic2, stored and indexed, not extracted).\n");
        sb.append("Production retrieval and request data are unchanged from v1; only the system instructions differ.\n\n");
        rows.stream().map(r -> (Map<String, Object>) r.get("request")).filter(q -> q.containsKey("system_prompt")).findFirst().ifPresent(q ->
                sb.append("System instructions sent (").append(PROMPT.version).append(", sha256 `").append(q.get("system_prompt_sha256"))
                  .append("`; the JSON schema line is appended by MuseClient in PROMPT_ONLY mode):\n\n```\n").append(q.get("system_prompt")).append("\n```\n\n"));
        for (var r : rows) {
            var req = (Map<String, Object>) r.get("request");
            var sel = (Map<String, Object>) r.get("selection");
            sb.append("## ").append(r.get("qid")).append(": ").append(r.get("question")).append("\n\n");
            sb.append("- Selection outcome: ").append(sel.get("outcome")).append("; candidates ").append(sel.get("candidate_knowledge_ids"))
              .append("; selected ").append(sel.get("selected_knowledge_ids")).append("\n");
            if (!req.containsKey("sha256")) { sb.append("- NO synthesis request was produced (not found or not eligible).\n\n"); continue; }
            sb.append("- Request file: `").append(req.get("file")).append("` (").append(req.get("bytes")).append(" bytes, sha256 `").append(req.get("sha256")).append("`)\n");
            sb.append("- Destination: ").append(req.get("destination")).append("; model ").append(req.get("model"))
              .append("; max_completion_tokens ").append(req.get("max_completion_tokens")).append(" (includes reasoning); reasoning_effort ")
              .append(req.get("reasoning_effort")).append("; temperature ").append(req.get("temperature")).append("; timeout ")
              .append(SynthesisTrialHarness.TIMEOUT).append("; response_format ").append(req.get("response_format_param")).append("\n");
            sb.append("- Question sent: \"").append(req.get("question_sent")).append("\" (matches: ").append(req.get("contains_question")).append(")\n");
            sb.append("- Evidence sent (Dita-selected):\n");
            for (var e : (List<Map<String, Object>>) req.get("evidence_sent"))
                sb.append("  - [").append(e.get("id")).append("] ").append(e.get("speaker")).append(", ").append(e.get("kind")).append(", stance ")
                  .append(e.get("stance")).append(", qualified ").append(e.get("qualified")).append(": \"").append(e.get("quote")).append("\"\n");
            sb.append("- Draft sent (Dita's deterministic answer, pseudonymised):\n\n```\n").append(req.get("draft_sent")).append("\n```\n");
            if (req.containsKey("comparison_with_v1")) sb.append("- Compared with the saved v1 request: ").append(req.get("comparison_with_v1")).append("\n");
            sb.append("- conflicting=").append(req.get("conflicting_sent")).append("; replaced=").append(req.get("replaced_sent")).append("\n");
            sb.append("- Conversation messages whose text is in the request: ").append(req.get("conversation_messages_in_request")).append("\n");
            var est = (Map<String, Object>) req.get("estimate");
            sb.append("- Estimated cost: at most about $").append(est.get("max_cost_usd")).append(" (").append(est.get("prompt_tokens_upper_estimate"))
              .append(" prompt tokens upper estimate + ").append(est.get("max_output_tokens_billable")).append(" output tokens worst case). Basis: ")
              .append(est.get("pricing_basis")).append(".\n\n");
        }
        sb.append("Live: `bash scripts/muse-live.sh synthesis live <qN> ").append(dir(BACKEND)).append(" ").append(PROMPT.name().toLowerCase(Locale.ROOT))
          .append("` sends ONE request after you type 'approve'. Each further question needs its own approval.\n");
        return sb.toString();
    }
}
