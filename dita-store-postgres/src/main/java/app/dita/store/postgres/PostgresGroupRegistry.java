package app.dita.store.postgres;

import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupRegistry;
import app.dita.core.model.SourceKind;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * chat_group registration (no RLS on chat_group: GroupDirectory reads across groups). Idempotent: an existing row for
 * the same id or the same (platform, platform_chat_id) is left unchanged. Private chats and channels are refused,
 * matching the chat_type CHECK in V1 (N-4).
 */
public final class PostgresGroupRegistry implements GroupRegistry {
    private final JdbcTemplate jdbc;

    public PostgresGroupRegistry(DataSource ds) { this.jdbc = new JdbcTemplate(ds); }

    @Override public void ensureRegistered(String groupId, String platform, String platformChatId,
                                           GroupDirectory.ChatType type, SourceKind kind) {
        Objects.requireNonNull(groupId); Objects.requireNonNull(platform); Objects.requireNonNull(platformChatId);
        String chatType = switch (type) {
            case GROUP -> "group";
            case SUPERGROUP -> "supergroup";
            case SYNTHETIC -> "synthetic";
            case PRIVATE, CHANNEL -> throw new IllegalArgumentException("private chats and channels are never registered (N-4)");
        };
        jdbc.update("INSERT INTO chat_group (id, platform, platform_chat_id, chat_type, source_kind) VALUES (?, ?, ?, ?, ?::source_kind) "
                + "ON CONFLICT DO NOTHING", groupId, platform, platformChatId, chatType, kind.name());
    }
}
