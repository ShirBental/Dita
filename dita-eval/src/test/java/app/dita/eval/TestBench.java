package app.dita.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Test helpers: synthetic temp benchmark trees. Never touches data/. */
public final class TestBench {
    private TestBench() {}

    /** Copies the real corpora/, prompts/, sealed/ and MANIFEST into {@code tmp/eval/value-benchmark}. */
    public static BenchPaths copyReal(Path tmp) throws IOException {
        BenchPaths real = BenchPaths.locate();
        Path bench = tmp.resolve(BenchPaths.BENCH_REL);
        for (String d : List.of("corpora", "prompts", "sealed")) copyTree(real.bench().resolve(d), bench.resolve(d));
        Files.copy(real.manifest(), bench.resolve("MANIFEST.sha256"));
        return BenchPaths.of(tmp, bench);
    }

    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
    }

    public static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content, StandardCharsets.UTF_8);
    }
}
