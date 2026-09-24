package app.doorprints.shared.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hand-written SpreadsheetML: package layout, cell references, typed cells and XML safety. */
class XlsxWriterTest {

    private val bundle = ExportFixture.bundle()

    @Test
    fun thePackageHasEveryPartExcelNeeds() {
        val paths = XlsxWriter.parts(bundle).map { it.path }
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet4.xml",
            ),
            paths,
        )
    }

    @Test
    fun everySheetIsDeclaredContentTypedAndRelated() {
        val parts = XlsxWriter.parts(bundle).associate { it.path to it.xml }
        val types = parts.getValue("[Content_Types].xml")
        val rels = parts.getValue("xl/_rels/workbook.xml.rels")
        val workbook = parts.getValue("xl/workbook.xml")
        for (i in 1..4) {
            assertTrue(types.contains("/xl/worksheets/sheet$i.xml"), "sheet$i missing from [Content_Types].xml")
            assertTrue(rels.contains("Target=\"worksheets/sheet$i.xml\""), "sheet$i missing from the rels")
            assertTrue(workbook.contains("r:id=\"rId$i\""), "sheet$i missing from workbook.xml")
        }
        // The styles part must not take an rId a sheet is using.
        assertTrue(rels.contains("Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""))
        assertTrue(types.contains("/xl/styles.xml"))
    }

    @Test
    fun stylesDeclareTheTwoNumberFormatsAndTheCountsMatch() {
        val styles = XlsxWriter.parts(bundle).first { it.path == "xl/styles.xml" }.xml
        assertTrue(styles.contains("numFmtId=\"164\""))
        assertTrue(styles.contains("numFmtId=\"165\""))
        assertTrue(styles.contains("<cellXfs count=\"4\">"))
        assertEquals(4, Regex("<xf ").findAll(styles.substringAfter("<cellXfs")).count())
        // Excel requires fill 0 = none and fill 1 = gray125.
        assertTrue(styles.indexOf("patternType=\"none\"") < styles.indexOf("patternType=\"gray125\""))
    }

    @Test
    fun headerRowIsBoldAndFrozen() {
        val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }.xml
        assertTrue(sheet.contains("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"))
        assertTrue(sheet.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\">"))
    }

    @Test
    fun moneyAndTimesAreTypedCellsNotText() {
        val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }.xml
        // Price is column E, first data row is 2: a number with the ₹ style (2), not an inline string.
        assertTrue(sheet.contains("<c r=\"E2\" s=\"2\"><v>32000</v></c>"), sheet)
        // "Added" is column T: a serial date with the date style (3).
        assertTrue(sheet.contains("<c r=\"T2\" s=\"3\"><v>"), sheet)
        assertFalse(sheet.contains("<v>₹"), "the currency sign belongs in the number format, not the value")
    }

    @Test
    fun serialDatesUseExcelsEpochAndTheExportsOffset() {
        assertEquals("46287.65659722", XlsxWriter.serialDate(ExportFixture.EXPORTED_AT, 330))
        assertEquals("46287.42743056", XlsxWriter.serialDate(ExportFixture.EXPORTED_AT, 0))
    }

    @Test
    fun blankCellsAreOmittedEntirely() {
        val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }.xml
        // The second house has no price, so E3 must not exist at all (an empty <c> would still be a value).
        assertFalse(sheet.contains("r=\"E3\""), sheet)
    }

    @Test
    fun columnReferencesPassZ() {
        assertEquals("A1", XlsxWriter.ref(0, 1))
        assertEquals("Z1", XlsxWriter.ref(25, 1))
        assertEquals("AA2", XlsxWriter.ref(26, 2))
        assertEquals("AB2", XlsxWriter.ref(27, 2))
        assertEquals("BA9", XlsxWriter.ref(52, 9))
    }

    @Test
    fun sheetNamesAreSanitisedTruncatedAndUnique() {
        val tables = listOf(
            ExportTable("a", "Houses / flats [2026]", emptyList(), emptyList()),
            ExportTable("b", "Houses / flats [2026]", emptyList(), emptyList()),
            ExportTable("c", "A very long sheet title that Excel will simply not accept", emptyList(), emptyList()),
        )
        val names = XlsxWriter.sheetNames(tables)
        assertTrue(names.none { it.any { ch -> ch in "[]:*?/\\" } }, names.toString())
        assertTrue(names.all { it.length <= 31 }, names.toString())
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size, names.toString())
    }

    @Test
    fun xmlEscapesMarkupAndDropsCharactersXmlCannotHold() {
        assertEquals("a &amp; b", XlsxWriter.xml("a & b"))
        assertEquals("&lt;i&gt;", XlsxWriter.xml("<i>"))
        assertEquals("&quot;q&quot;", XlsxWriter.xml("\"q\""))
        assertEquals("ab", XlsxWriter.xml("a\u0000b"), "a NUL would make the whole workbook unreadable")
        assertEquals("a\tb", XlsxWriter.xml("a\tb"), "tab, LF and CR are legal XML")
    }

    @Test
    fun aQuoteInAHouseNameSurvivesIntoTheSheet() {
        val sheet = XlsxWriter.parts(bundle).first { it.path == "xl/worksheets/sheet1.xml" }.xml
        assertTrue(sheet.contains("Green View | Block &quot;B&quot;"), sheet)
    }
}
