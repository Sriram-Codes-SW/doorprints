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

/**
 * A request the server refuses on purpose, with a message written for the caller (HTTP 400). {@link ApiExceptionHandler}
 * answers it with that message. Any other {@link IllegalArgumentException} is a library or programming error whose
 * text may name internals, so it is answered with a fixed "Malformed request" (SEC-015).
 *
 * <p>It extends {@link IllegalArgumentException} so that code which already catches or declares that type, such as
 * the {@code ClientClock} callers and the tool methods of the AI agent, keeps working unchanged.
 */
public class BadRequestException extends IllegalArgumentException {
    public BadRequestException(String message) {
        super(message);
    }
}
