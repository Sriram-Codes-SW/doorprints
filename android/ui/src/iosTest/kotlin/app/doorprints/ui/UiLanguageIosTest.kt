package app.doorprints.ui

import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S4b-BL-40: iOS's app language is always one the app ships strings for (en, hi, ta, te), English otherwise. */
class UiLanguageIosTest {
    @Test
    fun aShippedLanguageIsKeptWhateverItsRegionOrScript() {
        assertEquals("hi", shippedLanguageOf("hi-IN"))
        assertEquals("hi", shippedLanguageOf("hi"))
        assertEquals("ta", shippedLanguageOf("ta-LK"))
        assertEquals("te", shippedLanguageOf("te_IN"))
        assertEquals("en", shippedLanguageOf("en-GB"))
        assertEquals("hi", shippedLanguageOf("HI-in"))
        assertEquals("hi", shippedLanguageOf("hi-Latn-IN"))
    }

    @Test
    fun anyOtherLanguageFallsBackToEnglish() {
        listOf("mr-IN", "kn-IN", "bn", "ur-IN", "zh-Hans-CN", "fr-FR", "tam", "", null)
            .forEach { assertEquals("en", shippedLanguageOf(it), "$it") }
    }

    @Test
    fun theDevicesLanguageResolvesToAShippedOne() {
        assertTrue(appLanguage() in listOf("en", "hi", "ta", "te"), appLanguage())
        // And it is the device's first preferred language, as Compose resources pick the strings.
        val first = NSLocale.preferredLanguages.firstOrNull() as String?
        assertEquals(shippedLanguageOf(first), appLanguage(), "$first")
    }
}
