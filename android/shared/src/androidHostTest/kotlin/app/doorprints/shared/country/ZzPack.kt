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

/**
 * A synthetic country, "Zedland" (`zz`), for tests only (ADR-38, WP-C2): `$` before the amount with 1,234,567 grouping, the
 * words k, mm and bn for thousands, millions and billions, seven-digit phone numbers with `+99`, Thai digits, a unit of square
 * metres and a box around (0, 0). It shares nothing with India's pack, so a call site that still assumed India gives another
 * answer under it and the test fails. The TypeScript twin is `web/src/app/shared/testing/zz-pack.ts`; the answers are
 * `docs/schemas/country-pack-vectors.json` section `zz`. It is in a test source set, so no build ships it.
 */
object ZzPack : CountryPack {
    override val id = "zz"
    override val name = "Zedland"

    override val money = MoneyRules(
        symbol = "$",
        code = "ZZD",
        word = "Zd",
        name = "zeds",
        markers = listOf("\\$", "Zd\\.?"),
        group = { digits -> digits.reversed().chunked(3).joinToString(",").reversed() },
        xlsxFormat = "\"$\"#,##0",
        units = listOf(
            AmountUnit("k", 1_000L, UnitScope.ALWAYS),
            AmountUnit("mm", 1_000_000L, UnitScope.ALWAYS),
            AmountUnit("bn", 1_000_000_000L, UnitScope.AFTER_SYMBOL),
        ),
        saleFrom = 500_000L,
    )

    override val phone = PhoneRules(
        listing = "(?:\\+99[\\s-]?)?[2-5]\\d{2}[\\s-]?\\d{4}\\b",
        redact = listOf("(?:\\+?99[ \\-]?)?[2-5]\\d{2}[ \\-]?\\d{4}"),
        keptDigits = 7,
        keyMinDigits = 5,
        trunkPrefix = "8",
        landlineAreaCodeSkip = 2,
    )

    override val digitZeros = listOf(0x0E50)

    override val geo = GeoRules(
        countryCode = "zz",
        queryName = "Zedland",
        box = GeoBox(south = -10.0, west = -10.0, north = 10.0, east = 10.0),
        defaultLat = 1.5,
        defaultLon = 2.5,
        defaultZoom = 6.0,
        contains = { lat, lon -> kotlin.math.abs(lat) <= 5 && kotlin.math.abs(lon) <= 5 },
    )

    override val locale = LocaleRules(fallback = "zz-ZZ", speech = mapOf("hi" to "zz-ZZ", "zq" to "zq-ZZ"))
    override val areaUnit = "sq m"
    override val prompt = PromptWords(
        priceExamples = "\"25,000\", \"3 mm\"",
        feeExamples = "\"96,000\", \"2 mm\"",
        feeSchemaExample = "'96,000' or '2 mm'",
    )
    override val voiceHouseKeyTerms = "2 rooms, mm, bn, sq m, rent"
}
