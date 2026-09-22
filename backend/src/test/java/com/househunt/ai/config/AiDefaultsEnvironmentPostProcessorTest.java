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
    void enabledSelectsOpenAiCompatibleAndPgVectorButAllowsOverrides() {
        var env = new MockEnvironment();
        env.setProperty("app.ai.enabled", "true");
        env.setProperty("app.mcp.enabled", "true");
        env.setProperty("spring.ai.model.embedding", "custom");
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(env.getProperty("spring.ai.model.embedding")).isEqualTo("custom");
        assertThat(env.getProperty("spring.ai.vectorstore.type")).isEqualTo("pgvector");
        assertThat(env.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.ai.model.audio.speech")).isEqualTo("none");
    }
}
