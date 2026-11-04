package app.dita.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * TASK-051 observability: the transcript file of a SYNTHETIC replay run. Only {@link ReplayTelegramGateway} creates one,
 * so live Telegram mode never writes it. Each record is one JSON line, written and forced to disk at once, so the file is
 * complete even if the process is killed. A readable copy goes to {@code <name>.txt} and, when asked, to the terminal.
 * <p>Holds only what the replay gateway already sees: the synthetic incoming update and Dita's outgoing text. It never
 * holds credentials, Muse requests or Muse responses. It is append-only for the run and is NOT redacted by /forget: an
 * accepted exception to NO_RESTORATION_OF_DELETED_TEXT for synthetic fixtures only (Natasha L-4); never for live chats.
 * Files are opened without following symlinks and created owner-only (rw-------) (Natasha L-1, L-2).
 */
final class ReplayTranscript implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger log = System.getLogger(ReplayTranscript.class.getName());

    private final FileChannel jsonl, text;
    private final PrintStream echo;          // null = no terminal output
    private boolean failed, closed;

    /** Truncates any earlier transcript at {@code jsonlFile} (one run per file; the run tag is in every record). */
    ReplayTranscript(Path jsonlFile, PrintStream echo) {
        try {
            Path abs = jsonlFile.toAbsolutePath();
            if (abs.getParent() != null) Files.createDirectories(abs.getParent());
            FileChannel j = open(abs), t;
            try { t = open(readableSibling(abs)); }
            catch (IOException | RuntimeException e) { j.close(); throw e; }      // Natasha L-3: no leaked stream
            this.jsonl = j; this.text = t;
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("replay transcript file is not writable (path not shown): " + e.getClass().getSimpleName());
        }
        this.echo = echo;
    }

    private static FileChannel open(Path p) throws IOException {
        var opts = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        try { return FileChannel.open(p, opts, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException e) { return FileChannel.open(p, opts); }   // non-POSIX file system
    }

    /** Readable form of a transcript file: {@code transcript.jsonl} gives {@code transcript.txt}. */
    static Path readableSibling(Path jsonlFile) {
        String name = jsonlFile.getFileName().toString();
        return jsonlFile.resolveSibling((name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name) + ".txt");
    }

    synchronized void write(ObjectNode record, String readable) {
        if (closed) return;
        try {
            writeFully(jsonl, JSON.writeValueAsString(record) + "\n");
            if (readable != null) writeFully(text, readable);
        } catch (IOException e) {
            if (!failed) log.log(System.Logger.Level.WARNING, "replay transcript write failed: " + e.getClass().getSimpleName());
            failed = true;
        }
        if (readable != null && echo != null) { echo.print(readable); echo.flush(); }
    }

    private static void writeFully(FileChannel ch, String s) throws IOException {
        var buf = java.nio.ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) ch.write(buf);
        ch.force(true);                                                    // on disk before the next record
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (FileChannel ch : new FileChannel[] {jsonl, text}) {
            try { ch.force(true); } catch (IOException ignored) { }
            try { ch.close(); } catch (IOException ignored) { }
        }
    }
}
