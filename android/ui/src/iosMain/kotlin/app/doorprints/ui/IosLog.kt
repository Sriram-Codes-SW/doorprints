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

package app.doorprints.ui

import platform.Foundation.NSLog

/**
 * Writes [text] to the unified log as one line (NSLog), for the launch self-check, the start-up steps and the crash
 * hook (CMP-8b).
 *
 * The text goes in as the format string, with any `%` doubled, never as a variadic argument: Kotlin/Native does not turn
 * a Kotlin `String` passed through C varargs into an `NSString`, so `NSLog("%@", text)` hands NSLog the string's raw
 * bytes as an object pointer and the app dies with SIGSEGV in `objc_opt_respondsToSelector` (the first CI launches,
 * 2026-09-29: the faulting address was "DOORPRINTS" read as a pointer). The first parameter is not variadic, so it is
 * bridged to an `NSString` properly.
 */
internal fun logLine(text: String) {
    NSLog(text.replace("%", "%%"))
}
