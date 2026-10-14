package dev.wsqreis.docs.answer;

import java.util.List;

/** Receives an answer as it is produced; the web layer adapts it to Server-Sent Events. */
public interface AnswerListener {

    void sources(List<Source> sources);

    void token(String text);

    /** @param cached whether the answer came from the semantic cache instead of the model */
    void done(boolean cached);

    /** The question is outside what the documentation covers; nothing was sent to the model. */
    void declined(String reason);

    void failed(Throwable error);
}
