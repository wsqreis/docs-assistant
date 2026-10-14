-- Semantic cache: answers keyed by the embedding of the question, so paraphrases of a question
-- already answered skip retrieval and the LLM call entirely.
CREATE TABLE answer_cache (
    id          BIGSERIAL PRIMARY KEY,
    question    TEXT        NOT NULL,
    embedding   vector(384) NOT NULL,
    answer      TEXT        NOT NULL,
    sources     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX answer_cache_embedding ON answer_cache USING hnsw (embedding vector_cosine_ops);
