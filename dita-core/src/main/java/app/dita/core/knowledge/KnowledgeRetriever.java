package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;

import java.util.List;

/**
 * Ranks a group's CURRENT (ACTIVE) knowledge items for a question. Group-scoped: implementations must never return an
 * item of another group (the composer re-reads every hit from the store in the same scope and drops anything else).
 * Implementations: {@link LexicalKnowledgeRetriever} (in-process BM25, default / offline) and, on the POSTGRES backend,
 * Vespa's knowledge index (dita-app wires VespaProjection.retrieveKnowledge). TASK-049 adds Vespa dense candidates with content-bound semantic admission.
 */
public interface KnowledgeRetriever {
    /** semanticAdmission is set only after similarity and current-document checks, never from rank alone. */
    record Hit(String knowledgeId, double score, boolean semanticAdmission) {
        public Hit(String knowledgeId, double score) { this(knowledgeId, score, false); }
    }

    List<Hit> retrieve(GroupScope scope, String question, int k);

    /** The retriever's name, recorded with every answer that used it. */
    String name();
}
