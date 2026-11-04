package app.dita.app.trial;

import java.util.List;

/** TASK-054: decides ONE pending Muse call. Implementations must fail closed (anything but an explicit approval refuses). */
public interface CallApprover {
    enum Decision { APPROVED, REFUSED, TIMEOUT, UNAVAILABLE }

    /** Who/how the last decision for {@code callId} was made (recorded in approval.json). Default: nothing extra. */
    default java.util.Map<String, Object> decisionDetails(String callId) { return java.util.Map.of(); }

    /**
     * Everything the operator needs to judge one call. {@code requestFile}/{@code sha256}: the exact bytes that would be
     * sent. {@code summary}: human-readable outbound data (statements or question + evidence). No credential is ever here.
     */
    record PendingCall(String callId, TrialContext.Kind kind, String correlation, String requestFile, String sha256, int bytes,
                       String destination, String model, int maxCompletionTokens, String reasoningEffort, String timeout,
                       int promptTokensUpperEstimate, double maxCostUsd, String pricingBasis, List<String> summary) {
        public PendingCall { summary = List.copyOf(summary); }
        /**
         * What the operator must type: "approve <call number>-<first 8 hex of the request SHA-256>", e.g. "approve 003-1a2b3c4d".
         * The call number makes the phrase unique per call even when a repeated question produces identical bytes (Natasha F5).
         */
        public String approvalPhrase() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^call-(\\d+)").matcher(callId);
            return "approve " + (m.find() ? m.group(1) + "-" : callId + "-") + sha256.substring(0, 8);
        }
    }

    Decision decide(PendingCall call);
}
