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

package app.doorprints.server.ai.eval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two decisions of {@link GoldenSetEvalTest}'s seeding that must not depend on the run (S4b-BL-201), as plain
 * functions so a test can pin them without a provider key: the body each fixture house is saved with, and the check
 * that the index holds every fixture house.
 */
final class FixtureSeeding {

    private FixtureSeeding() {
    }

    /**
     * The JSON body that saves fixture house number {@code i} (its place in the golden set). {@code updatedAt} is
     * {@code now} minus {@code i} seconds: distinct and in the golden set's order, so the house list (newest edit first)
     * and every tool search over it come back in the same order every run, instead of an arbitrary order among 30 equal
     * timestamps. Pure: the same input gives the same body, and the golden set's own map is not changed.
     */
    static Map<String, Object> seedBody(Map<String, Object> house, int i, Instant now) {
        var body = new LinkedHashMap<String, Object>(house);
        // The golden set's own tags (v0.7), not house fields.
        body.remove("city");
        body.remove("region");
        body.put("updatedAt", now.minusSeconds(i).toString());
        body.put("deleted", false);
        return body;
    }

    /**
     * Null when {@code indexed} (the {@code indexed} field of the re-index answer) equals the number of fixture houses;
     * otherwise the harness error to record. A partial index would silently lower the Ask and Plan scores, which then
     * measure the indexing, not the model.
     */
    static String indexedCountError(Object indexed, int expected) {
        long got;
        try {
            got = indexed instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(indexed).strip());
        } catch (NumberFormatException e) {
            return "Re-index answered without a count of indexed houses (" + EvalScorer.truncate(String.valueOf(indexed), 40)
                    + "); expected " + expected + " fixture houses";
        }
        if (got != expected) {
            return "Re-index indexed " + got + " house(s) but the golden set has " + expected
                    + " fixture houses; ask/plan cases skipped because their scores would measure the partial index";
        }
        return null;
    }
}
