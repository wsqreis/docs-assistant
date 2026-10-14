package dev.wsqreis.docs.ingest;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ChunkStore {

    private final JdbcTemplate jdbc;

    public ChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** id -> content hash of everything indexed. */
    public Map<String, String> hashes() {
        var result = new HashMap<String, String>();
        jdbc.query("SELECT id, content_hash FROM doc_chunks", row -> {
            result.put(row.getString(1), row.getString(2));
        });
        return result;
    }

    public void upsert(List<Chunk> chunks, List<String> hashes, List<float[]> embeddings) {
        jdbc.batchUpdate("""
                INSERT INTO doc_chunks (id, page, anchor, title, breadcrumb, url, content, content_hash, embedding, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, now())
                ON CONFLICT (id) DO UPDATE SET
                    page = excluded.page, anchor = excluded.anchor, title = excluded.title,
                    breadcrumb = excluded.breadcrumb, url = excluded.url, content = excluded.content,
                    content_hash = excluded.content_hash, embedding = excluded.embedding, indexed_at = now()
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                var chunk = chunks.get(i);
                ps.setString(1, chunk.id());
                ps.setString(2, chunk.page());
                ps.setString(3, chunk.anchor());
                ps.setString(4, chunk.title());
                ps.setString(5, chunk.breadcrumb());
                ps.setString(6, chunk.url());
                ps.setString(7, chunk.content());
                ps.setString(8, hashes.get(i));
                ps.setString(9, toVectorLiteral(embeddings.get(i)));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    public int delete(List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("DELETE FROM doc_chunks WHERE id = ANY (?)", (Object) ids.toArray(String[]::new));
    }

    public long count() {
        var count = jdbc.queryForObject("SELECT count(*) FROM doc_chunks", Long.class);
        return count == null ? 0 : count;
    }

    /** Recomputes how many chunks contain each lexeme (used by keyword search to skip ubiquitous terms). */
    public void refreshLexemeStats() {
        jdbc.execute("TRUNCATE lexeme_stats");
        jdbc.execute("INSERT INTO lexeme_stats (word, ndoc) SELECT word, ndoc FROM ts_stat('SELECT search_vector FROM doc_chunks')");
    }

    public boolean lexemeStatsEmpty() {
        return Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM lexeme_stats)", Boolean.class));
    }

    /** pgvector accepts the text form '[0.1,0.2,...]' cast to vector. */
    public static String toVectorLiteral(float[] vector) {
        var sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
