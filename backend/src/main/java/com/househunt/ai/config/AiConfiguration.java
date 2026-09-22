package com.househunt.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Beans shared by the AI features. Only loaded with {@code app.ai.enabled=true}; the model/vector-store beans
 * themselves come from Spring AI auto-configuration (OpenAI-compatible chat + embeddings, PgVectorStore).
 */
@Configuration
@ConditionalOnBooleanProperty("app.ai.enabled")
@EnableAsync
public class AiConfiguration {

    public AiConfiguration(Environment env) {
        var key = env.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank()) {
            throw new IllegalStateException("APP_AI_ENABLED=true needs AI_API_KEY (a free Gemini API key from "
                    + "https://aistudio.google.com/apikey, or any non-empty value such as 'ollama' for Ollama)");
        }
    }

    /**
     * One ChatClient for all features; each call sets its own system prompt and options. No default advisors:
     * nothing is logged, and the agent adds its own bounded tool-calling advisor per request.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
