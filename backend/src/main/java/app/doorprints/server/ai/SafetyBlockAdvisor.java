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

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a provider's safety block into {@link ProviderBlockedException} before the services read the answer (S4b-BL-233): the
 * structured call ({@code responseEntity}) otherwise fails inside Spring AI with an exception that says nothing, and the person
 * is told the provider is unavailable. The rules are the clients' ({@code AiBlocked}, shared vectors {@code blockedResponses}):
 * only a finish reason that names the provider's safety system counts. {@code length}, {@code OTHER}, {@code LANGUAGE} and the
 * rest stay what they were, and so does an answer with no candidates at all (Spring AI does not keep Gemini's
 * {@code promptFeedback}, so a blocked prompt cannot be told from an empty answer there; docs/ai/ai-design.md 15.3).
 * Innermost in the chain, so a tool-calling loop sees the block on the turn it happens.
 */
public final class SafetyBlockAdvisor implements CallAdvisor {

    /** Finish reasons (upper case) the providers use for their own safety system: OpenAI-compatible and Gemini. */
    static final Set<String> BLOCKING = Set.of("CONTENT_FILTER", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION");

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var response = chain.nextCall(request);
        if (declined(response.chatResponse())) throw new ProviderBlockedException();
        return response;
    }

    /** True when any generation ended on a finish reason that is the provider's safety block. */
    static boolean declined(ChatResponse response) {
        if (response == null || response.getResults() == null) return false;
        for (var generation : response.getResults()) {
            var finish = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
            if (finish != null && BLOCKING.contains(finish.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "SafetyBlockAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
