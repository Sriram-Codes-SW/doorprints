package com.househunt.shared.export

/**
 * The fixture every export golden is built from (S4-00: "shared fixture files"). Small on purpose, but it carries
 * one of each awkward case, because those are what break exporters:
 *
 *  * a fully filled house and an almost empty one;
 *  * a label with a `|` and double quotes (Markdown tables, CSV quoting, XML escaping);
 *  * a note with a newline **and** a line that starts with `=` (CSV formula injection, HTML/Markdown escaping);
 *  * a phone number starting with `+` (also a formula to a spreadsheet);
 *  * a house with no score at all, so it ranks last and shows "—" everywhere;
 *  * a checklist that is not in `Checklist.keys` order, to pin the display order;
 *  * a non-zero UTC offset (+05:30), so a bug that prints UTC is visible.
 *
 * The same fixture is used by the web team's suite; keep the two in step.
 */
object ExportFixture {

    const val EXPORTED_AT = 1_790_072_130_000L // 2026-09-22T10:15:30Z = 2026-09-22 15:45 IST
    const val IST_OFFSET_MINUTES = 330

    val house1 = ExportHouse(
        id = "h1",
        label = "Sunrise Apartments",
        address = "12, 5th Cross",
        street = "5th Cross",
        locality = "Indiranagar",
        lat = 12.978321,
        lon = 77.640812,
        status = "SHORTLISTED",
        price = 32_000,
        priceType = "RENT",
        bedrooms = 2,
        rating = 4,
        contactName = "Owner, R. Rao",
        contactPhone = "+91 98450 00000",
        listingUrl = "https://example.com/l/1",
        notes = "Water 24x7.\n=SUM(A1) was in the ad",
        checklist = mapOf("water" to 5, "noise" to 2, "parking" to 4),
        createdAt = 1_790_000_000_000,
        updatedAt = 1_790_072_130_120,
    )

    val house2 = ExportHouse(
        id = "h2",
        label = "Green View | Block \"B\"",
        lat = 12.9,
        lon = 77.6,
        status = "REJECTED",
        createdAt = 1_790_000_100_000,
        updatedAt = 1_790_000_100_000,
    )

    val visit1 = ExportVisit(
        id = "v1",
        houseId = "h1",
        lat = 12.978,
        lon = 77.64,
        street = "5th Cross",
        arrivedAt = 1_790_003_000_000,
        leftAt = 1_790_003_600_000,
        source = "MANUAL",
        updatedAt = 1_790_003_600_000,
    )

    val photo1 = ExportPhoto(id = "p1", houseId = "h1", fileName = "p1.jpg", createdAt = 1_790_004_000_000)

    val houses = listOf(house1, house2)
    val visits = listOf(visit1)
    val photos = listOf(photo1)

    fun options(
        language: String = "en",
        scope: ExportScope = ExportScope.ALL,
        includeContacts: Boolean = true,
        includeRejected: Boolean = true,
        photoScope: PhotoScope = PhotoScope.ALL,
    ) = ExportOptions(
        scope = scope,
        includeRejected = includeRejected,
        photos = photoScope,
        includeContacts = includeContacts,
        language = language,
        utcOffsetMinutes = IST_OFFSET_MINUTES,
        exportedAtMillis = EXPORTED_AT,
    )

    /** The parameter is not called `options` so its default can call the [options] function above. */
    fun bundle(chosen: ExportOptions = options()): ExportBundle =
        ExportBundle.build(chosen, houses, visits, photos)

    /** Stand-in for a real JPEG data URI, so the golden does not carry an image. */
    val fakePhotoSrc: (ExportPhoto) -> String? = { "data:image/jpeg;base64,AAAA" }
}
