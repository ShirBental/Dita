package app.dita.core.store;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.GroupScope;
import app.dita.core.model.AnswerRecord;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.MessageLink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Partitioned by group: there is no code path that reads another group's partition. */
public final class InMemoryMessageStore implements MessageStore {
    private final Map<String, Map<String, Message>> messages = new HashMap<>();
    private final Map<String, List<MessageEvent>> events = new HashMap<>();
    private final Map<String, Map<String, MessageLink>> links = new HashMap<>();
    private final Map<String, Map<String, KnowledgeItem>> knowledge = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> ditaReplies = new HashMap<>();
    private final Map<String, Map<String, KnowledgeRelation>> relations = new HashMap<>();
    private final Map<String, Map<String, AnswerRecord>> answers = new HashMap<>();
    private Map<String, KnowledgeRelation> rel(GroupScope s) { return relations.computeIfAbsent(s.groupId(), g -> new LinkedHashMap<>()); }
    private Map<String, AnswerRecord> ans(GroupScope s) { return answers.computeIfAbsent(s.groupId(), g -> new LinkedHashMap<>()); }

    private Map<String, Message> partition(GroupScope s) { return messages.computeIfAbsent(s.groupId(), g -> new LinkedHashMap<>()); }
    private List<MessageEvent> ev(GroupScope s) { return events.computeIfAbsent(s.groupId(), g -> new ArrayList<>()); }
    private Map<String, MessageLink> lk(GroupScope s) { return links.computeIfAbsent(s.groupId(), g -> new LinkedHashMap<>()); }
    private Map<String, KnowledgeItem> kn(GroupScope s) { return knowledge.computeIfAbsent(s.groupId(), g -> new LinkedHashMap<>()); }

    /**
     * All-or-nothing for the group (N-21): on any exception the group's partitions are restored, so a failed deletion
     * leaves no half-applied tombstone and can simply be retried. Mirrors a Postgres transaction rollback.
     */
    @Override public synchronized <T> T atomically(GroupScope scope, Supplier<T> work) {
        String g = scope.groupId();
        var m = new LinkedHashMap<>(partition(scope));
        var e = new ArrayList<>(ev(scope));
        var l = new LinkedHashMap<>(lk(scope));
        var k = new LinkedHashMap<>(kn(scope));
        var r = new LinkedHashMap<>(ditaReplies.getOrDefault(g, Map.of()));
        var rl = new LinkedHashMap<>(rel(scope));
        var an = new LinkedHashMap<>(ans(scope));
        try {
            return work.get();
        } catch (RuntimeException ex) {
            messages.put(g, m); events.put(g, e); links.put(g, l); knowledge.put(g, k); ditaReplies.put(g, r);
            relations.put(g, rl); answers.put(g, an);
            throw ex;
        }
    }

    @Override public synchronized void append(GroupScope scope, Message m) {
        scope.requireSame(m.groupId());
        Message existing = partition(scope).get(m.id());
        if (existing != null) {
            if (existing.authorId().equals(m.authorId()) && existing.sentAt().equals(m.sentAt())) return;
            throw new IllegalStateException("conflicting duplicate message " + m.id());
        }
        if (m.replyToId() != null && !partition(scope).containsKey(m.replyToId())) {
            throw new IllegalStateException("reply target not in this group: " + m.replyToId());
        }
        partition(scope).put(m.id(), m);
        ev(scope).add(new MessageEvent(m.groupId(), m.id(), MessageEvent.Kind.CREATED, m.text(), m.sentAt()));
    }

    @Override public synchronized void edit(GroupScope scope, String id, String newText, Instant at) {
        Message m = require(scope, id);
        if (m.deleted()) throw new IllegalStateException("cannot edit deleted message " + id);
        partition(scope).put(id, m.withText(newText));
        ev(scope).add(new MessageEvent(scope.groupId(), id, MessageEvent.Kind.EDITED, newText, at));
    }

    @Override public synchronized List<String> tombstone(GroupScope scope, String id, Instant at, DeletionMode mode, DeletionSource source) {
        Message m = require(scope, id);
        if (m.deleted()) throw new IllegalStateException("already deleted " + id);
        // Same bounds as the DB CHECKs (Shuri #3): never before creation; EARLY within 24h; LATE after the 5-minute floor.
        if (at.isBefore(m.sentAt())) throw new IllegalArgumentException("deletion before creation");
        java.time.Duration age = java.time.Duration.between(m.sentAt(), at);
        if (mode == DeletionMode.EARLY_PURGE && age.compareTo(java.time.Duration.ofHours(24)) > 0) throw new IllegalArgumentException("EARLY_PURGE beyond 24h");
        if (mode == DeletionMode.LATE_TEXT_ONLY && age.compareTo(java.time.Duration.ofMinutes(5)) <= 0) throw new IllegalArgumentException("LATE deletion inside the 5-minute floor");
        List<MessageEvent> e = ev(scope);
        List<String> versions = e.stream().filter(x -> x.messageId().equals(id) && x.text() != null).map(MessageEvent::text).toList();
        partition(scope).put(id, m.asDeleted());
        e.replaceAll(x -> x.messageId().equals(id) ? new MessageEvent(x.groupId(), x.messageId(), x.kind(), null, x.occurredAt()) : x);
        e.add(new MessageEvent(scope.groupId(), id, MessageEvent.Kind.DELETED, null, at));
        return versions;
    }

    @Override public synchronized Set<String> editedMessageIds(GroupScope scope) {
        Set<String> out = new java.util.HashSet<>();
        for (MessageEvent e : ev(scope)) if (e.kind() == MessageEvent.Kind.EDITED) out.add(e.messageId());
        return out;
    }

    private Message require(GroupScope scope, String id) {
        return find(scope, id).orElseThrow(() -> new NoSuchElementException(id));
    }

    @Override public synchronized Optional<Message> find(GroupScope scope, String id) { return Optional.ofNullable(partition(scope).get(id)); }

    @Override public synchronized List<Message> live(GroupScope scope) {
        return partition(scope).values().stream().filter(m -> !m.deleted())
                .sorted(Comparator.comparing(Message::sentAt).thenComparing(Message::id)).toList();
    }

    @Override public synchronized List<Message> windowBefore(GroupScope scope, String id, int limit) {
        Message target = require(scope, id);
        List<Message> earlier = live(scope).stream().filter(m -> m.sentAt().isBefore(target.sentAt())).toList();
        return earlier.subList(Math.max(0, earlier.size() - limit), earlier.size());
    }

    @Override public synchronized List<MessageEvent> history(GroupScope scope, String id) {
        return ev(scope).stream().filter(e -> e.messageId().equals(id)).toList();
    }

    @Override public synchronized void addLink(GroupScope scope, MessageLink link) {
        scope.requireSame(link.groupId());
        for (String dep : link.evidenceMessageIds()) {
            if (require(scope, dep).deleted()) throw new IllegalStateException("link evidence uses deleted message " + dep);
        }
        lk(scope).putIfAbsent(link.key(), link);
    }

    @Override public synchronized void removeLinks(GroupScope scope, Collection<String> keys) { keys.forEach(lk(scope)::remove); }

    @Override public synchronized List<MessageLink> links(GroupScope scope) { return List.copyOf(lk(scope).values()); }

    @Override public synchronized void addKnowledge(GroupScope scope, KnowledgeItem k) {
        scope.requireSame(k.groupId());
        for (KnowledgeItem.Evidence e : k.evidence()) {
            if (require(scope, e.messageId()).deleted()) throw new IllegalStateException("cannot derive knowledge from deleted message " + e.messageId());
            if (e.viaLinkKey() != null && !lk(scope).containsKey(e.viaLinkKey())) throw new IllegalStateException("unknown link " + e.viaLinkKey());
        }
        if (kn(scope).putIfAbsent(k.id(), k) != null) throw new IllegalStateException("duplicate knowledge " + k.id());
    }

    @Override public synchronized void replaceKnowledge(GroupScope scope, KnowledgeItem k) {
        scope.requireSame(k.groupId());
        if (!kn(scope).containsKey(k.id())) throw new NoSuchElementException(k.id());
        kn(scope).put(k.id(), k);
    }

    @Override public synchronized void removeKnowledge(GroupScope scope, String id) {
        kn(scope).remove(id);
        rel(scope).values().removeIf(r -> r.involves(id));                              // cascade, like the V5 FKs
    }

    @Override public synchronized void addRelation(GroupScope scope, KnowledgeRelation r) {
        scope.requireSame(r.groupId());
        if (!kn(scope).containsKey(r.fromId()) || !kn(scope).containsKey(r.toId())) throw new IllegalStateException("relation ends must exist in this group");
        if (r.evidenceMessageId() != null) require(scope, r.evidenceMessageId());
        rel(scope).putIfAbsent(r.key(), r);
    }

    @Override public synchronized void removeRelations(GroupScope scope, Collection<String> keys) { keys.forEach(rel(scope)::remove); }

    @Override public synchronized List<KnowledgeRelation> relations(GroupScope scope) { return List.copyOf(rel(scope).values()); }

    @Override public synchronized void recordAnswer(GroupScope scope, AnswerRecord a) {
        scope.requireSame(a.groupId());
        for (String k : a.knowledgeIds()) if (!kn(scope).containsKey(k)) throw new IllegalStateException("unknown knowledge " + k);
        for (String mid : a.messageIds()) require(scope, mid);
        ans(scope).putIfAbsent(a.replyId(), a);
    }

    @Override public synchronized Optional<AnswerRecord> answer(GroupScope scope, String replyId) { return Optional.ofNullable(ans(scope).get(replyId)); }

    @Override public synchronized List<KnowledgeItem> knowledge(GroupScope scope) { return List.copyOf(kn(scope).values()); }

    @Override public synchronized void recordDitaReply(GroupScope scope, String replyId, Collection<String> cited) {
        for (String c : cited) require(scope, c);
        ditaReplies.computeIfAbsent(scope.groupId(), g -> new LinkedHashMap<>()).put(replyId, Set.copyOf(cited));
    }

    @Override public synchronized List<String> ditaRepliesCiting(GroupScope scope, String messageId) {
        return ditaReplies.getOrDefault(scope.groupId(), Map.of()).entrySet().stream()
                .filter(e -> e.getValue().contains(messageId)).map(Map.Entry::getKey).sorted().toList();
    }
}
