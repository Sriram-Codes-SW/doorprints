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

package app.doorprints.shared.country

import app.doorprints.shared.location.IndiaOutline

/**
 * The one country pack the app ships (ADR-38): India. Every India-specific constant the features used to carry (the rupee
 * and its lakh/crore grouping and amount words, the Indian phone shapes, the geocoder's country and box, the starting map
 * view, the speech locale and the AI prompts' currency words) is here and nowhere else; `tools/check-country-literals.mjs`
 * fails when a new one appears outside this file. The TypeScript twin is `web/src/app/shared/country/india-pack.ts`.
 *
 * The boundary lines are not here: [GeoRules.contains] points at [IndiaOutline], the Government of India's outline test (ADR-22).
 */
object IndiaPack : CountryPack {
    override val id = "in"
    override val name = "India"

    override val money = MoneyRules(
        symbol = "₹",
        code = "INR",
        word = "Rs",
        name = "rupees",
        markers = listOf("₹", "Rs\\.?", "INR"),
        group = ::groupLakh,
        xlsxFormat = "\"₹\"\\ #,##,##0",
        units = listOf(
            AmountUnit("k", 1_000L, UnitScope.ALWAYS),
            AmountUnit("thousand", 1_000L, UnitScope.ALWAYS),
            AmountUnit("l", 100_000L, UnitScope.AFTER_SYMBOL),
            AmountUnit("lac", 100_000L, UnitScope.ALWAYS),
            AmountUnit("lakh", 100_000L, UnitScope.ALWAYS),
            AmountUnit("lakhs", 100_000L, UnitScope.ALWAYS),
            AmountUnit("lacs", 100_000L, UnitScope.ALWAYS),
            AmountUnit("cr", 10_000_000L, UnitScope.ALWAYS),
            AmountUnit("crore", 10_000_000L, UnitScope.ALWAYS),
            AmountUnit("crores", 10_000_000L, UnitScope.ALWAYS),
            AmountUnit("m", 1_000_000L, UnitScope.NEVER),
            AmountUnit("mn", 1_000_000L, UnitScope.NEVER),
            AmountUnit("million", 1_000_000L, UnitScope.NEVER),
        ),
        saleFrom = 1_000_000L,
    )

    override val phone = PhoneRules(
        // An Indian mobile number, with or without +91, spaces or dashes, or a landline with its STD code (011 4155 0101, (022) 2655 0101).
        listing = """(?:\+91[\s-]?)?(?:0)?[6-9]\d{4}[\s-]?\d{5}\b|(?<!\d)\(?0\d{2,4}\)?[\s-]?\d{3,4}[\s-]?\d{3,4}\b""",
        redact = listOf(
            "(?:(?:\\+?91|0)[ \\-]?)?[6-9](?:[ .\\-]?\\d){9}", // Indian mobile
            "\\(?0\\d{2,4}\\)?[ \\-]?\\d{3,4}[ \\-]?\\d{3,4}", // STD code (also "(022)") + landline
        ),
        keptDigits = 10,
        keyMinDigits = 6,
        trunkPrefix = "0",
        landlineAreaCodeSkip = 3,
    )

    /** Arabic-Indic, Urdu, Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam. */
    override val digitZeros = listOf(0x0660, 0x06F0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66)

    override val geo = GeoRules(
        countryCode = "in",
        queryName = "India",
        // ADR-22: the north reaches 37.1 degrees in Ladakh, as the Government of India depicts the country.
        box = GeoBox(south = 6.5, west = 68.1, north = 37.1, east = 97.4),
        defaultLat = 20.5937,
        defaultLon = 78.9629,
        defaultZoom = 4.0,
        contains = { lat, lon -> IndiaOutline.contains(lat, lon) }, // read on first use: the outline is not loaded at start-up
    )

    override val locale = LocaleRules(
        fallback = "en-IN",
        speech = mapOf("hi" to "hi-IN", "ta" to "ta-IN", "te" to "te-IN"),
    )

    override val areaUnit = "sq ft"

    override val prompt = PromptWords(
        priceExamples = "\"25,000\", \"1.2 Cr\", \"85 lakh\"",
        feeExamples = "\"96,000\", \"1.2 lakh\"",
        feeSchemaExample = "'96,000' or '1.2 lakh'",
    )

    override val voiceHouseKeyTerms = "2 BHK, 3 BHK, lakh, crore, sq ft, rent, deposit, maintenance, society, locality"

    /** 1250000 -> 12,50,000: the last three digits, then pairs. */
    private fun groupLakh(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,${digits.takeLast(3)}"
    }
}
