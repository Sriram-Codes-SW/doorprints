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

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AiDefaultsEnvironmentPostProcessorTest {

    private final AiDefaultsEnvironmentPostProcessor epp = new AiDefaultsEnvironmentPostProcessor();

    @Test
    void disabledByDefaultForcesEverythingOff() {
        var env = new MockEnvironment();
        env.setProperty("spring.ai.model.chat", "openai"); // even an explicit setting is overridden
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.vectorstore.type")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.ai.model.image")).isEqualTo("none");
    }

    @Test
    void disabledNeedsNoEmbeddingProviderEither() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.embedding.provider", "openai");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.chat.client.enabled")).isEqualTo("false");
    }

    @Test
    void enabledWithDefaultProviderUsesNativeGeminiEmbeddingsAndOpenAiCompatibleChat() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        env.setProperty("app.mcp.enabled", "true");
        env.setProperty("spring.ai.model.embedding", "openai"); // would add a second EmbeddingModel: overridden
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("app.ai.embedding.provider")).isEqualTo("google-genai");
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.vectorstore.type")).isEqualTo("pgvector");
        assertThat(env.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.ai.model.audio.speech")).isEqualTo("none");
    }

    @Test
    void providerIsNormalisedForTheConditionalBean() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        env.setProperty("app.ai.embedding.provider", " Google-GenAI ");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("app.ai.embedding.provider")).isEqualTo("google-genai");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
    }

    @Test
    void providerDefaultsToAiStudio() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("app.ai.provider")).isEqualTo("aistudio");
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(env.getProperty("app.ai.embedding.provider")).isEqualTo("google-genai");
    }

    @Test
    void vertexSelectsGoogleGenAiChatAndTheAppsVertexEmbeddings() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        env.setProperty("app.ai.provider", " Vertex ");
        env.setProperty("spring.ai.model.chat", "openai"); // a leftover setting must not mix providers
        env.setProperty("app.ai.embedding.provider", "openai");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("app.ai.provider")).isEqualTo("vertex");
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("google-genai");
        assertThat(env.getProperty("app.ai.embedding.provider")).isEqualTo("vertex");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.multimodal")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.vectorstore.type")).isEqualTo("pgvector");
        assertThat(env.getProperty("spring.ai.model.image")).isEqualTo("none");
    }

    @Test
    void vertexSelectedButAiDisabledStillForcesEverythingOff() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.provider", "vertex");
        epp.postProcessEnvironment(env, new SpringApplication());
        // chat=none keeps GoogleGenAiChatAutoConfiguration (matchIfMissing=true) off: no Google client, no ADC lookup.
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.vectorstore.type")).isEqualTo("none");
    }

    @Test
    void openAiProviderSelectsOpenAiCompatibleEmbeddingsButAllowsOverrides() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        env.setProperty("app.ai.embedding.provider", "openai");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("openai");

        var custom = new MockEnvironment();
        custom.setProperty("app.ai.enabled", "true");
        custom.setProperty("app.ai.embedding.provider", "openai");
        custom.setProperty("spring.ai.model.embedding", "custom");
        epp.postProcessEnvironment(custom, new SpringApplication());
        assertThat(custom.getProperty("spring.ai.model.embedding")).isEqualTo("custom");
        assertThat(custom.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    }
}
