package app.dita.projections;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.model.Assertion;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j conversation-graph projection over the HTTP transactional Cypher API (/db/neo4j/tx/commit).
 * Stores structure only: NO message text and no knowledge wording (data minimisation; nothing to leak or restore).
 * Nodes: (:Message {uid, groupId, id, authorId, sentAt, deleted, deletionMode}), (:Knowledge {uid, groupId, id, kind,
 * redacted, sourceDeleted, status, certainty, predicate, validity, subjectId, speakerId}) (RFC-002 structure only: ids
 * and closed-vocabulary values, never subject/qualifier/entity surfaces or statements); edges: [:LINK {uid, key, kind,
 * status, score}], [:DERIVED_FROM {basis, role}] Knowledge→Message, [:RELATES {uid, groupId, key, kind}]
 * Knowledge→Knowledge (RFC-002 §3.5; written when the truth is a {@link KnowledgeTruth}).
 * uid = groupId|id keeps groups disjoint (single-property uniqueness constraints work in Community edition).
 */
public final class Neo4jProjection implements ProjectionListener {
    private final String commitUrl;
    private final Http http;

    public Neo4jProjection(String baseUrl, String user, String password) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        java.net.URI u = java.net.URI.create(b);
        if ("http".equals(u.getScheme()) && !("127.0.0.1".equals(u.getHost()) || "localhost".equals(u.getHost()))) {
            throw new IllegalArgumentException("basic auth over plain HTTP is only allowed to loopback (N-33)");
        }
        this.commitUrl = b + "/db/neo4j/tx/commit";
        this.http = new Http("Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    private static final String SAFE_GROUP = "[A-Za-z0-9_-]{1,128}";

    /** N-30: group ids are validated and message ids may not contain the separator, so uids cannot collide. */
    static String uid(String g, String id) {
        if (!g.matches(SAFE_GROUP)) throw new IllegalArgumentException("unsafe group id");
        if (id.indexOf('|') >= 0 || id.isEmpty()) throw new IllegalArgumentException("unsafe id");
        return g + "|" + id;
    }

    /** Runs statements in one transaction; returns rows of the last statement. */
    List<List<JsonNode>> cypher(Object... statementsAndParams) {
        List<Map<String, Object>> st = new ArrayList<>();
        for (int i = 0; i < statementsAndParams.length; i += 2) {
            st.add(Map.of("statement", statementsAndParams[i], "parameters", statementsAndParams[i + 1]));
        }
        Http.Response r = http.send("POST", commitUrl, Map.of("statements", st));
        if (r.status() != 200) { String reason = r.reason(); throw new Http.ProjectionException("neo4j returned HTTP " + r.status() + (reason.isEmpty() ? "" : ": " + reason)); }
        JsonNode errors = r.body().path("errors");
        if (errors.size() > 0) throw new Http.ProjectionException("neo4j error " + errors.get(0).path("code").asText());
        List<List<JsonNode>> rows = new ArrayList<>();
        JsonNode results = r.body().path("results");
        if (results.size() == 0) return rows;
        for (JsonNode d : results.get(results.size() - 1).path("data")) {
            List<JsonNode> row = new ArrayList<>();
            d.path("row").forEach(row::add);
            rows.add(row);
        }
        return rows;
    }

    public void ensureSchema() {
        cypher("CREATE CONSTRAINT dita_message_uid IF NOT EXISTS FOR (m:Message) REQUIRE m.uid IS UNIQUE", Map.of(),
               "CREATE CONSTRAINT dita_knowledge_uid IF NOT EXISTS FOR (k:Knowledge) REQUIRE k.uid IS UNIQUE", Map.of(),
               "CREATE INDEX dita_message_group IF NOT EXISTS FOR (m:Message) ON (m.groupId)", Map.of(),
               "CREATE INDEX dita_knowledge_group IF NOT EXISTS FOR (k:Knowledge) ON (k.groupId)", Map.of());
    }

    private static Map<String, Object> node(String g, Message m) {
        Map<String, Object> p = new HashMap<>();
        p.put("uid", uid(g, m.id())); p.put("g", g); p.put("id", m.id()); p.put("author", m.authorId());
        p.put("sentAt", m.sentAt().toString()); p.put("deleted", m.deleted());
        return p;
    }

    private static final String MERGE_NODE = "MERGE (m:Message {uid:$uid}) ON CREATE SET m.groupId=$g, m.id=$id "
            + "SET m.authorId=$author, m.sentAt=$sentAt, m.deleted = coalesce(m.deleted, false) OR $deleted";

    @Override public void onMessage(String g, String id, ProjectionSource truth) {
        Message m = truth.message(g, id).orElse(null);
        if (m == null || m.deleted()) {
            cypher("MERGE (m:Message {uid:$uid}) ON CREATE SET m.groupId=$g, m.id=$id SET m.deleted=true REMOVE m.authorId, m.sentAt",
                    Map.of("uid", uid(g, id), "g", g, "id", id));
            return;
        }
        cypher(MERGE_NODE, node(g, m));                                           // never un-deletes (OR)
    }

    @Override public void onLink(String g, String key, ProjectionSource truth) {
        MessageLink l = truth.link(g, key).orElse(null);
        if (l == null) { cypher("MATCH ()-[r:LINK {uid:$uid, groupId:$g}]->() DELETE r", Map.of("uid", uid(g, key), "g", g)); return; }
        Map<String, Object> p = new HashMap<>();
        p.put("uid", uid(g, key)); p.put("key", key); p.put("g", g);
        p.put("from", uid(g, l.fromId())); p.put("to", uid(g, l.toId()));
        p.put("kind", l.kind().name()); p.put("status", l.status().name()); p.put("score", l.score());
        cypher("MATCH (a:Message {uid:$from}), (b:Message {uid:$to}) WHERE a.groupId=$g AND b.groupId=$g "
                + "MERGE (a)-[r:LINK {uid:$uid}]->(b) SET r.groupId=$g, r.key=$key, r.kind=$kind, r.status=$status, r.score=$score", p);
    }

    @Override public void onKnowledge(String g, String id, ProjectionSource truth) {
        KnowledgeItem k = truth.knowledge(g, id).filter(x -> x.groupId().equals(g)).orElse(null);
        if (k == null) { cypher("MATCH (k:Knowledge {uid:$uid}) WHERE k.groupId=$g DETACH DELETE k", Map.of("uid", uid(g, id), "g", g)); return; }
        Map<String, Object> p = knowledgeParams(g, k);
        if (!(truth instanceof KnowledgeTruth kt)) { cypher(UPSERT_KNOWLEDGE, p); return; }
        List<Map<String, Object>> rels = new ArrayList<>();
        for (KnowledgeRelation r : kt.relations(g)) {
            if (!r.involves(id) || !r.groupId().equals(g)) continue;
            Map<String, Object> x = new HashMap<>();
            uid(g, r.toId());                                                // validates the id (no separator)
            x.put("uid", uid(g, r.fromId()) + "|" + r.kind().name() + "|" + r.toId());
            x.put("key", r.key()); x.put("kind", r.kind().name());
            x.put("from", uid(g, r.fromId())); x.put("to", uid(g, r.toId()));
            rels.add(x);
        }
        // One transaction: upsert node + DERIVED_FROM, drop this item's relation edges, recreate them from truth. An edge
        // whose other end is not projected yet appears when that item is projected (it rebuilds its own edges too).
        cypher(UPSERT_KNOWLEDGE, p,
               "MATCH (k:Knowledge {uid:$uid})-[r:RELATES]-() WHERE k.groupId=$g DELETE r", Map.of("uid", uid(g, id), "g", g),
               "UNWIND $rels AS x MATCH (a:Knowledge {uid:x.from}), (b:Knowledge {uid:x.to}) WHERE a.groupId=$g AND b.groupId=$g "
                       + "MERGE (a)-[r:RELATES {uid:x.uid}]->(b) SET r.groupId=$g, r.key=x.key, r.kind=x.kind",
               Map.of("rels", rels, "g", g));
    }

    private static final String UPSERT_KNOWLEDGE = "MERGE (k:Knowledge {uid:$uid}) ON CREATE SET k.groupId=$g, k.id=$id "
            + "SET k.kind=$kind, k.redacted=$redacted, k.sourceDeleted=$sourceDeleted, k.status=$status, k.certainty=$certainty, "
            + "k.predicate=$predicate, k.validity=$validity, k.subjectId=$subjectId, k.speakerId=$speakerId "
            + "WITH k OPTIONAL MATCH (k)-[old:DERIVED_FROM]->() DELETE old "
            + "WITH DISTINCT k UNWIND $src AS s MATCH (m:Message {uid:s.uid}) WHERE m.groupId=$g "
            + "MERGE (k)-[d:DERIVED_FROM {basis:s.basis, role:s.role}]->(m)";

    /** Node parameters. Structure only: no statement, subject surface, qualifier/entity surface or quote (tested). */
    static Map<String, Object> knowledgeParams(String g, KnowledgeItem k) {
        List<Map<String, Object>> src = new ArrayList<>();
        for (KnowledgeItem.Evidence e : k.evidence()) {
            src.add(Map.of("uid", uid(g, e.messageId()), "basis", e.basis().name(), "role", e.role().name()));
        }
        Assertion a = k.assertion();
        Map<String, Object> p = new HashMap<>();
        p.put("uid", uid(g, k.id())); p.put("g", g); p.put("id", k.id()); p.put("kind", k.kind().name());
        p.put("redacted", k.statementRedacted()); p.put("sourceDeleted", k.hasDeletedSource()); p.put("src", src);
        p.put("status", a == null ? null : a.status().name());
        p.put("certainty", a == null ? null : a.certainty().name());
        p.put("predicate", a == null ? null : a.predicate());
        p.put("validity", a == null ? null : a.validity().name());
        p.put("subjectId", a == null ? null : a.subjectId());
        p.put("speakerId", a == null ? null : a.speakerId());
        return p;
    }

    @Override public void onMessageDeleted(DeletionEvent e, ProjectionSource truth) {
        String g = e.groupId();
        List<String> removedLinks = e.removedLinkKeys().stream().map(k -> uid(g, k)).toList();
        List<String> removedK = e.removedKnowledgeIds().stream().map(k -> uid(g, k)).toList();
        cypher("MERGE (m:Message {uid:$uid}) ON CREATE SET m.groupId=$g, m.id=$id SET m.deleted=true, m.deletionMode=$mode "
                + "REMOVE m.authorId, m.sentAt", 
                Map.of("uid", uid(g, e.messageId()), "g", g, "id", e.messageId(), "mode", e.mode().name()),
               "MATCH ()-[r:LINK]->() WHERE r.uid IN $links AND r.groupId = $g DELETE r", Map.of("links", removedLinks, "g", g),
               "MATCH (k:Knowledge) WHERE k.uid IN $ks AND k.groupId=$g DETACH DELETE k", Map.of("ks", removedK, "g", g));
        e.addedLinkKeys().forEach(key -> onLink(g, key, truth));
        java.util.Set<String> touched = new java.util.LinkedHashSet<>(e.updatedKnowledgeIds());
        touched.addAll(e.redactedKnowledgeIds()); touched.addAll(e.preservedWithDeletedSourceIds());
        touched.removeAll(e.removedKnowledgeIds());
        touched.forEach(id -> onKnowledge(g, id, truth));                // sourceDeleted/redacted flags, relations
    }

    @Override public void reconcile(String g, ProjectionSource truth) {
        for (List<JsonNode> row : cypher("MATCH (m:Message {groupId:$g}) WHERE NOT m.deleted RETURN m.id", Map.of("g", g))) {
            String id = row.get(0).asText();
            if (truth.message(g, id).filter(x -> !x.deleted()).isEmpty()) onMessage(g, id, truth);
        }
        for (List<JsonNode> row : cypher("MATCH (:Message {groupId:$g})-[r:LINK]->() RETURN r.key", Map.of("g", g))) {
            onLink(g, row.get(0).asText(), truth);
        }
        java.util.Set<String> ks = new java.util.LinkedHashSet<>();
        for (List<JsonNode> row : cypher("MATCH (k:Knowledge {groupId:$g}) RETURN k.id", Map.of("g", g))) ks.add(row.get(0).asText());
        if (truth instanceof KnowledgeTruth kt) ks.addAll(kt.knowledgeIds(g));   // items this projection never saw
        ks.forEach(id -> onKnowledge(g, id, truth));
    }

    /**
     * Graph-aware contextual expansion: seeds from another retriever, then k-hop neighbours within the same group
     * that are not deleted. Paths using only explicit replies propagate more weight; others are flagged as inferred.
     */
    public Retriever expanding(Retriever seed, int hops, double explicitWeight, double candidateWeight) {
        if (hops < 1 || hops > 4) throw new IllegalArgumentException("hops in [1,4]");
        return (scope, query, k) -> {
            List<Hit> seeds = seed.retrieve(scope, query, k);
            Map<String, Hit> best = new LinkedHashMap<>();
            seeds.forEach(h -> best.put(h.messageId(), h));
            if (seeds.isEmpty()) return seeds;
            Map<String, Double> seedScore = new HashMap<>();
            seeds.forEach(h -> seedScore.put(h.messageId(), h.score()));
            String q = "MATCH (s:Message {groupId:$g}) WHERE s.id IN $ids "
                    + "MATCH p=(s)-[:LINK*1.." + hops + "]-(n:Message {groupId:$g}) "
                    + "WHERE NOT n.deleted AND n.id <> s.id AND all(x IN nodes(p) WHERE x.groupId = $g AND (x = s OR NOT coalesce(x.deleted, false))) "
                    + "RETURN s.id, n.id, length(p), all(r IN relationships(p) WHERE r.kind = 'EXPLICIT_REPLY')";
            for (List<JsonNode> row : cypher(q, Map.of("g", scope.groupId(), "ids", new ArrayList<>(seedScore.keySet())))) {
                String s = row.get(0).asText(), n = row.get(1).asText();
                int len = row.get(2).asInt();
                boolean explicit = row.get(3).asBoolean();
                double score = seedScore.get(s) * Math.pow(explicit ? explicitWeight : candidateWeight, len);
                Hit prev = best.get(n);
                if (prev == null || prev.score() < score) {
                    boolean inferred = !explicit && (prev == null || prev.inferredContext());
                    best.put(n, new Hit(n, score, "neo4j:" + (explicit ? "explicit" : "inferred") + ":" + len, inferred));
                }
            }
            return best.values().stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(k).toList();
        };
    }

    /** Inspection helper for tests; requires a group parameter so callers stay scoped (N-33). */
    public List<List<JsonNode>> query(String cypher, Map<String, Object> params) {
        if (!params.containsKey("g") && !params.containsKey("u") && !params.containsKey("f")) throw new IllegalArgumentException("scoped queries only");
        return cypher(cypher, params);
    }

}
