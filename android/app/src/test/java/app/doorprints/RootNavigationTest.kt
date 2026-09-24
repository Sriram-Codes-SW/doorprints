package app.doorprints

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.RootScreens
import app.doorprints.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The common root's navigation (ADR-23 CMP-5: the graph moved to `:ui`, on JetBrains navigation-compose) behaves as
 * before for the notification links MainActivity hands it (docs/06 TC-I-35 checks the same on an emulator): a link
 * present at a cold start opens its screen over the Map, the same house link twice does not stack two forms, Settings
 * opens as its tab, Export is single-top, each link is reported handled, and Back returns to the Map. The screens
 * still in `:app` are stand-ins here (RootScreens), so only the graph is under test; the house form, Export and Import
 * are the real, common ones since CMP-6 (English: "Save a house" for a new house, "House details" for a stored one,
 * "Save a copy").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class RootNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val links = MutableStateFlow<DeepLink?>(null)
    private var handled = 0

    @Before fun clearFileProviderCache() {
        // The house form's camera file goes through androidx FileProvider, which caches its path roots per authority in
        // a static map, but Robolectric gives each test a new data directory (as in ScreensScreenshotTest).
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private object Stand : RootScreens {
        @Composable
        override fun Map(
            onOpenHouse: (String) -> Unit,
            onNewHouse: (Double, Double) -> Unit,
            onOpenHouses: () -> Unit,
            showAddTip: Boolean,
            onAddTipShown: () -> Unit,
            deletedHouse: String?,
            onDeletedShown: () -> Unit,
        ) = Text("map screen")
    }

    private fun start(link: DeepLink?) {
        links.value = link
        compose.setContent {
            ProvideAppServices {
                DoorprintsRoot(links, onDeepLinkHandled = { handled++; links.value = null }, screens = Stand)
            }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    @Test
    fun aNewHouseLinkAtColdStartOpensTheFormOverTheMap() {
        start(DeepLink.NewHouse(12.9716, 77.5946, null))
        shows("Save a house")
        // The link's coordinates reached the form (its Latitude and Longitude fields, six decimals).
        shows("12.971600")
        shows("77.594600")
        assertEquals(1, handled)
        assertNull(links.value)
        back()
        shows("map screen")
    }

    @Test
    fun theSameHouseLinkTwiceStacksOneForm() {
        val id = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e90"
        // Stored first, so the form's title is "House details" whenever Room answers (not "House not found").
        runBlocking {
            val at = 1_760_000_000_000
            ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
                .saveHouse(HouseEntity(id = id, label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
        }
        start(DeepLink.OpenHouse(id))
        shows("House details")
        links.value = DeepLink.OpenHouse(id)
        compose.waitForIdle()
        assertEquals(2, handled)
        back()
        shows("map screen")
    }

    @Test
    fun theFormsDoneClosesIt() {
        start(null)
        shows("map screen")
        // No visit id: with one, the root first looks the visit up in Room, and the test's coroutine interceptor does
        // not bring the effect back to the main thread afterwards as the app's main dispatcher does.
        links.value = DeepLink.NewHouse(13.0, 77.5, null)
        compose.waitForIdle()
        shows("Save a house")
        // The form's back arrow: nothing typed, so it closes without asking.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        shows("map screen")
    }

    @Test
    fun settingsOpensAsItsTabAndBackReturnsToTheMap() {
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        // The Settings screen's heading (the bar's item says "Settings" too).
        compose.onNodeWithText("Server (optional)").assertExists()
        back()
        shows("map screen")
    }

    @Test
    fun exportIsSingleTop() {
        start(DeepLink.OpenScreen(Routes.EXPORT))
        shows("Save a copy")
        links.value = DeepLink.OpenScreen(Routes.EXPORT)
        compose.waitForIdle()
        assertEquals(2, handled)
        back()
        shows("map screen")
    }
}
