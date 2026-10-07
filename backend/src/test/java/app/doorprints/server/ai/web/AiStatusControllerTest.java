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

import app.doorprints.server.secrets.GeminiKey;
import app.doorprints.server.secrets.ServerSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TC-U-171 (S4b-BL-149): the key is needed only when the chat base URL is the Gemini API. No database needed. */
class AiStatusControllerTest {

    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/";

    private static AiStatusController controller(String provider, String baseUrl, Optional<String> key,
                                                 boolean paused) {
        var geminiKey = mock(GeminiKey.class);
        when(geminiKey.current()).thenReturn(key);
        var settings = mock(ServerSettings.class);
        when(settings.aiPaused()).thenReturn(paused);
        return new AiStatusController(true, false, provider, baseUrl, "chat-model", "vertex-model", "embed-model", "",
                geminiKey, settings);
    }

    private static boolean on(AiStatusController c) {
        return c.status(mock(HttpServletRequest.class)).enabled();
    }

    @Test
    void geminiHostNeedsAKey() {
        assertThat(AiStatusController.keyNeeded("aistudio", GEMINI, "")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", "HTTPS://GenerativeLanguage.GoogleAPIs.com/v1beta/openai/", ""))
                .isTrue();
        assertThat(on(controller("aistudio", GEMINI, Optional.empty(), false))).isFalse();
        assertThat(on(controller("aistudio", GEMINI, Optional.of("k"), false))).isTrue();
    }

    @Test
    void aBlankOrUnreadableUrlIsTheDefaultGeminiSoAKeyIsStillNeeded() {
        assertThat(AiStatusController.keyNeeded("aistudio", "", "")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", null, "")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", "not a url", "")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", "/v1", "")).isTrue();
    }

    @Test
    void ollamaAndOtherHostsNeedNoKey() {
        for (var url : new String[] {"http://localhost:11434/v1", "http://host.docker.internal:11434/v1",
                "http://192.168.1.5:1234/v1", "https://api.openai.com/v1/",
                "https://generativelanguage.googleapis.com.evil.example/v1", "https://evil.example/generativelanguage.googleapis.com"}) {
            assertThat(AiStatusController.keyNeeded("aistudio", url, "")).as(url).isFalse();
            assertThat(on(controller("aistudio", url, Optional.empty(), false))).as(url).isTrue();
        }
    }

    @Test
    void vertexNeverNeedsAKeyWhateverTheUrl() {
        assertThat(AiStatusController.keyNeeded("vertex", GEMINI, "")).isFalse();
        assertThat(on(controller("vertex", GEMINI, Optional.empty(), false))).isTrue();
    }

    @Test
    void anExplicitSettingWinsOverTheHost() {
        // A proxy in front of Gemini on another host, or an Ollama that sits behind a key: the owner says so.
        assertThat(AiStatusController.keyNeeded("aistudio", "https://proxy.example/v1beta/openai/", "true")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", "http://localhost:11434/v1", " TRUE ")).isTrue();
        assertThat(AiStatusController.keyNeeded("aistudio", GEMINI, "false")).isFalse();
        // Blank means unset: the host decides again. Vertex ignores the setting.
        assertThat(AiStatusController.keyNeeded("aistudio", GEMINI, "  ")).isTrue();
        assertThat(AiStatusController.keyNeeded("vertex", GEMINI, "true")).isFalse();
    }

    @Test
    void pausedIsOffForEveryEndpoint() {
        assertThat(on(controller("aistudio", "http://localhost:11434/v1", Optional.of("k"), true))).isFalse();
        assertThat(on(controller("aistudio", "http://localhost:11434/v1", Optional.empty(), true))).isFalse();
        assertThat(on(controller("vertex", GEMINI, Optional.empty(), true))).isFalse();
    }

    @Test
    void theStatusNeverHoldsTheUrlOrAKey() {
        var s = controller("aistudio", "http://localhost:11434/v1", Optional.of("secret-key-1"), false)
                .status(mock(HttpServletRequest.class));
        assertThat(s.toString()).doesNotContain("localhost").doesNotContain("secret-key-1");
    }
}
