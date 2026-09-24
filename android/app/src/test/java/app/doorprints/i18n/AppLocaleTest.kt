package app.doorprints.i18n

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.doorprints.ui.getString
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.nav_map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [AppLocale.applyDefault] puts the process's default locale on the language Android resolved for the resources, so
 * the Compose strings (which read the default) and the Android strings agree (docs/06 TC-U-60).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class AppLocaleTest {
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
        assertEquals("नक्शा", phone.getString(Res.string.nav_map))
    }

    @Test
    fun noSupportedLanguageMeansEnglishOnBothSides() {
        val phone = phoneSetTo("fr-FR")

        AppLocale.applyDefault(phone)

        assertEquals("en", Locale.getDefault().language)
        assertEquals("Map", phone.getString(Res.string.nav_map))
    }
}
