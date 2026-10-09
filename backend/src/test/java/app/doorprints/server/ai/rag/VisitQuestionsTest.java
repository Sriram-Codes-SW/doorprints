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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S4b-BL-194 item 2: the conservative rule that says a question is about the person's visits. */
class VisitQuestionsTest {

    @Test
    void questionsAboutVisitsAreRecognised() {
        assertThat(VisitQuestions.isAbout("Which houses have I already visited and when?")).isTrue();
        assertThat(VisitQuestions.isAbout("how many visits did I make to Blue gate")).isTrue();
        assertThat(VisitQuestions.isAbout("VISITED houses")).isTrue();
        assertThat(VisitQuestions.isAbout("When did I last visit the flat on MG Road?")).isTrue();
        assertThat(VisitQuestions.isAbout("Which houses have I not visited yet?")).isTrue();
    }

    @Test
    void otherQuestionsAreNot() {
        assertThat(VisitQuestions.isAbout("Which 2 BHK is cheapest?")).isFalse();
        assertThat(VisitQuestions.isAbout("Which house has the best water pressure?")).isFalse();
        assertThat(VisitQuestions.isAbout("Is there a revisiting policy?")).isFalse();
        assertThat(VisitQuestions.isAbout("supervisor")).isFalse();
        assertThat(VisitQuestions.isAbout("")).isFalse();
        assertThat(VisitQuestions.isAbout(null)).isFalse();
    }
}
