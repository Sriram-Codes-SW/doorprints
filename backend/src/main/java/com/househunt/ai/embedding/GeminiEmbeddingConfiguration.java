package com.househunt.ai.embedding;

import com.househunt.ai.config.AiProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Registers {@link GeminiEmbeddingModel} as the application's only {@link EmbeddingModel} when AI is enabled and
 * {@code app.ai.embedding.provider=google-genai} (the default). PgVector's auto-configuration then picks it up;
 * the OpenAI embedding auto-configuration is switched off by
 * {@link com.househunt.ai.config.AiDefaultsEnvironmentPostProcessor}. With AI disabled nothing here is loaded.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.ai.enabled")
@ConditionalOnProperty(name = "app.ai.embedding.provider", havingValue = AiProperties.Embedding.GOOGLE_GENAI,
        matchIfMissing = true)
public class GeminiEmbeddingConfiguration {

    static final String API_KEY_PROPERTY = "app.ai.embedding.api-key";

    @Bean
    public EmbeddingModel geminiEmbeddingModel(AiProperties props, Environment env) {
        var e = props.embedding();
        var key = env.getProperty(API_KEY_PROPERTY, "");
        if (key.isBlank()) {
            throw new IllegalStateException("app.ai.embedding.provider=google-genai needs an API key: set AI_API_KEY "
                    + "(a free Gemini API key from https://aistudio.google.com/apikey) or AI_EMBEDDING_API_KEY");
        }
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(e.timeout());
        var builder = RestClient.builder().requestFactory(requestFactory);
        return new GeminiEmbeddingModel(builder, e.baseUrl(), key, e.model(), e.dimensions(), e.taskType(),
                e.maxRetries(), Duration.ofSeconds(2));
    }
}
