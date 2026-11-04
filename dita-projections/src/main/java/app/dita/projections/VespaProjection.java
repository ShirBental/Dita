package app.dita.projections;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Vespa projection + retriever (schemas vespa/app/schemas/message.sd and knowledge.sd). Message text is indexed only
 * from CURRENT Postgres truth; a missing/deleted truth row deletes the document (N-19). Every query filters on group_id,
 * and results are re-checked against the scope (defence in depth).
 * <p>Knowledge documents (RFC-002 §4, K-7) are keyed by (groupId, id) and hold ONLY the Dita-rendered template text of
 * {@link KnowledgeDocument} (live verbatim spans; nothing the model wrote). An item that is gone, redacted, audit-only
 * or without a live quote is deleted from the index. A message deletion re-renders (or deletes) every knowledge
 * document that cites the message, found by visiting the group's documents, so no quote of a purged message survives
 * even if the deletion event lists are incomplete.
 */
public final class VespaProjection implements ProjectionListener, Retriever {
    private static final String GROUP_ID = "[A-Za-z0-9_-]{1,128}";
    private final String base;
    private final Http http = new Http(null);

    public VespaProjection(String baseUrl) { this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; }

    static String docId(String groupId, String messageId) {
        return URLEncoder.encode(groupId + "::" + messageId, StandardCharsets.UTF_8);
    }

    private String docUrl(String g, String id) { return base + "/document/v1/dita/message/docid/" + docId(g, id); }

    private void check(Http.Response r, String what, int... allowed) {
        for (int a : allowed) if (r.status() == a) return;
        String reason = r.reason();
        throw new Http.ProjectionException("vespa " + what + " returned HTTP " + r.status() + (reason.isEmpty() ? "" : ": " + reason));
    }

    @Override public void onMessage(String groupId, String messageId, ProjectionSource truth) {
        requireGroup(groupId);
        Message m = truth.message(groupId, messageId).filter(x -> !x.deleted()).orElse(null);
        if (m == null) { check(http.send("DELETE", docUrl(groupId, messageId), null), "delete", 200, 404); return; }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("group_id", groupId); fields.put("message_id", messageId); fields.put("text", m.text());
        Http.Response r = http.send("POST", docUrl(groupId, messageId), Map.of("fields", fields));
        check(r, "put", 200);
        requireFed(r.body(), "message", groupId, messageId);
    }

    @Override public void onLink(String groupId, String linkKey, ProjectionSource truth) { }

    // ------------------------------------------------------------------ knowledge documents

    private String knowledgeUrl(String g, String id) { return base + "/document/v1/dita/knowledge/docid/" + docId(g, id); }

    @Override public void onKnowledge(String groupId, String knowledgeId, ProjectionSource truth) {
        requireGroup(groupId);
        Optional<KnowledgeDocument> doc = truth.knowledge(groupId, knowledgeId)
                .filter(k -> k.groupId().equals(groupId) && k.id().equals(knowledgeId))
                .flatMap(k -> KnowledgeDocument.render(k, truth::message));
        if (doc.isEmpty()) { check(http.send("DELETE", knowledgeUrl(groupId, knowledgeId), null), "delete knowledge", 200, 404); return; }
        Http.Response r = http.send("POST", knowledgeUrl(groupId, knowledgeId), Map.of("fields", knowledgeFields(doc.get())));
        check(r, "put knowledge", 200);
        requireFed(r.body(), "knowledge", groupId, knowledgeId);
    }

    static Map<String, Object> knowledgeFields(KnowledgeDocument d) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("group_id", d.groupId()); f.put("knowledge_id", d.knowledgeId()); f.put("text", d.text());
        f.put("content_hash", contentHash(d.text()));
        f.put("status", d.status()); f.put("predicate", d.predicate()); f.put("validity", d.validity());
        f.put("subject_id", d.subjectId()); f.put("speaker_id", d.speakerId()); f.put("source_ids", d.sourceIds());
        return f;
    }

    @Override public void onMessageDeleted(DeletionEvent e, ProjectionSource truth) {
        requireGroup(e.groupId());
        check(http.send("DELETE", docUrl(e.groupId(), e.messageId()), null), "delete", 200, 404);
        Set<String> affected = new LinkedHashSet<>();
        affected.addAll(e.removedKnowledgeIds()); affected.addAll(e.updatedKnowledgeIds());
        affected.addAll(e.redactedKnowledgeIds()); affected.addAll(e.preservedWithDeletedSourceIds());
        visitKnowledge(e.groupId(), d -> {
            for (JsonNode s : d.path("fields").path("source_ids")) if (e.messageId().equals(s.asText())) affected.add(d.path("fields").path("knowledge_id").asText());
        });
        affected.forEach(id -> onKnowledge(e.groupId(), id, truth));
    }

    private void visitKnowledge(String groupId, Consumer<JsonNode> each) { visit("knowledge", groupId, each); }

    private void visit(String type, String groupId, Consumer<JsonNode> each) {
        String sel = URLEncoder.encode(type + ".group_id==\"" + groupId + "\"", StandardCharsets.UTF_8);
        String cont = null;
        do {
            String url = base + "/document/v1/dita/" + type + "/docid?selection=" + sel + "&cluster=dita&wantedDocumentCount=500"
                    + (cont == null ? "" : "&continuation=" + URLEncoder.encode(cont, StandardCharsets.UTF_8));
            Http.Response r = http.send("GET", url, null);
            check(r, "visit " + type, 200);
            for (JsonNode d : r.body().path("documents")) {
                if (!groupId.equals(d.path("fields").path("group_id").asText())) {
                    throw new app.dita.core.model.IsolationViolation("vespa visit returned a document outside scope " + groupId);
                }
                each.accept(d);
            }
            cont = r.body().path("continuation").asText(null);
        } while (cont != null && !cont.isBlank());
    }

    /** Knowledge ids for a query, BM25 over this group's knowledge documents only. */
    public List<KnowledgeHit> retrieveKnowledge(GroupScope scope, String query, int k, boolean activeOnly) {
        requireGroup(scope.groupId());
        if (k < 1 || k > 400) throw new IllegalArgumentException("k in [1,400]");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("yql", knowledgeYql(scope.groupId(), activeOnly));
        body.put("query", query);
        body.put("type", "any");
        body.put("ranking", "bm25");
        body.put("hits", k);
        Http.Response r = http.send("POST", base + "/search/", body);
        check(r, "search knowledge", 200);
        return parseKnowledgeHits(scope, r.body());
    }

    public record KnowledgeHit(String knowledgeId, double relevance, String validity) {}

    static String contentHash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Dense candidates are ids only; no indexed text is used as answer evidence. Exact search avoids ANN filter recall loss. */
    public List<app.dita.core.knowledge.KnowledgeRetriever.Hit> retrieveDenseKnowledge(
            GroupScope scope, String query, int k, double minCosine, ProjectionSource truth) {
        Http.Response r = http.send("POST", base + "/search/", denseBody(scope, query, k, minCosine, null));
        check(r, "dense knowledge", 200);
        return parseDenseKnowledge(scope, r.body(), minCosine, truth);
    }

    /**
     * TASK-052: startup proof that dense retrieval works (schema, rank profile, query embedder), still fail-closed.
     * Root cause it addresses: the dense query sends no {@code timeout}, so Vespa applies its default query timeout
     * (0.5 s), and the first {@code embed(...)} after a container start can exceed it ("Chain timed out", HTTP 504).
     * Only this probe gets an explicit per-attempt timeout, and only HTTP 504 is retried, at most {@code attempts}
     * times. Any other status, or a 504 on every attempt, throws: dense never silently boots as lexical. User queries
     * keep Vespa's default timeout. Returns the attempt number that succeeded (1-based), for evidence.
     */
    public int awaitDenseReadiness(GroupScope scope, double minCosine, ProjectionSource truth,
                                   java.time.Duration perAttempt, int attempts) {
        return awaitDenseReadiness(b -> http.send("POST", base + "/search/", b), scope, minCosine, truth, perAttempt, attempts);
    }

    /** The probe loop with the HTTP call injected (package-private seam for socket-free tests). */
    int awaitDenseReadiness(java.util.function.Function<Map<String,Object>, Http.Response> send, GroupScope scope, double minCosine,
                            ProjectionSource truth, java.time.Duration perAttempt, int attempts) {
        if (attempts < 1 || attempts > 5 || perAttempt.isNegative() || perAttempt.isZero() || perAttempt.toSeconds() > 20)
            throw new IllegalArgumentException("readiness bounds: 1..5 attempts, 0 < timeout <= 20 s");
        String timeout = perAttempt.toMillis() + "ms";
        for (int i = 1; ; i++) {
            Http.Response r = send.apply(denseBody(scope, "semantic retrieval readiness", 1, minCosine, timeout));
            if (r.status() == 504 && i < attempts) {
                System.getLogger(VespaProjection.class.getName()).log(System.Logger.Level.WARNING,
                        "dense readiness attempt " + i + "/" + attempts + " timed out (HTTP 504); retrying");
                continue;
            }
            check(r, "dense knowledge readiness (attempt " + i + "/" + attempts + ")", 200);
            parseDenseKnowledge(scope, r.body(), minCosine, truth);
            return i;
        }
    }

    Map<String,Object> denseBody(GroupScope scope, String query, int k, double minCosine, String timeout) {
        requireGroup(scope.groupId());
        if (k < 1 || k > 400 || !Double.isFinite(minCosine) || minCosine < 0 || minCosine > 1)
            throw new IllegalArgumentException("invalid dense retrieval bounds");
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("yql", "select knowledge_id, group_id, validity, content_hash from knowledge where group_id contains \""
                + scope.groupId() + "\" and validity contains \"ACTIVE\" and ({targetHits:" + k
                + ",approximate:false}nearestNeighbor(embedding,q))");
        body.put("ranking", "dense"); body.put("hits", k);
        body.put("semanticQuery", query); body.put("input.query(q)", "embed(dita-english, @semanticQuery)");
        if (timeout != null) body.put("timeout", timeout);
        return body;
    }

    static List<app.dita.core.knowledge.KnowledgeRetriever.Hit> parseDenseKnowledge(
            GroupScope scope, JsonNode body, double minCosine, ProjectionSource truth) {
        parseKnowledgeHits(scope, body); // errors and cross-scope results always fail closed
        List<app.dita.core.knowledge.KnowledgeRetriever.Hit> out = new ArrayList<>();
        for (JsonNode c : body.path("root").path("children")) {
            JsonNode f = c.path("fields");
            double cosine = c.path("relevance").asDouble(Double.NaN);
            if (!Double.isFinite(cosine) || cosine < minCosine || cosine > 1.00001) continue;
            String id = f.path("knowledge_id").asText();
            var item = truth.knowledge(scope.groupId(), id).orElse(null);
            if (item != null && (!scope.groupId().equals(item.groupId()) || !id.equals(item.id())))
                throw new app.dita.core.model.IsolationViolation("truth returned knowledge outside requested scope");
            if (item == null || item.assertion() == null
                    || item.assertion().validity() != app.dita.core.model.Assertion.Validity.ACTIVE) continue;
            var live = KnowledgeDocument.render(item, truth::message);
            if (live.isEmpty() || !contentHash(live.get().text()).equals(f.path("content_hash").asText())) continue;
            out.add(new app.dita.core.knowledge.KnowledgeRetriever.Hit(id, cosine, true));
        }
        return List.copyOf(out);
    }

    // ---------------- TASK-054 G1: semantic (bge-small) retrieval over RAW MESSAGES ----------------

    /** Derived message embedding field and rank profile added by scripts/task054-message-embedding-package.py. */
    public static final String MESSAGE_EMBEDDING_FIELD = "text_embedding", MESSAGE_DENSE_PROFILE = "dense";
    public static final String VIA_DENSE = "vespa:dense";

    /**
     * Group-filtered exact nearest-neighbour search over the message text embedding (the query is embedded inside Vespa by
     * the same frozen dita-english embedder). Hits below {@code minCosine} are dropped. Ids only: evidence text always
     * comes from truth, and the answer path re-checks every message (live, unedited, in scope, history access).
     */
    public List<Hit> retrieveDenseMessages(GroupScope scope, String query, int k, double minCosine) {
        Http.Response r = http.send("POST", base + "/search/", denseMessageBody(scope, query, k, minCosine, null));
        check(r, "dense messages", 200);
        return parseDenseMessages(scope, r.body(), minCosine);
    }

    static Map<String, Object> denseMessageBody(GroupScope scope, String query, int k, double minCosine, String timeout) {
        requireGroup(scope.groupId());
        if (k < 1 || k > 200 || !Double.isFinite(minCosine) || minCosine < 0 || minCosine > 1)
            throw new IllegalArgumentException("invalid dense message retrieval bounds");
        if (query == null || query.isBlank() || query.length() > 500) throw new IllegalArgumentException("dense query must be 1..500 chars");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("yql", "select message_id, group_id from message where group_id contains \"" + scope.groupId()
                + "\" and ({targetHits:" + k + ",approximate:false}nearestNeighbor(" + MESSAGE_EMBEDDING_FIELD + ",q))");
        body.put("ranking", MESSAGE_DENSE_PROFILE); body.put("hits", k);
        body.put("semanticQuery", query); body.put("input.query(q)", "embed(dita-english, @semanticQuery)");
        if (timeout != null) body.put("timeout", timeout);
        return body;
    }

    /** Same fail-closed rules as {@link #parseHits}; plus the cosine floor. Relevance of the dense profile is the cosine. */
    static List<Hit> parseDenseMessages(GroupScope scope, JsonNode body, double minCosine) {
        List<Hit> out = new ArrayList<>();
        for (Hit h : parseHits(scope, body, VIA_DENSE)) {
            if (!Double.isFinite(h.score()) || h.score() < minCosine || h.score() > 1.00001) continue;
            out.add(h);
        }
        return List.copyOf(out);
    }

    /**
     * Startup proof that semantic MESSAGE retrieval works (message schema field, rank profile, query embedder). Same
     * bounded 504 retry as {@link #awaitDenseReadiness}; any other failure throws, so an enabled semantic mode never boots
     * as keyword-only. Returns the successful attempt (1-based).
     */
    public int awaitDenseMessageReadiness(GroupScope scope, double minCosine, java.time.Duration perAttempt, int attempts) {
        return awaitDenseMessageReadiness(b -> http.send("POST", base + "/search/", b), scope, minCosine, perAttempt, attempts);
    }

    int awaitDenseMessageReadiness(java.util.function.Function<Map<String,Object>, Http.Response> send, GroupScope scope, double minCosine,
                                   java.time.Duration perAttempt, int attempts) {
        if (attempts < 1 || attempts > 5 || perAttempt.isNegative() || perAttempt.isZero() || perAttempt.toSeconds() > 20)
            throw new IllegalArgumentException("readiness bounds: 1..5 attempts, 0 < timeout <= 20 s");
        String timeout = perAttempt.toMillis() + "ms";
        for (int i = 1; ; i++) {
            Http.Response r = send.apply(denseMessageBody(scope, "semantic message retrieval readiness", 1, minCosine, timeout));
            if (r.status() == 504 && i < attempts) continue;
            check(r, "dense message readiness (attempt " + i + "/" + attempts + ")", 200);
            parseDenseMessages(scope, r.body(), minCosine);
            return i;
        }
    }

    /** The dense message channel as a {@link Retriever} (for fusion). */
    public Retriever denseMessages(double minCosine) {
        return (scope, query, k) -> retrieveDenseMessages(scope, query, Math.min(k, 200), minCosine);
    }

    static String knowledgeYql(String groupId, boolean activeOnly) {
        requireGroup(groupId);
        return "select knowledge_id, group_id, validity from knowledge where group_id contains \"" + groupId + "\""
                + (activeOnly ? " and validity contains \"ACTIVE\"" : "") + " and userQuery()";
    }

    /** Same failure rules as {@link #parseHits}: any error fails; a hit from another group is an isolation violation. */
    static List<KnowledgeHit> parseKnowledgeHits(GroupScope scope, JsonNode body) {
        JsonNode errors = body.path("root").path("errors");
        if (errors.isArray() && errors.size() > 0) {
            throw new Http.ProjectionException("vespa query error code " + errors.get(0).path("code").asInt()
                    + ": " + errors.get(0).path("summary").asText());
        }
        if (body.path("root").isMissingNode()) throw new Http.ProjectionException("vespa response has no root");
        List<KnowledgeHit> out = new ArrayList<>();
        for (JsonNode c : body.path("root").path("children")) {
            JsonNode f = c.path("fields");
            if (!scope.groupId().equals(f.path("group_id").asText())) {
                throw new app.dita.core.model.IsolationViolation("vespa returned a hit outside scope " + scope.groupId());
            }
            out.add(new KnowledgeHit(f.path("knowledge_id").asText(), c.path("relevance").asDouble(), f.path("validity").asText(null)));
        }
        return out;
    }

    public int knowledgeStatus(String groupId, String knowledgeId) { return http.send("GET", knowledgeUrl(groupId, knowledgeId), null).status(); }

    /** Inspection helper for tests: the stored fields of one knowledge document (empty if absent). */
    public Optional<JsonNode> knowledgeDocument(String groupId, String knowledgeId) {
        requireGroup(groupId);
        Http.Response r = http.send("GET", knowledgeUrl(groupId, knowledgeId), null);
        if (r.status() == 404) return Optional.empty();
        check(r, "get knowledge", 200);
        return Optional.of(r.body().path("fields"));
    }

    @Override public void reconcile(String groupId, ProjectionSource truth) {
        requireGroup(groupId);
        List<String> stale = new ArrayList<>();
        visit("message", groupId, d -> stale.add(d.path("fields").path("message_id").asText()));
        for (String id : stale) if (truth.message(groupId, id).filter(x -> !x.deleted()).isEmpty()) onMessage(groupId, id, truth);
        // Knowledge: re-render every indexed item from truth (removes gone/purged ones), then add items never seen.
        Set<String> ids = new LinkedHashSet<>();
        visitKnowledge(groupId, d -> ids.add(d.path("fields").path("knowledge_id").asText()));
        if (truth instanceof KnowledgeTruth kt) ids.addAll(kt.knowledgeIds(groupId));
        ids.forEach(id -> onKnowledge(groupId, id, truth));
    }

    private static void requireGroup(String g) {
        if (!g.matches(GROUP_ID)) throw new IllegalArgumentException("group id not safe for a Vespa query: " + g);
    }

    /** BM25 over this group only. */
    @Override public List<Hit> retrieve(GroupScope scope, String query, int k) {
        requireGroup(scope.groupId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("yql", "select message_id, group_id from message where group_id contains \"" + scope.groupId() + "\" and userQuery()");
        body.put("query", query);
        body.put("type", "any");
        body.put("ranking", "bm25");
        body.put("hits", k);
        Http.Response r = http.send("POST", base + "/search/", body);
        check(r, "search", 200);
        return parseHits(scope, r.body(), "vespa:bm25");
    }

    /**
     * Parses a search response. ANY root.errors entry is a failure (never "no results"): an invalid query must not
     * look like a clean, empty answer (regression: malformed nearestNeighbor YQL once produced a false-positive PASS).
     * Hits from another group are a hard isolation failure, not silently filtered.
     */
    static List<Hit> parseHits(GroupScope scope, JsonNode body, String via) {
        JsonNode errors = body.path("root").path("errors");
        if (errors.isArray() && errors.size() > 0) {
            throw new Http.ProjectionException("vespa query error code " + errors.get(0).path("code").asInt()
                    + ": " + errors.get(0).path("summary").asText());
        }
        if (body.path("root").isMissingNode()) throw new Http.ProjectionException("vespa response has no root");
        List<Hit> out = new ArrayList<>();
        for (JsonNode c : body.path("root").path("children")) {
            JsonNode f = c.path("fields");
            if (!scope.groupId().equals(f.path("group_id").asText())) {
                throw new app.dita.core.model.IsolationViolation("vespa returned a hit outside scope " + scope.groupId());
            }
            out.add(new Hit(f.path("message_id").asText(), c.path("relevance").asDouble(), via, false));
        }
        return out;
    }

    /** Feed responses must name exactly the document we wrote; anything else is a failure. */
    static void requireFed(JsonNode body, String groupId, String messageId) { requireFed(body, "message", groupId, messageId); }

    static void requireFed(JsonNode body, String type, String groupId, String id) {
        String expected = "id:dita:" + type + "::" + groupId + "::" + id;
        if (!expected.equals(body.path("id").asText()) || body.has("message")) {
            throw new Http.ProjectionException("vespa feed not acknowledged for the expected document");
        }
    }

    /** Group-filtered nearest-neighbour YQL. The annotated operator MUST be parenthesised after AND. */
    static String nearestNeighborYql(String groupId, int targetHits, boolean orUserQuery) {
        requireGroup(groupId);
        if (targetHits < 1 || targetHits > 1000) throw new IllegalArgumentException("targetHits in [1,1000]");
        String nn = "({targetHits:" + targetHits + "}nearestNeighbor(embedding,q))";
        return "select message_id, group_id from message where group_id contains \"" + groupId + "\" and "
                + (orUserQuery ? "(" + nn + " or userQuery())" : nn);
    }

    public int status(String groupId, String messageId) { return http.send("GET", docUrl(groupId, messageId), null).status(); }
}
