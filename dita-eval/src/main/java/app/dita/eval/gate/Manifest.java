package app.dita.eval.gate;

import app.dita.eval.BenchPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code MANIFEST.sha256}: {@code sha256sum}-compatible lines ({@code <hex>  <path relative to eval/value-benchmark>})
 * covering every file under corpora/, prompts/ and sealed/. Can be checked independently with
 * {@code shasum -a 256 -c MANIFEST.sha256} from inside eval/value-benchmark.
 */
public final class Manifest {
    public static final List<String> COVERED_DIRS = List.of("corpora", "prompts", "sealed");

    private Manifest() {}

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) d.update(buf, 0, r);
            return HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Regular files under the covered directories (symlinks are not followed and are not listed). */
    public static List<Path> coveredFiles(BenchPaths p) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String d : COVERED_DIRS) {
            Path dir = p.bench().resolve(d);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).forEach(out::add);
            }
        }
        out.sort(null);
        return out;
    }

    public static Map<String, String> compute(BenchPaths p) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (Path f : coveredFiles(p)) m.put(p.rel(f), sha256(f));
        return m;
    }

    public static String render(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        entries.forEach((rel, h) -> sb.append(h).append("  ").append(rel).append('\n'));
        return sb.toString();
    }

    public static Path write(BenchPaths p) throws IOException {
        Files.writeString(p.manifest(), render(compute(p)), StandardCharsets.UTF_8);
        return p.manifest();
    }

    /** Parses manifest text; malformed lines are returned as errors rather than silently skipped. */
    public static Map<String, String> parse(String text, List<String> errors) {
        Map<String, String> m = new LinkedHashMap<>();
        int n = 0;
        for (String line : text.split("\n")) {
            n++;
            if (line.isBlank()) continue;
            if (!line.matches("[0-9a-f]{64}  \\S.*")) { errors.add("malformed manifest line " + n); continue; }
            String rel = line.substring(66);
            if (m.put(rel, line.substring(0, 64)) != null) errors.add("duplicate manifest entry line " + n);
        }
        return m;
    }

    public static void main(String[] args) throws IOException {
        Path out = write(BenchPaths.locate());
        System.out.println("wrote " + out);
    }
}
