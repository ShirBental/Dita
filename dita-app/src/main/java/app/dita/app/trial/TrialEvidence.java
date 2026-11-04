package app.dita.app.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TASK-054: per-run evidence directory ({@code <evidence-dir>/<run-tag>/}). Files are created once (CREATE_NEW) and
 * owner-only where the filesystem supports it. Holds synthetic trial content only; never headers or credentials.
 * Not redacted by /forget (synthetic data only, as for the replay transcript, Natasha L-4).
 */
public final class TrialEvidence {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path root;
    private final String runTag;
    private final AtomicInteger seq = new AtomicInteger();

    public TrialEvidence(Path base, String runTag) {
        this.runTag = runTag;
        this.root = base.resolve(runTag);
        try { Files.createDirectories(root); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Path root() { return root; }
    public String runTag() { return runTag; }
    public int nextSeq() { return seq.incrementAndGet(); }

    public Path dir(String name) {
        try { return Files.createDirectories(root.resolve(name)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Path write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            return file;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Path writeJson(Path file, Object value) {
        try { return write(file, JSON.writeValueAsString(value)); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /** Path relative to the project working directory, for operator display. */
    public String display(Path p) {
        Path cwd = Path.of("").toAbsolutePath();
        Path abs = p.toAbsolutePath();
        return abs.startsWith(cwd) ? cwd.relativize(abs).toString() : abs.toString();
    }
}
