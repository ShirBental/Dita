package app.dita.muse;

import app.dita.core.answer.synthesis.AnswerSynthesizer;

/** Test bridge: the exact user content MuseAnswerSynthesizer would send for a request (size reporting only). */
public final class MuseRequestBytes {
    private MuseRequestBytes() {}
    public static String userContent(AnswerSynthesizer.Request r) { return MuseAnswerSynthesizer.input(r).toString(); }
}
