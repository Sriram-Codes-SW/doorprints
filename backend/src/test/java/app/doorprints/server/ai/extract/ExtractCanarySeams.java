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

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The canary suite's hand on Extract (S4b-BL-237, docs/ai/ai-design.md 8.6): test sources only, in the service's package
 * so it can reach the package-private seams of {@link ListingExtractionService}. The golden-set eval sets one canary for a
 * run and {@link #reset} afterwards; {@code ExtractCanarySeamsTest} proves the default path is the production one.
 */
public final class ExtractCanarySeams {

    private ExtractCanarySeams() {
    }

    /** The model's output becomes the draft without {@link DraftSanitizer} (canary {@code no-sanitizer}). */
    public static void skipSanitizer(boolean on) {
        ListingExtractionService.canarySkipSanitizer = on;
    }

    /** Rewrites the system text before it is sent (canary {@code prompt-without-rules}); null restores the default. */
    public static void systemText(UnaryOperator<String> rewrite) {
        ListingExtractionService.canarySystemText = rewrite;
    }

    /** Builds the whole prompt from the raw listing text (canary {@code no-wrapping}); null restores the default. */
    public static void prompt(Function<String, ExtractionPrompts.Built> build) {
        ListingExtractionService.canaryPrompt = build;
    }

    /** The prompt the service would send for {@code text} and {@code nonce} right now (default or canary), for tests. */
    public static ExtractionPrompts.Built builtPrompt(String text, String nonce) {
        return ListingExtractionService.builtPrompt(text, nonce);
    }

    public static void reset() {
        ListingExtractionService.canarySkipSanitizer = false;
        ListingExtractionService.canarySystemText = null;
        ListingExtractionService.canaryPrompt = null;
    }

    public static boolean isDefault() {
        return !ListingExtractionService.canarySkipSanitizer && ListingExtractionService.canarySystemText == null
                && ListingExtractionService.canaryPrompt == null;
    }
}
