package dev.wsqreis.docs.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SearchServiceTest {

    private static SearchResult r(String id) {
        return new SearchResult(id, "page", id, id, id, "url#" + id, "content", 0.0);
    }

    @Test
    void reciprocalRankFusionRewardsResultsFoundByBothLists() {
        var vector = List.of(r("a"), r("b"), r("c"));
        var keyword = List.of(r("c"), r("d"));

        var fused = SearchService.fuse(List.of(vector, keyword), 10);

        // c: 1/63 + 1/61 beats a: 1/61 alone
        assertThat(fused).extracting(SearchResult::id).containsExactly("c", "a", "b", "d");
        assertThat(fused.getFirst().score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
    }

    @Test
    void fusionRespectsTheLimit() {
        assertThat(SearchService.fuse(List.of(List.of(r("a"), r("b"), r("c"))), 2)).hasSize(2);
    }
}
