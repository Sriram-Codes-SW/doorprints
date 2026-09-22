package com.househunt.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * {@code app.ai.*} settings. Every value has a safe default so the record can also be built in unit tests
 * with {@code AiProperties.defaults()}.
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        Integer maxInputChars,
        Integer maxQuestionChars,
        Integer maxOutputTokens,
        RateLimit rateLimit,
        Rag rag,
        Agent agent,
        Embedding embedding) {

    public AiProperties {
        maxInputChars = positiveOr(maxInputChars, 8000);
        maxQuestionChars = positiveOr(maxQuestionChars, 1000);
        maxOutputTokens = positiveOr(maxOutputTokens, 2048);
        rateLimit = rateLimit == null ? new RateLimit(null, null, null) : rateLimit;
        rag = rag == null ? new Rag(null, null) : rag;
        agent = agent == null ? new Agent(null, null, null) : agent;
        embedding = embedding == null ? new Embedding(null, null, null, null, null, null, null) : embedding;
    }

    public static AiProperties defaults() {
        return new AiProperties(false, null, null, null, null, null, null, null);
    }

    public record RateLimit(Integer requestsPerMinute, Integer burst, Integer mcpRequestsPerMinute) {
        public RateLimit {
            requestsPerMinute = positiveOr(requestsPerMinute, 10);
            burst = positiveOr(burst, 5);
            mcpRequestsPerMinute = positiveOr(mcpRequestsPerMinute, 60);
        }
    }

    public record Rag(Integer topK, Double similarityThreshold) {
        public Rag {
            topK = Math.min(positiveOr(topK, 6), 20);
            similarityThreshold = similarityThreshold == null ? 0.25 : Math.clamp(similarityThreshold, 0.0, 1.0);
        }
    }

    public record Agent(Integer maxToolCalls, Integer maxCallsPerTool, Integer maxStops) {
        public Agent {
            maxToolCalls = positiveOr(maxToolCalls, 12);
            maxCallsPerTool = positiveOr(maxCallsPerTool, 4);
            maxStops = Math.min(positiveOr(maxStops, 8), 25);
        }
    }

    /**
     * Which {@code EmbeddingModel} backs the vector store ({@code app.ai.embedding.*}). {@code google-genai} (default)
     * calls the native Gemini API ({@code models/<model>:batchEmbedContents}) with
     * {@code com.househunt.ai.embedding.GeminiEmbeddingModel}; {@code openai} uses Spring AI's OpenAI-compatible embedding model (Ollama, OpenAI, ...).
     * The API key is deliberately not part of this record (records print every component in {@code toString()}); it
     * is read from {@code app.ai.embedding.api-key} where the bean is built.
     *
     * @param taskType optional Gemini task type (only {@code gemini-embedding-001} accepts one; empty = none)
     */
    public record Embedding(String provider, String model, Integer dimensions, String baseUrl, String taskType,
                            Integer maxRetries, Duration timeout) {

        public static final String GOOGLE_GENAI = "google-genai";
        public static final String OPENAI = "openai";

        public Embedding {
            provider = provider == null || provider.isBlank() ? GOOGLE_GENAI : provider.strip().toLowerCase(Locale.ROOT);
            model = model == null || model.isBlank() ? "gemini-embedding-2" : model.strip();
            dimensions = positiveOr(dimensions, 768);
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com/v1beta" : baseUrl.strip();
            taskType = taskType == null || taskType.isBlank() ? null : taskType.strip().toUpperCase(Locale.ROOT);
            maxRetries = maxRetries == null || maxRetries < 0 ? 2 : Math.min(maxRetries, 5);
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout;
        }
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
