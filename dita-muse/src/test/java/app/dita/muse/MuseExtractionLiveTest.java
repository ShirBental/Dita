package app.dita.muse;

import app.dita.core.model.KnowledgeItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE EXTRACTION-QUALITY EVALUATION on SYNTHETIC messages (separate from connectivity). One call.
 * Skipped unless DITA_MUSE_EXTRACTION_APPROVED=yes and MODEL_API_KEY (scripts/muse-live.sh extraction).
 * Every criterion is recorded in target/muse-extraction-report.json; the test fails if any criterion fails (N-37).
 */
class MuseExtractionLiveTest {
    static final int MAX_REJECTIONS = 5;

    @Test void extractionMeetsCriteria() throws Exception {
        assumeTrue(LiveReport.approved("DITA_MUSE_EXTRACTION_APPROVED"), "extraction call not approved");
        MuseClient.resetProcessCountForTests();
        MuseSettings s = LiveSettings.extraction(System::getenv);              // same factory as the offline preview
        String model = s.model();
        MuseSettings.ResponseFormat fmt = s.responseFormat();
        MuseSettings.ReasoningEffort effort = s.reasoningEffort();
        Map<String, Object> rep = new LinkedHashMap<>();
        MuseClient client = new MuseClient(s, LiveSettings.guarded(MuseTransport.http(), rep), System::getenv);
        rep.put("test", "extraction-quality (experiment: reasoning_effort=low, PROMPT_ONLY, cap 4096)");
        rep.put("when", Instant.now().toString());
        rep.put("endpoint", MuseSettings.DOCUMENTED_URL);
        rep.put("model_requested", model);
        rep.put("response_format", fmt.name());
        rep.put("reasoning_effort", effort.wire());
        rep.put("max_completion_tokens", 4096);
        rep.put("data_sent", "SYNTHETIC parents fixture: " + Fixture.messages().size() + " messages incl. a prompt-injection attempt (m10)");
        MuseKnowledgeExtractor.Extraction x = null;
        Map<String, Boolean> criteria = new LinkedHashMap<>();
        try {
            x = new MuseKnowledgeExtractor(client).extract(Fixture.G, Fixture.messages());
            rep.put("status", "OK");
            rep.put("http_status", x.call().httpStatus());
            rep.put("usage", x.call().usage());
            rep.put("response_shape", x.call().shape());
            rep.put("model_reported", x.call().model());
            rep.put("latency_ms", x.call().latencyMs());
            LiveReport.tokens(rep, x.call().promptTokens(), x.call().completionTokens(), x.call().reasoningTokens());
            rep.put("finish_reason", x.call().finishReason());
            rep.put("request_id", x.call().requestId());
            final var ex = x;
            rep.put("accepted_items", x.items().stream().map(k -> Map.of("kind", k.kind().name(), "polarity", k.polarity(),
                    "sources", k.sourceMessageIds(), "verbatim_evidence", ex.evidence().get(k.id()),
                    "model_statement_unverified", k.statement())).toList());
            rep.put("contradictions", x.contradictions());
            rep.put("rejections", x.rejections());
            List<KnowledgeItem> recs = x.items().stream().filter(k -> k.kind() == KnowledgeItem.Kind.RECOMMENDATION).toList();
            criteria.put("at_least_one_verified_recommendation", !recs.isEmpty());
            criteria.put("contradiction_m5_vs_m2_or_m3", x.contradictions().stream().anyMatch(c -> c.messageIds().contains("m5")
                    && (c.messageIds().contains("m2") || c.messageIds().contains("m3"))));
            criteria.put("no_item_cites_injection_m10", x.items().stream().noneMatch(k -> k.sourceMessageIds().contains("m10")));
            criteria.put("no_decision_items", x.items().stream().noneMatch(k -> k.kind() == KnowledgeItem.Kind.DECISION));
            criteria.put("rejections_at_most_" + MAX_REJECTIONS, x.rejections().size() <= MAX_REJECTIONS);
            // N-41: max_tokens is an unverified field name; record whether the cap was honoured (null = no usage data).
            rep.put("max_tokens_respected", x.call().completionTokens() == null ? null : x.call().completionTokens() <= 4096);
        } catch (MuseException e) {
            rep.put("status", "ERROR");
            rep.put("error_kind", e.kind().name());
            rep.put("http_status", e.status());
            rep.put("error", e.getMessage());
            rep.put("finish_reason", e.details().finishReason());
            rep.put("usage", e.details().usage());
            rep.put("response_shape", e.details().shape());
            var u = e.details().usage();
            LiveReport.tokens(rep, u.get("prompt_tokens"), u.get("completion_tokens"), u.get("completion_tokens_details.reasoning_tokens"));
        } finally {
            rep.put("criteria", criteria);
            rep.put("calls_made", client.callsMade());
            LiveReport.write("muse-extraction-report.json", rep);
        }
        assertNotNull(x, "extraction call failed: see target/muse-extraction-report.json");
        assertEquals(1, client.callsMade());
        assertEquals(5, criteria.size());
        criteria.forEach((k, v) -> assertTrue(v, "criterion failed: " + k));
    }
}
