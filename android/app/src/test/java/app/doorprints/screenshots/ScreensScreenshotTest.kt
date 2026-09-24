package app.doorprints.screenshots

import android.os.LocaleList
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import app.doorprints.DoorprintsApp
import app.doorprints.data.HouseEntity
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.AssistantScreen
import app.doorprints.ui.CompareScreen
import app.doorprints.ui.ExportScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.ImportScreen
import app.doorprints.ui.SettingsScreen
import app.doorprints.shared.model.HouseStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Screenshots of every screen except the Map (MapLibre is native code), in both themes and all four languages,
 * rendered on the JVM by Robolectric and compared by Roborazzi with the reference images in src/test/screenshots
 * (docs/06 TC-U-56). They show that a refactor, such as each Compose Multiplatform phase (ADR-23), leaves the screens
 * unchanged. Record new references with `./gradlew :app:recordRoborazziDebug`, on Linux (other platforms' Skia can
 * differ by a pixel); CI runs the unit tests with `-Proborazzi.test.verify=true`. Settings shows "System default"
 * selected: the language here comes from the configuration, not from a saved choice.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-hdpi", application = ScreenshotTestApp::class)
class ScreensScreenshotTest(private val lang: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        // androidx FileProvider caches its path roots per authority in a static map, but Robolectric gives each test a
        // new data directory, so from the second test on the cached root no longer contains the camera file.
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        // Dates and times are shown in the device's zone: the same one on every machine (restored in tearDown).
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        RuntimeEnvironment.setQualifiers("+$lang" + if (dark) "-night" else "-notnight")
        // As the app does (AppLocale.applyDefault): Compose resources read the language from the default locale.
        AppLocale.applyDefault(ApplicationProvider.getApplicationContext())
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            repo.saveHouse(house("a", "Green View 2BHK", HouseStatus.SHORTLISTED, 28_000, 2, mapOf("water" to 5, "light" to 4), 1_760_000_000_000))
            repo.saveHouse(house("b", "Lake Road flat", HouseStatus.NEW, 22_500, 1, mapOf("water" to 3), 1_760_000_100_000))
        }
    }

    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()

    @After fun tearDown() {
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun house(id: String, label: String, status: HouseStatus, price: Long, bhk: Int, checklist: Map<String, Int>, at: Long) =
        HouseEntity(
            id = id, label = label, lat = 12.9716, lon = 77.5946, status = status, price = price, bedrooms = bhk,
            locality = "Indiranagar", checklist = checklist, createdAt = at, updatedAt = at,
        )

    private fun shoot(screen: String, content: @Composable () -> Unit) {
        // Surface in the theme's background, as Root's Scaffold draws it around every screen.
        compose.setContent { DoorprintsTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.background) { content() } } }
        awaitStableFrame()
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${lang}_${if (dark) "dark" else "light"}.png")
    }

    /**
     * Room answers on its own threads, which Compose's idling does not wait for (the Export screen's counts and
     * buttons came in after the first frame on some runs): idle the main looper and wait until two frames in a row are
     * the same, up to about 3 s.
     */
    private fun awaitStableFrame() {
        var last: IntArray? = null
        repeat(20) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            if (last?.contentEquals(pixels) == true) return
            last = pixels
        }
    }

    @Test fun houses() = shoot("houses") { HouseListScreen(onOpenHouse = {}) }
    @Test fun compare() = shoot("compare") { CompareScreen(onOpenHouse = {}) }
    @Test fun houseEdit() = shoot("house_edit") { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) }
    @Test fun houseNew() = shoot("house_new") { HouseEditScreen(houseId = null, newLat = 12.9716, newLon = 77.5946, visitId = null, onDone = {}) }
    @Test fun settings() = shoot("settings") { SettingsScreen() }
    @Test fun assistant() = shoot("assistant") { AssistantScreen(onOpenHouse = {}) }
    @Test fun export() = shoot("export") { ExportScreen(onBack = {}) }
    @Test fun import() = shoot("import") { ImportScreen(onBack = {}) }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun params(): List<Array<Any>> = listOf("en", "hi", "ta", "te").flatMap { l -> listOf(false, true).map { arrayOf<Any>(l, it) } }
    }
}
