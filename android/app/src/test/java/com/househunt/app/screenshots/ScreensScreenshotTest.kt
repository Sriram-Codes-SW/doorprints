package com.househunt.app.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.househunt.app.HouseHuntApp
import com.househunt.app.data.HouseEntity
import com.househunt.app.ui.AssistantScreen
import com.househunt.app.ui.CompareScreen
import com.househunt.app.ui.ExportScreen
import com.househunt.app.ui.HouseEditScreen
import com.househunt.app.ui.HouseHuntTheme
import com.househunt.app.ui.HouseListScreen
import com.househunt.app.ui.ImportScreen
import com.househunt.app.ui.SettingsScreen
import com.househunt.shared.model.HouseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every screen except the Map (MapLibre is native code), in both themes and all four languages,
 * rendered on the JVM by Robolectric and compared by Roborazzi with the reference images in src/test/screenshots
 * (docs/06 TC-U-60). They show that a refactor, such as each Compose Multiplatform phase (ADR-23), leaves the screens
 * unchanged. Record new references with `./gradlew :app:recordRoborazziDebug`; CI runs `verifyRoborazziDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-hdpi", application = ScreenshotTestApp::class)
class ScreensScreenshotTest(private val lang: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        // androidx FileProvider caches its path roots per authority in a static map, but Robolectric gives each test a
        // new data directory, so from the second test on the cached root no longer contains the camera file.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        RuntimeEnvironment.setQualifiers("+$lang" + if (dark) "-night" else "-notnight")
        val repo = ApplicationProvider.getApplicationContext<HouseHuntApp>().container.repository
        runBlocking {
            repo.saveHouse(house("a", "Green View 2BHK", HouseStatus.SHORTLISTED, 28_000, 2, mapOf("water" to 5, "light" to 4)))
            repo.saveHouse(house("b", "Lake Road flat", HouseStatus.NEW, 22_500, 1, mapOf("water" to 3)))
        }
    }

    private fun house(id: String, label: String, status: HouseStatus, price: Long, bhk: Int, checklist: Map<String, Int>) =
        HouseEntity(
            id = id, label = label, lat = 12.9716, lon = 77.5946, status = status, price = price, bedrooms = bhk,
            locality = "Indiranagar", checklist = checklist, createdAt = 1_760_000_000_000, updatedAt = 1_760_000_000_000,
        )

    private fun shoot(screen: String, content: @Composable () -> Unit) {
        // Surface in the theme's background, as Root's Scaffold draws it around every screen.
        compose.setContent { HouseHuntTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.background) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${lang}_${if (dark) "dark" else "light"}.png")
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
