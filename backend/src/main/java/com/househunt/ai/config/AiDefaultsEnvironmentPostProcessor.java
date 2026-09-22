package com.househunt.ai.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * One switch for all Spring AI auto-configuration: {@code app.ai.enabled} / {@code app.mcp.enabled}.
 *
 * <p>Spring AI's model auto-configurations are {@code @ConditionalOnProperty(spring.ai.model.chat=openai,
 * matchIfMissing = true)} and the PgVector store auto-configuration needs an {@code EmbeddingModel} bean, so
 * merely having the starters on the classpath would try to build OpenAI clients and a vector store. When AI is
 * disabled we force every selector to {@code none} (highest precedence, so nothing re-enables it by accident);
 * when enabled we only switch off the model types this app never uses (image, audio, moderation).
 * The MCP server gets the same treatment via {@code spring.ai.mcp.server.enabled}.
 *
 * <p>Runs after {@code ConfigDataEnvironmentPostProcessor} (lowest precedence) so application.yml and
 * environment variables are already visible.
 */
public class AiDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String FORCED = "houseHuntAiForced";
    static final String DEFAULTS = "houseHuntAiDefaults";

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
        if (ai) {
            defaults.put("spring.ai.model.chat", "openai");
            defaults.put("spring.ai.model.embedding", "openai");
            defaults.put("spring.ai.vectorstore.type", "pgvector");
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

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
