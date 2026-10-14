package dev.wsqreis.docs.ingest;

/**
 * One retrievable piece of documentation: a section, or a window of a long section.
 *
 * @param breadcrumb page title and parent section titles, e.g. "Servlet Web Applications › Spring MVC"
 * @param url        deep link to the section on docs.spring.io
 * @param content    cleaned section text, without the breadcrumb
 */
public record Chunk(String id, String page, String anchor, String title, String breadcrumb, String url, String content) {

    /** What gets embedded and indexed: the breadcrumb gives a short window its context. */
    public String indexText() {
        return breadcrumb + "\n" + content;
    }
}
