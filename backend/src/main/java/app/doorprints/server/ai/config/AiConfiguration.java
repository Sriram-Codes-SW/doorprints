package app.doorprints.server.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.regex.Pattern;

/**
 * Beans shared by the AI features. Only loaded with {@code app.ai.enabled=true}. With {@code app.ai.provider=aistudio}
 * (default) chat comes from Spring AI's OpenAI-compatible auto-configuration; embeddings from
 * {@code app.doorprints.server.ai.embedding.GeminiEmbeddingConfiguration} ({@code app.ai.embedding.provider=google-genai},
 * default) or the OpenAI-compatible auto-configuration ({@code openai}). With {@code app.ai.provider=vertex} chat comes
 * from Spring AI's Google GenAI auto-configuration and embeddings from {@code app.doorprints.server.ai.embedding.VertexEmbeddingModel},
 * both through {@code app.doorprints.server.ai.vertex.VertexAiConfiguration}. PgVectorStore from its auto-configuration.
 * The constructor fails startup with a clear message when the selected provider is not configured.
 */
@Configuration
@ConditionalOnBooleanProperty("app.ai.enabled")
@EnableAsync
public class AiConfiguration {

    /** Google Cloud project ids: 6-30 characters, lower-case letters, digits and hyphens, starting with a letter. */
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    /** {@code asia-south1}, {@code us-central1}, {@code global}, {@code us}, {@code eu}. */
    private static final Pattern LOCATION = Pattern.compile("[a-z][a-z0-9-]{1,40}");

    public AiConfiguration(Environment env, AiProperties props) {
        if (props.vertexProvider()) {
            validateVertex(props.vertex());
            return;
        }
        if (!AiProperties.AISTUDIO.equals(props.provider())) {
            throw new IllegalStateException("app.ai.provider (AI_PROVIDER) must be '" + AiProperties.AISTUDIO
                    + "' (Gemini Developer API key from AI Studio, default) or '" + AiProperties.VERTEX
                    + "' (Google Cloud Vertex AI with Application Default Credentials)");
        }
        var key = env.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank()) {
            throw new IllegalStateException("APP_AI_ENABLED=true needs AI_API_KEY (a free Gemini API key from "
                    + "https://aistudio.google.com/apikey, or any non-empty value such as 'ollama' for Ollama)");
        }
        var provider = props.embedding().provider();
        if (!AiProperties.Embedding.GOOGLE_GENAI.equals(provider) && !AiProperties.Embedding.OPENAI.equals(provider)) {
            throw new IllegalStateException("app.ai.embedding.provider (AI_EMBEDDING_PROVIDER) must be '"
                    + AiProperties.Embedding.GOOGLE_GENAI + "' (Gemini API, default) or '" + AiProperties.Embedding.OPENAI
                    + "' (any OpenAI-compatible /embeddings endpoint, e.g. Ollama)");
        }
    }

    public static void validateVertex(AiProperties.Vertex v) {
        if (!PROJECT_ID.matcher(v.projectId()).matches()) {
            throw new IllegalStateException("AI_PROVIDER=vertex needs GCP_PROJECT_ID (app.ai.vertex.project-id): the "
                    + "Google Cloud project id, e.g. doorprints-ai-123456 (see docs/ai/vertex-setup.md)");
        }
        if (!LOCATION.matcher(v.location()).matches() || !LOCATION.matcher(v.embeddingLocation()).matches()) {
            throw new IllegalStateException("app.ai.vertex.location / embedding-location (GCP_LOCATION / "
                    + "AI_VERTEX_EMBEDDING_LOCATION) must be a Vertex AI location such as asia-south1, us-central1 or global");
        }
        if (!v.endpoint().isEmpty() && !v.endpoint().startsWith("https://") && !v.endpoint().startsWith("http://")) {
            throw new IllegalStateException("app.ai.vertex.endpoint (AI_VERTEX_ENDPOINT) must be an http(s) URL");
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
