package dev.wsqreis.docs.answer;

import dev.wsqreis.docs.ingest.ChunkStore;
import dev.wsqreis.docs.ingest.DocsReindexed;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
public class AnswerCache {

    public record Hit(String answer, List<Source> sources, double similarity) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AnswerCache(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Hit> find(float[] question, double minSimilarity, Duration maxAge) {
        var vector = ChunkStore.toVectorLiteral(question);
        var hits = jdbc.query("""
                SELECT answer, sources, 1 - (embedding <=> ?::vector) AS similarity
                FROM answer_cache
                WHERE created_at > now() - make_interval(secs => ?)
                ORDER BY embedding <=> ?::vector
                LIMIT 1
                """, (rs, i) -> new Hit(rs.getString("answer"),
                json.readValue(rs.getString("sources"), new TypeReference<List<Source>>() {
                }), rs.getDouble("similarity")), vector, maxAge.toSeconds(), vector);
        return hits.stream().filter(hit -> hit.similarity() >= minSimilarity).findFirst();
    }

    public void store(String question, float[] embedding, String answer, List<Source> sources) {
        jdbc.update("INSERT INTO answer_cache (question, embedding, answer, sources) VALUES (?, ?::vector, ?, ?)",
                question, ChunkStore.toVectorLiteral(embedding), answer, json.writeValueAsString(sources));
    }

    /** Cached answers may quote or cite text that just changed. */
    @EventListener(DocsReindexed.class)
    public int clear() {
        return jdbc.update("DELETE FROM answer_cache");
    }
}
