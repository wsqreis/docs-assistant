package dev.wsqreis.docs.web;

import dev.wsqreis.docs.answer.AnswerListener;
import dev.wsqreis.docs.answer.AnswerService;
import dev.wsqreis.docs.answer.Source;
import dev.wsqreis.docs.search.SearchMode;
import dev.wsqreis.docs.search.SearchResult;
import dev.wsqreis.docs.search.SearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DocsController {

    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(2).toMillis();

    private final AnswerService answers;
    private final SearchService search;

    public DocsController(AnswerService answers, SearchService search) {
        this.answers = answers;
        this.search = search;
    }

    /** Retrieval only, in any mode: used by the console to compare vector, keyword and hybrid side by side. */
    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam @NotBlank @Size(max = 500) String q,
                                     @RequestParam(defaultValue = "VECTOR") SearchMode mode,
                                     @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return search.search(q, mode, limit);
    }

    /**
     * Streams an answer as Server-Sent Events: {@code sources} (the numbered sections), then {@code token}
     * events, then {@code done} ({@code {"cached": bool}}), or {@code declined} / {@code failure}.
     */
    @GetMapping(path = "/ask", produces = "text/event-stream")
    public SseEmitter ask(@RequestParam @NotBlank @Size(max = 500) String q) {
        var emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        var listener = new SseAnswerListener(emitter);
        Thread.startVirtualThread(() -> {
            try {
                answers.ask(q, listener);
            } catch (RuntimeException e) {
                // e.g. the database is down before streaming starts: still end the stream with an error event
                listener.failed(e);
            }
        });
        return emitter;
    }

    private record SseAnswerListener(SseEmitter emitter) implements AnswerListener {

        @Override
        public void sources(List<Source> sources) {
            send("sources", sources);
        }

        @Override
        public void token(String text) {
            send("token", Map.of("text", text));
        }

        @Override
        public void done(boolean cached) {
            send("done", Map.of("cached", cached));
            emitter.complete();
        }

        @Override
        public void declined(String reason) {
            send("declined", Map.of("reason", reason));
            emitter.complete();
        }

        @Override
        public void failed(Throwable error) {
            send("failure", Map.of("message", "The model is unavailable right now. Try again in a moment."));
            emitter.complete();
        }

        private void send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                // the client went away; nothing left to deliver
            }
        }
    }
}
