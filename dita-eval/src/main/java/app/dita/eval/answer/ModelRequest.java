package app.dita.eval.answer;

import app.dita.eval.arms.ArmId;

/**
 * One planned model request. {@code body} is the exact serialized request body that would be sent; CanaryGuard checks
 * it in full. {@code fixedText} is the arm-independent prompt text (system prompt + user template scaffolding).
 */
public record ModelRequest(String qid, ArmId arm, String question, String contextJson, String fixedText, String body,
                           int maxCompletionTokens) {
    @Override public String toString() { return "ModelRequest[qid=" + qid + ", arm=" + arm + ", body=<" + body.length() + " chars>]"; }
}
