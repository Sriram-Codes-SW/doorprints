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
import app.doorprints.server.config.ApiKeyFilter;
import app.doorprints.server.device.DeviceKeyStore;
import app.doorprints.server.secrets.GeminiKey;
import app.doorprints.server.secrets.ServerSettings;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Always available so the web and Android apps can decide whether to show AI features. Reveals no secrets
 * (model names only when AI is on).
 */
@RestController
public class AiStatusController {

    /** {@code offForDevice}: AI is on, but the owner turned it off for the calling device (docs/03 §12.1). */
    public record AiStatus(boolean enabled, boolean mcpEnabled, String chatModel, String embeddingModel,
                           boolean offForDevice) {
    }

    /** The Gemini API's host: the only chat endpoint that needs a key for the status to say AI is on (S4b-BL-149). */
    static final String GEMINI_HOST = "generativelanguage.googleapis.com";
    private static final String GEMINI_OPENAI_BASE_URL = "https://" + GEMINI_HOST + "/v1beta/openai/";

    private final AiStatus status;
    private final boolean needsKey;
    private final GeminiKey geminiKey;
    private final ServerSettings settings;

    /**
     * Reads the AI switches once. The status never names the provider; it carries the chat and embedding model only
     * while AI is on.
     */
    public AiStatusController(@Value("${app.ai.enabled:false}") boolean enabled,
                              @Value("${app.mcp.enabled:false}") boolean mcpEnabled,
                              @Value("${app.ai.provider:aistudio}") String provider,
                              @Value("${spring.ai.openai.base-url:" + GEMINI_OPENAI_BASE_URL + "}") String baseUrl,
                              @Value("${spring.ai.openai.chat.model:}") String openAiChatModel,
                              @Value("${spring.ai.google.genai.chat.model:}") String vertexChatModel,
                              @Value("${app.ai.embedding.model:}") String embeddingModel,
                              @Value("${app.ai.key-required:}") String keyRequired,
                              GeminiKey geminiKey, ServerSettings settings) {
        this.geminiKey = geminiKey;
        this.settings = settings;
        this.needsKey = keyNeeded(provider, baseUrl, keyRequired);
        // The provider itself is not exposed (the response shape is shared with the web and Android apps).
        var chatModel = AiProperties.VERTEX.equals(AiProperties.normalizeProvider(provider)) ? vertexChatModel
                : openAiChatModel;
        this.status = new AiStatus(enabled, mcpEnabled, enabled ? chatModel : null, enabled ? embeddingModel : null, false);
    }

    /**
     * Whether AI reads as off while no key is set. Vertex AI uses the server's Google Cloud credentials, so never. For
     * AI Studio the owner's explicit {@code app.ai.key-required} ({@code AI_KEY_REQUIRED}: {@code true} or
     * {@code false}) wins, for example {@code true} for a proxy in front of Gemini on another host; unset, the chat
     * base URL decides: only the Gemini API host needs a key, and an Ollama or other OpenAI-compatible endpoint of the
     * self-hoster's own does not (a key, if set, is still sent). A blank or unreadable URL is read as the default, the
     * Gemini API. Both settings are the server's own ({@code AI_BASE_URL}), never a request value (T-I44).
     */
    static boolean keyNeeded(String provider, String baseUrl, String keyRequired) {
        if (AiProperties.VERTEX.equals(AiProperties.normalizeProvider(provider))) return false;
        if (keyRequired != null && !keyRequired.isBlank()) return Boolean.parseBoolean(keyRequired.strip());
        return GEMINI_HOST.equals(hostOf(baseUrl));
    }

    /** The lower-case host of the URL; the Gemini host for a blank or unreadable one (so a key stays required). */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return GEMINI_HOST;
        try {
            var host = java.net.URI.create(baseUrl.strip()).getHost();
            return host == null ? GEMINI_HOST : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return GEMINI_HOST;
        }
    }

    /**
     * What AI features this caller may use right now. It reads as off when the server has AI disabled, when a key is
     * needed and none is set, or when the owner paused AI; for a device the owner switched off, it reads as off with
     * {@code offForDevice} set so the app can say why.
     */
    @GetMapping("/api/ai/status")
    public AiStatus status(HttpServletRequest request) {
        if (!status.enabled()) return status;
        // No key yet where the endpoint needs one (the Gemini API), or the owner paused AI for the whole server (owner page, docs/03 §12.1): off for everyone.
        if ((needsKey && geminiKey.current().isEmpty()) || settings.aiPaused()) {
            return new AiStatus(false, status.mcpEnabled(), null, null, false);
        }
        if (request.getAttribute(ApiKeyFilter.DEVICE_ATTRIBUTE) instanceof DeviceKeyStore.Caller c && !c.aiAllowed()) {
            return new AiStatus(false, status.mcpEnabled(), null, null, true);
        }
        return status;
    }
}
