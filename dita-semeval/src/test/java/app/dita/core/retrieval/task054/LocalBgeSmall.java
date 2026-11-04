package app.dita.core.retrieval.task054;

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
 * LOCAL reproduction of the deployed Vespa "dita-english" hugging-face-embedder: the same frozen BAAI/bge-small-en-v1.5
 * files (TASK-049 hashes, copied into the built package), CLS pooling, L2 normalisation, max 512 tokens, and the same
 * query prefix. It runs on CPU, offline; nothing leaves the machine. It is an EMULATION of Vespa's embedder (numerics
 * may differ in the last digits), never a substitute for the gated real-stack test.
 */
final class LocalBgeSmall implements AutoCloseable {
    static final Path DIR = Path.of(System.getProperty("dita.bge.dir", "../build/task054-semantic/package/models"));
    static final String MODEL_SHA = "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35";
    static final String TOKENIZER_SHA = "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66";
    static final String QUERY_PREFIX = "Represent this sentence for searching relevant passages: ";

    static boolean available() { return Files.isRegularFile(DIR.resolve("model.onnx")) && Files.isRegularFile(DIR.resolve("tokenizer.json")); }

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final Map<String, float[]> cache = new HashMap<>();

    LocalBgeSmall() throws Exception {
        if (!sha(DIR.resolve("model.onnx")).equals(MODEL_SHA)) throw new IllegalStateException("bge-small model hash mismatch: refusing to load");
        if (!sha(DIR.resolve("tokenizer.json")).equals(TOKENIZER_SHA)) throw new IllegalStateException("bge-small tokenizer hash mismatch");
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1); o.setInterOpNumThreads(1);
        session = env.createSession(DIR.resolve("model.onnx").toString(), o);
        tokenizer = HuggingFaceTokenizer.newInstance(DIR.resolve("tokenizer.json"), Map.of("maxLength", "512", "truncation", "true"));
    }

    private static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    float[] document(String text) throws Exception { return embed(text); }
    float[] query(String text) throws Exception { return embed(QUERY_PREFIX + text); }

    synchronized float[] embed(String text) throws Exception {
        float[] hit = cache.get(text);
        if (hit != null) return hit;
        Encoding e = tokenizer.encode(text);
        long[][] ids = {e.getIds()}, mask = {e.getAttentionMask()}, types = {e.getTypeIds()};
        Map<String, OnnxTensor> in = new HashMap<>();
        try (OnnxTensor a = OnnxTensor.createTensor(env, ids); OnnxTensor b = OnnxTensor.createTensor(env, mask);
             OnnxTensor c = OnnxTensor.createTensor(env, types)) {
            in.put("input_ids", a); in.put("attention_mask", b);
            if (session.getInputNames().contains("token_type_ids")) in.put("token_type_ids", c);
            try (OrtSession.Result r = session.run(in)) {
                float[][][] hidden = (float[][][]) r.get(0).getValue();
                float[] cls = hidden[0][0].clone();
                double n = 0; for (float v : cls) n += v * v; n = Math.sqrt(n);
                for (int i = 0; i < cls.length; i++) cls[i] /= (float) n;
                cache.put(text, cls);
                return cls;
            }
        }
    }

    static double dot(float[] a, float[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }

    @Override public void close() throws Exception { session.close(); tokenizer.close(); }
}
