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

package app.doorprints.drive

/**
 * The diagnostic code of a failure nobody expected (S4b-BL-146): `<step>/<ClassName>`, for example
 * `join/java.lang.NoClassDefFoundError`. A phone that says only "could not prepare the backup" gives the owner nothing to
 * send; this gives the one thing that is safe to show: where it happened and the exception's class.
 *
 * What goes in is only a step from the caller's own list and the exception's class name filtered to `[A-Za-z0-9_.$]`
 * and cut to [MAX_CLASS_NAME] characters (the tail is kept: that is the class). Nothing else about the exception is read:
 * not its text, its cause, its stack, a URL, an id or a file name (docs/02 §10: the Drive code never logs content). The
 * source test `DriveCodeTest` fails if this file ever names any of those.
 */
object DriveCode {
    const val MAX_CLASS_NAME = 120
    private const val MAX_STEP = 16
    private const val UNKNOWN = "Unknown"

    /** The code of [e] at [step] (lower-case words and hyphens: `connect`, `create`, `join-locate`, `join-recover`, `join-add`, `join-write`, `join-ready`, `backup`, `sync`). */
    fun of(step: String, e: Throwable): String = sanitizeStep(step) + "/" + sanitizeClassName(className(e))

    /** [name] reduced to the allow-list and at most [MAX_CLASS_NAME] long; [UNKNOWN] when nothing is left. */
    fun sanitizeClassName(name: String?): String {
        val kept = name.orEmpty().filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '$' }
        return if (kept.isEmpty()) UNKNOWN else kept.takeLast(MAX_CLASS_NAME)
    }

    private fun sanitizeStep(step: String): String {
        val kept = step.filter { it in 'a'..'z' || it == '-' }
        return if (kept.isEmpty() || kept.length != step.length || kept.length > MAX_STEP) "unknown" else kept
    }

    private fun className(e: Throwable): String? =
        try {
            e::class.qualifiedName ?: e::class.simpleName
        } catch (_: Throwable) {
            null
        }
}
