package com.househunt.ai.web;

import com.househunt.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Always available so the web and Android apps can decide whether to show AI features. Reveals no secrets
 * (model names only when AI is on).
 */
@RestController
public class AiStatusController {

    public record AiStatus(boolean enabled, boolean mcpEnabled, String chatModel, String embeddingModel) {
    }

    private final AiStatus status;

    public AiStatusController(@Value("${app.ai.enabled:false}") boolean enabled,
                              @Value("${app.mcp.enabled:false}") boolean mcpEnabled,
                              @Value("${app.ai.provider:aistudio}") String provider,
                              @Value("${spring.ai.openai.chat.model:}") String openAiChatModel,
                              @Value("${spring.ai.google.genai.chat.model:}") String vertexChatModel,
                              @Value("${app.ai.embedding.model:}") String embeddingModel) {
        // The provider itself is not exposed (the response shape is shared with the web and Android apps).
        var chatModel = AiProperties.VERTEX.equals(AiProperties.normalizeProvider(provider)) ? vertexChatModel
                : openAiChatModel;
        this.status = new AiStatus(enabled, mcpEnabled, enabled ? chatModel : null, enabled ? embeddingModel : null);
    }

    @GetMapping("/api/ai/status")
    public AiStatus status() {
        return status;
    }
}
