package com.househunt.app.ui

import com.househunt.app.data.HouseEntity
import com.househunt.app.location.Place
import com.househunt.shared.api.HouseDraftDto

/*
 * The house form's and the lists' decisions that need no Android, written as pure functions so they are unit tested
 * (HouseFormRulesTest; UX review, whole-app audit).
 */

/**
 * Fills a new house's name, street, locality and address from a reverse-geocoded [place] that arrived after the form
 * was shown (the form no longer waits for the lookup). A field is filled only where both [draft] and [baseline] still
 * hold the value the form started with (the default name, or nothing), and the same value goes into both, so the fill
 * never counts as an unsaved change and never overwrites what the user typed meanwhile. [streetLabel] turns a street
 * into the default name ("House on MG Road"). Returns the new draft and baseline.
 */
internal fun fillPlace(
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
internal enum class ListingField { NAME, ADDRESS, STREET, LOCALITY, PRICE, BHK, CONTACT, PHONE, LISTING, NOTES }

/** What a listing fill did: the new draft, the fields it filled, and those it left because the user had typed them. */
internal data class ListingMerge(
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
internal fun mergeListing(h: HouseEntity, a: HouseDraftDto, labelIsPlaceholder: Boolean): ListingMerge {
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
internal fun parseCoordinate(text: String, limit: Double): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return value.takeIf { !it.isNaN() && it in -limit..limit }
}

/**
 * "Lowest price" (UX review, whole-app audit): monthly rents first and then sale prices, each from low to high, so
 * ₹25,000 / month is never sorted next to ₹1,00,00,000. Houses without a price go last within their kind.
 */
internal fun sortByPrice(houses: List<HouseEntity>): List<HouseEntity> =
    houses.sortedWith(compareBy<HouseEntity>({ it.priceType == "SALE" }, { it.price ?: Long.MAX_VALUE }))

/** A visit recorded less than this long ago makes "I am here now" say so instead of adding another. */
internal const val RECENT_VISIT_MS = 10 * 60_000L

/** True when the newest visit ([lastArrivedAt]) is too recent for "I am here now" to add another. */
internal fun visitIsRecent(lastArrivedAt: Long?, now: Long): Boolean =
    lastArrivedAt != null && now - lastArrivedAt in 0 until RECENT_VISIT_MS

/** How old a last-known fix may be for *Save house here* to use it when no fresh fix comes. */
internal const val LAST_FIX_MAX_AGE_MS = 2 * 60_000L

/**
 * Whether the phone's last known location may stand in for a fresh fix (UX review, whole-app audit): only when it is
 * under two minutes old and at least as accurate as Hunt mode's own threshold ([maxAccuracyM]). A fix of any age
 * saved houses in the wrong place for good.
 */
internal fun lastFixUsable(ageMs: Long, accuracyM: Float?, maxAccuracyM: Float): Boolean =
    ageMs in 0..LAST_FIX_MAX_AGE_MS && accuracyM != null && accuracyM <= maxAccuracyM

/**
 * The house form's field pairs (price and BHK, street and locality, latitude and longitude) stack below this much room
 * inside the form's gutters (UX review, whole-app audit, round 2). A 360 dp phone has 328 dp there and keeps them side
 * by side (160 dp per field, or 220 dp for the price beside the 100 dp BHK); README section 8, device check 21 (o)
 * checks the Tamil and Telugu labels there at 100 % and 115 % font (round 3), and the Design Director confirmed the
 * threshold (README 1.26).
 */
internal const val PAIR_STACK_BELOW_DP = 300f

/** From this font scale the pairs stack whatever the width ("வாடகை ₹/மாதம்" and "தீர்க்கரேகை" would be cut off). */
internal const val PAIR_STACK_FONT_SCALE = 1.3f

/** True when a field pair must stack: [widthDp] is the room the pair has, [fontScale] the system font scale. */
internal fun stackFieldPair(widthDp: Float, fontScale: Float): Boolean =
    widthDp < PAIR_STACK_BELOW_DP || fontScale >= PAIR_STACK_FONT_SCALE
