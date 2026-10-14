package dev.wsqreis.docs.eval;

import dev.wsqreis.docs.search.SearchMode;
import dev.wsqreis.docs.search.SearchResult;
import dev.wsqreis.docs.search.SearchService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval quality on hand-labelled questions, for each search mode. No LLM involved: it measures only
 * whether the right documentation section is retrieved, which is what bounds answer quality.
 *
 * <pre>java -jar docs-assistant.jar --spring.main.web-application-type=none --eval=eval/questions.json</pre>
 */
@Component
public class RetrievalEval implements ApplicationRunner {

    static final int DEPTH = 10;

    record Question(String id, String kind, String question, List<String> expected) {
    }

    record Scores(double hitAt1, double hitAt3, double hitAt5, double mrr, int questions) {
    }

    private final SearchService search;
    private final JsonMapper json;
    private final ApplicationContext context;

    public RetrievalEval(SearchService search, JsonMapper json, ApplicationContext context) {
        this.search = search;
        this.json = json;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("eval")) {
            return;
        }
        List<Question> questions = json.readValue(Path.of(args.getOptionValues("eval").getFirst()).toFile(),
                new TypeReference<>() {
                });

        var report = new LinkedHashMap<String, Object>();
        report.put("ranAt", Instant.now().toString());
        report.put("questions", questions.size());
        var misses = new LinkedHashMap<String, List<String>>();

        System.out.printf("%n%-8s %-8s %7s %7s %7s %7s%n", "mode", "kind", "hit@1", "hit@3", "hit@5", "MRR");
        for (var mode : SearchMode.values()) {
            var ranks = new LinkedHashMap<Question, Integer>();
            for (var q : questions) {
                ranks.put(q, firstRelevantRank(search.search(q.question(), mode, DEPTH), q.expected()));
            }
            var byKind = new LinkedHashMap<String, Scores>();
            for (var kind : List.of("all", "concept", "exact")) {
                var scores = scores(ranks, kind);
                byKind.put(kind, scores);
                System.out.printf("%-8s %-8s %6.0f%% %6.0f%% %6.0f%% %7.3f%n", mode.name().toLowerCase(), kind,
                        scores.hitAt1() * 100, scores.hitAt3() * 100, scores.hitAt5() * 100, scores.mrr());
            }
            report.put(mode.name().toLowerCase(), byKind);
            misses.put(mode.name().toLowerCase(), ranks.entrySet().stream()
                    .filter(e -> e.getValue() == 0 || e.getValue() > 5).map(e -> e.getKey().id()).toList());
        }
        report.put("missedTop5", misses);
        System.out.println("\nNot in the top 5: " + misses);

        var dir = Path.of("target", "eval");
        Files.createDirectories(dir);
        var file = dir.resolve("retrieval-" + System.currentTimeMillis() + ".json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Results: " + file.toAbsolutePath());

        if (args.containsOption("exit")) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /** 1-based rank of the first result whose section is one of the expected ones; 0 if none in the list. */
    static int firstRelevantRank(List<SearchResult> results, List<String> expected) {
        for (int i = 0; i < results.size(); i++) {
            if (matches(results.get(i).anchor(), expected)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** "a.b" matches exactly; "a.b*" also matches subsections such as "a.b.c". */
    static boolean matches(String anchor, List<String> expected) {
        for (var e : expected) {
            if (e.endsWith("*")) {
                var prefix = e.substring(0, e.length() - 1);
                if (anchor.equals(prefix) || anchor.startsWith(prefix + ".")) {
                    return true;
                }
            } else if (anchor.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private static Scores scores(Map<Question, Integer> ranks, String kind) {
        var selected = new ArrayList<Integer>();
        ranks.forEach((q, rank) -> {
            if (kind.equals("all") || kind.equals(q.kind())) {
                selected.add(rank);
            }
        });
        int n = selected.size();
        double at1 = selected.stream().filter(r -> r == 1).count() / (double) n;
        double at3 = selected.stream().filter(r -> r >= 1 && r <= 3).count() / (double) n;
        double at5 = selected.stream().filter(r -> r >= 1 && r <= 5).count() / (double) n;
        double mrr = selected.stream().mapToDouble(r -> r == 0 ? 0 : 1.0 / r).sum() / n;
        return new Scores(at1, at3, at5, mrr, n);
    }
}
