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

package app.doorprints.server.ai.extract;

import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.ai.web.AiUsageLogger;
import app.doorprints.server.common.BadRequestException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

/** Feature 1: free text listing -> validated {@link HouseDraft} via ChatClient structured output. */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class ListingExtractionService {

    private final ChatClient chat;
    private final AiProperties props;

    public ListingExtractionService(ChatClient chat, AiProperties props) {
        this.chat = chat;
        this.props = props;
    }

    /**
     * Reads a pasted listing (a message, an ad, page text) into a draft house for the user to confirm.
     * The text goes to the model with temperature 0; this service does not store it. The structured answer is then
     * validated by {@link DraftSanitizer} against the same text.
     * @throws IllegalArgumentException if the text is blank or longer than the configured limit
     * @throws AiUnavailableException if the model call fails
     */
    public HouseDraft extract(String text) {
        if (text == null || text.isBlank()) throw new BadRequestException("text must not be blank");
        if (text.length() > props.maxInputChars()) {
            throw new BadRequestException("text is longer than " + props.maxInputChars() + " characters");
        }
        var prompt = ExtractionPrompts.build(text, PromptSafety.nonce());
        long started = System.nanoTime();
        try {
            var result = chat.prompt()
                    .system(prompt.system())
                    .user(prompt.user())
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(props.maxOutputTokens()))
                    .call()
                    .responseEntity(RawListing.class);
            AiUsageLogger.log("extract-listing", result.response(), started);
            return DraftSanitizer.sanitize(result.entity(), text);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Listing extraction failed", e);
        }
    }
}
