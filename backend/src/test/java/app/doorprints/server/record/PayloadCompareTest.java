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

package app.doorprints.server.record;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a pushed payload is the one that is stored (S4b-BL-163, the tie rule of docs/03 section 10.1). The stored
 * side has been through PostgreSQL's {@code jsonb}, which writes {@code {"a": 1}} with a space after the colon, sorts
 * the keys (shorter first, then by bytes) and normalises numbers, so the texts differ for the same JSON value.
 */
class PayloadCompareTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean same(String stored, String incoming) {
        return PayloadCompare.same(JSON, stored, incoming);
    }

    @Test
    void theSameValueIsTheSameWhateverTheSpacingOrKeyOrder() {
        // What the client sent, and what jsonb hands back for it (colon space, short keys first).
        assertThat(same("{\"name\": \"Ravi\"}", "{\"name\":\"Ravi\"}")).isTrue();
        assertThat(same("{\"name\": \"Ravi\", \"phone\": \"98\"}", "{\"phone\":\"98\",\"name\":\"Ravi\"}")).isTrue();
        assertThat(same("{\"a\": {\"bb\": [1, 2], \"c\": null}}", "{\"a\":{\"c\":null,\"bb\":[1,2]}}")).isTrue();
        assertThat(same("{}", "{}")).isTrue();
    }

    @Test
    void numbersAreComparedByValueSinceJsonbNormalisesThem() {
        assertThat(same("{\"n\": 100}", "{\"n\":1e2}")).isTrue();
        assertThat(same("{\"n\": 1.5}", "{\"n\":1.50}")).isTrue();
        assertThat(same("{\"n\": 12.9716}", "{\"n\":12.9716}")).isTrue();
        assertThat(same("{\"n\": 100}", "{\"n\":101}")).isFalse();
    }

    @Test
    void aDifferentValueIsDifferent() {
        assertThat(same("{\"name\": \"Ravi\"}", "{\"name\":\"Asha\"}")).isFalse();
        assertThat(same("{\"name\": \"Ravi\"}", "{\"name\":\"Ravi\",\"x\":1}")).isFalse();
        assertThat(same("{\"name\": \"Ravi\", \"x\": 1}", "{\"name\":\"Ravi\"}")).isFalse();
        assertThat(same("{\"a\": [1, 2]}", "{\"a\":[2,1]}")).isFalse();
        assertThat(same("{\"a\": \"1\"}", "{\"a\":1}")).isFalse();
        assertThat(same("{\"a\": null}", "{}")).isFalse();
    }

    @Test
    void textThatIsNotJsonIsNeverTheSame() {
        assertThat(same("{\"a\": 1}", "not json")).isFalse();
        assertThat(same("not json", "not json")).isFalse();
    }
}
