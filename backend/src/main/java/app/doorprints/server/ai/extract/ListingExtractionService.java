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
import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.House;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
    /**
     * Builds the whole prompt from the raw text instead of {@link ExtractionPrompts#build} (canary {@code no-wrapping}:
     * a fixed tag and no neutralisation). Null in production.
     */
    static volatile Function<String, ExtractionPrompts.Built> canaryPrompt;

    private final ChatClient chat;
    private final AiProperties props;
    private final AiKinds kinds;

    public ListingExtractionService(ChatClient chat, AiProperties props) {
        this(chat, props, AiKinds.OFF);
    }

    @Autowired
    public ListingExtractionService(ChatClient chat, AiProperties props, AiKinds kinds) {
        this.chat = chat;
        this.props = props;
        this.kinds = kinds;
    }

    /**
     * As {@link #extract(String)}, for a candidate of {@code kind} (kinds PR 7, S4b-BL-211; kinds PR 28): none or {@code house}
     * is the house's extract; another kind whose {@code ai.extract} slot is not empty, while this server lists it in
     * {@code app.kinds.available} ({@link AiKinds}), gets its kind file's prompt, schema and checks; a kind outside the list,
     * or one with no listing extract, is a 400, never read as a house. Every kind, the house too, is read through its kind
     * file by the same code ({@link ExtractionPrompts#build(AiKind, String, String)}, {@link KindOutputConverter},
     * {@link KindDraftSanitizer}).
     */
    public HouseDraft extract(String text, String kind) {
        if (kind == null || House.HOUSE_KIND.equals(kind)) return extract(text);
        kinds.require(kind);
        var k = kinds.of(kind);
        if (k == null || k.extract().isEmpty() || k.extractFields().isEmpty()) {
            throw new BadRequestException("kind " + kind + " has no listing extract");
        }
        return extract(k, text);
    }

    /**
     * Reads a pasted listing (a message, an ad, page text) into a draft house for the user to confirm.
     * The text goes to the model with temperature 0; this service does not store it. The structured answer is then
     * validated by {@link KindDraftSanitizer} against the same text.
     * @throws IllegalArgumentException if the text is blank or longer than the configured limit
     * @throws AiUnavailableException if the model call fails
     */
    public HouseDraft extract(String text) {
        return extract(AiKinds.HOUSE, text);
    }

    private HouseDraft extract(AiKind k, String text) {
        if (text == null || text.isBlank()) throw new BadRequestException("text must not be blank");
        if (text.length() > props.maxInputChars()) {
            throw new BadRequestException("text is longer than " + props.maxInputChars() + " characters");
        }
        var prompt = builtPrompt(k, text, PromptSafety.nonce());
        long started = System.nanoTime();
        try {
            var result = chat.prompt()
                    .system(systemText(prompt.system()))
                    .user(prompt.user())
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(props.maxOutputTokens()))
                    .call()
                    .responseEntity(new KindOutputConverter(k));
            AiUsageLogger.log("extract-listing", result.response(), started);
            return finish(k, result.entity(), text);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Listing extraction failed", e);
        }
    }

    /** The prompt as sent: {@link ExtractionPrompts#build} with the call's nonce, unless the no-wrapping canary builds it. */
    static ExtractionPrompts.Built builtPrompt(AiKind k, String text, String nonce) {
        var canary = canaryPrompt;
        return canary == null ? ExtractionPrompts.build(k, text, nonce) : canary.apply(text);
    }

    /** As above, for a house. */
    static ExtractionPrompts.Built builtPrompt(String text, String nonce) {
        return builtPrompt(AiKinds.HOUSE, text, nonce);
    }

    /** The system text as sent: the built one, unless a canary rewrites it. */
    static String systemText(String built) {
        var canary = canarySystemText;
        return canary == null ? built : canary.apply(built);
    }

    /** The draft the caller gets: the sanitised one, unless the no-sanitizer canary is on. */
    static HouseDraft finish(AiKind k, KindOutputConverter.Raw raw, String text) {
        if (canarySkipSanitizer) return unsanitized(raw);
        return raw == null ? KindDraftSanitizer.sanitize(k, null, text)
                : KindDraftSanitizer.sanitize(k, raw.fields(), raw.amenities(), text);
    }

    /**
     * The model's output as a draft with the numbers read but nothing confirmed against the text and nothing clamped:
     * what Extract would be without {@link KindDraftSanitizer}. Canary only; the house's fields.
     */
    static HouseDraft unsanitized(KindOutputConverter.Raw raw) {
        var warnings = new ArrayList<String>(List.of("canary: sanitizer bypassed"));
        if (raw == null) return new HouseDraft(null, null, null, null, null, null, null, null, null, null, null, List.of(), warnings);
        var f = raw.fields();
        return new HouseDraft(f.get("label"), f.get("address"), f.get("street"), f.get("locality"),
                DraftSanitizer.price(f.get("price"), warnings), DraftSanitizer.priceType(f.get("priceType"), warnings),
                DraftSanitizer.bedrooms(f.get("bedrooms"), warnings), f.get("contactName"), f.get("contactPhone"),
                f.get("listingUrl"), f.get("notes"), raw.amenities() == null ? List.of() : List.copyOf(raw.amenities()),
                warnings);
    }
}
