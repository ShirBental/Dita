package app.dita.core.events;

/** Transactional outbox port: in Postgres the row is written in the caller's transaction. */
public interface EventPublisher {
    long publish(String groupId, Object payload);

    EventPublisher NONE = (g, p) -> -1L;
}
