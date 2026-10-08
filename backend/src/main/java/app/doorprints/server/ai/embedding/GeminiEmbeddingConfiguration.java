/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.ai.embedding;

import app.doorprints.server.ai.config.AiProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import app.doorprints.server.secrets.GeminiKey;
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
 * {@link app.doorprints.server.ai.config.AiDefaultsEnvironmentPostProcessor}. With AI disabled nothing here is loaded.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.ai.enabled")
@ConditionalOnProperty(name = "app.ai.embedding.provider", havingValue = AiProperties.Embedding.GOOGLE_GENAI,
        matchIfMissing = true)
public class GeminiEmbeddingConfiguration {


    /**
     * Builds the Gemini embedding model with a read timeout, no redirects (the key header must not follow one) and a
     * key supplier. A separate {@code AI_EMBEDDING_API_KEY} wins if set; otherwise the key in use for AI is read on
     * every request.
     */
    @Bean
    public EmbeddingModel geminiEmbeddingModel(AiProperties props, Environment env, GeminiKey geminiKey) {
        var e = props.embedding();
        // AI_EMBEDDING_API_KEY, when set on its own, wins; otherwise the key in use for AI: the owner page's, or
        // AI_API_KEY (docs/03 §12.1). Read on every request.
        var own = env.getProperty("AI_EMBEDDING_API_KEY", "").strip();
        java.util.function.Supplier<String> key = own.isEmpty() ? () -> geminiKey.current().orElse(null) : () -> own;
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
