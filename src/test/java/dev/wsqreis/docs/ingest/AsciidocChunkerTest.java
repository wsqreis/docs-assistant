package dev.wsqreis.docs.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsciidocChunkerTest {

    private final AsciidocChunker chunker = new AsciidocChunker();

    private static final String PAGE = """
            [[web.server]]
            = Embedded Web Servers

            Intro text.

            [[web.server.port]]
            == Changing the Port

            Set configprop:server.port[] to choose the port.
            See xref:reference:web/server.adoc#web.server.shutdown[graceful shutdown] and {url-docs}/x.html[the guide].

            include-code::PortConfiguration[]

            [[web.server.port.random]]
            === Random Port

            Use javadoc:org.example.web.WebServer#getPort()[] or javadoc:org.example.Lifecycle[format=annotation].
            """;

    @Test
    void oneChunkPerSectionWithAnchorBreadcrumbAndDeepLink() {
        var chunks = chunker.chunk("reference", "web/server.adoc", PAGE);

        assertThat(chunks).extracting(Chunk::anchor).containsExactly("web.server", "web.server.port", "web.server.port.random");
        var random = chunks.get(2);
        assertThat(random.breadcrumb()).isEqualTo("Embedded Web Servers › Changing the Port › Random Port");
        assertThat(random.url()).isEqualTo("https://docs.spring.io/spring-boot/reference/web/server.html#web.server.port.random");
        assertThat(random.page()).isEqualTo("reference/web/server");
    }

    @Test
    void rootModulePagesHaveNoModuleInTheUrl() {
        var chunk = chunker.chunk("ROOT", "index.adoc", "[[index]]\n= Overview\n\nHello.\n").getFirst();
        assertThat(chunk.url()).isEqualTo("https://docs.spring.io/spring-boot/index.html#index");
    }

    @Test
    void rewritesAntoraMacrosToWhatReadersSee() {
        var chunks = chunker.chunk("reference", "web/server.adoc", PAGE);

        assertThat(chunks.get(1).content())
                .contains("Set server.port to choose the port.")
                .contains("See graceful shutdown and the guide.")
                .contains("(code example: PortConfiguration)")
                .doesNotContain("xref:", "configprop:", "{url-docs}");
        assertThat(chunks.get(2).content()).contains("Use WebServer.getPort() or @Lifecycle.");
    }

    @Test
    void dropsCommentsAttributesAndBlockDelimiters() {
        var page = """
                = Title

                // internal note
                :attr: value
                [source,java]
                ----
                int port = 8080;
                ----
                Text.
                """;
        var content = chunker.chunk("ROOT", "p.adoc", page).getFirst().content();
        assertThat(content).isEqualTo("int port = 8080;\nText.");
    }

    @Test
    void splitsLongSectionsIntoParagraphAlignedWindows() {
        var paragraph = "This sentence is about forty characters. ".repeat(10).strip();
        var page = "= Title\n\n" + String.join("\n\n", java.util.Collections.nCopies(6, paragraph)) + "\n";

        var chunks = chunker.chunk("ROOT", "long.adoc", page);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(AsciidocChunker.WINDOW_CHARS));
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
        assertThat(chunks).extracting(Chunk::anchor).containsOnly("title");
    }

    @Test
    void idsAreStableAcrossRuns() {
        var first = chunker.chunk("reference", "web/server.adoc", PAGE);
        var second = chunker.chunk("reference", "web/server.adoc", PAGE);
        assertThat(first).extracting(Chunk::id).isEqualTo(second.stream().map(Chunk::id).toList());
    }
}
