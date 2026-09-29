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

package app.doorprints.shared.export

/**
 * The four languages a copy can be made in, by their **native** names (docs/05 section 8).
 *
 * One list for every place that shows a language: the Export screen's "Language of the copy" choices, the Settings
 * language picker, and the cover of the HTML, PDF and Markdown copies. The cover used to print the raw code
 * (`ta`), which means nothing to the family member a copy is sent to; the native name is recognisable whatever
 * language the reader or the phone is in.
 */
object ExportLanguages {

    /** Code to native name, in the order the choices are offered. */
    val NATIVE_NAMES: Map<String, String> = mapOf(
        "en" to "English",
        "hi" to "हिन्दी",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
    )

    /** The native name of [code], or the code itself for anything outside en/hi/ta/te. */
    fun nativeName(code: String): String = NATIVE_NAMES[code] ?: code
}
