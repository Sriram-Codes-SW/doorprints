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

import java.util.regex.Pattern;

/**
 * Whether an Ask question is about the person's visits (S4b-BL-194 item 2). A visit is not something the question's
 * embedding finds reliably among house documents that mostly say "not visited yet", so such a question also gets the
 * houses that have visits ({@code RagService}). The rule is deliberately narrow: the English words the apps use
 * (visit, visits, visited, visiting) as whole words; other languages are not guessed.
 */
final class VisitQuestions {

    private static final Pattern WORD = Pattern.compile("\\bvisit(?:s|ed|ing)?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private VisitQuestions() {
    }

    static boolean isAbout(String question) {
        return question != null && WORD.matcher(question).find();
    }
}
