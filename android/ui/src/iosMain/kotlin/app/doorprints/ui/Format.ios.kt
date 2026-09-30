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

// A star import: dateWithTimeIntervalSince1970 comes from an NSDate category, an extension in Kotlin/Native.
import platform.Foundation.*

internal actual fun formatDate(epochMillis: Long, language: String, withTime: Boolean): String {
    val formatter = NSDateFormatter()
    // The language with region IN, as on Android; the formatter uses the device's time zone.
    formatter.locale = NSLocale(localeIdentifier = "${language.ifEmpty { "en" }}_IN")
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = if (withTime) NSDateFormatterShortStyle else NSDateFormatterNoStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

// Not NSString's "%.6f": C rounds the exact binary value ("12.345678" for 12.3456785) where Java rounds its shortest
// decimal half up ("12.345679"), and the form writes what Android writes (S4b-BL-40).
internal actual fun formatSixDecimals(value: Double): String = sixDecimalsHalfUp(value)

internal actual fun utcOffsetMillis(epochMillis: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)).toInt() * 1000

// The shipped language the strings are shown in (S4b-BL-40): Compose resources pick the strings by the first of
// NSLocale's preferred languages, English when the app does not ship it. The app's own language setting is ADR-23
// phase 8.
actual fun appLanguage(): String = shippedLanguageOf(NSLocale.preferredLanguages.firstOrNull() as String?)

/** The languages the app ships strings for; the first is the default. */
private val SHIPPED_LANGUAGES = listOf("en", "hi", "ta", "te")

/**
 * The shipped language for an iOS language [tag] ("hi-IN", "ta", "en_GB", "zh-Hans-CN"): its language code when the
 * app ships it (en, hi, ta, te), otherwise "en", which Compose resources fall back to (the default `values` strings).
 */
internal fun shippedLanguageOf(tag: String?): String {
    val language = tag.orEmpty().substringBefore('-').substringBefore('_').lowercase()
    return language.takeIf { it in SHIPPED_LANGUAGES } ?: SHIPPED_LANGUAGES.first()
}
