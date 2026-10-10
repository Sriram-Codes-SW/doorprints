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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canary seams of Extract (S4b-BL-237) are off by default and change nothing until the canary suite turns one on:
 * the system text goes out as built and the draft is the sanitised one.
 */
class ExtractCanarySeamsTest {

    private static final String TEXT = "2BHK for rent in Adyar, Chennai. Rent 28k. Call 98400 12345.";

    @AfterEach
    void reset() {
        ExtractCanarySeams.reset();
    }

    @Test
    void bothSeamsAreOffByDefaultAndTheDefaultPathIsTheProductionOne() {
        assertThat(ExtractCanarySeams.isDefault()).isTrue();
        var built = ExtractionPrompts.build(TEXT, "nonce").system();
        assertThat(ListingExtractionService.systemText(built)).isSameAs(built);
        var raw = new RawListing("Adyar 2BHK", null, null, "Adyar", "28k", "RENT", "2", null, "98400 12345",
                "https://evil.example/phish", "Deposit 10 months", List.of("lift"));
        var draft = ListingExtractionService.finish(raw, TEXT);
        assertThat(draft).isEqualTo(DraftSanitizer.sanitize(raw, TEXT));
        assertThat(draft.listingUrl()).as("a link the text does not hold is dropped by the sanitiser").isNull();
        assertThat(draft.price()).isEqualTo(28000L);
    }

    @Test
    void theNoSanitizerCanaryKeepsWhatTheModelInventedAndSaysSo() {
        ExtractCanarySeams.skipSanitizer(true);
        var raw = new RawListing("Adyar 2BHK", null, null, "Adyar", "28k", "RENT", "2", null, "99999 00000",
                "https://evil.example/phish", "Deposit 10 months", List.of("lift"));
        var draft = ListingExtractionService.finish(raw, TEXT);
        assertThat(draft.listingUrl()).isEqualTo("https://evil.example/phish");
        assertThat(draft.contactPhone()).isEqualTo("99999 00000");
        assertThat(draft.price()).isEqualTo(28000L);
        assertThat(draft.bedrooms()).isEqualTo(2);
        assertThat(draft.warnings()).contains("canary: sanitizer bypassed");
        assertThat(ListingExtractionService.finish(null, TEXT).warnings()).contains("canary: sanitizer bypassed");
    }

    @Test
    void thePromptCanaryRewritesTheSystemTextOnlyWhileItIsSetAndResetRestoresTheDefault() {
        ExtractCanarySeams.systemText(s -> s.replace("Rules:", "Notes:"));
        var built = ExtractionPrompts.build(TEXT, "nonce").system();
        assertThat(ListingExtractionService.systemText(built)).contains("Notes:").doesNotContain("Rules:");
        assertThat(ExtractCanarySeams.isDefault()).isFalse();
        ExtractCanarySeams.reset();
        assertThat(ExtractCanarySeams.isDefault()).isTrue();
        assertThat(ListingExtractionService.systemText(built)).isSameAs(built);
    }
}
