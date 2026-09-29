package com.saarthi.chat;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Announces the chatbot's active mode exactly once, after the context is up.
 *
 * <p>Kept separate from {@link GeminiKeyResolver#logStartupMode()} so that it
 * runs on {@link ApplicationReadyEvent}: by then the whole context is healthy,
 * so the line reflects what will actually serve requests. The key is never
 * logged, and no request content is logged.
 */
@Component
public class ChatStartupLogger {

    private final GeminiKeyResolver keys;
    private final LocalAdvisoryCorpus corpus;

    public ChatStartupLogger(GeminiKeyResolver keys, LocalAdvisoryCorpus corpus) {
        this.keys = keys;
        this.corpus = corpus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        keys.logStartupMode();
        if (!keys.isConfigured()) {
            // Makes the fallback's real coverage explicit, so nobody assumes the
            // assistant is silently guessing.
            System.out.println("Chatbot: local reference corpus available with "
                    + corpus.cropCount() + " crop entries and "
                    + corpus.topicCount() + " guidance topics.");
        }
    }
}
