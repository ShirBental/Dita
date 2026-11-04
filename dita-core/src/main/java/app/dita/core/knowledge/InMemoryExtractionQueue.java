package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reference implementation (not durable). Partitioned by group. */
public final class InMemoryExtractionQueue implements ExtractionQueue {
    private final Map<String, LinkedHashMap<String, ExtractionBatch.Target>> pending = new HashMap<>();
    private final Map<String, java.util.Set<String>> batched = new HashMap<>();
    private final Map<String, LinkedHashMap<String, ExtractionBatch>> batches = new HashMap<>();

    @Override public synchronized void enqueue(GroupScope scope, ExtractionBatch.Target t, Instant at) {
        if (batched.getOrDefault(scope.groupId(), java.util.Set.of()).contains(t.messageId())) return;
        pending.computeIfAbsent(scope.groupId(), g -> new LinkedHashMap<>()).putIfAbsent(t.messageId(), t);
    }

    @Override public synchronized List<ExtractionBatch> collect(GroupScope scope, int maxSize, Instant at) {
        var p = pending.getOrDefault(scope.groupId(), new LinkedHashMap<>());
        List<ExtractionBatch> out = new ArrayList<>();
        while (!p.isEmpty()) {
            List<ExtractionBatch.Target> chunk = new ArrayList<>();
            var it = p.values().iterator();
            while (it.hasNext() && chunk.size() < maxSize) { chunk.add(it.next()); it.remove(); }
            ExtractionBatch b = new ExtractionBatch(batchId(scope.groupId(), chunk), scope.groupId(), chunk,
                    ExtractionBatch.Status.COLLECTED, 0, null, null, at, at);
            chunk.forEach(c -> batched.computeIfAbsent(scope.groupId(), g -> new java.util.HashSet<>()).add(c.messageId()));
            batches.computeIfAbsent(scope.groupId(), g -> new LinkedHashMap<>()).putIfAbsent(b.id(), b);
            out.add(b);
        }
        return out;
    }

    @Override public synchronized List<ExtractionBatch> collect(GroupScope scope, java.util.Collection<String> only, int maxSize, Instant at) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        var p = pending.getOrDefault(scope.groupId(), new LinkedHashMap<>());
        java.util.Set<String> wanted = java.util.Set.copyOf(only);
        List<ExtractionBatch> out = new ArrayList<>();
        List<ExtractionBatch.Target> chunk = new ArrayList<>();
        for (var it = p.values().iterator(); it.hasNext(); ) {
            var t = it.next();
            if (!wanted.contains(t.messageId())) continue;                          // unrelated messages stay pending
            chunk.add(t); it.remove();
            if (chunk.size() == maxSize) { out.add(newBatch(scope, chunk, at)); chunk = new ArrayList<>(); }
        }
        if (!chunk.isEmpty()) out.add(newBatch(scope, chunk, at));
        return out;
    }

    private ExtractionBatch newBatch(GroupScope scope, List<ExtractionBatch.Target> chunk, Instant at) {
        ExtractionBatch b = new ExtractionBatch(batchId(scope.groupId(), chunk), scope.groupId(), List.copyOf(chunk),
                ExtractionBatch.Status.COLLECTED, 0, null, null, at, at);
        chunk.forEach(c -> batched.computeIfAbsent(scope.groupId(), g -> new java.util.HashSet<>()).add(c.messageId()));
        batches.computeIfAbsent(scope.groupId(), g -> new LinkedHashMap<>()).putIfAbsent(b.id(), b);
        return b;
    }

    @Override public synchronized List<String> pendingIds(GroupScope scope) {
        return List.copyOf(pending.getOrDefault(scope.groupId(), new LinkedHashMap<>()).keySet());
    }

    @Override public synchronized Optional<ExtractionBatch> batch(GroupScope scope, String id) {
        return Optional.ofNullable(batches.getOrDefault(scope.groupId(), new LinkedHashMap<>()).get(id));
    }

    @Override public synchronized List<ExtractionBatch> batches(GroupScope scope, ExtractionBatch.Status s) {
        return batches.getOrDefault(scope.groupId(), new LinkedHashMap<>()).values().stream().filter(b -> b.status() == s).toList();
    }

    @Override public synchronized boolean transition(GroupScope scope, ExtractionBatch.Status expected, ExtractionBatch next) {
        scope.requireSame(next.groupId());
        var m = batches.getOrDefault(scope.groupId(), new LinkedHashMap<>());
        ExtractionBatch cur = m.get(next.id());
        if (cur == null || cur.status() != expected) return false;
        m.put(next.id(), next);
        return true;
    }

    @Override public synchronized List<String> groupsWithApproved() {
        return batches.entrySet().stream().filter(e -> e.getValue().values().stream().anyMatch(b -> b.status() == ExtractionBatch.Status.APPROVED))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /** Deterministic batch id: hash of group + target message ids. Shared with the Postgres implementation. */
    public static String batchId(String groupId, List<ExtractionBatch.Target> targets) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(groupId.getBytes(StandardCharsets.UTF_8));
            for (ExtractionBatch.Target t : targets) { d.update((byte) 0); d.update(t.messageId().getBytes(StandardCharsets.UTF_8)); }
            return "xb-" + HexFormat.of().formatHex(d.digest()).substring(0, 20);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
