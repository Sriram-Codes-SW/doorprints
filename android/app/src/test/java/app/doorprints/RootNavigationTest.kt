package app.doorprints

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.RootScreens
import app.doorprints.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The common root's navigation (ADR-23 CMP-5: the graph moved to `:ui`, on JetBrains navigation-compose) behaves as
 * before for the notification links MainActivity hands it (docs/06 TC-I-35 checks the same on an emulator): a link
 * present at a cold start opens its screen over the Map, the same house link twice does not stack two forms, Settings
 * opens as its tab, Export is single-top, each link is reported handled, and Back returns to the Map. The screens
 * still in `:app` are stand-ins here (RootScreens), so only the graph is under test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class RootNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val links = MutableStateFlow<DeepLink?>(null)
    private var handled = 0

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

        @Composable
        override fun HouseForm(
            houseId: String?,
            newLat: Double?,
            newLon: Double?,
            visitId: String?,
            onDone: () -> Unit,
            onOpenHouses: () -> Unit,
            onCreated: (String) -> Unit,
            onDeleted: (String) -> Unit,
            showSaved: Boolean,
            onSavedShown: () -> Unit,
        ) = TextButton(onClick = onDone) { Text("form ${houseId ?: "new"} $newLat $newLon $visitId") }

        @Composable
        override fun Export(onBack: () -> Unit, onOpenMap: () -> Unit) = Text("export screen")

        @Composable
        override fun Import(onBack: () -> Unit, onOpenHouses: (importedRunId: String?) -> Unit) = Text("import screen")
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
        shows("form new 12.9716 77.5946 null")
        assertEquals(1, handled)
        assertNull(links.value)
        back()
        shows("map screen")
    }

    @Test
    fun theSameHouseLinkTwiceStacksOneForm() {
        val id = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e90"
        start(DeepLink.OpenHouse(id))
        shows("form $id null null null")
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
        compose.onNodeWithText("form new 13.0 77.5 null").performClick()
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
        shows("export screen")
        links.value = DeepLink.OpenScreen(Routes.EXPORT)
        compose.waitForIdle()
        assertEquals(2, handled)
        back()
        shows("map screen")
    }
}
