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

package app.doorprints.server.device;

import app.doorprints.server.config.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards the owner page's calls, {@code /owner/api/**} (docs/03 §12.1). A call must:
 * <ol>
 *   <li>carry the header {@value #HEADER}: a page on another site cannot send it without a CORS preflight, and the
 *       owner API allows no cross-origin call, so a cross-site form or image cannot reach it;</li>
 *   <li>come from the page's own origin: {@code Sec-Fetch-Site} is {@code same-origin} when the browser sends it,
 *       and otherwise an {@code Origin} header, if any, matches this server;</li>
 *   <li>carry an open owner session in the {@code dp_owner} cookie ({@code HttpOnly}, {@code SameSite=Strict}), except
 *       {@code POST /owner/api/session}, which opens one from a setup link.</li>
 * </ol>
 * The session id is put in the request as {@value #SESSION_ATTRIBUTE}.
 */
public class OwnerFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Doorprints-Owner";
    public static final String SESSION_ATTRIBUTE = "doorprints.ownerSession";
    static final String API = "/owner/api";

    private static final Logger log = LoggerFactory.getLogger(OwnerFilter.class);

    private final OwnerAuth auth;

    public OwnerFilter(OwnerAuth auth) {
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isUnder(RequestPaths.path(request), API);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var method = request.getMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            write(response, 405, "Method not allowed");
            return;
        }
        if (!"1".equals(request.getHeader(HEADER)) || !sameOrigin(request)) {
            log.warn("owner.reject reason=cross-site-or-no-header path={}", RequestPaths.forLog(RequestPaths.path(request)));
            write(response, 403, "Use the owner page");
            return;
        }
        var path = RequestPaths.path(request);
        if ("POST".equals(method) && path.equals(API + "/session")) {
            chain.doFilter(request, response);
            return;
        }
        var session = auth.check(cookie(request));
        if (session.isEmpty()) {
            write(response, 401, "Sign in to the owner page with the link from the server's log");
            return;
        }
        request.setAttribute(SESSION_ATTRIBUTE, session.get());
        chain.doFilter(request, response);
    }

    static boolean sameOrigin(HttpServletRequest request) {
        var site = request.getHeader("Sec-Fetch-Site");
        if (site != null) return "same-origin".equals(site);
        var origin = request.getHeader("Origin");
        return origin == null || origin.equals(serverOrigin(request));
    }

    /** This server's origin as the browser uses it: the page's {@code Origin} when sent, else scheme, host and port. */
    static String origin(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        return origin != null && !origin.isBlank() && !"null".equals(origin) ? origin : serverOrigin(request);
    }

    private static String serverOrigin(HttpServletRequest request) {
        var scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static String cookie(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (OwnerApiController.COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static void write(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
