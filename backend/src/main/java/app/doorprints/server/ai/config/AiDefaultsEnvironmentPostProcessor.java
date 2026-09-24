package app.doorprints.server.ai.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One switch for all Spring AI auto-configuration: {@code app.ai.enabled} / {@code app.mcp.enabled}.
 *
 * <p>Spring AI's model auto-configurations are {@code @ConditionalOnProperty(spring.ai.model.chat=openai,
 * matchIfMissing = true)} and the PgVector store auto-configuration needs an {@code EmbeddingModel} bean, so
 * merely having the starters on the classpath would try to build OpenAI clients and a vector store. When AI is
 * disabled we force every selector to {@code none} (highest precedence, so nothing re-enables it by accident);
 * when enabled we only switch off the model types this app never uses (image, audio, moderation), and the
 * OpenAI-compatible embedding model unless {@code app.ai.embedding.provider=openai} (the default provider
 * {@code google-genai} is the app's own {@code GeminiEmbeddingModel}, see docs/ai/ai-design.md 3.1).
 *
 * <p>Provider switch {@code app.ai.provider} ({@code AI_PROVIDER}): {@code aistudio} (default) keeps the setup above;
 * {@code vertex} selects Spring AI's Google GenAI chat auto-configuration ({@code spring.ai.model.chat=google-genai},
 * backed by the app's own Vertex-mode {@code com.google.genai.Client} from
 * {@code app.doorprints.server.ai.vertex.VertexAiConfiguration}) and the app's {@code VertexEmbeddingModel}
 * ({@code app.ai.embedding.provider} is forced to {@code vertex}, every Spring AI embedding auto-configuration off).
 * Only the Google GenAI <em>chat</em> starter is on the classpath: its auto-configuration is conditional on
 * {@code spring.ai.model.chat=google-genai} (matchIfMissing), which is forced to {@code none} with AI disabled and is
 * {@code openai} for AI Studio, so an AI-disabled or AI Studio startup never builds a Google client and never looks
 * up Google credentials. The Google GenAI embedding/image connection auto-configurations have no switch, but they are
 * conditional on classes from modules that are deliberately not on the classpath.
 * The MCP server gets the same treatment via {@code spring.ai.mcp.server.enabled}.
 *
 * <p>Runs after {@code ConfigDataEnvironmentPostProcessor} (lowest precedence) so application.yml and
 * environment variables are already visible.
 */
public class AiDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String FORCED = "doorprintsAiForced";
    static final String DEFAULTS = "doorprintsAiDefaults";
    /** Spring AI's selector value for the Google GenAI models ({@code SpringAIModels.GOOGLE_GEN_AI}). */
    static final String SPRING_AI_GOOGLE_GENAI = "google-genai";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        boolean ai = env.getProperty("app.ai.enabled", Boolean.class, false);
        boolean mcp = env.getProperty("app.mcp.enabled", Boolean.class, false);

        Map<String, Object> forced = new HashMap<>();
        Map<String, Object> defaults = new HashMap<>();

        // Never used by this app, whatever the switch says.
        for (var unused : new String[] {"image", "audio.speech", "audio.transcription", "moderation"}) {
            forced.put("spring.ai.model." + unused, "none");
        }
        var aiProvider = AiProperties.normalizeProvider(env.getProperty("app.ai.provider", ""));
        forced.put("app.ai.provider", aiProvider);
        if (ai && AiProperties.VERTEX.equals(aiProvider)) {
            // Vertex AI: Google GenAI chat (Vertex mode) + the app's VertexEmbeddingModel; nothing OpenAI-compatible.
            forced.put("spring.ai.model.chat", SPRING_AI_GOOGLE_GENAI);
            defaults.put("spring.ai.vectorstore.type", "pgvector");
            forced.put("app.ai.embedding.provider", AiProperties.Embedding.VERTEX);
            forced.put("spring.ai.model.embedding", "none");
            forced.put("spring.ai.model.embedding.text", "none");
            forced.put("spring.ai.model.embedding.multimodal", "none");
        } else if (ai) {
            defaults.put("spring.ai.model.chat", "openai");
            defaults.put("spring.ai.vectorstore.type", "pgvector");
            var provider = embeddingProvider(env);
            // Normalised so the case-sensitive @ConditionalOnProperty on GeminiEmbeddingConfiguration agrees with us.
            forced.put("app.ai.embedding.provider", provider);
            if (AiProperties.Embedding.OPENAI.equals(provider)) {
                defaults.put("spring.ai.model.embedding", "openai");
            } else {
                // google-genai: app.doorprints.server.ai.embedding.GeminiEmbeddingModel is the only EmbeddingModel. The
                // OpenAI embedding auto-configuration must stay off (its @ConditionalOnMissingBean looks for an
                // OpenAiEmbeddingModel, so it would add a second EmbeddingModel and break PgVector's injection).
                forced.put("spring.ai.model.embedding", "none");
                forced.put("spring.ai.model.embedding.text", "none");
                forced.put("spring.ai.model.embedding.multimodal", "none");
            }
        } else {
            forced.put("spring.ai.model.chat", "none");
            forced.put("spring.ai.model.embedding", "none");
            forced.put("spring.ai.model.embedding.text", "none");
            forced.put("spring.ai.model.embedding.multimodal", "none");
            forced.put("spring.ai.vectorstore.type", "none");
            forced.put("spring.ai.chat.client.enabled", "false");
        }
        forced.put("spring.ai.mcp.server.enabled", Boolean.toString(mcp));

        env.getPropertySources().addFirst(new MapPropertySource(FORCED, forced));
        env.getPropertySources().addLast(new MapPropertySource(DEFAULTS, defaults));
    }

    /** {@code app.ai.embedding.provider}, lower case; {@code google-genai} when unset. */
    static String embeddingProvider(ConfigurableEnvironment env) {
        var p = env.getProperty("app.ai.embedding.provider", "");
        return p.isBlank() ? AiProperties.Embedding.GOOGLE_GENAI : p.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
