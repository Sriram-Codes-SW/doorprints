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

package app.doorprints.shared.listing

import app.doorprints.shared.ai.DraftSanitizer
import app.doorprints.shared.ai.RawListing
import app.doorprints.shared.api.HouseDraftDto

/**
 * The no-AI listing parser (docs/11 5.29, S4b-FR-4; 5.9): what a portal's share sheet hands over (its title, price,
 * BHK, locality, the link) read into a [HouseDraftDto] on the device, with no network and no model. The page is never
 * fetched. The result goes through [DraftSanitizer] like the model's, so the same caps and the same rules apply (a
 * phone number or a link only when it is in the text); the form's merge and its summary then apply unchanged. The
 * TypeScript port is `web/src/app/shared/listing-text.ts`; `docs/schemas/listing-fixtures.json` is what both must
 * read the same way.
 */
object ListingText {
    /** A share sheet's text is capped here (SEC-043); more is never a listing. */
    const val MAX_CHARS = 20_000

    /** The portals named in the source label, by host (a subdomain counts). */
    val PORTALS: Map<String, String> = mapOf(
        "magicbricks.com" to "MagicBricks",
        "99acres.com" to "99acres",
        "housing.com" to "Housing.com",
        "nobroker.in" to "NoBroker",
        "squareyards.com" to "Square Yards",
        "nestaway.com" to "NestAway",
    )

    private val URL = Regex("""https?://[^\s<>"']+""")
    private val TRACKING = Regex("""^(utm_.*|fbclid|gclid|igshid|ref|src)$""")
    /** An Indian mobile number, with or without +91, spaces or dashes. */
    private val PHONE = Regex("""(?:\+91[\s-]?)?(?:0)?[6-9]\d{4}[\s-]?\d{5}\b""")
    private val BHK = Regex("""\b(\d{1,2})\s*-?\s*(?:BHK|bhk|Bhk|bedroom|bedrooms|BR)\b""")
    private val STUDIO = Regex("""\b(studio|1\s*RK)\b""", RegexOption.IGNORE_CASE)
    /** A price with the rupee sign or Rs, or a bare lakh/crore amount. */
    private val PRICE = Regex(
        """(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)\s*(k|thousand|lac|lakh|lakhs|lacs|l|cr|crore|crores)?\b|\b(\d+(?:\.\d+)?)\s*(k|thousand|lac|lakh|lakhs|lacs|cr|crore|crores)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RENT = Regex("""\b(for rent|rent|rental|per month|/\s*month|monthly|lease|pm)\b""", RegexOption.IGNORE_CASE)
    private val SALE = Regex("""\b(for sale|sale|resale|buy|selling)\b""", RegexOption.IGNORE_CASE)
    /** "in Indiranagar, Bengaluru", "at HSR Layout Sector 2, Bangalore": the place before the city. */
    private val LOCALITY = Regex(
        """\b(?:in|at|near)\s+([A-Z][\w.'-]*(?:\s+(?:[A-Z0-9][\w.'-]*|of|the)){0,4}?),?\s+(?:Bengaluru|Bangalore|Chennai|Hyderabad|Mumbai|Navi Mumbai|Thane|Pune|Delhi|New Delhi|Gurgaon|Gurugram|Noida|Kolkata|Kochi|Coimbatore|Mysuru|Mysore|Ahmedabad|Jaipur|Lucknow|Chandigarh|Indore|Bhopal|Nagpur|Surat|Vadodara|Visakhapatnam|Vijayawada|Thiruvananthapuram|Trivandrum|Madurai|Mangaluru|Mangalore)\b""",
    )
    private val AREA = Regex("""(\d{3,5})\s*(?:sq\.?\s*ft|sqft|sq\.?\s*feet|square\s*feet|sq\.?\s*m)\b""", RegexOption.IGNORE_CASE)
    private val FURNISHING = Regex("""\b(fully[- ]furnished|semi[- ]furnished|unfurnished|furnished)\b""", RegexOption.IGNORE_CASE)

    /** Ten lakh and above with no rent words is a sale (a monthly rent of ten lakh is not a house hunt). */
    private const val SALE_FROM_RUPEES = 1_000_000L

    /** The draft from [text]: the fields the text says, the whole text kept in the notes so nothing shared is lost. */
    fun parse(text: String): HouseDraftDto {
        val t = text.take(MAX_CHARS)
        val url = urlIn(t)
        // The words are read with the links taken out: a portal's address says "rent" or "buy" for its own reasons.
        val words = t.replace(URL, " ")
        val priceMatch = PRICE.find(words)
        val priceText = priceMatch?.let { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + " " + m.groupValues[2] else m.groupValues[3] + " " + m.groupValues[4]
        }?.trim()
        val rupees = priceText?.let { DraftSanitizer.price(it, mutableListOf()) }
        val priceType = when {
            RENT.containsMatchIn(words) -> "RENT"
            SALE.containsMatchIn(words) -> "SALE"
            rupees != null && rupees >= SALE_FROM_RUPEES -> "SALE"
            else -> null
        }
        val bedrooms = BHK.find(words)?.groupValues?.get(1) ?: STUDIO.find(words)?.let { "studio" }
        val details = listOfNotNull(
            AREA.find(words)?.let { "${it.groupValues[1]} sq ft" },
            FURNISHING.find(words)?.value?.lowercase()?.replaceFirstChar { it.uppercase() },
        )
        val notes = (if (details.isEmpty()) t else details.joinToString(", ") + "\n\n" + t).trim()
        val raw = RawListing(
            label = label(t, url),
            locality = LOCALITY.find(words)?.groupValues?.get(1),
            price = priceText,
            priceType = priceType,
            bedrooms = bedrooms,
            contactPhone = PHONE.find(words)?.value,
            listingUrl = url,
            notes = notes,
        )
        val draft = DraftSanitizer.sanitize(raw, t)
        return draft.copy(
            listingUrl = draft.listingUrl?.let(::cleanUrl),
            // The sanitiser's own remarks about the model do not apply to a parser: a made-up label is the design.
            warnings = draft.warnings.filterNot { it.startsWith("label: generated") },
        )
    }

    /** The first link in [text], as written (trailing punctuation dropped), or null. */
    fun urlIn(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')

    /** [url] without its tracking parameters (`utm_*`, `fbclid`, `gclid`, …) and without a bare `?`, for comparing too. */
    fun cleanUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val fragment = url.substringAfter('#', "")
        val query = url.substring(q + 1).substringBefore('#')
        val kept = query.split('&').filter { it.isNotEmpty() && !TRACKING.matches(it.substringBefore('=').lowercase()) }
        val base = url.substring(0, q)
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + (if (fragment.isEmpty()) "" else "#$fragment")
    }

    /** The portal's name for [url] ("MagicBricks"), or null when the host is not one of [PORTALS]. */
    fun portal(url: String?): String? {
        val host = url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore(':')?.lowercase() ?: return null
        return PORTALS.entries.firstOrNull { (site, _) -> host == site || host.endsWith(".$site") }?.value
    }

    /** The first line that is not the link: what the portal calls the listing ("2 BHK Flat for Rent in Indiranagar"). */
    private fun label(text: String, url: String?): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && (url == null || !it.contains(url)) && !URL.matches(it) }
        ?.take(LABEL_MAX)?.trim()

    private const val LABEL_MAX = 120
}
