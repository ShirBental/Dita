package app.dita.core.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single place a platform chat becomes a {@link GroupScope} (Natasha N-4). Private chats and channels never
 * get a scope; chat-id migrations (Telegram group → supergroup) map to the same group; only ACTIVE groups
 * resolve. Adapters must obtain scopes here, never by constructing GroupScope from message content.
 * In-memory reference; the Postgres implementation reads chat_group + chat_alias.
 */
public final class GroupDirectory {
    public enum ChatType { GROUP, SUPERGROUP, PRIVATE, CHANNEL, SYNTHETIC }
    private record Key(String platform, String chatId) {}

    private final Map<Key, String> aliases = new HashMap<>();
    private final Function<String, Boolean> isActive;

    public GroupDirectory(Function<String, Boolean> isActive) { this.isActive = isActive; }

    public void register(String platform, String chatId, ChatType type, String groupId) {
        requireGroupType(type);
        if (aliases.putIfAbsent(new Key(platform, chatId), groupId) != null) {
            throw new IllegalStateException("chat already registered: " + platform + "/" + chatId);
        }
    }

    /** Telegram migrate_to_chat_id: the new id resolves to the same group's memory. */
    public void migrate(String platform, String oldChatId, String newChatId) {
        String g = aliases.get(new Key(platform, oldChatId));
        if (g == null) throw new IllegalStateException("unknown chat " + oldChatId);
        aliases.putIfAbsent(new Key(platform, newChatId), g);
    }

    public Optional<GroupScope> resolve(String platform, String chatId, ChatType type) {
        if (type == ChatType.PRIVATE || type == ChatType.CHANNEL) return Optional.empty();
        String g = aliases.get(new Key(platform, chatId));
        if (g == null || !Boolean.TRUE.equals(isActive.apply(g))) return Optional.empty();
        return Optional.of(new GroupScope(g));
    }

    private static void requireGroupType(ChatType t) {
        if (t == ChatType.PRIVATE || t == ChatType.CHANNEL) throw new IllegalArgumentException("not a group chat: " + t);
    }
}
