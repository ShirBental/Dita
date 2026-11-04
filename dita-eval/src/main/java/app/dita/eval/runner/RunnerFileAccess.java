package app.dita.eval.runner;

import app.dita.eval.BenchPaths;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.guard.LeakageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The only way runner code touches the filesystem (N-50). Reads are allowed only beneath {@code prompts/} and
 * {@code corpora/}; writes only beneath {@code results/}. {@code sealed/}, {@code data/} and everything else are
 * refused. This is a procedural/application control, not a security boundary.
 */
public final class RunnerFileAccess {
    private final BenchPaths paths;

    public RunnerFileAccess(BenchPaths paths) { this.paths = paths; }

    public BenchPaths paths() { return paths; }

    public List<Prompt> prompts(String split) {
        if (!split.matches("[a-z0-9-]+")) throw new IllegalArgumentException("bad split name");
        return Prompt.parseAll(readText(paths.prompts().resolve(split + ".jsonl")));
    }

    public Corpus corpus(String corpusId) {
        if (!corpusId.matches(Corpus.ID_PATTERN)) throw new IllegalArgumentException("bad corpus id");
        return Corpus.parse(corpusId, readText(paths.corpora().resolve(corpusId + ".jsonl")));
    }

    /** The isolation-decoy sibling, if present. It is loaded into its own group partition and is never a query scope. */
    public Optional<Corpus> decoyFor(String corpusId) {
        Path p = paths.corpora().resolve(corpusId + "-decoy.jsonl");
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Optional.of(corpus(corpusId + "-decoy")) : Optional.empty();
    }

    /** Reads a UTF-8 file iff it lies beneath prompts/ or corpora/ (after resolving symlinks); throws otherwise. */
    public String readText(Path p) {
        Path checked = checkReadable(p);
        try { return Files.readString(checked, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("cannot read " + paths.rel(checked), e); }
    }

    Path checkReadable(Path p) {
        Path n = p.toAbsolutePath().normalize();
        if (paths.isUnderData(n)) throw new LeakageException("runner may not open paths under data/");
        if (!(n.startsWith(paths.prompts()) || n.startsWith(paths.corpora())))
            throw new LeakageException("runner may only open prompts/ and corpora/, refused: " + safeRel(n));
        try {
            Path real = n.toRealPath();
            Path rp = paths.prompts().toRealPath(), rc = paths.corpora().toRealPath();
            if (!(real.startsWith(rp) || real.startsWith(rc))) throw new LeakageException("symlink escapes prompts/ or corpora/: " + safeRel(n));
            return real;
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve " + safeRel(n), e);
        }
    }

    public Path writeResult(String fileName, String content) {
        if (!fileName.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("bad result file name");
        Path out = paths.results().resolve(fileName).normalize();
        if (!out.startsWith(paths.results())) throw new LeakageException("runner writes only to results/");
        try {
            Files.createDirectories(paths.results());
            Files.writeString(out, content, StandardCharsets.UTF_8);
            return out;
        } catch (IOException e) { throw new IllegalStateException("cannot write result", e); }
    }

    private String safeRel(Path n) { return n.startsWith(paths.bench()) ? paths.rel(n) : "<outside benchmark>"; }
}
