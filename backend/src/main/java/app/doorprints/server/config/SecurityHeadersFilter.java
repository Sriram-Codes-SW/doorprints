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

package app.doorprints.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security headers on every API response (F-10). Runs first, so 400/401/429 responses written by later filters carry
 * them too. The API only serves JSON and images, so the CSP forbids everything and no page may frame it.
 *
 * <p>{@code Cache-Control: no-store} keeps private JSON (locations, notes, phone numbers) out of shared and disk
 * caches; photo responses set their own private caching in the controller and are left alone. HSTS is sent only when
 * the request arrived over HTTPS (directly, or via a trusted proxy with {@code server.forward-headers-strategy}).
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String OWNER_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    /**
     * Sets the same hardening headers on every response; the owner page gets a CSP that lets it load its own script,
     * style and QR image, and every other path a CSP that allows nothing. Responses are marked no-store except photo
     * bytes.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        var path = RequestPaths.path(request);
        if (path.equals("/owner") || RequestPaths.isUnder(path, "/owner")) {
            // The owner page (docs/03 §12.1): its own script and style only, the server-drawn QR code as a data: image,
            // calls to this server only; never framed, never cached.
            response.setHeader("Content-Security-Policy", OWNER_CSP);
            response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        } else {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
            response.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
        }
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        // Only a photo's bytes may be cached (GET); the meta answer of PUT /api/photos/{id}/meta is the person's own words.
        if (!(path.startsWith("/api/photos/") && "GET".equals(request.getMethod()))) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
