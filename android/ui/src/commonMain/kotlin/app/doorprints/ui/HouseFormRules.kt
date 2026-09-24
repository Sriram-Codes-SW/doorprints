package app.doorprints.ui

import app.doorprints.data.HouseEntity
import app.doorprints.location.Place
import app.doorprints.shared.api.HouseDraftDto

/*
 * The house form's and the lists' decisions that need no Android, written as pure functions so they are unit tested
 * (HouseFormRulesTest in commonTest; UX review, whole-app audit). Common code since CMP-4 P4c (was `:app`'s), public
 * because `:app`'s house form, list and map use them until they move here.
 */

/**
 * Fills a new house's name, street, locality and address from a reverse-geocoded [place] that arrived after the form
 * was shown (the form no longer waits for the lookup). A field is filled only where both [draft] and [baseline] still
 * hold the value the form started with (the default name, or nothing), and the same value goes into both, so the fill
 * never counts as an unsaved change and never overwrites what the user typed meanwhile. [streetLabel] turns a street
 * into the default name ("House on MG Road"). Returns the new draft and baseline.
 */
fun fillPlace(
    draft: HouseEntity,
    baseline: HouseEntity,
    place: Place,
    defaultLabel: String,
    streetLabel: (String) -> String,
): Pair<HouseEntity, HouseEntity> {
    var d = draft
    var b = baseline
    val street = place.street?.takeIf { it.isNotBlank() }
    if (street != null && d.label == defaultLabel && b.label == defaultLabel) {
        val label = streetLabel(street)
        d = d.copy(label = label)
        b = b.copy(label = label)
    }
    if (street != null && d.street.isNullOrBlank() && b.street.isNullOrBlank()) {
        d = d.copy(street = street)
        b = b.copy(street = street)
    }
    val locality = place.locality?.takeIf { it.isNotBlank() }
    if (locality != null && d.locality.isNullOrBlank() && b.locality.isNullOrBlank()) {
        d = d.copy(locality = locality)
        b = b.copy(locality = locality)
    }
    val address = place.address?.takeIf { it.isNotBlank() }
    if (address != null && d.address.isNullOrBlank() && b.address.isNullOrBlank()) {
        d = d.copy(address = address)
        b = b.copy(address = address)
    }
    return d to b
}

/** A form field that "Fill in from listing text" can set, named in its result line. */
enum class ListingField { NAME, ADDRESS, STREET, LOCALITY, PRICE, BHK, CONTACT, PHONE, LISTING, NOTES }

/** What a listing fill did: the new draft, the fields it filled, and those it left because the user had typed them. */
data class ListingMerge(
    val house: HouseEntity,
    val filled: List<ListingField>,
    val kept: List<ListingField>,
)

/**
 * Copies the AI suggestion [a] into the form [h], **only into empty fields** (UX review, whole-app audit): a
 * successful fill used to overwrite what the user had already typed. A field the listing has but the user already
 * filled with something else is left as it is and reported in [ListingMerge.kept]; one it fills is in
 * [ListingMerge.filled]. The name counts as empty while it is still the form's own default ([labelIsPlaceholder]).
 * The rent/buy choice goes with the price. Amenities join the listing's notes. Location, status, rating and checklist
 * are never touched, and nothing is saved until *Save*.
 */
fun mergeListing(h: HouseEntity, a: HouseDraftDto, labelIsPlaceholder: Boolean): ListingMerge {
    val filled = mutableListOf<ListingField>()
    val kept = mutableListOf<ListingField>()
    fun <T> pick(field: ListingField, current: T?, suggested: T?, isEmpty: (T?) -> Boolean): T? {
        if (suggested == null || isEmpty(suggested)) return current
        return when {
            isEmpty(current) -> {
                filled += field
                suggested
            }
            current != suggested -> {
                kept += field
                current
            }
            else -> current
        }
    }
    val blank: (String?) -> Boolean = { it.isNullOrBlank() }
    val listingNotes = listOfNotNull(
        a.notes?.takeIf { it.isNotBlank() },
        a.amenities.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString("\n").ifBlank { null }

    val label = pick(ListingField.NAME, h.label.takeIf { !labelIsPlaceholder }, a.label, blank) ?: h.label
    val price = pick(ListingField.PRICE, h.price, a.price) { it == null }
    val priceType = if (h.price == null && a.price != null && a.priceType != null) a.priceType else h.priceType
    return ListingMerge(
        house = h.copy(
            label = label,
            address = pick(ListingField.ADDRESS, h.address, a.address, blank),
            street = pick(ListingField.STREET, h.street, a.street, blank),
            locality = pick(ListingField.LOCALITY, h.locality, a.locality, blank),
            price = price,
            priceType = priceType,
            bedrooms = pick(ListingField.BHK, h.bedrooms, a.bedrooms) { it == null },
            contactName = pick(ListingField.CONTACT, h.contactName, a.contactName, blank),
            contactPhone = pick(ListingField.PHONE, h.contactPhone, a.contactPhone, blank),
            listingUrl = pick(ListingField.LISTING, h.listingUrl, a.listingUrl, blank),
            notes = pick(ListingField.NOTES, h.notes, listingNotes, blank),
        ),
        filled = filled,
        kept = kept,
    )
}

/**
 * A typed latitude or longitude: the number when it parses and lies within ±[limit] (90 or 180), otherwise null.
 * A comma is taken as the decimal point, as some keyboards type it.
 */
fun parseCoordinate(text: String, limit: Double): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return value.takeIf { !it.isNaN() && it in -limit..limit }
}

/**
 * "Lowest price" (UX review, whole-app audit): monthly rents first and then sale prices, each from low to high, so
 * ₹25,000 / month is never sorted next to ₹1,00,00,000. Houses without a price go last within their kind.
 */
fun sortByPrice(houses: List<HouseEntity>): List<HouseEntity> =
    houses.sortedWith(compareBy<HouseEntity>({ it.priceType == "SALE" }, { it.price ?: Long.MAX_VALUE }))

/** A visit recorded less than this long ago makes "I am here now" say so instead of adding another. */
const val RECENT_VISIT_MS = 10 * 60_000L

/** True when the newest visit ([lastArrivedAt]) is too recent for "I am here now" to add another. */
fun visitIsRecent(lastArrivedAt: Long?, now: Long): Boolean =
    lastArrivedAt != null && now - lastArrivedAt in 0 until RECENT_VISIT_MS

/** How old a last-known fix may be for *Save house here* to use it when no fresh fix comes. */
const val LAST_FIX_MAX_AGE_MS = 2 * 60_000L

/**
 * Whether the phone's last known location may stand in for a fresh fix (UX review, whole-app audit): only when it is
 * under two minutes old and at least as accurate as Hunt mode's own threshold ([maxAccuracyM]). A fix of any age
 * saved houses in the wrong place for good.
 */
fun lastFixUsable(ageMs: Long, accuracyM: Float?, maxAccuracyM: Float): Boolean =
    ageMs in 0..LAST_FIX_MAX_AGE_MS && accuracyM != null && accuracyM <= maxAccuracyM

/**
 * The house form's field pairs (price and BHK, street and locality, latitude and longitude) stack below this much room
 * inside the form's gutters (UX review, whole-app audit, round 2). A 360 dp phone has 328 dp there and keeps them side
 * by side (160 dp per field, or 220 dp for the price beside the 100 dp BHK); README section 8, device check 21 (o)
 * checks the Tamil and Telugu labels there at 100 % and 115 % font (round 3), and the Design Director confirmed the
 * threshold (README 1.26).
 */
const val PAIR_STACK_BELOW_DP = 300f

/** From this font scale the pairs stack whatever the width ("வாடகை ₹/மாதம்" and "தீர்க்கரேகை" would be cut off). */
const val PAIR_STACK_FONT_SCALE = 1.3f

/** True when a field pair must stack: [widthDp] is the room the pair has, [fontScale] the system font scale. */
fun stackFieldPair(widthDp: Float, fontScale: Float): Boolean =
    widthDp < PAIR_STACK_BELOW_DP || fontScale >= PAIR_STACK_FONT_SCALE

/**
 * True for an http(s) link with a host, the only kind the house form's *Open* hands to a browser. The rule of
 * Android's `Uri.parse(text)` that the form used before CMP-6 (`LinkParityTest` compares the two): the scheme is
 * everything before the first `:`, compared without case; the host is the authority after `//` (up to the first `/`,
 * `\`, `?` or `#`), less any user info (up to the last `@`) and a trailing `:port`, percent-decoded, and must not be
 * blank.
 */
fun isWebLink(text: String): Boolean {
    val schemeEnd = text.indexOf(':')
    if (schemeEnd < 0) return false
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = linkHost(text, schemeEnd) ?: return false
    // A malformed escape decodes to U+FFFD, which is not blank.
    val decoded = percentDecoded(host) ?: return true
    return decoded.isNotBlank()
}

/** The encoded host of [text] whose scheme ends at [schemeEnd], or null when there is no `//` authority. */
private fun linkHost(text: String, schemeEnd: Int): String? {
    if (text.length <= schemeEnd + 2 || text[schemeEnd + 1] != '/' || text[schemeEnd + 2] != '/') return null
    var end = schemeEnd + 3
    while (end < text.length && text[end] != '/' && text[end] != '\\' && text[end] != '?' && text[end] != '#') end++
    val authority = text.substring(schemeEnd + 3, end)
    val userInfoEnd = authority.lastIndexOf('@')
    // The port: digits after the last ':', read from the end; any other character first means there is none.
    var portStart = -1
    for (i in authority.indices.reversed()) {
        val c = authority[i]
        if (c == ':') {
            portStart = i
            break
        }
        if (c !in '0'..'9') break
    }
    return if (portStart < 0) authority.substring(userInfoEnd + 1) else authority.substring(userInfoEnd + 1, portStart)
}

/** [s] with its `%XX` escapes decoded as UTF-8, or null when an escape is malformed. */
private fun percentDecoded(s: String): String? {
    val out = StringBuilder()
    val bytes = ArrayList<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
        bytes.clear()
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 > s.lastIndex) return null
            val high = hexValue(s[i + 1]) ?: return null
            val low = hexValue(s[i + 2]) ?: return null
            bytes += (high * 16 + low).toByte()
            i += 3
        } else {
            flush()
            out.append(c)
            i++
        }
    }
    flush()
    return out.toString()
}

/** An ASCII hex digit's value, or null (as Android's `UriCodec`: no other script's digits). */
private fun hexValue(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}
