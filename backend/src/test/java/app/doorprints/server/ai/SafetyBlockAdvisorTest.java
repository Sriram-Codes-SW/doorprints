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

package app.doorprints.server.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4b-BL-233: which finish reasons the server reads as the provider's own safety block. The same list as the clients
 * ({@code AiBlocked}, the shared vectors {@code blockedResponses}); everything else stays what it was.
 */
class SafetyBlockAdvisorTest {

    private static ChatResponse answering(String text, String finishReason) {
        var metadata = finishReason == null ? ChatGenerationMetadata.NULL : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(), metadata)));
    }

    @Test
    void theProvidersSafetyFinishReasonsAreDeclinedWhateverTheCaseAndWithOrWithoutPartialText() {
        for (var reason : List.of("content_filter", "CONTENT_FILTER", "SAFETY", "safety", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII",
                "RECITATION")) {
            assertThat(SafetyBlockAdvisor.declined(answering("", reason))).as(reason).isTrue();
            assertThat(SafetyBlockAdvisor.declined(answering("partial", reason))).as(reason + " with text").isTrue();
        }
    }

    @Test
    void everyOtherFinishReasonStaysGeneric() {
        for (var reason : java.util.Arrays.asList("stop", "STOP", "length", "MAX_TOKENS", "OTHER", "LANGUAGE", "MALFORMED_FUNCTION_CALL",
                "tool_calls", "", null)) {
            assertThat(SafetyBlockAdvisor.declined(answering("{\"ok\":true}", reason))).as(String.valueOf(reason)).isFalse();
        }
    }

    @Test
    void noResponseOrNoGenerationsIsNotARead() {
        // Spring AI does not keep Gemini's promptFeedback, so "no candidates" cannot be told from a blocked prompt: it stays generic.
        assertThat(SafetyBlockAdvisor.declined(null)).isFalse();
        assertThat(SafetyBlockAdvisor.declined(new ChatResponse(List.of()))).isFalse();
    }

    @Test
    void oneBlockedGenerationAmongOthersIsDeclined() {
        var response = new ChatResponse(List.of(answering("a", "stop").getResult(), answering("b", "SAFETY").getResult()));
        assertThat(SafetyBlockAdvisor.declined(response)).isTrue();
    }

    @Test
    void theBlockIsFoundInTheCauseChainAndNothingElseIs() {
        var wrapped = new app.doorprints.server.ai.web.AiUnavailableException("Answering failed",
                new RuntimeException("x", new ProviderBlockedException()));
        assertThat(ProviderErrors.isDeclined(wrapped)).isTrue();
        assertThat(ProviderErrors.isDeclined(new RuntimeException("content_filter SAFETY"))).as("message text is never read").isFalse();
        assertThat(ProviderErrors.isDeclined(null)).isFalse();
        assertThat(new ProviderBlockedException().getMessage()).doesNotContain("SAFETY");
    }
}
