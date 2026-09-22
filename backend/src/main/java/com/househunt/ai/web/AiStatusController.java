package com.househunt.ai.web;

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
                              @Value("${spring.ai.openai.chat.model:}") String chatModel,
                              @Value("${app.ai.embedding.model:}") String embeddingModel) {
        this.status = new AiStatus(enabled, mcpEnabled, enabled ? chatModel : null, enabled ? embeddingModel : null);
    }

    @GetMapping("/api/ai/status")
    public AiStatus status() {
        return status;
    }
}
