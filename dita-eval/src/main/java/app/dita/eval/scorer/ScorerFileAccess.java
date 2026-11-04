package app.dita.eval.scorer;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.gate.SyntheticDataGate;
import app.dita.eval.guard.LeakageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scorer-side file access: runs after the runner, on saved outputs. It may read sealed gold, corpora, prompts and
 * results, never {@code data/}. Before any gold is read, the gate (manifest hashes plus the synthetic-data checks) is
 * re-run, so a sealed file changed since the manifest was frozen fails the scoring.
 */
public final class ScorerFileAccess {
    private final BenchPaths paths;

    public ScorerFileAccess(BenchPaths paths) { this.paths = paths; }

    public BenchPaths paths() { return paths; }

    public void verifyManifestOrThrow() throws IOException {
        SyntheticDataGate.Report r = new SyntheticDataGate(paths).run();
        if (!r.passed()) throw new IllegalStateException("gate failed at scoring time: " + r.violations());
    }

    public String read(Path p) {
        Path n = p.toAbsolutePath().normalize();
        if (paths.isUnderData(n)) throw new LeakageException("scorer may not open paths under data/");
        if (!n.startsWith(paths.bench())) throw new LeakageException("scorer reads only inside eval/value-benchmark");
        try { return Files.readString(n, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("cannot read " + paths.rel(n), e); }
    }

    public Map<String, GoldLabel> gold(String split) {
        if (!split.matches("[a-z0-9-]+")) throw new IllegalArgumentException("bad split");
        Map<String, GoldLabel> out = new LinkedHashMap<>();
        for (String line : read(paths.sealedGold().resolve(split + ".jsonl")).split("\n")) {
            if (line.isBlank()) continue;
            GoldLabel g = GoldLabel.parse(Json.parse(line));
            if (out.put(g.qid(), g) != null) throw new IllegalStateException("duplicate gold qid " + g.qid());
        }
        return out;
    }

    public Corpus corpus(String id) {
        if (!id.matches(Corpus.ID_PATTERN)) throw new IllegalArgumentException("bad corpus id");
        return Corpus.parse(id, read(paths.corpora().resolve(id + ".jsonl")));
    }

    public String result(String name) {
        if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("bad result name");
        return read(paths.results().resolve(name));
    }

    public Path writeResult(String name, String content) throws IOException {
        if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("bad result name");
        Files.createDirectories(paths.results());
        return Files.writeString(paths.results().resolve(name), content, StandardCharsets.UTF_8);
    }

    public List<String> promptsRaw(String split) {
        return List.of(read(paths.prompts().resolve(split + ".jsonl")).split("\n"));
    }
}
