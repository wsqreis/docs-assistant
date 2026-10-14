package dev.wsqreis.docs.mcp;

import dev.wsqreis.docs.search.SearchMode;
import dev.wsqreis.docs.search.SearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exposed over MCP (streamable HTTP at /mcp), so coding agents like Claude Code or Copilot can ground their
 * answers in the official Spring Boot docs. Retrieval only: the calling agent's own model writes the answer.
 */
@Component
public class DocsTools {

    private static final int EXCERPT_CHARS = 400;

    public record Hit(String title, String section, String url, String excerpt) {
    }

    public record Section(String url, String breadcrumb, String content) {
    }

    private final SearchService search;
    private final JdbcTemplate jdbc;

    public DocsTools(SearchService search, JdbcTemplate jdbc) {
        this.search = search;
        this.jdbc = jdbc;
    }

    @Tool(name = "search_spring_boot_docs", description = """
            Search the Spring Boot 4.1 reference documentation. Returns the most relevant sections with a link
            and a short excerpt. Use read_spring_boot_docs_section to read a full section before relying on it.""")
    public List<Hit> searchDocs(
            @ToolParam(description = "What to look for, in plain English or with exact property/class names") String query,
            @ToolParam(description = "How many sections to return, 1-10", required = false) Integer limit) {
        int k = limit == null ? 5 : Math.clamp(limit, 1, 10);
        return search.search(query, SearchMode.VECTOR, k).stream()
                .map(r -> new Hit(r.title(), r.breadcrumb(), r.url(), excerpt(r.content())))
                .toList();
    }

    @Tool(name = "read_spring_boot_docs_section", description = """
            Read the full text of one documentation section, given its URL from search_spring_boot_docs.""")
    public Section readSection(@ToolParam(description = "Section URL as returned by search_spring_boot_docs") String url) {
        var parts = jdbc.query("SELECT breadcrumb, content FROM doc_chunks WHERE url = ? ORDER BY id",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, url);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("No documentation section with URL " + url + ". Use a URL from search_spring_boot_docs.");
        }
        var content = String.join("\n\n", parts.stream().map(p -> p[1]).toList());
        return new Section(url, parts.getFirst()[0], content);
    }

    private static String excerpt(String content) {
        return content.length() <= EXCERPT_CHARS ? content : content.substring(0, EXCERPT_CHARS) + "…";
    }
}
