package app.doorprints.i18n

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.doorprints.ui.Formats
import app.doorprints.ui.appLanguage
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.nav_map
import app.doorprints.ui.uiLanguage
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [AppLocale.applyDefault] puts the process's default locale on the language Android resolved for the resources, so
 * the Compose strings (which read the default) and the Android strings agree (docs/06 TC-U-60); and the dates and the
 * theme's language rules follow that language too, not the phone's first one (S4b-BL-18, docs/06 TC-U-61).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class AppLocaleTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val savedDefault = LocaleList.getDefault()

    @After
    fun restore() {
        LocaleList.setDefault(savedDefault)
    }

    private fun phoneSetTo(vararg tags: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
        },
    )

    @Test
    fun anUnsupportedFirstLanguageFallsThroughToTheNextSupportedOne() {
        val phone = phoneSetTo("mr-IN", "hi-IN")

        AppLocale.applyDefault(phone)

        assertEquals("hi", Locale.getDefault().language)
        assertEquals("नक्शा", uiString(Res.string.nav_map))
    }

    @Test
    fun noSupportedLanguageMeansEnglishOnBothSides() {
        val phone = phoneSetTo("fr-FR")

        AppLocale.applyDefault(phone)

        assertEquals("en", Locale.getDefault().language)
        assertEquals("Map", uiString(Res.string.nav_map))
    }

    @Test
    fun datesFollowTheResolvedLanguageNotThePhonesFirstOne() {
        AppLocale.applyDefault(phoneSetTo("mr-IN", "hi-IN"))

        assertEquals("hi", appLanguage())
        val millis = 1_760_000_000_000
        assertEquals(Formats.date(millis, "hi"), Formats.date(millis))
        assertEquals(Formats.dateTime(millis, "hi"), Formats.dateTime(millis))
        // Before S4b-BL-18 the dates were written for the configuration's first locale, Marathi.
        assertNotEquals(Formats.date(millis, "mr"), Formats.date(millis))
    }

    @Test
    fun theUiLanguageIsTheResolvedOneNotTheConfigurationsFirst() {
        // The activity's configuration starts with Marathi, which the app does not ship.
        RuntimeEnvironment.setQualifiers("+mr")
        AppLocale.applyDefault(phoneSetTo("mr-IN", "hi-IN"))
        var language = ""

        compose.setContent { language = uiLanguage() }
        compose.waitForIdle()

        assertEquals("hi", language)
    }

    /** A Compose string outside composition, as the app reads one in a coroutine. */
    private fun uiString(resource: StringResource) = runBlocking { getString(resource) }
}
