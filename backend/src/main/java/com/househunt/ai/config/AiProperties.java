package com.househunt.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Agent agent) {

    public AiProperties {
        maxInputChars = positiveOr(maxInputChars, 8000);
        maxQuestionChars = positiveOr(maxQuestionChars, 1000);
        maxOutputTokens = positiveOr(maxOutputTokens, 2048);
        rateLimit = rateLimit == null ? new RateLimit(null, null, null) : rateLimit;
        rag = rag == null ? new Rag(null, null) : rag;
        agent = agent == null ? new Agent(null, null, null) : agent;
    }

    public static AiProperties defaults() {
        return new AiProperties(false, null, null, null, null, null, null);
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

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
