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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal actual fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String {
    val formatter = if (withTime) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
    // The language with region IN (hi-IN, ta-IN), so the date's pattern is the Indian one in every language.
    val locale = Locale.Builder().setLanguage(language.ifEmpty { "en" }).setRegion("IN").build()
    return formatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}

internal actual fun formatSixDecimals(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

internal actual fun utcOffsetMillis(epochMillis: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMillis)

// The default locale: AppLocale.applyDefault (in :app) keeps it on the language Android resolved for the strings.
actual fun appLanguage(): String = Locale.getDefault().language
