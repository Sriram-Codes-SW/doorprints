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

package app.doorprints.server.ai.web;

import app.doorprints.server.ai.config.AiProperties;
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
