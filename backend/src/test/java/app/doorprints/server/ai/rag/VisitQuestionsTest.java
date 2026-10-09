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

/**
 * S4b-BL-194 item 2: the conservative rules that say a question is about the person's visits, and whether it asks for
 * the houses without one.
 */
class VisitQuestionsTest {

    /** Question | is about visits | is negated (only read for a question about visits). */
    private static final String[] TABLE = {
            "Which houses have I already visited and when?|true|false",
            "how many visits did I make to Blue gate|true|false",
            "VISITED houses|true|false",
            "When did I last visit the flat on MG Road?|true|false",
            "Which houses have I not visited yet?|true|true",
            "Which houses have I NOT VISITED?|true|true",
            "Which houses haven't I visited yet?|true|true",
            "Which houses haven’t I visited?|true|true",
            "Which houses have I never visited?|true|true",
            "Which houses have I yet to visit?|true|true",
            "List my unvisited houses|true|true",
            "Which houses did I not visit?|true|true",
            "Which houses have no visits?|true|true",
            "Which 2 BHK is cheapest?|false|false",
            "Which house has the best water pressure?|false|false",
            "Is there a revisiting policy?|false|false",
            "Should I revisit the Blue gate house?|false|false",
            "Is there visitor parking?|false|false",
            "supervisor|false|false"};

    @Test
    void theRulesOnATableOfQuestions() {
        for (var row : TABLE) {
            var cells = row.split("\\|");
            boolean about = Boolean.parseBoolean(cells[1]);
            assertThat(VisitQuestions.isAbout(cells[0])).as("about: " + cells[0]).isEqualTo(about);
            if (about) {
                assertThat(VisitQuestions.isNegated(cells[0])).as("negated: " + cells[0])
                        .isEqualTo(Boolean.parseBoolean(cells[2]));
            }
        }
    }

    @Test
    void nullAndBlankAreNotAboutVisits() {
        assertThat(VisitQuestions.isAbout(null)).isFalse();
        assertThat(VisitQuestions.isAbout("  ")).isFalse();
        assertThat(VisitQuestions.isNegated(null)).isFalse();
    }
}
