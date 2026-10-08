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

package app.doorprints.server.ai.config;

import app.doorprints.server.secrets.GeminiKey;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
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

    /**
     * Fails startup with a message naming the setting when the provider or embedding provider is not one the app
     * supports, so a typo is caught at boot rather than on the first AI call. A missing Gemini key is not an error:
     * AI then reads as off until the owner sets one.
     */
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
        // No AI_API_KEY is fine since ADR-25: the owner can set the Gemini key on the owner page (docs/03 §12.1);
        // until a key exists, AI reads as off.
        var provider = props.embedding().provider();
        if (!AiProperties.Embedding.GOOGLE_GENAI.equals(provider) && !AiProperties.Embedding.OPENAI.equals(provider)) {
            throw new IllegalStateException("app.ai.embedding.provider (AI_EMBEDDING_PROVIDER) must be '"
                    + AiProperties.Embedding.GOOGLE_GENAI + "' (Gemini API, default) or '" + AiProperties.Embedding.OPENAI
                    + "' (any OpenAI-compatible /embeddings endpoint, e.g. Ollama)");
        }
    }

    /**
     * Checks the Vertex settings are well formed (project id, locations, optional http(s) endpoint) before any Google
     * client is built.
     * @throws IllegalStateException with the setting name and an example value
     */
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

    /**
     * The OpenAI-compatible chat client sends the Gemini key in use now (the owner page's, or AI_API_KEY) on every
     * request, so a key set or changed on the owner page works at once (docs/03 §12.1).
     */
    @Bean
    public OpenAiHttpClientBuilderCustomizer geminiKeyCustomizer(GeminiKey geminiKey) {
        return builder -> builder.interceptor(new GeminiKeyInterceptor(geminiKey::current));
    }
}
