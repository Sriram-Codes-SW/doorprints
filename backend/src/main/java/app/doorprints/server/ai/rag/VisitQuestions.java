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
 * Whether an Ask question is about the person's visits, and whether it asks for the houses WITHOUT one (S4b-BL-194
 * item 2). A visit is not something the question's embedding finds reliably among house documents that mostly say "not
 * visited yet", so such a question also gets the houses chosen by the document metadata {@code visited}
 * ({@code RagService}). The rules are deliberately narrow: the English words the apps use (visit, visits, visited,
 * visiting, unvisited) as whole words, and a short list of negations; other languages are not guessed.
 */
final class VisitQuestions {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern ABOUT = Pattern.compile("\\b(?:un)?visit(?:s|ed|ing)?\\b", FLAGS);
    private static final Pattern NEGATED = Pattern.compile(
            "\\bnot\\b|\\bnever\\b|\\b(?:have|has|did|do)n['\u2019]t\\b|\\byet to\\b|\\bunvisited\\b"
                    + "|\\bno visits?\\b|\\bwithout (?:a |any )?visits?\\b", FLAGS);

    private VisitQuestions() {
    }

    static boolean isAbout(String question) {
        return question != null && ABOUT.matcher(question).find();
    }

    /** Only meaningful for a question that {@link #isAbout}: it asks for the houses that have not been visited. */
    static boolean isNegated(String question) {
        return question != null && NEGATED.matcher(question).find();
    }
}
