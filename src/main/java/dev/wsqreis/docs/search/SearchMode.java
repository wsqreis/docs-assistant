package dev.wsqreis.docs.search;

public enum SearchMode {
    /** Semantic similarity of embeddings (pgvector, cosine). */
    VECTOR,
    /** PostgreSQL full-text search: exact terms like property names and class names. */
    KEYWORD,
    /** Both lists merged with Reciprocal Rank Fusion. */
    HYBRID
}
