package dev.wsqreis.docs.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Antora AsciiDoc pages into section-level chunks.
 *
 * <p>Sections are the natural unit: each has a stable {@code [[anchor]]}, so a chunk links straight to its
 * place in the published docs. The local embedding model reads at most 256 tokens, so long sections are cut
 * into windows of about {@value #WINDOW_CHARS} characters at paragraph boundaries. Antora macros are rewritten
 * to the text a reader sees.
 */
public class AsciidocChunker {

    static final int WINDOW_CHARS = 900;
    private static final String BASE_URL = "https://docs.spring.io/spring-boot/";

    private static final Pattern HEADING = Pattern.compile("^(=+)\\s+(.+)$");
    private static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\]]+)]]$|^\\[#([^\\],]+)[^\\]]*]$");

    private static final Pattern JAVADOC = Pattern.compile("javadoc:([\\w.$/{}-]+)(?:#([^\\[\\s]+))?\\[([^\\]]*)]");
    private static final Pattern CONFIGPROP = Pattern.compile("configprop:([\\w.\\-\\[\\]*]+)\\[[^\\]]*]");
    private static final Pattern INCLUDE_CODE = Pattern.compile("^include-code::(\\w+)\\[.*]$");
    private static final Pattern LINK_WITH_TEXT = Pattern.compile(
            "(?:xref:|link:|https?://|\\{url-[\\w-]+})[^\\s\\[]*\\[([^\\]]+)]");
    private static final Pattern BARE_XREF = Pattern.compile("xref:[^\\s\\[]*\\[]");
    private static final Pattern ATTRIBUTE_REF = Pattern.compile("\\{[\\w-]+}");

    /**
     * @param module Antora module ("ROOT", "reference", "how-to", ...)
     * @param page   path of the page inside the module's pages folder, e.g. "web/servlet.adoc"
     */
    public List<Chunk> chunk(String module, String page, String source) {
        var pagePath = page.replaceFirst("\\.adoc$", "");
        var pageUrl = BASE_URL + ("ROOT".equals(module) ? "" : module + "/") + pagePath + ".html";

        var sections = new ArrayList<Section>();
        Deque<String> titles = new ArrayDeque<>();
        String pendingAnchor = null;
        Section current = null;

        for (var line : source.split("\n", -1)) {
            var anchor = ANCHOR.matcher(line.strip());
            if (anchor.matches()) {
                pendingAnchor = anchor.group(1) != null ? anchor.group(1) : anchor.group(2);
                continue;
            }
            var heading = HEADING.matcher(line);
            if (heading.matches() && !line.startsWith("====")) {
                int level = heading.group(1).length();
                var title = cleanInline(heading.group(2).strip());
                while (titles.size() >= level) {
                    titles.removeLast();
                }
                titles.addLast(title);
                var id = pendingAnchor != null ? pendingAnchor : slug(title);
                current = new Section(id, title, String.join(" › ", titles), new StringBuilder());
                sections.add(current);
                pendingAnchor = null;
                continue;
            }
            if (current != null) {
                current.body().append(line).append('\n');
            }
        }

        var chunks = new ArrayList<Chunk>();
        for (var section : sections) {
            var text = cleanBody(section.body().toString());
            if (text.isBlank()) {
                continue;
            }
            var windows = windows(text);
            for (int i = 0; i < windows.size(); i++) {
                var key = module + "/" + pagePath + "#" + section.anchor() + (windows.size() > 1 ? "/" + i : "");
                chunks.add(new Chunk(id(key), module + "/" + pagePath, section.anchor(), section.title(),
                        section.breadcrumb(), pageUrl + "#" + section.anchor(), windows.get(i)));
            }
        }
        return chunks;
    }

    private record Section(String anchor, String title, String breadcrumb, StringBuilder body) {
    }

    /** Paragraph-aligned windows of at most WINDOW_CHARS; a single longer paragraph is cut at a sentence end. */
    static List<String> windows(String text) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        for (var paragraph : text.split("\n\n")) {
            for (var piece : splitLong(paragraph.strip())) {
                if (current.length() > 0 && current.length() + piece.length() + 2 > WINDOW_CHARS) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static List<String> splitLong(String paragraph) {
        if (paragraph.length() <= WINDOW_CHARS) {
            return List.of(paragraph);
        }
        var pieces = new ArrayList<String>();
        var rest = paragraph;
        while (rest.length() > WINDOW_CHARS) {
            int cut = Math.max(rest.lastIndexOf(". ", WINDOW_CHARS), rest.lastIndexOf('\n', WINDOW_CHARS));
            cut = cut < WINDOW_CHARS / 3 ? WINDOW_CHARS : cut + 1;
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }

    /** Drops AsciiDoc structure that a reader never sees and rewrites macros to their visible text. */
    static String cleanBody(String body) {
        var out = new StringBuilder();
        for (var raw : body.split("\n", -1)) {
            var line = raw.stripTrailing();
            var trimmed = line.strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("ifdef::") || trimmed.startsWith("ifndef::")
                    || trimmed.startsWith("endif::") || trimmed.matches("^:[\\w-]+:.*") || trimmed.startsWith("include::")
                    || trimmed.matches("^(-{4,}|={4,}|\\*{4,}|\\.{4,}|\\|===|_{4,})$")
                    || trimmed.matches("^\\[(source|NOTE|TIP|WARNING|IMPORTANT|CAUTION|cols|%|configprops|indent)[^]]*]$")) {
                continue;
            }
            var code = INCLUDE_CODE.matcher(trimmed);
            if (code.matches()) {
                out.append("(code example: ").append(code.group(1)).append(")\n");
                continue;
            }
            out.append(cleanInline(line)).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    static String cleanInline(String text) {
        var result = replace(JAVADOC, text, m -> {
            var label = m.group(3).strip();
            if (!label.isEmpty() && !label.startsWith("format=")) {
                return label; // explicit link text, e.g. javadoc:...#enum-constant-summary[in the CloudPlatform enum]
            }
            var target = m.group(1);
            var simple = target.substring(Math.max(target.lastIndexOf('.'), target.lastIndexOf('/')) + 1).replace('$', '.');
            var member = m.group(2) == null || m.group(2).contains("-") ? "" : "." + m.group(2).replaceAll("\\(.*\\)", "()");
            return (label.contains("annotation") ? "@" : "") + simple + member;
        });
        result = replace(CONFIGPROP, result, m -> m.group(1));
        result = replace(LINK_WITH_TEXT, result, m -> m.group(1));
        result = BARE_XREF.matcher(result).replaceAll("");
        result = ATTRIBUTE_REF.matcher(result).replaceAll("");
        return result.replace("\"`", "\"").replace("`\"", "\"");
    }

    private static String replace(Pattern pattern, String text, Function<MatchResult, String> replacement) {
        return pattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(replacement.apply(m)));
    }

    private static String slug(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String id(String key) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
