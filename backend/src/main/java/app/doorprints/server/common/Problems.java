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

package app.doorprints.server.common;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * The one writer of the small {@code application/problem+json} answers that servlet filters give (S4b-BL-164). A
 * filter runs before Spring's exception handlers, so it cannot return a {@code ProblemDetail}; until now six of them
 * each wrote the status, the content type and the JSON text by hand. One writer means one set of bytes for a given
 * status and text (TC-S-08 needs every wrong key to get the same answer) and one place that escapes the text.
 * <p>
 * The body is {@code {"status":..,"detail":".."}}, with {@code "code":".."} between them when a code is given. Callers
 * pass fixed text and numbers, never request data; the text is still escaped, so a quote or a control character in it
 * cannot break the JSON.
 */
public final class Problems {

    /** The media type of every answer. */
    public static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {
    }

    /** {@code {"status":429,"detail":"..."}} with the given headers (for example {@code Retry-After}). */
    public static void write(HttpServletResponse response, int status, String detail, Map<String, String> headers)
            throws IOException {
        write(response, status, null, detail, headers);
    }

    /** As {@link #write(HttpServletResponse, int, String, Map)} with no extra header. */
    public static void write(HttpServletResponse response, int status, String detail) throws IOException {
        write(response, status, null, detail, Map.of());
    }

    /** Also names a machine-readable {@code code} (for example {@code AI_PAUSED}); a null code leaves it out. */
    public static void write(HttpServletResponse response, int status, String code, String detail,
                             Map<String, String> headers) throws IOException {
        response.setStatus(status);
        headers.forEach(response::setHeader);
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body(status, code, detail));
    }

    /** The JSON text of an answer. */
    static String body(int status, String code, String detail) {
        var json = new StringBuilder("{\"status\":").append(status);
        if (code != null) json.append(",\"code\":\"").append(escape(code)).append('"');
        return json.append(",\"detail\":\"").append(escape(detail)).append("\"}").toString();
    }

    /** JSON string escaping (RFC 8259 section 7): the quote, the backslash and every control character. */
    static String escape(String text) {
        var out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
