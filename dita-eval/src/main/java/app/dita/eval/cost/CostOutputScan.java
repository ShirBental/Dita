package app.dita.eval.cost;

import app.dita.eval.BenchPaths;
import app.dita.eval.gate.SyntheticDataGate;
import app.dita.eval.guard.CanaryGuard;
import app.dita.eval.guard.Sentinels;
import app.dita.eval.scorer.ScorerFileAccess;
import app.dita.eval.scorer.SentinelSealer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * N-58 scans over cost outputs (usage, break-even and preliminary-analysis files). Findings name the rule, file and
 * line, never the matched text. Rules:
 * <ul>
 *   <li>SyntheticDataGate PII patterns (phone, e-mail, 9-digit id, URL, domain);</li>
 *   <li>canary-shaped tokens and hashed sealed-gold canaries (word n-grams);</li>
 *   <li>gold label field names;</li>
 *   <li>key-like strings ({@code sk-...}, bearer tokens, MODEL_API_KEY assignments, long opaque tokens that are not
 *       64-hex hashes) and raw request-id UUIDs (N-60);</li>
 *   <li>usage files must load under the numbers-only allowlist (N-55);</li>
 *   <li>no claim wording: the word "cheaper" must not appear in a report (only numbers and assumptions are reported).</li>
 * </ul>
 * A tripwire, not a guarantee.
 */
public final class CostOutputScan {
    public record Finding(String rule, String file, int line) {
        @Override public String toString() { return rule + " @ " + file + (line > 0 ? ":" + line : ""); }
    }

    static final Map<String, Pattern> KEYISH = new LinkedHashMap<>();
    static {
        KEYISH.put("KEY_SK", Pattern.compile("sk-[A-Za-z0-9_-]{6,}"));
        KEYISH.put("KEY_BEARER", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}"));
        KEYISH.put("KEY_ENV_ASSIGN", Pattern.compile("\\b[A-Z0-9_]*(?:API_KEY|SECRET|TOKEN)\\s*[=:]\\s*\\S{6,}"));
        KEYISH.put("RAW_REQUEST_ID_UUID", Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"));
    }
    /** Long opaque runs that mix upper case, lower case and digits (key-like); snake_case words and hex hashes are not. */
    static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_+/=-]{32,}");
    /** The one URL an SVG must carry; stripped before the PII scan (exact attribute only). */
    static final String SVG_NS = "xmlns=\"http://www.w3.org/2000/svg\"";
    static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern CLAIM = Pattern.compile("(?i)\\bcheaper\\b");

    private CostOutputScan() {}

    public static List<Finding> scanText(String rel, String text, Sentinels sentinels, boolean usageFile, boolean report) {
        List<Finding> out = new ArrayList<>();
        String piiText = rel.endsWith(".svg") ? text.replace(SVG_NS, "") : text;
        SyntheticDataGate.scanText(rel, piiText, Set.of()).forEach(v -> out.add(new Finding("PII_" + v.rule(), rel, v.line())));
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (CanaryGuard.CANARY_SHAPE.matcher(l).find()) out.add(new Finding("CANARY_SHAPE", rel, i + 1));
            for (String m : CanaryGuard.LABEL_MARKERS) if (l.contains(m)) out.add(new Finding("GOLD_LABEL_FIELD", rel, i + 1));
            for (Map.Entry<String, Pattern> e : KEYISH.entrySet()) if (e.getValue().matcher(l).find()) out.add(new Finding(e.getKey(), rel, i + 1));
            Matcher m = OPAQUE.matcher(l);
            while (m.find()) if (keyLike(m.group())) { out.add(new Finding("KEY_OPAQUE_TOKEN", rel, i + 1)); break; }
            if (report && CLAIM.matcher(l).find()) out.add(new Finding("CLAIM_WORDING", rel, i + 1));
        }
        if (sentinels != null && !sentinels.canaryHashes().isEmpty()) {
            Set<String> grams = Sentinels.ngramHashes(text);
            for (String h : sentinels.canaryHashes()) if (grams.contains(h)) { out.add(new Finding("SEALED_CANARY", rel, 0)); break; }
        }
        if (usageFile) {
            try { UsageJsonl.parse(text); }
            catch (IllegalArgumentException e) { out.add(new Finding("USAGE_NOT_NUMBERS_ONLY", rel, 0)); }
        }
        return out;
    }

    static boolean keyLike(String t) {
        if (HEX64.matcher(t).matches()) return false;
        boolean up = false, low = false, dig = false;
        for (char c : t.toCharArray()) { up |= Character.isUpperCase(c); low |= Character.isLowerCase(c); dig |= Character.isDigit(c); }
        return up && low && dig;
    }

    /** Sentinels (hashes only) from the sealed dev gold, read scorer-side. */
    public static Sentinels sealedSentinels(BenchPaths p) {
        return SentinelSealer.seal(new ScorerFileAccess(p).gold("dev").values());
    }

    /** The cost output files, relative to the benchmark root. Missing files are reported as findings. */
    public static final List<String> OUTPUTS = List.of("usage/recorded-usage.jsonl", "usage/unledgered-usage.jsonl", "usage/MANIFEST.sha256",
            "results/cost-prelim.json", "results/cost-prelim.md", "charts/cost-vs-questions.svg");

    public static List<Finding> run(BenchPaths p) throws IOException {
        Sentinels s = sealedSentinels(p);
        List<Finding> out = new ArrayList<>();
        for (String rel : OUTPUTS) {
            Path f = p.bench().resolve(rel);
            if (!Files.isRegularFile(f)) { out.add(new Finding("MISSING_OUTPUT", rel, 0)); continue; }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            out.addAll(scanText(rel, text, s, rel.startsWith("usage/") && rel.endsWith(".jsonl"), rel.startsWith("results/") || rel.startsWith("charts/")));
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        List<Finding> f = run(BenchPaths.locate());
        System.out.println("CostOutputScan: " + (f.isEmpty() ? "PASS" : "FAIL") + ", files=" + OUTPUTS.size() + ", findings=" + f.size());
        f.forEach(x -> System.out.println("  " + x));
        if (!f.isEmpty()) System.exit(1);
    }
}
