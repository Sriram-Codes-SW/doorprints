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

import app.doorprints.server.config.ApiKeyFilter;
import app.doorprints.server.config.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The owner's per-device AI switch (docs/03 §12.1): every AI request runs with the owner's Gemini key, so a device whose
 * switch is off (every new device) gets 403 {@code AI_OFF_FOR_DEVICE} on {@code /api/ai/**} and {@code /mcp}. Its AI
 * status reads as off; nothing else changes for it. The owner key is not affected.
 */
public class DeviceAiGuard extends OncePerRequestFilter {

    public static final String CODE = "AI_OFF_FOR_DEVICE";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = RequestPaths.path(request);
        boolean ai = (RequestPaths.isUnder(path, "/api/ai") && !path.equals("/api/ai/status"))
                || RequestPaths.isUnder(path, "/mcp");
        return !ai || RequestPaths.isPreflight(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(ApiKeyFilter.DEVICE_ATTRIBUTE) instanceof DeviceKeyStore.Caller caller
                && !caller.aiAllowed()) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"code\":\"" + CODE
                    + "\",\"detail\":\"AI is turned off for this device on the server's owner page.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
