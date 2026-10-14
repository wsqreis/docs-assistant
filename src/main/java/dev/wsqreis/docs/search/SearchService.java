package dev.wsqreis.docs.search;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    /** Standard RRF constant: dampens the advantage of the very top ranks. */
    static final int RRF_K = 60;
    /** How deep each list is read before fusing. */
    static final int CANDIDATES = 30;
    /** Lexemes in more than 10% of chunks are too common to discriminate between sections. */
    static final double MAX_DOC_FRACTION = 0.10;

    private final ChunkSearch search;
    private final EmbeddingModel embeddings;

    public SearchService(ChunkSearch search, EmbeddingModel embeddings) {
        this.search = search;
        this.embeddings = embeddings;
    }

    public List<SearchResult> search(String query, SearchMode mode, int limit) {
        return search(query, mode == SearchMode.KEYWORD ? null : embeddings.embed(query), mode, limit);
    }

    /** Same as {@link #search(String, SearchMode, int)} with the query embedding already computed. */
    public List<SearchResult> search(String query, float[] embedding, SearchMode mode, int limit) {
        return switch (mode) {
            case VECTOR -> search.byVector(embedding, limit);
            case KEYWORD -> search.byKeywords(query, limit, MAX_DOC_FRACTION);
            case HYBRID -> fuse(List.of(search.byVector(embedding, CANDIDATES),
                    search.byKeywords(query, CANDIDATES, MAX_DOC_FRACTION)), limit);
        };
    }

    /** The best vector similarity for a query: how close the docs come to the question at all. */
    public double bestSimilarity(String query) {
        return bestSimilarity(embeddings.embed(query));
    }

    public double bestSimilarity(float[] embedding) {
        var top = search.byVector(embedding, 1);
        return top.isEmpty() ? 0 : top.getFirst().score();
    }

    /**
     * Reciprocal Rank Fusion: each list contributes 1 / (k + rank) for every result it contains.
     * It needs no score calibration between cosine similarity and ts_rank, which live on unrelated scales.
     */
    static List<SearchResult> fuse(List<List<SearchResult>> rankings, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, SearchResult> byId = new LinkedHashMap<>();
        for (var ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                var result = ranking.get(rank);
                scores.merge(result.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
                byId.putIfAbsent(result.id(), result);
            }
        }
        var fused = new ArrayList<SearchResult>();
        for (var entry : byId.entrySet()) {
            var r = entry.getValue();
            fused.add(new SearchResult(r.id(), r.page(), r.anchor(), r.title(), r.breadcrumb(), r.url(), r.content(),
                    scores.get(entry.getKey())));
        }
        fused.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return fused.subList(0, Math.min(limit, fused.size()));
    }
}
