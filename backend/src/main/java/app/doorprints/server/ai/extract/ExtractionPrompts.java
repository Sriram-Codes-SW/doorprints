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
import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.AiKinds;

/** Prompt text for listing extraction. Kept separate from the service so tests can check the delimiting. */
public final class ExtractionPrompts {

    private ExtractionPrompts() {
    }

    /**
     * The system and user messages for one extraction call.
     */
    public record Built(String system, String user) {
    }

    /**
     * Builds the extraction prompt for a house: {@link #build(AiKind, String, String)} with the house kind file's text
     * ({@link AiKinds#HOUSE}). The listing is placed between tags carrying the per-call nonce ({@link PromptSafety}), and the
     * system text tells the model to treat it as data, to return null for what is not stated, and never to guess contact
     * details.
     */
    public static Built build(String listingText, String nonce) {
        return build(AiKinds.HOUSE, listingText, nonce);
    }

    /**
     * The extraction prompt for a listing of kind {@code k} (kinds PR 7, S4b-BL-211; the house too since kinds PR 28): the kind's own system text
     * ({@link AiKind#extractSystem}, with the same data-not-instructions and never-guess rules as the house's) and the
     * same user text, the listing wrapped in the per-call nonce's tag.
     */
    public static Built build(AiKind k, String listingText, String nonce) {
        var system = k.extractSystem().formatted("listing-" + nonce, PromptSafety.NEUTRAL_RULE);
        var user = "Extract the listing below.\n\n" + PromptSafety.wrap("listing", nonce, listingText);
        return new Built(system, user);
    }
}
