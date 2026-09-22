package com.househunt.ai.vertex;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.househunt.ai.config.AiDefaultsEnvironmentPostProcessor;
import com.househunt.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring of the provider switch with the real Spring AI 2.0.1 Google GenAI chat auto-configuration and the app's
 * environment post-processor (applied as an initializer, after the test properties, like at application startup).
 * The key guarantee: with AI disabled, or with the AI Studio provider, no Google client is built and Application
 * Default Credentials are never looked up (the build machine has none, so any lookup would fail the context).
 */
class VertexAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> new AiDefaultsEnvironmentPostProcessor()
                    .postProcessEnvironment(ctx.getEnvironment(), new SpringApplication()))
            .withConfiguration(AutoConfigurations.of(GoogleGenAiChatAutoConfiguration.class))
            .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build());

    @Test
    void aiDisabledBuildsNoGoogleBeansEvenWithVertexSelected() {
        runner.withUserConfiguration(Props.class, VertexAiConfiguration.class)
                .withPropertyValues("app.ai.provider=vertex", "app.ai.vertex.project-id=doorprints-ai")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(Client.class);
                    assertThat(ctx).doesNotHaveBean(GoogleGenAiChatModel.class);
                    assertThat(ctx).doesNotHaveBean(GoogleAccessTokenSource.class);
                    assertThat(ctx.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
                });
    }

    @Test
    void aiStudioProviderBuildsNoGoogleBeans() {
        runner.withUserConfiguration(Props.class, VertexAiConfiguration.class)
                .withPropertyValues("app.ai.enabled=true", "app.ai.provider=aistudio")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(Client.class);
                    assertThat(ctx).doesNotHaveBean(GoogleGenAiChatModel.class);
                    assertThat(ctx).doesNotHaveBean(GoogleAccessTokenSource.class);
                    assertThat(ctx.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("openai");
                });
    }

    @Test
    void vertexProviderUsesTheAppsClientForSpringAiChat() {
        var props = new AiProperties(true, AiProperties.VERTEX, null, null, null, null, null, null, null, null,
                new AiProperties.Vertex("doorprints-ai", "asia-south1", null, null, null));
        var credentials = GoogleCredentials.create(new AccessToken("t", Date.from(Instant.now().plus(Duration.ofHours(1)))));
        var client = VertexAiConfiguration.client(props, new GoogleAccessTokenSource(credentials));
        runner.withBean(Client.class, () -> client)
                .withPropertyValues("app.ai.enabled=true", "app.ai.provider= Vertex ",
                        "spring.ai.google.genai.chat.model=gemini-3.5-flash")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(Client.class);
                    assertThat(ctx.getBean(Client.class)).isSameAs(client);
                    assertThat(ctx).hasSingleBean(GoogleGenAiChatModel.class);
                    assertThat(ctx.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("google-genai");
                    assertThat(ctx.getEnvironment().getProperty("app.ai.provider")).isEqualTo("vertex");
                });
    }
}
