package dev.wsqreis.docs;

import dev.wsqreis.docs.answer.AnswerCache;
import dev.wsqreis.docs.ingest.IngestionService;
import dev.wsqreis.docs.mcp.DocsTools;
import dev.wsqreis.docs.search.SearchMode;
import dev.wsqreis.docs.search.SearchService;
import dev.wsqreis.docs.support.HashingEmbeddingModel;
import dev.wsqreis.docs.support.StreamingScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application against real PostgreSQL + pgvector; only the models are fakes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.embedding=none",
        "spring.ai.model.chat=none",
        "docs.answer.min-similarity=0.15",
        "docs.answer.idle-timeout=2s"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocsFlowIT {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // Singleton container: it must outlive the cached Spring context.
    static {
        postgres.start();
    }

    @TestConfiguration
    static class FakeModels {
        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return new HashingEmbeddingModel();
        }

        @Bean
        @Primary
        StreamingScriptedChatModel chatModel() {
            return new StreamingScriptedChatModel();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    IngestionService ingestion;
    @Autowired
    SearchService search;
    @Autowired
    DocsTools tools;
    @Autowired
    StreamingScriptedChatModel chat;
    @Autowired
    AnswerCache cache;
    @Autowired
    JdbcTemplate jdbc;

    static Path fixture() throws Exception {
        return Path.of(new ClassPathResource("docs/modules").getURI());
    }

    @BeforeEach
    void indexFixture() throws Exception {
        ingestion.ingest(fixture());
    }

    @Test
    @Order(1)
    void ingestionIsIncremental(@TempDir Path copy) throws Exception {
        assertThat(ingestion.ingest(fixture())).satisfies(r -> {
            assertThat(r.chunks()).isEqualTo(6);
            assertThat(r.embedded()).isZero();
        });

        copyTree(fixture(), copy);
        var server = copy.resolve("reference/pages/web/server.adoc");
        Files.writeString(server, Files.readString(server).replace("Applications start", "Every application starts"));
        Files.delete(copy.resolve("ROOT/pages/index.adoc"));

        var report = ingestion.ingest(copy);
        assertThat(report.embedded()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(2);

        ingestion.ingest(fixture()); // restore for the other tests
    }

    @Test
    @Order(2)
    void keywordSearchFindsExactPropertyNames() {
        var results = search.search("server.shutdown.timeout", SearchMode.KEYWORD, 3);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().anchor()).isEqualTo("web.server.shutdown.timeout");
    }

    @Test
    @Order(3)
    void vectorAndHybridSearchReturnDeepLinks() {
        for (var mode : List.of(SearchMode.VECTOR, SearchMode.HYBRID)) {
            var top = search.search("change the HTTP port of the server", mode, 1).getFirst();
            assertThat(top.anchor()).as(mode.name()).isEqualTo("web.server.port");
            assertThat(top.url()).isEqualTo("https://docs.spring.io/spring-boot/reference/web/server.html#web.server.port");
        }
    }

    @Test
    @Order(4)
    void streamsAnAnswerWithSourcesThenServesTheSameQuestionFromCache() throws Exception {
        cache.clear();
        chat.reply("Set ", "`server.port` ", "[1].");
        int callsBefore = chat.calls();

        var first = ask("How do I change the HTTP port of the server?");
        assertThat(first.names()).startsWith("sources").endsWith("done");
        assertThat(first.dataOf("sources")).contains("web.server.port");
        assertThat(String.join("", first.dataAll("token"))).contains("Set ").contains("[1].");
        assertThat(first.dataOf("done")).contains("\"cached\":false");
        assertThat(chat.lastPrompt().getUserMessage().getText()).contains("[1] Embedded Web Servers › Changing the Port");

        var second = ask("How do I change the HTTP port of the server?");
        assertThat(second.dataOf("done")).contains("\"cached\":true");
        assertThat(chat.calls()).isEqualTo(callsBefore + 1);
    }

    @Test
    @Order(5)
    void declinesOffTopicQuestionsWithoutCallingTheModel() throws Exception {
        int callsBefore = chat.calls();
        var events = ask("Sourdough loaf: knead, proof overnight, bake hot");
        assertThat(events.names()).containsExactly("declined");
        assertThat(chat.calls()).isEqualTo(callsBefore);
    }

    @Test
    @Order(6)
    void reportsModelFailuresAsAnEvent() throws Exception {
        cache.clear();
        chat.fail(new RuntimeException("503 from provider"));
        var events = ask("What is the graceful shutdown timeout?");
        assertThat(events.names()).containsExactly("sources", "failure");
        assertThat(events.dataOf("failure")).doesNotContain("503");
        chat.reply("ok");
    }

    @Test
    @Order(7)
    void endsTheStreamWhenTheModelStalls() throws Exception {
        cache.clear();
        chat.stall();
        var started = System.nanoTime();
        var events = ask("What is the graceful shutdown timeout?");
        assertThat(events.names()).containsExactly("sources", "failure");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        chat.reply("ok");
    }

    @Test
    @Order(8)
    void reindexingClearsTheAnswerCache(@TempDir Path copy) throws Exception {
        chat.reply("cached answer");
        ask("How do I change the HTTP port of the server?");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_cache", Long.class)).isPositive();

        copyTree(fixture(), copy);
        Files.writeString(copy.resolve("ROOT/pages/index.adoc"), "[[index]]\n= Example Docs\n\nChanged.\n");
        ingestion.ingest(copy);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_cache", Long.class)).isZero();
        ingestion.ingest(fixture());
    }

    @Test
    @Order(9)
    void mcpToolsSearchAndReadSections() {
        var hits = tools.searchDocs("graceful shutdown requests in progress", 2);
        assertThat(hits).isNotEmpty();
        var section = tools.readSection(hits.getFirst().url());
        assertThat(section.content()).isNotBlank();
        assertThatThrownBy(() -> tools.readSection("https://example.com/nope"))
                .hasMessageContaining("Use a URL from search_spring_boot_docs");
    }

    @Test
    @Order(10)
    void rejectsInvalidRequests() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/search?q=")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // --- SSE client helpers ---

    record Event(String name, String data) {
    }

    record Events(List<Event> events) {
        List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        String dataOf(String name) {
            return events.stream().filter(e -> e.name().equals(name)).map(Event::data).findFirst().orElse("");
        }

        List<String> dataAll(String name) {
            return events.stream().filter(e -> e.name().equals(name))
                    .map(e -> e.data().replaceAll("^\\{\"text\":\"(.*)\"}$", "$1")).toList();
        }
    }

    private Events ask(String question) throws Exception {
        var uri = URI.create("http://localhost:" + port + "/api/ask?q=" + URLEncoder.encode(question, StandardCharsets.UTF_8));
        var lines = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).header("accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofLines())
                .body().toList();
        var events = new ArrayList<Event>();
        String name = null;
        for (var line : lines) {
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:") && name != null) {
                events.add(new Event(name, line.substring(5)));
                name = null;
            }
        }
        return new Events(events);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (var source : walk.toList()) {
                var target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }
}
