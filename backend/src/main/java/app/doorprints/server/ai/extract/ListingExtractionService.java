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

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Feature 1: free text listing -> validated {@link HouseDraft} via ChatClient structured output. */
@Service
@ConditionalOnBooleanProperty("app.ai.enabled")
public class ListingExtractionService {

    /**
     * Eval-only seams for the canary suite (S4b-BL-237, docs/ai/ai-design.md 8.6), package-private and null or false
     * in production: nothing reads them from configuration, a property or the environment, so no deployment can turn
     * them on. Only the test class {@code ExtractCanarySeams} (same package, test sources) sets them, for one canary run.
     * {@code canarySystemText} rewrites the built system text before it is sent (a prompt without its rules);
     * {@code canarySkipSanitizer} hands the model's output on as a draft without {@link DraftSanitizer}.
     */
    static volatile UnaryOperator<String> canarySystemText;
    static volatile boolean canarySkipSanitizer;

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
                    .system(systemText(prompt.system()))
                    .user(prompt.user())
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(props.maxOutputTokens()))
                    .call()
                    .responseEntity(RawListing.class);
            AiUsageLogger.log("extract-listing", result.response(), started);
            return finish(result.entity(), text);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Listing extraction failed", e);
        }
    }

    /** The system text as sent: the built one, unless a canary rewrites it. */
    static String systemText(String built) {
        var canary = canarySystemText;
        return canary == null ? built : canary.apply(built);
    }

    /** The draft the caller gets: the sanitised one, unless the no-sanitizer canary is on. */
    static HouseDraft finish(RawListing raw, String text) {
        return canarySkipSanitizer ? unsanitized(raw) : DraftSanitizer.sanitize(raw, text);
    }

    /**
     * The model's output as a draft with the numbers read but nothing confirmed against the text and nothing clamped:
     * what Extract would be without {@link DraftSanitizer}. Canary only.
     */
    static HouseDraft unsanitized(RawListing raw) {
        var warnings = new ArrayList<String>(List.of("canary: sanitizer bypassed"));
        if (raw == null) return new HouseDraft(null, null, null, null, null, null, null, null, null, null, null, List.of(), warnings);
        return new HouseDraft(raw.label(), raw.address(), raw.street(), raw.locality(), DraftSanitizer.price(raw.price(), warnings),
                DraftSanitizer.priceType(raw.priceType(), warnings), DraftSanitizer.bedrooms(raw.bedrooms(), warnings),
                raw.contactName(), raw.contactPhone(), raw.listingUrl(), raw.notes(),
                raw.amenities() == null ? List.of() : List.copyOf(raw.amenities()), warnings);
    }
}
