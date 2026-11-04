package app.dita.eval;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locations inside {@code eval/value-benchmark}. Pure path arithmetic: nothing here opens a file. Which component may
 * open which directory is enforced by {@code runner.RunnerFileAccess} (prompts/ and corpora/ only) and
 * {@code scorer.ScorerFileAccess}. Procedural control, not a security boundary.
 */
public record BenchPaths(Path projectRoot, Path bench) {
    public static final String BENCH_REL = "eval/value-benchmark";

    public BenchPaths {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        bench = bench.toAbsolutePath().normalize();
    }

    /** Uses {@code -Ddita.project.root} if set, else walks up from the working directory. */
    public static BenchPaths locate() {
        String prop = System.getProperty("dita.project.root");
        Path start = prop != null ? Path.of(prop) : Path.of("").toAbsolutePath();
        for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(BENCH_REL))) return new BenchPaths(p, p.resolve(BENCH_REL));
        }
        throw new IllegalStateException("cannot locate " + BENCH_REL + " above " + start);
    }

    /** A benchmark tree rooted somewhere else (tests use temp copies); {@code data/} is resolved against projectRoot. */
    public static BenchPaths of(Path projectRoot, Path bench) { return new BenchPaths(projectRoot, bench); }

    public Path corpora() { return bench.resolve("corpora"); }
    public Path prompts() { return bench.resolve("prompts"); }
    public Path sealed() { return bench.resolve("sealed"); }
    public Path sealedGold() { return sealed().resolve("gold"); }
    public Path results() { return bench.resolve("results"); }
    public Path schema() { return bench.resolve("schema"); }
    public Path manifest() { return bench.resolve("MANIFEST.sha256"); }
    /** Never opened by any harness component; used only to refuse paths beneath it. */
    public Path dataDir() { return projectRoot.resolve("data"); }

    public boolean isUnderData(Path p) {
        Path n = p.toAbsolutePath().normalize();
        return n.startsWith(dataDir()) || n.startsWith(bench.resolve("data"));
    }

    public String rel(Path p) { return bench.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
}
