package app.dita.eval.runner;

import app.dita.eval.Json;
import app.dita.eval.answer.AnswerPrompt;
import app.dita.eval.answer.ModelAnswerer;
import app.dita.eval.answer.ModelRequest;
import app.dita.eval.arms.ArmContextFactory;
import app.dita.eval.arms.ArmId;
import app.dita.eval.arms.StoreReplay;
import app.dita.eval.guard.CanaryGuard;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Runs the answer loop: context → shared AnswerPrompt → CanaryGuard.check(request) → answerer. The guard runs on every
 * request, the mock's included, and a trip aborts the whole run. Rows hold ids, sizes, a request hash and the raw reply,
 * never the request text. Stage 0 only ever uses {@code MockAnswerer}; there is no live answerer.
 */
public final class AnswerRunner {
    public record Row(String qid, String arm, List<String> contextIds, int contextChars, String requestSha256,
                      String content, String finishReason, String answerer, boolean mock) {
        public ObjectNode toJson() {
            ObjectNode o = Json.object();
            o.put("qid", qid).put("arm", arm).put("backend", StoreReplay.BACKEND);
            o.set("context_ids", Json.strings(contextIds));
            o.put("context_chars", contextChars).put("request_sha256", requestSha256);
            o.put("content", content).put("finish_reason", finishReason).put("answerer", answerer).put("mock", mock);
            return o;
        }
    }

    private final ContextPlanner planner;
    private final CanaryGuard guard;
    private final ModelAnswerer answerer;

    public AnswerRunner(ContextPlanner planner, CanaryGuard guard, ModelAnswerer answerer) {
        this.planner = planner; this.guard = guard; this.answerer = answerer;
    }

    public List<Row> run(String split, List<ArmId> arms) {
        List<Row> rows = new ArrayList<>();
        for (Prompt p : planner.prompts(split)) {
            for (ArmId arm : arms) {
                ArmContextFactory.ArmContext ctx = planner.context(arm, p);
                ModelRequest req = AnswerPrompt.render(p, arm, ctx.context().json());
                guard.check(req);
                ModelAnswerer.Reply reply = answerer.answer(req);
                rows.add(new Row(p.qid(), arm.label, ctx.context().ids(), ctx.context().chars(), sha256(req.body()),
                        reply.content(), reply.finishReason(), answerer.name(), answerer.isMock()));
            }
        }
        return rows;
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
