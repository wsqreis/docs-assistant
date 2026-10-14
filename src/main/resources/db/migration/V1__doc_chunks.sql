CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE doc_chunks (
    id            TEXT PRIMARY KEY,
    page          TEXT        NOT NULL,
    anchor        TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    breadcrumb    TEXT        NOT NULL,
    url           TEXT        NOT NULL,
    content       TEXT        NOT NULL,
    -- hash of the embedding model id and the indexed text: a change in either triggers re-embedding
    content_hash  TEXT        NOT NULL,
    embedding     vector(384) NOT NULL,
    -- full-text side of hybrid search; section titles weigh more than body text
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', breadcrumb), 'A') || setweight(to_tsvector('english', content), 'B')
    ) STORED,
    indexed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX doc_chunks_embedding ON doc_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX doc_chunks_search ON doc_chunks USING gin (search_vector);
