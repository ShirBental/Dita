package app.dita.core.fixture;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads synthetic fixtures. Line format: {@code id|author|minutesFromStart|replyToId|text}; '#' comments.
 * Every message produced is marked {@link SourceKind#SYNTHETIC}.
 */
public final class SyntheticConversation {
    private SyntheticConversation() {}

    public static List<Message> load(GroupScope scope, Instant start, InputStream in) throws IOException {
        List<Message> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|", 5);
                if (f.length != 5) throw new IOException("bad fixture line: " + line);
                out.add(new Message(f[0], scope.groupId(), f[1], start.plusSeconds(Long.parseLong(f[2]) * 60),
                        f[3].isBlank() ? null : f[3], f[4], SourceKind.SYNTHETIC, false));
            }
        }
        return out;
    }
}
