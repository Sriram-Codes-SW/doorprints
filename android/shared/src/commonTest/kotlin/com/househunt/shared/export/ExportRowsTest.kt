package com.househunt.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The format-independent rules: filtering, ordering, redaction and the hand-written number formatting. */
class ExportRowsTest {

    @Test
    fun housesAreOrderedByCreatedAtThenId() {
        val shuffled = ExportBundle.build(
            ExportFixture.options(),
            listOf(ExportFixture.house2, ExportFixture.house1),
            ExportFixture.visits,
            ExportFixture.photos,
        )
        assertEquals(listOf("h1", "h2"), shuffled.houses.map { it.id })
    }

    @Test
    fun sameCreatedAtFallsBackToTheId() {
        val a = ExportFixture.house1.copy(id = "b", createdAt = 1)
        val b = ExportFixture.house1.copy(id = "a", createdAt = 1)
        val bundle = ExportBundle.build(ExportFixture.options(), listOf(a, b), emptyList(), emptyList())
        assertEquals(listOf("a", "b"), bundle.houses.map { it.id })
    }

    @Test
    fun rankingPutsTheBestScoreFirstAndUnscoredLast() {
        val bundle = ExportFixture.bundle()
        assertEquals(listOf("h1", "h2"), bundle.ranked.map { it.id })
        assertEquals(1, bundle.rankOf(ExportFixture.house1))
        assertEquals(2, bundle.rankOf(ExportFixture.house2))
        assertNull(ExportFixture.house2.score)
    }

    @Test
    fun shortlistedScopeDropsEverythingElse() {
        val bundle = ExportFixture.bundle(ExportFixture.options(scope = ExportScope.SHORTLISTED))
        assertEquals(listOf("h1"), bundle.houses.map { it.id })
    }

    @Test
    fun selectedScopeKeepsOnlyTheTickedHouses() {
        val options = ExportFixture.options(scope = ExportScope.SELECTED).copy(selectedIds = setOf("h2"))
        assertEquals(listOf("h2"), ExportFixture.bundle(options).houses.map { it.id })
    }

    @Test
    fun rejectedHousesCanBeLeftOut() {
        val bundle = ExportFixture.bundle(ExportFixture.options(includeRejected = false))
        assertEquals(listOf("h1"), bundle.houses.map { it.id })
    }

    @Test
    fun leavingContactsOutRemovesThemFromTheDataAndTheColumns() {
        val bundle = ExportFixture.bundle(ExportFixture.options(includeContacts = false))
        val house = bundle.houses.first { it.id == "h1" }
        assertNull(house.contactName)
        assertNull(house.contactPhone)
        val csv = CsvWriter.write(ExportRows.houses(bundle), bundle.options)
        assertFalse(csv.contains("98450"), "the phone number must not survive anywhere in the copy")
        assertFalse(csv.contains("Contact name"), "the column goes too, not just its value")
    }

    @Test
    fun photoScopeNoneDropsPhotosButKeepsHouses() {
        val bundle = ExportFixture.bundle(ExportFixture.options(photoScope = PhotoScope.NONE))
        assertEquals(2, bundle.houses.size)
        assertTrue(bundle.photos.isEmpty())
    }

    @Test
    fun photoScopeShortlistedKeepsOnlyShortlistedHousesPhotos() {
        val extra = ExportPhoto("p2", "h2", "p2.jpg", 1_790_004_100_000)
        val bundle = ExportBundle.build(
            ExportFixture.options(photoScope = PhotoScope.SHORTLISTED),
            ExportFixture.houses, ExportFixture.visits, ExportFixture.photos + extra,
        )
        assertEquals(listOf("p1"), bundle.photos.map { it.id })
    }

    @Test
    fun visitsOfHousesThatAreNotExportedAreDropped() {
        val orphan = ExportFixture.visit1.copy(id = "v2", houseId = "gone")
        val bundle = ExportBundle.build(
            ExportFixture.options(), ExportFixture.houses, listOf(ExportFixture.visit1, orphan), emptyList(),
        )
        assertEquals(listOf("v1"), bundle.visits.map { it.id })
    }

    @Test
    fun checklistOrderIsTheSharedDisplayOrderThenCustomKeysAlphabetically() {
        val house = ExportFixture.house1.copy(
            checklist = mapOf("c_ff02" to 3, "noise" to 2, "c_aa01" to 1, "water" to 5),
        )
        assertEquals(listOf("water", "noise", "c_aa01", "c_ff02"), ExportRows.orderedChecklistKeys(house))
    }

    @Test
    fun visitMinutesAreWholeMinutesAndNeverNegative() {
        assertEquals(10L, ExportFixture.visit1.minutes)
        assertNull(ExportFixture.visit1.copy(leftAt = null).minutes)
        assertEquals(0L, ExportFixture.visit1.copy(leftAt = ExportFixture.visit1.arrivedAt - 5_000).minutes)
    }

    @Test
    fun fixedFormatsWithoutAPlatformFormatter() {
        assertEquals("3.8", ExportRows.fixed(3.8333333, 1))
        assertEquals("4.0", ExportRows.fixed(3.95, 1))
        // Ties go away from zero, not to the even digit: kotlin.math.round would make this "3.8" on the JVM and
        // the web app's Math.round would not agree with it.
        assertEquals("3.9", ExportRows.fixed(3.85, 1))
        assertEquals("3", ExportRows.fixed(2.5, 0))
        assertEquals("12.978321", ExportRows.fixed(12.978321, 6))
        assertEquals("77.600000", ExportRows.fixed(77.6, 6))
        assertEquals("-0.500000", ExportRows.fixed(-0.5, 6))
        assertEquals("0", ExportRows.fixed(-0.0001, 0))
        assertEquals("5", ExportRows.fixed(5.0, 0))
    }

    @Test
    fun rupeesUseIndianGrouping() {
        assertEquals("₹0", ExportRows.rupees(0))
        assertEquals("₹999", ExportRows.rupees(999))
        assertEquals("₹32,000", ExportRows.rupees(32_000))
        assertEquals("₹1,00,000", ExportRows.rupees(100_000))
        assertEquals("₹12,50,000", ExportRows.rupees(1_250_000))
        assertEquals("₹1,23,45,678", ExportRows.rupees(12_345_678))
        assertEquals("-₹5,000", ExportRows.rupees(-5_000))
    }

    @Test
    fun timesUseTheExportsFixedOffsetNotUtc() {
        assertEquals("2026-09-22", ExportTime.date(ExportFixture.EXPORTED_AT, 330))
        assertEquals("2026-09-22 15:45", ExportTime.dateTime(ExportFixture.EXPORTED_AT, 330))
        assertEquals("2026-09-22 10:15", ExportTime.dateTime(ExportFixture.EXPORTED_AT, 0))
        assertEquals("+05:30", ExportTime.offsetLabel(330))
        assertEquals("+00:00", ExportTime.offsetLabel(0))
        assertEquals("-03:30", ExportTime.offsetLabel(-210))
    }

    @Test
    fun csvQuotingAndTheFormulaGuard() {
        assertEquals("plain", CsvWriter.field("plain"))
        assertEquals("\"a,b\"", CsvWriter.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.field("say \"hi\""))
        assertEquals("\" padded \"", CsvWriter.field(" padded "))
        assertEquals("'=SUM(A1)", CsvWriter.guard("=SUM(A1)"))
        assertEquals("'+91 98450", CsvWriter.guard("+91 98450"))
        assertEquals("'-1+1", CsvWriter.guard("-1+1"))
        assertEquals("'@here", CsvWriter.guard("@here"))
        assertEquals("safe", CsvWriter.guard("safe"))
    }

    @Test
    fun csvGuardCharacterSetMatchesTheWeb() {
        // Parity with web `csvCell` (/^[=+\-@\t\r]/): the same six triggers, and nothing else. The web suite
        // (deterministic.spec.ts) is to hold the mirror of these cases — requested of the Web team — so that a
        // change on either side fails one of them.
        assertEquals("'\tx", CsvWriter.guard("\tx"))
        assertEquals("'\rx", CsvWriter.guard("\rx"))
        // A leading line feed is not a formula trigger: not prefixed, only quoted.
        assertEquals("\nx", CsvWriter.guard("\nx"))
        assertEquals("\"\nx\"", CsvWriter.field(CsvWriter.guard("\nx")))
        assertEquals("\"'\rx\"", CsvWriter.field(CsvWriter.guard("\rx")))
    }

    @Test
    fun numbersKeepTheirMinusSignInCsv() {
        // The guard must not touch a numeric cell: -12.9 has to stay a number a spreadsheet can plot.
        val house = ExportFixture.house1.copy(lat = -12.978321)
        val bundle = ExportBundle.build(ExportFixture.options(), listOf(house), emptyList(), emptyList())
        val csv = CsvWriter.write(ExportRows.houses(bundle), bundle.options)
        assertTrue(csv.contains(",-12.978321,"), "expected a plain negative latitude in:\n$csv")
    }

    @Test
    fun markdownEscapesWhatWouldBecomeFormatting() {
        assertEquals("a \\* b", MarkdownWriter.text("a * b"))
        assertEquals("\\[link\\]", MarkdownWriter.text("[link]"))
        assertEquals("\\# not a heading", MarkdownWriter.text("# not a heading"))
        assertEquals("\\- not a list", MarkdownWriter.text("- not a list"))
        assertEquals("2026-09-22", MarkdownWriter.text("2026-09-22"), "a date must not be mangled")
    }

    @Test
    fun htmlEscapingCoversQuotesAndAngleBrackets() {
        assertEquals("&lt;b&gt;", HtmlWriter.esc("<b>"))
        assertEquals("a &amp; b", HtmlWriter.esc("a & b"))
        assertEquals("&quot;q&quot; &#39;s&#39;", HtmlWriter.esc("\"q\" 's'"))
    }

    @Test
    fun aScriptInANoteIsNeverExecutable() {
        val house = ExportFixture.house1.copy(notes = "<script>alert(1)</script>")
        val bundle = ExportBundle.build(ExportFixture.options(), listOf(house), emptyList(), emptyList())
        val html = HtmlWriter.write(bundle)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }
}
