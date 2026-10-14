package dev.wsqreis.docs.search;

/** A ranked chunk. {@code score} is comparable only within one mode. */
public record SearchResult(
        String id,
        String page,
        String anchor,
        String title,
        String breadcrumb,
        String url,
        String content,
        double score) {
}
