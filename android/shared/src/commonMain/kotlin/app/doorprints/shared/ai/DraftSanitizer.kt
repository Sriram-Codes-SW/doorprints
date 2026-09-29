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

package app.doorprints.shared.ai

import app.doorprints.shared.api.HouseDraftDto
import kotlinx.serialization.Serializable

/** What the model fills for *Fill in from listing text*: every field as written, or null (the server's `RawListing`). */
@Serializable
data class RawListing(
    val label: String? = null,
    val address: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val price: String? = null,
    val priceType: String? = null,
    val bedrooms: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val listingUrl: String? = null,
    val notes: String? = null,
    val amenities: List<String>? = null,
)

/**
 * Checks the model's listing before the form sees it: the server's `DraftSanitizer`, ported (docs/03 §13.1). The model
 * is an untrusted parser: every field is trimmed, cleaned and capped; the phone number and the link must appear in the
 * pasted text, which stops the commonest invented values; price and bedrooms are read as numbers. Each change is a
 * warning the form shows.
 */
object DraftSanitizer {
    const val LABEL_MAX = 200
    const val ADDRESS_MAX = 500
    const val STREET_MAX = 200
    const val LOCALITY_MAX = 200
    const val CONTACT_NAME_MAX = 200
    const val PHONE_MAX = 50
    const val URL_MAX = 1000
    const val NOTES_MAX = 2000
    const val AMENITIES_MAX = 20
    const val AMENITY_MAX = 50
    const val BEDROOMS_MAX = 20
    const val PRICE_MAX = 1_000_000_000_000L

    private val WS = Regex("\\s+")
    private val PRICE = Regex(
        "(\\d+(?:\\.\\d+)?)\\s*(k|thousand|l|lac|lakh|lakhs|lacs|cr|crore|crores|m|mn|million)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val FIRST_INT = Regex("\\d+")
    /** Absolute http(s) with a host and no spaces: what Java's `URI.create` accepts for these links. */
    private val HTTP_URL = Regex("^(?:https?)://[^\\s/?#]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)

    fun sanitize(raw: RawListing?, sourceText: String?): HouseDraftDto {
        val warnings = mutableListOf<String>()
        if (raw == null) return HouseDraftDto(label = "Untitled listing", warnings = listOf("Model returned nothing usable"))
        val source = sourceText ?: ""
        val address = clean(raw.address, ADDRESS_MAX, "address", warnings)
        val street = clean(raw.street, STREET_MAX, "street", warnings)
        val locality = clean(raw.locality, LOCALITY_MAX, "locality", warnings)
        val priceType = priceType(raw.priceType, warnings)
        val price = price(raw.price, warnings)
        val bedrooms = bedrooms(raw.bedrooms, warnings)
        val contactName = clean(raw.contactName, CONTACT_NAME_MAX, "contactName", warnings)
        val phone = phone(raw.contactPhone, source, warnings)
        val url = url(raw.listingUrl, source, warnings)
        val notes = clean(raw.notes, NOTES_MAX, "notes", warnings)
        val amenities = amenities(raw.amenities, warnings)
        var label = clean(raw.label, LABEL_MAX, "label", warnings)
        if (label == null) {
            label = defaultLabel(bedrooms, locality, street)
            warnings += "label: generated because the model returned none"
        }
        return HouseDraftDto(label, address, street, locality, price, priceType, bedrooms, contactName, phone, url, notes,
            amenities, warnings.toList())
    }

    private fun dropControls(s: String) = s.filterNot { c -> (c.code < 0x20 && c != '\n' && c != '\t') || c.code in 0x7f..0x9f }

    /** Trim, drop control characters, collapse whitespace (not in notes), cap; blank, "null", "n/a", "unknown" -> null. */
    fun clean(value: String?, max: Int, field: String, warnings: MutableList<String>): String? {
        if (value == null) return null
        var s = dropControls(value)
        s = if (field == "notes") s.trim() else s.replace(WS, " ").trim()
        if (s.isEmpty() || s.equals("null", true) || s.equals("n/a", true) || s.equals("unknown", true)) return null
        if (s.length > max) {
            warnings += "$field: truncated to $max characters"
            s = s.take(max)
        }
        return s
    }

    fun priceType(value: String?, warnings: MutableList<String>): String? {
        if (value.isNullOrBlank()) return null
        return when (value.trim().uppercase()) {
            "RENT", "RENTAL", "LEASE", "MONTHLY" -> "RENT"
            "SALE", "SELL", "BUY", "RESALE" -> "SALE"
            else -> {
                warnings += "priceType: '${abbreviate(value)}' is not RENT or SALE, dropped"
                null
            }
        }
    }

    /** "25,000", "₹ 25000/month", "25k", "1.2 Cr", "85 lakh" -> whole rupees (the fraction truncated, as `BigDecimal`). */
    fun price(value: String?, warnings: MutableList<String>): Long? {
        if (value.isNullOrBlank()) return null
        val m = PRICE.find(value.replace(",", "").replace("_", ""))
        if (m == null) {
            warnings += "price: could not read '${abbreviate(value)}', dropped"
            return null
        }
        val multiplier = when (m.groupValues[2].lowercase()) {
            "k", "thousand" -> 1_000L
            "l", "lac", "lakh", "lakhs", "lacs" -> 100_000L
            "cr", "crore", "crores" -> 10_000_000L
            "m", "mn", "million" -> 1_000_000L
            else -> 1L
        }
        val number = m.groupValues[1]
        val whole = number.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val fraction = number.substringAfter('.', "")
        // More digits than a Long holds is certainly out of range.
        if (whole.length > 15) {
            warnings += "price: $number is out of range, dropped"
            return null
        }
        var rupees = whole.toLong() * multiplier
        if (fraction.isNotEmpty()) {
            // multiplier * 0.fraction, truncated: exact with integers for the few digits a price has.
            var scale = 1L
            var frac = 0L
            for (d in fraction.take(12)) {
                scale *= 10
                frac = frac * 10 + (d - '0')
            }
            rupees += frac * multiplier / scale
        }
        if (rupees < 0 || rupees > PRICE_MAX) {
            warnings += "price: $rupees is out of range, dropped"
            return null
        }
        return rupees
    }

    fun bedrooms(value: String?, warnings: MutableList<String>): Int? {
        if (value.isNullOrBlank()) return null
        val lower = value.lowercase()
        if ("studio" in lower || "1rk" in lower) return 0
        val m = FIRST_INT.find(value)
        val n = m?.value?.toIntOrNull()
        if (n == null) {
            warnings += "bedrooms: could not read '${abbreviate(value)}', dropped"
            return null
        }
        if (n > BEDROOMS_MAX) {
            warnings += "bedrooms: $n is out of range, dropped"
            return null
        }
        return n
    }

    /** Only phone characters; 7-15 digits; the last 10 digits must appear in the pasted text. */
    fun phone(value: String?, source: String, warnings: MutableList<String>): String? {
        if (value.isNullOrBlank()) return null
        val kept = value.filter { it in '0'..'9' || it in "+()- " }.trim()
        val digits = kept.filter { it in '0'..'9' }
        if (digits.length !in 7..15) {
            warnings += "contactPhone: not a valid phone number, dropped"
            return null
        }
        val sourceDigits = source.filter { it in '0'..'9' }
        if (digits.takeLast(10) !in sourceDigits) {
            warnings += "contactPhone: not found in the listing text, dropped"
            return null
        }
        return kept.take(PHONE_MAX)
    }

    /** Only absolute http(s) links that appear, exactly, in the pasted text. */
    fun url(value: String?, source: String, warnings: MutableList<String>): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (!HTTP_URL.matches(v)) {
            warnings += "listingUrl: only http(s) links are allowed, dropped"
            return null
        }
        if (v !in source) {
            warnings += "listingUrl: not found in the listing text, dropped"
            return null
        }
        if (v.length > URL_MAX) {
            warnings += "listingUrl: too long, dropped"
            return null
        }
        return v
    }

    fun amenities(values: List<String>?, warnings: MutableList<String>): List<String> {
        if (values == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (v in values) {
            clean(v, AMENITY_MAX, "amenities", warnings)?.let { out += it.lowercase() }
            if (out.size == AMENITIES_MAX) {
                if (values.size > AMENITIES_MAX) warnings += "amenities: kept the first $AMENITIES_MAX"
                break
            }
        }
        return out.toList()
    }

    fun defaultLabel(bedrooms: Int?, locality: String?, street: String?): String {
        val where = locality ?: street
        val what = when (bedrooms) {
            null -> "House"
            0 -> "Studio"
            else -> "${bedrooms}BHK"
        }
        return (if (where == null) what else "$what in $where").take(LABEL_MAX)
    }

    private fun abbreviate(s: String): String {
        val t = s.replace(WS, " ").trim()
        return if (t.length <= 40) t else t.take(40) + "…"
    }
}
