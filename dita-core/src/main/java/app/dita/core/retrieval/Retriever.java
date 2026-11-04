package app.dita.core.retrieval;

import app.dita.core.model.GroupScope;

import java.util.List;

/** Implementations: Lexical, GraphExpanding, Hybrid (RRF); Vector and Vespa later. */
public interface Retriever {
    List<Hit> retrieve(GroupScope scope, String query, int k);
}
