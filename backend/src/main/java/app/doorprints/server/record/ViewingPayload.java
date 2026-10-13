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

import tools.jackson.databind.JsonNode;

/**
 * The candidate a {@code viewing} record's payload names (S4b-BL-247). Writers write {@link #KEY}; a payload stored
 * before S4b-BL-247 names it {@link #OLD_KEY} and is read the same, so no stored row needs rewriting (no migration). A
 * payload with both keys, whatever their values, is a bad row and names nothing, as on the website and the phone
 * ({@code docs/schemas/viewing-payload-vectors.json}).
 */
public final class ViewingPayload {

    /** The key every writer writes since S4b-BL-247. */
    public static final String KEY = "candidateId";
    /** The key before S4b-BL-247, still read. */
    public static final String OLD_KEY = "houseId";
    /** The longest candidate id a viewing may name (the clients' limit). */
    public static final int MAX_REF = 64;

    private ViewingPayload() {
    }

    /**
     * The candidate id the payload names under exactly one of the two keys: non-blank text of at most
     * {@link #MAX_REF} characters, or {@code null} (neither key, both keys, not text, blank or too long).
     */
    public static String candidateOf(JsonNode payload) {
        if (payload == null || !payload.isObject()) return null;
        var hasKey = payload.has(KEY);
        if (hasKey == payload.has(OLD_KEY)) return null;
        var node = payload.get(hasKey ? KEY : OLD_KEY);
        if (node == null || !node.isString()) return null;
        var id = node.asString();
        return id.isBlank() || id.length() > MAX_REF ? null : id;
    }
}
