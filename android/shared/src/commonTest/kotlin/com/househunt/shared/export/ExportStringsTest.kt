package com.househunt.shared.export

import com.househunt.shared.model.Checklist
import com.househunt.shared.model.HouseStatus
import com.househunt.shared.model.VisitSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The text inside an exported copy exists in all four languages (docs/05 section 8.2: every string must exist in
 * en, hi, ta and te). Android lint enforces that for `strings.xml`; nothing enforces it for a Kotlin map, so this
 * test does.
 */
class ExportStringsTest {

    @Test
    fun everyLanguageHasExactlyTheSameKeys() {
        val english = ExportStrings.EN.keys
        for (strings in ExportStrings.ALL) {
            val missing = english - strings.keys
            val spare = strings.keys - english
            assertTrue(missing.isEmpty(), "${strings.language} is missing $missing")
            assertTrue(spare.isEmpty(), "${strings.language} has keys English does not: $spare")
        }
    }

    @Test
    fun noValueIsLeftBlankOrUntranslated() {
        for (strings in ExportStrings.ALL) {
            for (key in ExportStrings.EN.keys) {
                assertTrue(strings[key].isNotBlank(), "${strings.language} has an empty '$key'")
            }
        }
    }

    @Test
    fun everySharedEnumAndChecklistKeyHasALabel() {
        for (strings in ExportStrings.ALL) {
            for (key in Checklist.keys) {
                assertTrue(strings.check(key) != key, "${strings.language} has no label for checklist key '$key'")
            }
            for (status in HouseStatus.entries) {
                assertTrue(strings.status(status.name) != status.name, "${strings.language}: ${status.name}")
            }
            for (source in VisitSource.entries) {
                assertTrue(strings.source(source.name) != source.name, "${strings.language}: ${source.name}")
            }
        }
    }

    @Test
    fun unknownLanguagesFallBackToEnglish() {
        assertEquals("en", ExportStrings.of("fr").language)
        assertEquals("en", ExportStrings.of("").language)
        assertEquals("hi", ExportStrings.of("hi").language)
        assertEquals("ta", ExportStrings.of("ta").language)
        assertEquals("te", ExportStrings.of("te").language)
    }

    @Test
    fun theIndianLanguagesReallyUseTheirOwnScript() {
        // A copy-paste of the English table would pass every other test in this class.
        assertTrue(ExportStrings.HI["cover.houses"].any { it in 'ऀ'..'ॿ' }, "Devanagari expected")
        assertTrue(ExportStrings.TA["cover.houses"].any { it in '஀'..'௿' }, "Tamil expected")
        assertTrue(ExportStrings.TE["cover.houses"].any { it in 'ఀ'..'౿' }, "Telugu expected")
    }

    @Test
    fun anExportInTamilIsInTamil() {
        val bundle = ExportFixture.bundle(ExportFixture.options(language = "ta"))
        val markdown = MarkdownWriter.write(bundle)
        assertTrue(markdown.contains(ExportStrings.TA["section.ranking"]))
        assertTrue(markdown.contains(ExportStrings.TA["check.water"]))
        val html = HtmlWriter.write(bundle)
        assertTrue(html.contains("<html lang=\"ta\">"))
        // The data itself is never translated: the user's own words stay as typed.
        assertTrue(html.contains("Sunrise Apartments"))
        // The cover names the language natively, not by its code.
        assertTrue(html.contains("<dd>தமிழ்</dd>"))
        // Photo alt text is the house plus its position, in the copy's language.
        val withPhotos = HtmlWriter.write(bundle, ExportFixture.fakePhotoSrc)
        assertTrue(withPhotos.contains("alt=\"Sunrise Apartments, ${ExportStrings.TA["section.photos"]} 1/1\""))
    }

    @Test
    fun everyLanguageHasANativeNameAndUnknownCodesFallBackToTheCode() {
        assertEquals(listOf("en", "hi", "ta", "te"), ExportLanguages.NATIVE_NAMES.keys.toList())
        assertEquals("हिन्दी", ExportLanguages.nativeName("hi"))
        assertEquals("தமிழ்", ExportLanguages.nativeName("ta"))
        assertEquals("తెలుగు", ExportLanguages.nativeName("te"))
        assertEquals("fr", ExportLanguages.nativeName("fr"))
    }
}
