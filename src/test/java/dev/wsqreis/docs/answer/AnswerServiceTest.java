package dev.wsqreis.docs.answer;

import dev.wsqreis.docs.search.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerServiceTest {

    private static final String MVC_TESTS = "https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.spring-mvc-tests";
    private static final String SLICES = "https://docs.spring.io/spring-boot/how-to/testing.html#howto.testing.slice-tests";

    private static SearchResult window(String id, String url, String title, String content) {
        return new SearchResult(id, "page", "anchor", title, "Testing › " + title, url, content, 0.5);
    }

    @Test
    void mergesWindowsOfTheSameSectionIntoOneNumberedExcerpt() {
        // A real answer listed "Auto-configured Spring MVC Tests" three times among its six sources.
        var results = List.of(
                window("a#0", MVC_TESTS, "Auto-configured Spring MVC Tests", "first window"),
                window("a#1", MVC_TESTS, "Auto-configured Spring MVC Tests", "second window"),
                window("b#0", SLICES, "Structure @Configuration Classes", "slice tests"),
                window("a#2", MVC_TESTS, "Auto-configured Spring MVC Tests", "third window"));

        var sections = AnswerService.bySection(results);

        assertThat(sections).extracting(SearchResult::url).containsExactly(MVC_TESTS, SLICES);
        assertThat(sections.getFirst().content()).isEqualTo("first window\n\nsecond window\n\nthird window");

        var message = AnswerService.userMessage("How do I test a controller?", sections);
        assertThat(message).contains("[1] Testing › Auto-configured Spring MVC Tests", "[2] Testing › Structure @Configuration Classes")
                .doesNotContain("[3]");
    }
}
