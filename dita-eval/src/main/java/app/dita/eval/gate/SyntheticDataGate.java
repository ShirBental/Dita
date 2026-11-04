package app.dita.eval.gate;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.corpus.Corpus;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * N-46: proves (as far as patterns can) that benchmark data is synthetic, before any run. It fails on:
 * <ul>
 *   <li>phone numbers (Israeli 0X/05X forms, +972, E.164), except allowlisted canaries: strings of the obviously fake
 *       form {@code 05X-0000-NNN} that are declared in some gold record's {@code forbidden_strings};</li>
 *   <li>e-mail addresses, 9-digit ID-like numbers and URLs/domains;</li>
 *   <li>corpus-shaped files outside {@code corpora/};</li>
 *   <li>any covered file (corpora/, prompts/, sealed/) whose hash is not in {@code MANIFEST.sha256}, plus stale or
 *       unsafe manifest entries;</li>
 *   <li>any path under {@code data/} (refused by path, never opened) and any symlink.</li>
 * </ul>
 * Findings report rule, file and line, never the matched text. Pattern checks cannot prove the absence of private
 * data. They are a tripwire, not a guarantee.
 */
public final class SyntheticDataGate {
    public record Violation(String rule, String path, int line) {
        @Override public String toString() { return rule + " @ " + path + (line > 0 ? ":" + line : ""); }
    }

    public record Report(List<Violation> violations, int filesScanned, Set<String> allowlistedCanaries) {
        public boolean passed() { return violations.isEmpty(); }
    }

    static final Map<String, Pattern> PII = new LinkedHashMap<>();
    static {
        PII.put("PHONE_IL", Pattern.compile("(?<![\\d+])0[2-9]\\d?(?:[-\\s.]?\\d){7}(?!\\d)"));
        PII.put("PHONE_972", Pattern.compile("\\+\\s?972"));
        PII.put("PHONE_E164", Pattern.compile("\\+[1-9](?:[\\s-]?\\d){7,14}(?!\\d)"));
        PII.put("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"));
        PII.put("ID_9_DIGITS", Pattern.compile("(?<![\\d])\\d{9}(?!\\d)"));
        PII.put("URL", Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+"));
        PII.put("DOMAIN", Pattern.compile("(?i)\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|io|ai|info|biz|app|dev|co\\.il|org\\.il|gov\\.il|ac\\.il|il)\\b"));
    }
    /** The only phone shape that may be allowlisted: obviously fake, with a zero block. */
    public static final Pattern FAKE_CANARY = Pattern.compile("^05\\d-0000-\\d{3}$");
    private static final Pattern STRIP = Pattern.compile("DITA-CANARY-[0-9A-Fa-f]{16}|\\b[0-9a-f]{64}\\b");
    private static final Set<String> SCANNED_DIRS = Set.of("corpora", "prompts", "sealed", "results");
    private static final Set<String> TEXT_EXT = Set.of(".jsonl", ".json", ".txt", ".md", ".csv", ".sha256");

    private final BenchPaths p;

    public SyntheticDataGate(BenchPaths paths) { this.p = paths; }

    public Report run() throws IOException { return run(List.of()); }

    /** {@code extraInputs}: any additional paths a run is configured to use; each must be inside corpora/ and not under data/. */
    public Report run(List<Path> extraInputs) throws IOException {
        List<Violation> v = new ArrayList<>();
        for (Path x : extraInputs) {
            Path n = x.toAbsolutePath().normalize();
            if (p.isUnderData(n)) v.add(new Violation("PATH_UNDER_DATA", "<refused>", 0));
            else if (!n.startsWith(p.corpora())) v.add(new Violation("CORPUS_OUTSIDE_ALLOWLIST", relOrOutside(n), 0));
        }
        if (p.isUnderData(p.bench())) { v.add(new Violation("PATH_UNDER_DATA", "<benchmark root>", 0)); return new Report(v, 0, Set.of()); }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(p.bench())) {
            s.forEach(f -> {
                if (p.isUnderData(f)) { v.add(new Violation("PATH_UNDER_DATA", "<refused>", 0)); return; }
                if (Files.isSymbolicLink(f)) { v.add(new Violation("SYMLINK", p.rel(f), 0)); return; }
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) files.add(f);
            });
        }
        Set<String> canaries = allowlistedCanaries(v);
        Set<String> canaryDigits = new HashSet<>();
        canaries.forEach(c -> canaryDigits.add(c.replaceAll("\\D", "")));
        int scanned = 0;
        for (Path f : files) {
            String rel = p.rel(f);
            String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
            String name = f.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            if (!TEXT_EXT.contains(ext)) {
                if (SCANNED_DIRS.contains(top)) v.add(new Violation("NON_TEXT_FILE", rel, 0));
                continue;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (!top.equals("corpora") && looksLikeCorpus(text)) v.add(new Violation("CORPUS_OUTSIDE_ALLOWLIST", rel, 0));
            if (top.equals("corpora") && name.endsWith(".jsonl")) {
                try { Corpus.parse(name.substring(0, name.length() - 6), text); }
                catch (RuntimeException e) { v.add(new Violation("CORPUS_UNPARSEABLE", rel, 0)); }
            }
            if (SCANNED_DIRS.contains(top)) { v.addAll(scanText(rel, text, canaryDigits)); scanned++; }
        }
        v.addAll(checkManifest());
        return new Report(List.copyOf(v), scanned, canaries);
    }

    /** Pattern scan of one text; phone matches whose digits equal an allowlisted canary's digits are permitted. */
    public static List<Violation> scanText(String rel, String text, Set<String> allowedCanaryDigits) {
        List<Violation> out = new ArrayList<>();
        String[] lines = STRIP.matcher(text).replaceAll(" ").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Map.Entry<String, Pattern> e : PII.entrySet()) {
                Matcher m = e.getValue().matcher(lines[i]);
                while (m.find()) {
                    boolean phone = e.getKey().startsWith("PHONE");
                    if (phone && allowedCanaryDigits.contains(m.group().replaceAll("\\D", ""))) continue;
                    out.add(new Violation(e.getKey(), rel, i + 1));
                }
            }
        }
        return out;
    }

    /** Fake canaries declared in gold forbidden_strings. A declared string that is not of the fake form is a violation. */
    private Set<String> allowlistedCanaries(List<Violation> v) throws IOException {
        Set<String> out = new HashSet<>();
        Path gold = p.sealedGold();
        if (!Files.isDirectory(gold, LinkOption.NOFOLLOW_LINKS)) return out;
        try (Stream<Path> s = Files.list(gold)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".jsonl") && Files.isRegularFile(x, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                int n = 0;
                for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\n")) {
                    n++;
                    if (line.isBlank()) continue;
                    JsonNode r;
                    try { r = Json.parse(line); } catch (IllegalArgumentException e) { v.add(new Violation("GOLD_UNPARSEABLE", p.rel(f), n)); continue; }
                    for (JsonNode fs : r.path("forbidden_strings")) {
                        String s1 = fs.asText();
                        boolean phoneLike = PII.get("PHONE_IL").matcher(s1).find() || PII.get("PHONE_E164").matcher(s1).find();
                        if (!phoneLike) continue;
                        if (FAKE_CANARY.matcher(s1).matches()) out.add(s1);
                        else v.add(new Violation("CANARY_NOT_OBVIOUSLY_FAKE", p.rel(f), n));
                    }
                }
            }
        }
        return out;
    }

    static boolean looksLikeCorpus(String text) {
        int n = 0;
        for (String line : text.split("\n")) {
            if (++n > 200) break;
            String t = line.strip();
            if (t.matches("[A-Za-z0-9_-]+\\|[^|]+\\|\\d+\\|[^|]*\\|.+")) return true;   // legacy fixture format
            if (t.startsWith("{") && t.contains("\"author\"") && t.contains("\"text\"") && t.contains("\"sent_at\"")) return true;
        }
        return false;
    }

    List<Violation> checkManifest() throws IOException {
        List<Violation> v = new ArrayList<>();
        if (!Files.isRegularFile(p.manifest(), LinkOption.NOFOLLOW_LINKS)) {
            v.add(new Violation("MANIFEST_MISSING", "MANIFEST.sha256", 0));
            return v;
        }
        List<String> errors = new ArrayList<>();
        Map<String, String> listed = Manifest.parse(Files.readString(p.manifest(), StandardCharsets.UTF_8), errors);
        errors.forEach(e -> v.add(new Violation("MANIFEST_MALFORMED", "MANIFEST.sha256", 0)));
        for (String rel : listed.keySet()) {
            Path f = p.bench().resolve(rel).normalize();
            if (rel.startsWith("/") || rel.contains("..") || !f.startsWith(p.bench())) { v.add(new Violation("MANIFEST_UNSAFE_PATH", "MANIFEST.sha256", 0)); continue; }
            if (p.isUnderData(f) || rel.startsWith("data/")) { v.add(new Violation("PATH_UNDER_DATA", "MANIFEST.sha256", 0)); continue; }
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) v.add(new Violation("MANIFEST_ENTRY_MISSING_FILE", rel, 0));
        }
        for (Path f : Manifest.coveredFiles(p)) {
            String rel = p.rel(f);
            String expected = listed.get(rel);
            if (expected == null) v.add(new Violation("HASH_NOT_IN_MANIFEST", rel, 0));
            else if (!expected.equals(Manifest.sha256(f))) v.add(new Violation("HASH_MISMATCH", rel, 0));
        }
        return v;
    }

    private String relOrOutside(Path n) { return n.startsWith(p.bench()) ? p.rel(n) : "<outside benchmark>"; }

    public static void main(String[] args) throws IOException {
        Report r = new SyntheticDataGate(BenchPaths.locate()).run();
        System.out.println("SyntheticDataGate: " + (r.passed() ? "PASS" : "FAIL") + ", text files scanned=" + r.filesScanned()
                + ", allowlisted canaries=" + r.allowlistedCanaries().size());
        r.violations().forEach(x -> System.out.println("  " + x));
        if (!r.passed()) System.exit(1);
    }
}
