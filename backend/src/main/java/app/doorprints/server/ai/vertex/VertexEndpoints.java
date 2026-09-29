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

package app.doorprints.server.ai.vertex;

import java.util.Locale;

/**
 * Vertex AI REST base URLs, derived from the location exactly like the google-genai Java SDK 1.65.0 does
 * ({@code ApiClient}): {@code global} -> {@code https://aiplatform.googleapis.com}, the multi-regions {@code us} /
 * {@code eu} -> {@code https://aiplatform.<loc>.rep.googleapis.com}, any region ->
 * {@code https://<region>-aiplatform.googleapis.com}. An explicit endpoint (tests, Private Service Connect) wins.
 */
public final class VertexEndpoints {

    private VertexEndpoints() {
    }

    /** Base URL without the API version and without a trailing slash. */
    public static String root(String endpointOverride, String location) {
        if (endpointOverride != null && !endpointOverride.isBlank()) return stripSlash(endpointOverride.strip());
        var loc = location == null ? "" : location.strip().toLowerCase(Locale.ROOT);
        if (loc.isEmpty() || loc.equals("global")) return "https://aiplatform.googleapis.com";
        if (loc.equals("us") || loc.equals("eu")) return "https://aiplatform." + loc + ".rep.googleapis.com";
        return "https://" + loc + "-aiplatform.googleapis.com";
    }

    /** {@link #root} plus {@code /<apiVersion>}, e.g. {@code https://asia-south1-aiplatform.googleapis.com/v1beta1}. */
    public static String versioned(String endpointOverride, String location, String apiVersion) {
        return root(endpointOverride, location) + "/" + apiVersion;
    }

    private static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
