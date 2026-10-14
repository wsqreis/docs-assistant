package dev.wsqreis.docs.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Streams a fixed list of tokens, or fails, and counts calls, so tests can tell cache hits from model calls. */
public class StreamingScriptedChatModel implements ChatModel {

    private volatile List<String> tokens = List.of("Answer");
    private volatile RuntimeException failure;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Prompt lastPrompt;

    public void reply(String... tokens) {
        this.tokens = List.of(tokens);
        this.failure = null;
    }

    public void fail(RuntimeException failure) {
        this.failure = failure;
    }

    public int calls() {
        return calls.get();
    }

    public Prompt lastPrompt() {
        return lastPrompt;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("answers are streamed");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt = prompt;
        if (failure != null) {
            return Flux.error(failure);
        }
        return Flux.fromIterable(tokens).map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
    }
}
