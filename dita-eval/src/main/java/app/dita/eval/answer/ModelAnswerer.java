package app.dita.eval.answer;

/** Answers one guarded request. Implementations: {@link MockAnswerer} (offline plumbing) and {@link MuseAnswererStub}. */
public interface ModelAnswerer {
    /** Raw model reply: {@code content} is the text the model returned; {@code finishReason} e.g. "stop" or "length". */
    record Reply(String content, String finishReason, Integer promptTokens, Integer completionTokens) {}

    String name();

    /** True for answerers whose scores are plumbing checks and must never be reported as results. */
    boolean isMock();

    Reply answer(ModelRequest request);
}
