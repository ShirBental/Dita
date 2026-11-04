package app.dita.core.model;

/**
 * Persists a group the first time an adapter sees it (Shuri core finding #2). The in-memory store needs nothing; the
 * Postgres store needs a chat_group row before any message can reference the group (FK). Idempotent.
 * Only adapters call this, and only for chats they already accepted (allow-listed group chats; never private/channel).
 */
@FunctionalInterface
public interface GroupRegistry {
    void ensureRegistered(String groupId, String platform, String platformChatId, GroupDirectory.ChatType type, SourceKind kind);

    GroupRegistry NONE = (groupId, platform, platformChatId, type, kind) -> { };
}
