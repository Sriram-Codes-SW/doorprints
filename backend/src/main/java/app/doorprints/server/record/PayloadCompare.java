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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Whether two payload texts hold the same JSON value (S4b-BL-163). The stored payload is {@code jsonb}, so what is read
 * back is PostgreSQL's text, not the client's: a space after each colon and comma, keys re-ordered, numbers
 * normalised. Comparing texts would call every retried PUT a change. Objects are equal regardless of key order, arrays
 * in order, numbers by value.
 */
final class PayloadCompare {

    private PayloadCompare() {
    }

    static boolean same(ObjectMapper json, String stored, String incoming) {
        try {
            return same(json.readTree(stored), json.readTree(incoming));
        } catch (JacksonException e) {
            return false;
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Map.Entry<String, JsonNode> field : a.properties()) {
                var other = b.get(field.getKey());
                if (other == null || !same(field.getValue(), other)) return false;
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
}
