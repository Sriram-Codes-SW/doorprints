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

/** Where the listing parser (not the AI-draft sanitiser, which reads every word) takes an amount word as part of a price. */
enum class UnitScope {
    /** Also in a bare "45 lakh". */
    ALWAYS,

    /** Only after a currency sign or word: a lone "5 l" is not a price. */
    AFTER_SYMBOL,

    /** Never by the listing parser. */
    NEVER,
}

/** How one amount word ("lakh", "k") counts: [word] in lower case, what it multiplies by, and where a pasted listing may use it. */
data class AmountUnit(val word: String, val multiplier: Long, val listing: UnitScope)

/** A box of latitude and longitude (south-west and north-east corners). */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
}

/**
 * The country pack (ADR-38, docs/03 section 21): everything the app knows about the one country it is built for that a
 * feature could ask instead of assuming: how money is written and how an amount is read, the shapes of a phone number, the
 * geocoder's country, the default map view, the locale defaults and the words of the AI prompts that name a currency. A new
 * country is a new object of this shape, and no feature changes. The TypeScript twin is
 * `web/src/app/shared/country/country-pack.ts`; `docs/schemas/country-pack-vectors.json` holds both to the same answers.
 *
 * Three things are deliberately not here: India's boundary lines (the Government of India's depiction, ADR-22: a pack may
 * only point at the border test, [contains]); the words of the screens (the string resources); the kinds
 * (`docs/schemas/kinds/`).
 *
 * Code reads the pack through [Country.pack] at the moment it needs it (never at start-up), so a test can swap it with
 * [Country.use] and see every call site follow.
 */
interface CountryPack {
    /** The ISO 3166-1 alpha-2 code, lower case. */
    val id: String

    /** The English name of the country, as the AI prompts name the market the app is for. */
    val name: String
    val money: MoneyRules
    val phone: PhoneRules

    /** The zero of each non-Latin digit script whose ten digits follow it, read as 0-9 before any number is matched. */
    val digitZeros: List<Int>
    val geo: GeoRules
    val locale: LocaleRules

    /** The unit a listing's area is written in and the app records, as words in the documents the AI reads. */
    val areaUnit: String
    val prompt: PromptWords

    /** The words a transcriber is asked to spell right for a house hunt. */
    val voiceHouseKeyTerms: String
}

class MoneyRules(
    /** The sign written before an amount: `₹12,50,000`. */
    val symbol: String,
    /** The ISO 4217 code. */
    val code: String,
    /** The ASCII word the AI documents and prompts use for the currency: `Rs 25000`. */
    val word: String,
    /** The plural name of the currency, as the AI schema descriptions say it. */
    val name: String,
    /** Regular-expression sources of what a pasted listing puts before an amount: the sign, the word, the code. */
    val markers: List<String>,
    /** The digits (0-9 only, no sign) with the country's group separators: `1250000` gives `12,50,000`. */
    val group: (String) -> String,
    /** The spreadsheet number format of a currency cell, unescaped (the writer escapes the quotes for XML). */
    val xlsxFormat: String,
    val units: List<AmountUnit>,
    /** From this many whole units a price with no rent words is a sale. */
    val saleFrom: Long,
)

class PhoneRules(
    /** Regular-expression source of a number as a listing writes it. */
    val listing: String,
    /** Regular-expression sources of the country's own number shapes, tried by the redactor between the international `+cc` shape and the long digit run. */
    val redact: List<String>,
    /** A number is compared on its last this-many digits, and a saved one is also looked for without what precedes them. */
    val keptDigits: Int,
    /** Fewer digits than this and a number identifies nobody (the comparison key is null). */
    val keyMinDigits: Int,
    /** The digit a national number may start with (the trunk prefix). */
    val trunkPrefix: String,
    /** A landline with a trunk prefix and its area code is often written without them: this many leading digits are dropped. */
    val landlineAreaCodeSkip: Int,
)

class GeoRules(
    /** The country code the geocoder is limited to (lower case). */
    val countryCode: String,
    /** The English name added after a name sent to a geocoder that takes no country code, or null. */
    val queryName: String?,
    /** The box a platform geocoder is asked for; the quick first test of a found point. */
    val box: GeoBox,
    /** Where a map starts when nothing says where to look. */
    val defaultLat: Double,
    val defaultLon: Double,
    val defaultZoom: Double,
    /** Whether a point is inside the country's border as the country depicts it: a reference to the border test, never the lines. */
    val contains: (Double, Double) -> Boolean,
)

class LocaleRules(
    /** The BCP 47 tag of the app's language when it has none of its own (speech input's hint). */
    val fallback: String,
    /** The app languages whose speech tag has a region: language code to tag. */
    val speech: Map<String, String>,
)

class PromptWords(
    /** The amounts the AI is shown as examples of a price copied as written: `"25,000", "1.2 Cr", "85 lakh"`. */
    val priceExamples: String,
    /** The same for a fee per year. */
    val feeExamples: String,
    /** The example inside the fee field's schema description. */
    val feeSchemaExample: String,
)

/** The active country pack: India's unless a test swapped it. */
object Country {
    private var active: CountryPack? = null

    /** The active pack. */
    val pack: CountryPack get() = active ?: IndiaPack

    /** Makes [pack] the active one (tests only: the app ships one pack) and returns the call that puts the previous one back. */
    fun use(pack: CountryPack): () -> Unit {
        val before = active
        active = pack
        return { active = before }
    }

    /** `Rs 25000`: an amount as the AI documents and prompts write it. */
    fun promptMoney(amount: Any): String = "${pack.money.word} $amount"

    private var cachedFor: CountryPack? = null
    private var cached: Derived? = null

    /** The patterns built from the active pack, once per pack. */
    val derived: Derived
        get() {
            val p = pack
            val hit = cached
            if (hit != null && cachedFor === p) return hit
            val d = Derived(p)
            cached = d
            cachedFor = p
            return d
        }
}

/** The patterns built from a pack (see `derived()` of the TypeScript twin). */
class Derived(pack: CountryPack) {
    /** The listing parser's phone number. */
    val listingPhone = Regex(pack.phone.listing)

    /** The listing parser's price: a marked amount, or a bare one with a unit word. */
    val listingPrice: Regex

    /** An amount as the AI-draft sanitiser reads it: a number and an optional unit word. */
    val draftAmount: Regex

    /** The redactor's phone-like text. */
    val redactPhone: Regex

    /** A unit word's multiplier (lower case); 1 for none. */
    val multiplier: Map<String, Long> = pack.money.units.associate { it.word to it.multiplier }

    init {
        fun words(pick: (AmountUnit) -> Boolean) = pack.money.units.filter(pick).joinToString("|") { it.word }
        val afterSymbol = words { it.listing != UnitScope.NEVER }
        val bare = words { it.listing == UnitScope.ALWAYS }
        val all = words { true }
        val markers = pack.money.markers.joinToString("|")
        listingPrice = Regex(
            """(?:$markers)\s*([\d,]+(?:\.\d+)?)\s*($afterSymbol)?\b|\b(\d+(?:\.\d+)?)\s*($bare)\b""",
            RegexOption.IGNORE_CASE,
        )
        draftAmount = Regex("(\\d+(?:\\.\\d+)?)\\s*($all)?\\b", RegexOption.IGNORE_CASE)
        val word = "[\\p{L}\\p{M}\\p{N}]"
        val shapes = listOf("\\+\\d(?:[ .()\\-]{0,2}\\d){6,14}") + pack.phone.redact + "\\d{10,15}"
        redactPhone = Regex("(?<![\\p{L}\\p{M}\\p{N}+])(?:${shapes.joinToString("|")})(?!$word)")
    }
}
