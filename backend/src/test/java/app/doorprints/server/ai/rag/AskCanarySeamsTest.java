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

package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.rag.AskModels.ModelAnswer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canary seams of Ask (S4b-BL-237) are off by default and change nothing until the canary suite turns one on: the
 * system text goes out as built, and a house the model lists but never states a fact about is not cited.
 */
class AskCanarySeamsTest {

    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final List<Document> DOCS = List.of(
            Document.builder().id(A.toString()).text("House: Blue gate\nNotes: quiet").metadata(Map.of("label", "Blue gate")).build(),
            Document.builder().id(B.toString()).text("House: Corner flat\nNotes: noisy").metadata(Map.of("label", "Corner flat")).build());

    @AfterEach
    void reset() {
        AskCanarySeams.reset();
    }

    @Test
    void bothSeamsAreOffByDefaultAndAListedHouseWithoutAnInlineFactIsNotCited() {
        assertThat(AskCanarySeams.isDefault()).isTrue();
        var built = "system text";
        assertThat(RagService.systemText(built)).isSameAs(built);
        var answer = new ModelAnswer("The Blue gate house is quiet [house:" + A + "].", List.of(A.toString(), B.toString()));
        var cited = RagService.citations(answer, DOCS, "quiet").stream().map(c -> c.houseId()).toList();
        assertThat(cited).containsExactly(A);
    }

    @Test
    void theNoCitationFilterCanaryLetsTheListedHouseThroughAndResetTakesItBack() {
        AskCanarySeams.listedIdsCount(true);
        var answer = new ModelAnswer("The Blue gate house is quiet [house:" + A + "].", List.of(A.toString(), B.toString()));
        var cited = RagService.citations(answer, DOCS, "quiet").stream().map(c -> c.houseId()).toList();
        assertThat(cited).containsExactly(A, B);
        // Still only retrieved houses, and still no citation on the refusal: the canary bypasses one rule, not the others.
        var invented = new ModelAnswer("Quiet [house:" + A + "].", List.of("33333333-3333-4333-8333-333333333333"));
        assertThat(RagService.citations(invented, DOCS, "quiet")).hasSize(1);
        assertThat(RagService.citations(new ModelAnswer(AskPrompts.I_DONT_KNOW, List.of(A.toString())), DOCS, "q")).isEmpty();
        AskCanarySeams.reset();
        assertThat(AskCanarySeams.isDefault()).isTrue();
        assertThat(RagService.citations(answer, DOCS, "quiet")).hasSize(1);
    }

    @Test
    void thePromptCanaryRewritesTheSystemTextOnlyWhileItIsSet() {
        AskCanarySeams.systemText(s -> "no rules");
        assertThat(RagService.systemText("built")).isEqualTo("no rules");
        assertThat(AskCanarySeams.isDefault()).isFalse();
        AskCanarySeams.reset();
        assertThat(RagService.systemText("built")).isEqualTo("built");
    }
}
