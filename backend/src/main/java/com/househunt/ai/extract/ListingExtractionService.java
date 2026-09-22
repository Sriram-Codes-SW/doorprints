package com.househunt.ai.extract;

import com.househunt.ai.PromptSafety;
import com.househunt.ai.config.AiProperties;
import com.househunt.ai.web.AiUnavailableException;
import com.househunt.ai.web.AiUsageLogger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

/** Feature 1: free text listing -> validated {@link HouseDraft} via ChatClient structured output. */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class ListingExtractionService {

    private final ChatClient chat;
    private final AiProperties props;

    public ListingExtractionService(ChatClient chat, AiProperties props) {
        this.chat = chat;
        this.props = props;
    }

    public HouseDraft extract(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (text.length() > props.maxInputChars()) {
            throw new IllegalArgumentException("text is longer than " + props.maxInputChars() + " characters");
        }
        var prompt = ExtractionPrompts.build(text, PromptSafety.nonce());
        long started = System.nanoTime();
        try {
            var result = chat.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(props.maxOutputTokens()))
                    .call()
                    .responseEntity(RawListing.class);
            AiUsageLogger.log("extract-listing", result.response(), started);
            return DraftSanitizer.sanitize(result.entity(), text);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Listing extraction failed", e);
        }
    }
}
