package app.dita.core.memory;

import app.dita.core.model.GroupScope;

import java.time.Instant;
import java.util.Optional;

/** Platform-verified membership facts (Telegram: chat_member updates / getChatMember). */
public interface MembershipPort {
    Optional<Instant> joinedAt(GroupScope scope, String memberId);
}
