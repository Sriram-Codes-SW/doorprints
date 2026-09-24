package app.doorprints.server.ai.config;

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
        String provider,
        Boolean indexOnChange,
        Integer maxInputChars,
        Integer maxQuestionChars,
        Integer maxOutputTokens,
        RateLimit rateLimit,
        Rag rag,
        Agent agent,
        Embedding embedding,
        Vertex vertex) {

    /** Gemini Developer API (Google AI Studio key): OpenAI-compatible chat + native embeddings. The default. */
    public static final String AISTUDIO = "aistudio";
    /** Google Cloud Vertex AI (Gemini Enterprise Agent Platform), Application Default Credentials, no API key. */
    public static final String VERTEX = "vertex";

    public AiProperties {
        provider = normalizeProvider(provider);
        indexOnChange = indexOnChange == null || indexOnChange;
        maxInputChars = positiveOr(maxInputChars, 8000);
        maxQuestionChars = positiveOr(maxQuestionChars, 1000);
        maxOutputTokens = positiveOr(maxOutputTokens, 2048);
        rateLimit = rateLimit == null ? new RateLimit(null, null, null) : rateLimit;
        rag = rag == null ? new Rag(null, null) : rag;
        agent = agent == null ? new Agent(null, null, null) : agent;
        embedding = embedding == null ? new Embedding(null, null, null, null, null, null, null) : embedding;
        vertex = vertex == null ? new Vertex(null, null, null, null, null) : vertex;
    }

    public static AiProperties defaults() {
        return new AiProperties(false, null, null, null, null, null, null, null, null, null, null);
    }

    /** {@code app.ai.provider} lower case and trimmed; {@value #AISTUDIO} when unset. Unknown values are kept (and
     * rejected at startup by {@link AiConfiguration}). */
    public static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? AISTUDIO : provider.strip().toLowerCase(Locale.ROOT);
    }

    public boolean vertexProvider() {
        return VERTEX.equals(provider);
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
     * {@code app.doorprints.server.ai.embedding.GeminiEmbeddingModel}; {@code openai} uses Spring AI's OpenAI-compatible embedding model (Ollama, OpenAI, ...);
     * {@code vertex} is forced when {@code app.ai.provider=vertex} (model, dimensions, task type, retries and timeout
     * below then apply to the Vertex AI endpoint).
     * The API key is deliberately not part of this record (records print every component in {@code toString()}); it
     * is read from {@code app.ai.embedding.api-key} where the bean is built.
     *
     * @param taskType optional Gemini task type (only {@code gemini-embedding-001} accepts one; empty = none)
     */
    public record Embedding(String provider, String model, Integer dimensions, String baseUrl, String taskType,
                            Integer maxRetries, Duration timeout) {

        public static final String GOOGLE_GENAI = "google-genai";
        public static final String OPENAI = "openai";
        /**
         * Set (forced) by {@link AiDefaultsEnvironmentPostProcessor} when {@code app.ai.provider=vertex}: embeddings
         * then come from {@code app.doorprints.server.ai.embedding.VertexEmbeddingModel} with the same model and dimensions.
         */
        public static final String VERTEX = "vertex";

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

    /**
     * Vertex AI settings ({@code app.ai.vertex.*}), used only with {@code app.ai.provider=vertex}. Authentication is
     * always Application Default Credentials (Workload Identity Federation in GitHub Actions, the attached service
     * account on Cloud Run, {@code gcloud auth application-default login} locally); there is deliberately no key or
     * credentials-file property.
     *
     * @param projectId          Google Cloud project id (required)
     * @param location           region for chat, default {@code asia-south1} (Mumbai); {@code global} also works
     * @param embeddingLocation  region for embeddings; empty = {@code location} (set it when the embedding model is
     *                           not offered in the chat region, see docs/ai/vertex-setup.md)
     * @param endpoint           optional base URL override without API version (tests, Private Service Connect);
     *                           empty = derived from the location like the google-genai SDK does
     * @param apiVersion         REST API version, default {@code v1beta1} (the google-genai SDK's Vertex default)
     */
    public record Vertex(String projectId, String location, String embeddingLocation, String endpoint,
                         String apiVersion) {

        public static final String DEFAULT_LOCATION = "asia-south1";

        public Vertex {
            projectId = projectId == null ? "" : projectId.strip();
            location = location == null || location.isBlank() ? DEFAULT_LOCATION : location.strip().toLowerCase(Locale.ROOT);
            embeddingLocation = embeddingLocation == null || embeddingLocation.isBlank() ? location
                    : embeddingLocation.strip().toLowerCase(Locale.ROOT);
            endpoint = endpoint == null ? "" : endpoint.strip();
            apiVersion = apiVersion == null || apiVersion.isBlank() ? "v1beta1" : apiVersion.strip();
        }
    }

    private static int positiveOr(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
