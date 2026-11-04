package app.dita.core.knowledge;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * TASK-046 arm A4: local NLI cross-encoder (cross-encoder/nli-deberta-v3-xsmall @ a1508764, Apache-2.0, MNLI+SNLI), ONNX on
 * CPU. EXPERIMENT ONLY. Refuses to load unless model.onnx has the pinned sha256 (Hugging Face LFS etag). Runs fully offline;
 * no text leaves the machine. Single-threaded for determinism.
 */
final class NliModel implements AutoCloseable {
    static final Path DIR = Path.of(System.getProperty("dita.nli.dir", "../eval/TASK-046/nli/cross-encoder-nli-deberta-v3-xsmall"));
    static final String PINNED_SHA256 = "7105da41f625c42eca24e9465ec99150d02a80e644659d7a1daa93a6357155d4";
    /** N-117: the tokenizer is pinned too (MANIFEST.sha256). */
    static final String TOKENIZER_SHA256 = "5124ef2ead1a10a717703bc436de7f353da76d6340e4587719b42b1693707964";
    /** config.json id2label */
    static final int CONTRADICTION = 0, ENTAILMENT = 1, NEUTRAL = 2;

    record Scores(double contradiction, double entailment, double neutral) {}

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean tokenTypes;

    NliModel() throws Exception {
        Path model = DIR.resolve("onnx/model.onnx");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model)));
        if (!sha.equals(PINNED_SHA256)) throw new IllegalStateException("NLI model hash mismatch: refusing to load");
        String tsha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(DIR.resolve("tokenizer.json"))));
        if (!tsha.equals(TOKENIZER_SHA256)) throw new IllegalStateException("NLI tokenizer hash mismatch: refusing to load");
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1); o.setInterOpNumThreads(1);
        session = env.createSession(model.toString(), o);
        tokenizer = HuggingFaceTokenizer.newInstance(DIR.resolve("tokenizer.json"), Map.of("maxLength", "256", "truncation", "true"));
        tokenTypes = session.getInputNames().contains("token_type_ids");
    }

    private final Map<String, Scores> cache = new HashMap<>();

    /** Softmax scores for (premise, hypothesis); deterministic, so memoised by the exact input pair. */
    Scores score(String premise, String hypothesis) throws Exception {
        String key = premise + "\u0000" + hypothesis;
        Scores hit = cache.get(key);
        if (hit != null) return hit;
        Scores s = compute(premise, hypothesis);
        cache.put(key, s);
        return s;
    }

    private Scores compute(String premise, String hypothesis) throws Exception {
        Encoding e = tokenizer.encode(premise, hypothesis);
        long[][] ids = {e.getIds()}, mask = {e.getAttentionMask()}, types = {e.getTypeIds()};
        Map<String, OnnxTensor> in = new HashMap<>();
        try (OnnxTensor a = OnnxTensor.createTensor(env, ids); OnnxTensor b = OnnxTensor.createTensor(env, mask);
             OnnxTensor c = OnnxTensor.createTensor(env, types)) {
            in.put("input_ids", a); in.put("attention_mask", b);
            if (tokenTypes) in.put("token_type_ids", c);
            try (OrtSession.Result r = session.run(in)) {
                float[] logits = ((float[][]) r.get(0).getValue())[0];
                double max = Math.max(logits[0], Math.max(logits[1], logits[2]));
                double[] ex = {Math.exp(logits[0] - max), Math.exp(logits[1] - max), Math.exp(logits[2] - max)};
                double sum = ex[0] + ex[1] + ex[2];
                return new Scores(ex[CONTRADICTION] / sum, ex[ENTAILMENT] / sum, ex[NEUTRAL] / sum);
            }
        }
    }

    @Override public void close() throws Exception { session.close(); tokenizer.close(); }
}
