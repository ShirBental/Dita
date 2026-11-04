package app.dita.core.retrieval;

/** A retrieved message. {@code via} records how it was found (lexical, graph:EXPLICIT_REPLY, ...). */
public record Hit(String messageId, double score, String via, boolean inferredContext) {}
