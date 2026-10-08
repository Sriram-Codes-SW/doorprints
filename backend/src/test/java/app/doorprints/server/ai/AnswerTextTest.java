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

import app.doorprints.server.ai.rag.AskPrompts;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The shared cases are in ParityVectorsTest ({@code answerText}); these are the ones the vectors cannot hold. */
class AnswerTextTest {

    @Test
    void anAddressInTheRecordsIsKeptAndOneOnlyInTheQuestionIsNot() {
        assertThat(AnswerText.clean("![x](https://evil.example/a.png) https://example.com/l/1 https://evil.example/log?d=",
                "House: A\nNotes: see https://example.com/l/1.")).isEqualTo("x https://example.com/l/1 [link removed]");
    }

    @Test
    void theRefusalSentenceAndEmptyTextAreUntouched() {
        assertThat(AnswerText.clean(AskPrompts.I_DONT_KNOW, "")).isEqualTo(AskPrompts.I_DONT_KNOW);
        assertThat(AnswerText.clean(null, "x")).isNull();
        assertThat(AnswerText.clean("", "x")).isEmpty();
    }

    @Test
    void hostileLongInputIsScannedInLinearTime() {
        for (var unit : new String[] {"[", "![", "[a](", "](", "http://", "[x](http://"}) {
            var text = unit.repeat(200_000);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AnswerText.clean(text, text));
        }
        var open = "[" + "a".repeat(200_000);
        var nested = "[".repeat(30_000) + "[x]".repeat(30_000);
        var parens = "[a](" + "(".repeat(100_000);
        var address = "http://" + "a".repeat(1_000_000);
        for (var text : new String[] {nested, open, parens, address}) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AnswerText.clean(text, text));
        }
        assertThat(AnswerText.clean("x ".repeat(100_000) + "https://evil.example/y", ""))
                .isEqualTo("x ".repeat(100_000) + "[link removed]");
    }
}
