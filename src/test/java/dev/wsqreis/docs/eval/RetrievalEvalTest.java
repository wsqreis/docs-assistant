package dev.wsqreis.docs.eval;

import dev.wsqreis.docs.search.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvalTest {

    @Test
    void starMatchesTheSectionAndItsSubsectionsOnly() {
        var expected = List.of("web.graceful-shutdown*");
        assertThat(RetrievalEval.matches("web.graceful-shutdown", expected)).isTrue();
        assertThat(RetrievalEval.matches("web.graceful-shutdown.disabling", expected)).isTrue();
        assertThat(RetrievalEval.matches("web.graceful-shutdown-other", expected)).isFalse();
        assertThat(RetrievalEval.matches("web.graceful", List.of("web.graceful-shutdown"))).isFalse();
    }

    @Test
    void rankIsOneBasedAndZeroWhenAbsent() {
        var results = List.of(hit("a"), hit("b"), hit("c"));
        assertThat(RetrievalEval.firstRelevantRank(results, List.of("b"))).isEqualTo(2);
        assertThat(RetrievalEval.firstRelevantRank(results, List.of("z"))).isZero();
    }

    private static SearchResult hit(String anchor) {
        return new SearchResult(anchor, "p", anchor, anchor, anchor, "u", "c", 0);
    }
}
