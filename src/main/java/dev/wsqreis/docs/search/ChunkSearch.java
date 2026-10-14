package dev.wsqreis.docs.search;

import dev.wsqreis.docs.ingest.ChunkStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** The two retrieval primitives, as SQL. */
@Repository
public class ChunkSearch {

    private static final String COLUMNS = "id, page, anchor, title, breadcrumb, url, content";

    private static final RowMapper<SearchResult> ROW = (rs, i) -> new SearchResult(rs.getString("id"),
            rs.getString("page"), rs.getString("anchor"), rs.getString("title"), rs.getString("breadcrumb"),
            rs.getString("url"), rs.getString("content"), rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    public ChunkSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Nearest neighbours by cosine distance (HNSW index); score is cosine similarity. */
    public List<SearchResult> byVector(float[] query, int limit) {
        var vector = ChunkStore.toVectorLiteral(query);
        return jdbc.query("SELECT " + COLUMNS + ", 1 - (embedding <=> ?::vector) AS score FROM doc_chunks "
                + "ORDER BY embedding <=> ?::vector LIMIT ?", ROW, vector, vector, limit);
    }

    /**
     * Full-text search with corpus-aware term selection.
     *
     * <p>The question is reduced to stemmed lexemes, and lexemes found in more than {@code maxDocFraction} of
     * all chunks are dropped: in these docs "spring", "use" and "configur" appear in most sections, and OR-ing
     * them matches nearly everything (ts_rank_cd has no notion of term rarity). The remaining terms are OR-ed,
     * because a natural-language question rarely contains every word of the section that answers it.
     * Lexemes are cast to tsquery directly, so identifiers such as "spring.jpa.open" or "in-view" are not re-parsed.
     */
    public List<SearchResult> byKeywords(String query, int limit, double maxDocFraction) {
        return jdbc.query("""
                WITH total AS (SELECT count(*) AS n FROM doc_chunks),
                terms AS (
                    SELECT t.word
                    FROM unnest(tsvector_to_array(to_tsvector('english', ?))) AS t(word)
                    JOIN lexeme_stats s ON s.word = t.word, total
                    -- the floor keeps small corpora searchable, where a tenth of the chunks is less than one
                    WHERE s.ndoc <= GREATEST(total.n * ?, 3)
                ),
                q AS (SELECT string_agg(format('%%L', word), ' | ')::tsquery AS terms FROM terms)
                SELECT %s, ts_rank_cd(search_vector, q.terms) AS score
                FROM doc_chunks, q
                WHERE q.terms IS NOT NULL AND search_vector @@ q.terms
                ORDER BY score DESC
                LIMIT ?
                """.formatted(COLUMNS), ROW, query, maxDocFraction, limit);
    }
}
