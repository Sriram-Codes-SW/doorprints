package app.doorprints

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests of the installed debug APK on an emulator (android-emulator.yml) or a Test Lab device (docs/06
 * TC-I-35): the app starts, every tab opens (the Assistant tab only exists with the server's AI features on, so it is
 * not among them), a house can be added from a "new house" intent and then shows in the
 * list. Each step saves a screenshot to the test storage (CI artifact). The device language is English. Since CMP-7
 * the Map's India view (ADR-22) is photographed at India zoom 4, Jammu and Kashmir with Ladakh at zoom 6 and Arunachal
 * Pradesh at zoom 6 ([theMapShowsIndiasBoundary]), the evidence for TC-M-25's screens on each emulator API level.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *listOfNotNull(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 },
        ).toTypedArray(),
    )

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() {
        scenario?.close()
    }

    private fun launch(intent: Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitFor(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text), timeoutMs)

    private fun tab(label: String): SemanticsNodeInteraction =
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst()

    /** A screenshot in the test storage; Test Lab runs without the storage service, so a failure is not fatal. */
    private fun shot(name: String) {
        compose.waitForIdle()
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            TestStorage().openOutputFile("screens/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun everyTabOpens() {
        launch()
        waitFor("Houses")
        shot("01_map")
        for ((label, name) in listOf("Houses" to "02_houses", "Compare" to "03_compare", "Settings" to "04_settings", "Map" to "05_map_again")) {
            tab(label).performClick()
            shot(name)
        }
    }

    @Test fun addAHouseFromANewHouseIntent() {
        val name = "Smoke test house ${System.currentTimeMillis() % 100_000}"
        launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(Notifications.EXTRA_NEW_LAT, 12.9716)
                .putExtra(Notifications.EXTRA_NEW_LON, 77.5946),
        )
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), 20_000)
        // Replace, not append: the form starts with a default name ("New house", or a street from the geocoder).
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(name)
        shot("10_new_house")
        // The top bar's Save: the form has a second one at its end, off screen inside the scrolling column.
        compose.onNode(hasText("Save") and hasClickAction() and !hasAnyAncestor(hasScrollAction())).performClick()
        // The name is on the new-house form already, so wait for the saved house's own page ("House details").
        waitFor("House details")
        shot("11_saved")
        compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onFirst().performClick()
        tab("Houses").performClick()
        waitFor(name)
        shot("12_in_list")
    }

    /**
     * The Map's India view (ADR-22) on this device, for a person to look at (TC-M-25 (1)-(3); the 64 JVM screenshots
     * have no Map): the camera goes to India at zoom 4, to Jammu and Kashmir with Ladakh (Gilgit-Baltistan, Shaksgam
     * and Aksai Chin in view) at zoom 6 and to Arunachal Pradesh at zoom 6, and each is saved once MapLibre says the
     * frame is fully rendered (every tile loaded), as `screens/2x_map_*.png` taken with UiAutomation, which includes
     * the map's GL surface. The tiles come from the network: when a frame does not finish within [TILES_TIMEOUT_MS]
     * (no network, a slow tile server, or a style that did not load) the shot is still saved and the test passes, and
     * `screens/map_notes.txt` and logcat (tag SmokeTest) say which frames were incomplete, so the artifact is read
     * with that in mind. What the shots should show is TC-M-25's (docs/06): India's outline as the Government of India
     * shows it, no Line of Control or Line of Actual Control.
     */
    @Test fun theMapShowsIndiasBoundary() {
        launch()
        waitFor("Houses")
        // The Map's style: its "Loading the map…" line goes when the style is in, or the error card comes instead.
        val styleIn = runCatching {
            compose.waitUntil(STYLE_TIMEOUT_MS) {
                compose.onAllNodes(hasText("Loading the map…")).fetchSemanticsNodes().isEmpty()
            }
        }.isSuccess
        val failed = compose.onAllNodes(hasText(MAP_FAILED_START, substring = true)).fetchSemanticsNodes().isNotEmpty()
        val notes = mutableListOf("loading line gone: $styleIn", "map failed card: $failed")
        // A tap on Zoom in counts as the user placing the map, so the first framing (the emulator's location at zoom
        // 16, when it has one) never moves the camera away from the places below.
        compose.onNode(hasContentDescription("Zoom in") and hasClickAction()).performClick()
        compose.waitForIdle()
        val mapView = findMapView()
        notes += "map view found: ${mapView != null}"
        for ((name, place) in MAP_PLACES) {
            val complete = mapView?.let { showAndWait(it, place) } ?: false
            notes += "$name: fully rendered = $complete"
            Log.i("SmokeTest", "$name: fully rendered = $complete")
            screenShot(name)
            if (name == DIAGNOSED_PLACE && mapView != null) {
                labelDiagnostics(mapView).forEach { line ->
                    notes += "diag: $line"
                    Log.i(DIAG_TAG, line)
                }
            }
        }
        runCatching {
            TestStorage().openOutputFile("screens/map_notes.txt").use { it.write(notes.joinToString("\n").toByteArray()) }
        }
    }

    /** The MapLibre view inside the Compose hierarchy (an AndroidView). */
    private fun findMapView(): MapView? {
        var found: MapView? = null
        fun search(view: View) {
            if (found != null) return
            if (view is MapView) {
                found = view
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) search(view.getChildAt(i))
            }
        }
        scenario?.onActivity { search(it.window.decorView) }
        return found
    }

    /** Moves the camera to [place] at once and waits for a fully rendered frame; false on timeout. */
    private fun showAndWait(mapView: MapView, place: CameraPosition): Boolean {
        val done = CountDownLatch(1)
        val listener = MapView.OnDidFinishRenderingMapListener { fully -> if (fully) done.countDown() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.addOnDidFinishRenderingMapListener(listener)
            mapView.getMapAsync { it.cameraPosition = place }
        }
        val complete = done.await(TILES_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.removeOnDidFinishRenderingMapListener(listener)
        }
        compose.waitForIdle()
        return complete
    }

    /**
     * What MapLibre knows about the base map's labels at the current camera (read on the main thread, after the frame
     * at [DIAGNOSED_PLACE] finished rendering), for `screens/map_notes.txt` and logcat (tag [DIAG_TAG]): the style's
     * glyphs URL and whether it is fully loaded; every symbol layer with its visibility, zoom range, text-font and
     * filter; how many symbols MapLibre has PLACED over the whole map view (queryRenderedFeatures, total and for each
     * label/place layer); and how many `place` features the loaded tiles hold (querySourceFeatures). Placed symbols with
     * no text on the screenshot point at drawing (the GL renderer, e.g. SwiftShader); none placed while the tiles hold
     * places points at layout (a filter, the glyphs, a hidden layer). Each read is on its own, so one failure is
     * reported and the rest still run.
     */
    private fun labelDiagnostics(mapView: MapView): List<String> {
        val lines = mutableListOf<String>()
        val done = CountDownLatch(1)
        fun read(what: String, block: () -> Unit) {
            runCatching(block).onFailure { lines += "$what failed: $it" }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getMapAsync { map: MapLibreMap ->
                try {
                    val style = map.style
                    if (style == null) {
                        lines += "no style"
                        return@getMapAsync
                    }
                    lines += "camera: ${map.cameraPosition}"
                    lines += "style fully loaded: ${style.isFullyLoaded}, url: ${style.uri}"
                    read("glyphs") { lines += "glyphs: ${JSONObject(style.json).optString("glyphs", "(none)")}" }
                    val symbols = style.layers.filterIsInstance<SymbolLayer>()
                    lines += "symbol layers: ${symbols.size}"
                    symbols.forEach { layer ->
                        read("layer ${layer.id}") {
                            lines += "layer ${layer.id}: source-layer=${layer.sourceLayer} " +
                                "visibility=${layer.visibility.value} zoom=${layer.minZoom}..${layer.maxZoom} " +
                                "text-font=${layer.textFont.run { if (isExpression) expression.toString() else value?.contentToString() }} " +
                                "filter=${layer.filter}"
                        }
                    }
                    val box = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
                    read("rendered symbols") {
                        val ids = symbols.map { it.id }.toTypedArray()
                        lines += "view ${mapView.width}x${mapView.height}: rendered symbols (all symbol layers) = " +
                            map.queryRenderedFeatures(box, *ids).size
                        symbols.filter { l ->
                            listOf("label_country", "label_city", "label_state", "place", "label_town", "label_village")
                                .any { l.id.startsWith(it) }
                        }.forEach { l -> lines += "rendered ${l.id} = ${map.queryRenderedFeatures(box, l.id).size}" }
                        lines += "rendered (any layer) = ${map.queryRenderedFeatures(box).size}"
                    }
                    read("source places") {
                        style.sources.filterIsInstance<VectorSource>().forEach { src ->
                            lines += "source ${src.id}: place features in loaded tiles = " +
                                src.querySourceFeatures(arrayOf("place"), null).size
                        }
                    }
                } catch (e: Exception) {
                    lines += "diagnostics failed: $e"
                } finally {
                    done.countDown()
                }
            }
        }
        if (!done.await(5, TimeUnit.SECONDS)) lines += "map not ready within 5 s"
        return lines
    }

    /** The whole screen with UiAutomation, which (unlike Compose's capture) includes the map's GL surface. */
    private fun screenShot(name: String) {
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            TestStorage().openOutputFile("screens/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private companion object {
        const val STYLE_TIMEOUT_MS = 30_000L

        /** The start of the Map's error card (map_failed, English). */
        const val MAP_FAILED_START = "The map could not load"
        const val TILES_TIMEOUT_MS = 45_000L

        /** The frame whose labels [labelDiagnostics] reads, and its logcat tag. */
        const val DIAGNOSED_PLACE = "20_map_india_z4"
        const val DIAG_TAG = "DoorprintsMapDiag"

        fun at(lat: Double, lon: Double, zoom: Double): CameraPosition =
            CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(0.0).build()

        /** TC-M-25's places: the whole of India, then the two areas the India view changes most, at zoom 6. */
        val MAP_PLACES = listOf(
            "20_map_india_z4" to at(22.5, 80.0, 4.0),
            "21_map_kashmir_ladakh_z6" to at(35.0, 76.5, 6.0),
            "22_map_arunachal_z6" to at(28.0, 94.0, 6.0),
        )
    }
}
