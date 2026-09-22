package com.househunt.ai.config;

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
