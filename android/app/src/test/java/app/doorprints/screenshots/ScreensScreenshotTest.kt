/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.screenshots

import android.app.KeyguardManager
import android.graphics.Bitmap
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.DefaultTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import app.doorprints.ui.res.count_houses
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isHeading
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.house_checklist
import app.doorprints.ui.res.house_contact
import app.doorprints.ui.res.house_cost
import app.doorprints.ui.res.house_questions
import app.doorprints.ui.res.common_try_again
import app.doorprints.ui.res.compare_empty
import app.doorprints.ui.res.houses_empty
import app.doorprints.ui.res.settings_ai_use_own_key
import app.doorprints.ui.res.settings_app_lock
import app.doorprints.ui.res.settings_path_trace_clear
import app.doorprints.shared.model.DefaultQuestions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource
import android.os.LocaleList
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.data.TrackPointEntity
import app.doorprints.ui.AiSettingsSection
import app.doorprints.ui.AreasEditor
import app.doorprints.ui.AppLockSection
import app.doorprints.ui.BrokerForm
import app.doorprints.ui.BrokerList
import app.doorprints.shared.model.Broker
import app.doorprints.ui.ShareUpdatesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import app.doorprints.ui.SaveAreaDialogContent
import app.doorprints.ui.OfflineMapsServices
import app.doorprints.ui.OfflineMapsSection
import app.doorprints.ui.OfflineAreaState
import app.doorprints.ui.OfflineArea
import app.doorprints.ui.GeoBounds
import app.doorprints.ui.PathTraceSection
import app.doorprints.ui.RoomsSection
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.LengthUnit
import org.junit.Assume.assumeTrue
import app.doorprints.ui.LockScreenContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
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
import app.doorprints.ui.CriteriaEditor
import app.doorprints.ui.QuestionsEditor
import app.doorprints.ui.ViewingsHistory
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.doorprints.ui.ExportScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.ImportScreen
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalPlatformFeatures
import app.doorprints.ui.MapScreen
import app.doorprints.ui.PlatformFeatures
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.SettingsScreen
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.HouseStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
    /**
     * The screens' effects run on a [StandardTestDispatcher], so on the main (test) thread, as in the app, where they
     * run on the main thread's dispatcher (S4b-BL-42). With the rule's default, an UnconfinedTestDispatcher wrapped in
     * `ApplyingContinuationInterceptor`, a coroutine that resumed after a thread switch stayed on that thread: Room's
     * and DataStore's flows collected by `collectAsStateWithLifecycle` and Export's count after
     * `withContext(Dispatchers.Default)` wrote their state on a worker, and the interceptor applied the snapshot there
     * too (`Snapshot.sendApplyNotifications` on DefaultDispatcher-worker and arch_disk_io threads, three to four times
     * per Export shot). That is the only composition work these tests ran off the main thread, and the likely source of
     * `export[hi-dark=true]`'s CalledFromWrongThreadException (once in about eight runs during CMP-6; not reproduced
     * since in several hundred shots). Those continuations now wait in [effects]' scheduler until [awaitStableFrame]
     * runs them on the main thread.
     */
    private val effects = StandardTestDispatcher()

    @get:Rule val compose = createComposeRule(effects)

    @Before fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(countingExecutor)
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
        ArchTaskExecutor.getInstance().setDelegate(null)
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun house(id: String, label: String, status: HouseStatus, price: Long, bhk: Int, checklist: Map<String, Int>, at: Long) =
        HouseEntity(
            id = id, label = label, lat = 12.9716, lon = 77.5946, status = status, price = price, bedrooms = bhk,
            locality = "Indiranagar", checklist = checklist, createdAt = at, updatedAt = at,
        )

    private fun file(screen: String) = "src/test/screenshots/${screen}_${lang}_${if (dark) "dark" else "light"}.png"

    /**
     * [readyText] is required at every call, so that a screen fed by Room or DataStore cannot be shot without saying what
     * shows when its data is in (S4b-BL-138): a Room-backed screen draws only its heading (Compare) or nothing (the house
     * list) until the first answer, and that frame is as stable as the final one, so [awaitStableFrame] alone accepted it
     * when the answer was late (reproduced under load: `houses[ta-dark=true]`, `iosCompareEmpty[ta-dark=*]`). [STATIC]
     * is the explicit "this screen shows no stored data, or its defaults are what the shot shows".
     */
    private fun show(readyText: String?, content: @Composable () -> Unit) {
        // Surface in the theme's background, as Root's Scaffold draws it around every screen.
        compose.setContent {
            // As MainActivity does: the screens read the platform's and the app's seams (ADR-23 CMP-3, CMP-5).
            ProvideAppServices {
                DoorprintsTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
            }
        }
        // Wait for the screen's own text, in Text or in a contentDescription (Export's counts are the latter), so a shot
        // never captures the table before its rows (S4b-BL-100, S4b-BL-138). The wait pumps the effects dispatcher too:
        // the screens' coroutines run on [effects], which only [settle] advances.
        if (readyText != null) {
            val ready = hasText(readyText, substring = true) or hasContentDescription(readyText, substring = true)
            val deadline = System.nanoTime() + 20_000_000_000L
            while (compose.onAllNodes(ready).fetchSemanticsNodes().isEmpty()) {
                check(System.nanoTime() < deadline) { "'$readyText' did not show in 20 s" }
                settle()
            }
        }
        awaitStableFrame()
    }

    private fun shoot(screen: String, readyText: String?, content: @Composable () -> Unit) {
        show(readyText, content)
        compose.onRoot().captureRoboImage(file(screen))
    }

    /** A string of the app in the shot's language (the default locale is set in [setUp]). */
    private fun text(res: StringResource): String = runBlocking { getString(res) }

    /**
     * Room and DataStore answer on their own threads, which Compose's idling does not wait for (the Export screen's
     * counts and buttons came in after the first frame on some runs): [settle] until those threads, the main looper and
     * [effects]' scheduler are all idle, then capture; the frame is taken when two settled frames in a row are the same
     * (a last check, should an answer land between the threads' check and the capture). Until S4b-BL-77 this slept 150
     * ms per pass and needed three like passes: at least 0.45 s a shot, up to 4.5 s.
     */
    private fun awaitStableFrame() {
        var last: IntArray? = null
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            settle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            if (last?.contentEquals(pixels) == true) return
            last = pixels
        }
        error("The screen did not settle in 20 s")
    }

    /**
     * Runs the main looper, the effects that resumed since (a Room or DataStore answer, Export's count) on the main
     * thread, and Compose, until Room has no task and the coroutine workers are parked three checks in a row and for at
     * least 25 ms: an answer that starts the next step (an effect relaunched for new rows, whose count comes back from a
     * worker) is followed up in the same call. runCurrent, not advanceUntilIdle, so no delay is skipped and no ticking
     * clock runs forever. The 25 ms are for a worker that was just handed a task but has not started on it, which still
     * looks parked (Dispatchers.Default and IO have no public queue to look at; DataStore reads there, and so does the
     * houses' flow, behind the brokers move): on a loaded machine 10 ms once let Compare's houses come in too late.
     */
    private fun settle() {
        var quiet = 0
        val start = System.nanoTime()
        while (quiet < 3 || System.nanoTime() - start < 25_000_000L) {
            val workersIdle = workersParked()
            shadowOf(Looper.getMainLooper()).idle()
            compose.runOnIdle { effects.scheduler.runCurrent() }
            compose.waitForIdle()
            quiet = if (workersIdle && shadowOf(Looper.getMainLooper()).isIdle) quiet + 1 else 0
            Thread.sleep(1)
        }
    }

    /** Room and the coroutine workers (Dispatchers.Default and IO: DataStore's, Export's count) have nothing to run. */
    private fun workersParked(): Boolean = roomTasks.get() == 0 && Thread.getAllStackTraces().keys.none { t ->
        t.name.startsWith("DefaultDispatcher-worker") && (t.state == Thread.State.RUNNABLE || t.state == Thread.State.BLOCKED)
    }

    /**
     * Room's tasks queued or running: Room runs its queries on the architecture components' disk executor, through
     * [ArchTaskExecutor] at each call, so a delegate that counts them sees every one from its hand-over to its end (a
     * worker's thread state alone missed a query handed over but not yet started, once in a full run).
     */
    private val roomTasks = AtomicInteger()

    private val countingExecutor = object : TaskExecutor() {
        private val real = DefaultTaskExecutor()
        override fun executeOnDiskIO(runnable: Runnable) {
            roomTasks.incrementAndGet()
            real.executeOnDiskIO {
                try {
                    runnable.run()
                } finally {
                    roomTasks.decrementAndGet()
                }
            }
        }
        override fun postToMainThread(runnable: Runnable) = real.postToMainThread(runnable)
        override fun isMainThread(): Boolean = real.isMainThread
    }

    /**
     * The house form in bands of sections (S4b-BL-77), so that a change to one section re-records that band's images
     * only, not the whole form's: [bands] names each band after the heading it starts at ([Res.string] keys, read in
     * the shot's language), the first band from the top of the screen, the last to the end of the form. The window is
     * tall enough for the whole form (the test fails when the form scrolls), and the shots are English in both themes
     * and Hindi light only, to keep the image set small; [only] keeps the bands named (the iPhone's differ in one).
     */
    private fun shootForm(screen: String, readyText: String?, only: Set<String>? = null, content: @Composable () -> Unit) {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        RuntimeEnvironment.setQualifiers("+h5200dp")
        val headings = mutableMapOf<String, String>()
        show(readyText) {
            bands.forEach { (name, res) -> if (res != null) headings[name] = stringResource(res) }
            content()
        }
        val scroll = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().maxOf { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }
        check(scroll == 0f) { "The house form scrolls by $scroll px in the shot: make the window taller (h5200dp)" }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        // The form ends at its lowest node (the Save button, then its 24 dp spacer), not at the bottom of the window.
        val all = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val end = (all.filter { it.boundsInRoot.height < image.height / 2 }.maxOf { it.boundsInRoot.bottom } + 24 * density)
            .toInt().coerceAtMost(image.height)
        val tops = bands.map { (name, res) ->
            if (res == null) {
                0
            } else {
                compose.onAllNodes(isHeading() and hasText(headings.getValue(name)), useUnmergedTree = true)
                    .fetchSemanticsNodes().single().boundsInRoot.top.toInt()
            }
        }
        check(tops.zipWithNext().all { (a, b) -> a < b }) { "The form's sections are not in the order of bands: $tops" }
        bands.forEachIndexed { i, (name, _) ->
            if (only != null && name !in only) return@forEachIndexed
            val bottom = tops.getOrElse(i + 1) { end }
            Bitmap.createBitmap(image, 0, tops[i], image.width, bottom - tops[i]).captureRoboImage(file("${screen}_$name"))
        }
    }

    private val bands: List<Pair<String, StringResource?>> = listOf(
        "top" to null,
        "cost" to Res.string.house_cost,
        "questions" to Res.string.house_questions,
        "checklist" to Res.string.house_checklist,
        "contact" to Res.string.house_contact,
    )

    @Test fun houses() = shoot("houses", readyText = "Green View 2BHK") { HouseListScreen(onOpenHouse = {}) }
    @Test fun compare() = shoot("compare", readyText = "Green View 2BHK") {
        // As the root's Compare destination does (CMP-5): the houses (null until Room answers) and the visit counts.
        val repo = LocalAppServices.current.repository
        val houses by repo.houses.collectAsState(initial = null)
        val counts by repo.visitCounts.collectAsState(initial = emptyList())
        CompareScreen(houses, counts, onOpenHouse = {})
    }
    /** The form of a saved house, in its bands (see [shootForm]). */
    @Test fun houseEdit() = shootForm("house_edit", readyText = "Green View 2BHK") { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) }

    /**
     * A new house's form as it opens, one screen high (its title and the top of the form; the rest is the edit form's,
     * but for the Photos prompt and no Visits): English in both themes and Hindi light only, as the bands above.
     */
    @Test fun houseNew() {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        shoot("house_new", STATIC) { HouseEditScreen(houseId = null, newLat = 12.9716, newLon = 77.5946, visitId = null, onDone = {}) }
    }
    @Test fun settings() = shoot("settings", STATIC) { SettingsScreen() }

    /** Settings → AI features turned on with "Use my own Gemini key on this phone" chosen (docs/03 §13.1, ADR-26). */
    @Test fun settingsAiOwnKey() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            repo.setAiFeatures(true)
            repo.setAiProvider(AiProviderChoice.DEVICE)
        }
        shoot("settings_ai_own_key", readyText = text(Res.string.settings_ai_use_own_key)) {
            val settings by repo.settings.settings.collectAsState(AppSettings())
            val off by repo.aiOff.collectAsState()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { AiSettingsSection(settings, off) }
        }
    }
    /**
     * The app lock (docs/11 5.19): Settings' *Lock Doorprints* turned on, with its times, and under it the lock screen's
     * text and *Unlock* after a cancelled try. One shot for both, to keep the image set small.
     */
    @Test fun appLock() {
        val context = ApplicationProvider.getApplicationContext<DoorprintsApp>()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val repo = context.container.repository
        runBlocking { repo.settings.saveAppLock(true) }
        shoot("app_lock", readyText = text(Res.string.settings_app_lock)) {
            val settings by repo.settings.settings.collectAsState(AppSettings())
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppLockSection(settings)
                HorizontalDivider()
                LockScreenContent(failed = true, onUnlock = {}, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            }
        }
    }
    /** The path trace (docs/11 5.27): its switch on, with a kept point so *Clear the path* shows. */
    @Test fun huntTrace() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            repo.settings.savePathTrace(true)
            repo.saveTrackPoint(TrackPointEntity(at = System.currentTimeMillis(), lat = 12.97, lon = 77.64, accuracyM = 8f))
        }
        shoot("hunt_trace", readyText = text(Res.string.settings_path_trace_clear)) {
            val settings by repo.settings.settings.collectAsState(AppSettings())
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { PathTraceSection(settings) }
        }
    }
    /**
     * Path trace v2 (docs/11 5.27; S4b-FR-15, S4b-FR-24), English and the light theme only (the repository keeps few, small
     * pictures): the Map's trace legend with both looks, *Save this walk?* and its house picker, the house page's *Saved
     * walks*, and *Have I been here?* with a walked and an imprecise answer. The sheets are shown as content: a sheet's own
     * window is not captured.
     */
    private fun englishLightOnly() = assumeTrue(lang == "en" && !dark)

    private val shotWalk = (0..5).map { k ->
        app.doorprints.shared.trace.TracePoint(12.969 + k * 0.00054, 77.5946, 1_760_000_000_000L + k * 60_000L, 1_760_000_000_000L)
    }
    private val shotHouses get() = runBlocking { ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository.houses.first() }

    @Test fun traceLegend() {
        englishLightOnly()
        shoot("trace_legend") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                app.doorprints.ui.TraceLegend(app.doorprints.shared.trace.RepeatLook.CLEAR)
                app.doorprints.ui.TraceLegend(app.doorprints.shared.trace.RepeatLook.SUBTLE)
            }
        }
    }

    @Test fun walkEnd() {
        englishLightOnly()
        shoot("walk_end", "Save this walk?") {
            app.doorprints.ui.WalkEndSheetContent(app.doorprints.ui.WalkSummary(1_760_000_000_000L, shotWalk), shotHouses, 30, null, {}, {}, {})
        }
    }

    @Test fun walkEndPick() {
        englishLightOnly()
        shoot("walk_end_pick", "Which house was this walk to?") {
            app.doorprints.ui.WalkEndSheetContent(
                app.doorprints.ui.WalkSummary(1_760_000_000_000L, shotWalk), shotHouses, 30, null, {}, {}, {}, initiallyPicking = true,
            )
        }
    }

    @Test fun savedWalks() {
        englishLightOnly()
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            listOf(1_760_000_000_000L, 1_759_000_000_000L).forEach { id ->
                shotWalk.forEach { p -> repo.saveTrackPoint(TrackPointEntity(at = id + (p.atMs - 1_760_000_000_000L), lat = p.lat, lon = p.lon, accuracyM = 5f, walkId = id)) }
                repo.saveWalk("a", id)
            }
        }
        shoot("saved_walks", "Saved walks") {
            Column(Modifier.padding(16.dp)) { app.doorprints.ui.SavedWalksCard("a") {} }
        }
    }

    private fun checkAnswer(status: app.doorprints.shared.trace.PlaceCheckStatus, rows: List<app.doorprints.shared.trace.PlaceRow>, fuzzy: Boolean = false) =
        app.doorprints.ui.PlaceCheckState.Answer(
            app.doorprints.ui.PlaceKind.HOUSE, 12.97, 77.6,
            app.doorprints.shared.trace.PlaceCheckResult(status, fuzzy, rows.minOfOrNull { it.distanceM }, rows),
            listOf(app.doorprints.shared.trace.TraceWalk(shotWalk)),
        )

    @Test fun placeCheckWalked() {
        englishLightOnly()
        val rows = listOf(
            app.doorprints.shared.trace.PlaceRow(0, 6.0, 1_760_000_100_000L, app.doorprints.shared.trace.PlaceBand.WALKED, app.doorprints.shared.trace.WalkSource.TRACE),
            app.doorprints.shared.trace.PlaceRow(0, 18.0, 1_759_000_100_000L, app.doorprints.shared.trace.PlaceBand.WALKED, app.doorprints.shared.trace.WalkSource.SAVED),
        )
        shoot("place_check_walked", "Did I walk past this house?") {
            app.doorprints.ui.PlaceCheckSheetContent(checkAnswer(app.doorprints.shared.trace.PlaceCheckStatus.WALKED, rows), {}, {}, {})
        }
    }

    @Test fun placeCheckImprecise() {
        englishLightOnly()
        shoot("place_check_imprecise", "Location not precise enough.") {
            app.doorprints.ui.PlaceCheckSheetContent(
                app.doorprints.ui.PlaceCheckState.Answer(
                    app.doorprints.ui.PlaceKind.HERE, 12.97, 77.6,
                    app.doorprints.shared.trace.PlaceCheckResult(app.doorprints.shared.trace.PlaceCheckStatus.IMPRECISE, false, null, emptyList()), emptyList(), 70.0,
                ),
                {}, {}, {},
            )
        }
    }

    /**
     * Offline maps (docs/11 5.20): Settings' section with one saved area and one still saving, and the *Save this area
     * for offline* dialog's body with its estimate and the mobile-data note, over a fake store (the real one is
     * MapLibre's; a dialog window is not captured, so the body is shown as content).
     */
    @Test fun offlineMaps() = shoot("offline_maps", readyText = "Koramangala") {
        val fake = object : OfflineMapsServices {
            override val supported = true
            override val areas = MutableStateFlow(
                listOf(
                    OfflineArea("a", "Indiranagar", GeoBounds(12.96, 77.63, 12.985, 77.655), OfflineAreaState.SAVING, 2_450_000),
                    OfflineArea("b", "Koramangala", GeoBounds(12.92, 77.6, 12.95, 77.64), OfflineAreaState.READY, 18_200_000),
                ),
            )
            override fun save(name: String, bounds: GeoBounds) = Unit
            override fun delete(id: String) = Unit
            override fun networkMetered(): Boolean = true
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OfflineMapsSection(fake)
            HorizontalDivider()
            SaveAreaDialogContent(
                bounds = GeoBounds(12.96, 77.63, 12.985, 77.655), metered = true, name = "Indiranagar", onName = {},
            )
        }
    }
    /** *Share updates with…* (docs/11 5.28): two names, one shared to before, the counts and the switches. */
    @Test fun shareUpdates() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            val priya = repo.settings.addShareContact("Priya")!!
            repo.settings.markShared(priya.id, 1_759_900_000_000)
            repo.settings.addShareContact("Amma")
        }
        shoot("share_updates", readyText = "Priya") { ShareUpdatesScreen(onBack = {}) }
    }
    /**
     * Brokers (docs/11 5.25, slice 1b): Settings > Brokers with two brokers (one with a house, stars and an agency), and
     * under it one broker's page with its fields and its houses. One shot for both, to keep the image set small.
     */
    @Test fun brokers() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        val ravi = Broker(
            name = "Ravi Kumar", phone = "+91 98400 11111", agency = "Adyar Homes", feeTerms = "15 days' rent, once",
            notes = "Replies fast; shows keys on weekends", rating = 4,
        )
        val raviId = runBlocking {
            val id = repo.saveBroker(ravi)
            repo.saveHouse(repo.getHouse("a")!!.copy(brokerId = id))
            repo.saveBroker(Broker(name = "Meena Iyer", agency = "Beach Road Realty"))
            id
        }
        shoot("brokers", readyText = "Meena Iyer") {
            val brokers by repo.observeBrokers().collectAsState(initial = null)
            val houses by repo.brokerHouses(raviId).collectAsState(initial = emptyList())
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrokerList(brokers, mapOf(raviId to houses.size), onOpenBroker = {}, onAdd = {})
                HorizontalDivider()
                BrokerForm(raviId, ravi, houses, onDone = {}, onOpenHouse = {})
            }
        }
    }
    /**
     * The house form's Rooms section (docs/11 5.6, slice 1c) with two rooms, sized in feet with the areas and the total:
     * English in both themes and Hindi light only, to keep the image set small; on a screen tall enough for both cards.
     */
    @Test fun houseRooms() {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        RuntimeEnvironment.setQualifiers("+h1400dp")
        val rooms = listOf(
            HouseRoom("r1", "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch near the window", 0),
            HouseRoom("r2", "KITCHEN", null, 300, 244, null, null, 1),
        )
        shoot("house_rooms", STATIC) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { RoomsSection(rooms, LengthUnit.FT) {} }
        }
    }
    /**
     * Settings > Criteria (docs/11 5.4, slice 2) with water a High must-have of at least 4, noise archived, a custom
     * criterion and the rating share at 25 %: English in both themes and Hindi light only, to keep the image set small.
     */
    @Test fun criteria() {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            repo.saveCriterion(Criterion("water", weight = 3, mustHave = true, minScore = 4, sort = 0))
            repo.saveCriterion(Criterion("noise", sort = 5, archived = true))
            repo.addCriterion("Pets allowed")
            repo.saveRatingShare(0.25)
        }
        shoot("criteria", readyText = "Pets allowed") { CriteriaEditor() }
    }
    /**
     * Settings > Questions (docs/11 5.5, slice 3a): the bank seeded in the shot's language with the first defaults of
     * Money on screen: English in both themes and Hindi light only, to keep the image set small.
     */
    @Test fun questions() {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking { repo.seedQuestions(lang) }
        shoot("questions", readyText = DefaultQuestions.ALL.first().question(lang).text) { QuestionsEditor() }
    }
    /**
     * Settings > Viewings (docs/11 5.8, slice 3b-1): an upcoming, a missed and a done viewing at a fixed clock, English
     * in both themes (the dark one since Wave D) and Hindi light only, to keep the image set small.
     */
    @Test fun viewings() {
        assumeTrue(lang == "en" || (lang == "hi" && !dark))
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        val now = 1_760_100_000_000
        runBlocking {
            repo.saveViewing(Viewing("v_00000001", "a", now + 26 * 3_600_000L, kind = "SECOND", withWhom = "Ravi", notes = "Ask for the water bill."))
            repo.saveViewing(Viewing("v_00000002", "b", now - 20 * 3_600_000L))
            repo.saveViewing(Viewing("v_00000003", "a", now - 72 * 3_600_000L, status = "DONE", visitId = "x"))
        }
        shoot("viewings", readyText = "Ask for the water bill.") {
            Column(Modifier.verticalScroll(rememberScrollState())) { ViewingsHistory(null, onOpenViewing = {}, onPlan = { _, _ -> }, nowMs = now) }
        }
    }
    /**
     * Settings > My areas (docs/11 slice 4a): two areas (one with the wake-up off) and their notes, one on an area and
     * one on a street, English and Hindi light only, to keep the image set small.
     */
    @Test fun areas() {
        assumeTrue(!dark && (lang == "en" || lang == "hi"))
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking {
            repo.saveArea(Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500))
            repo.saveArea(Area("a_5b6c7d8e", "Indiranagar 2nd stage", 12.9784, 77.6408, 1200, enabled = false))
            repo.saveAreaNote(AreaNote("n_11223344", areaId = "a_1f2e3d4c", text = "Water tanker every morning; the low streets flood in the monsoon."))
            repo.saveAreaNote(AreaNote("n_55667788", street = "MG Road", text = "Noisy after 9 pm: the bus depot is on the corner."))
        }
        shoot("areas", readyText = "Noisy after 9 pm") { Column(Modifier.verticalScroll(rememberScrollState())) { AreasEditor(onOpenArea = {}) } }
    }
    @Test fun assistant() = shoot("assistant", readyText = text(Res.string.common_try_again)) { AssistantScreen(onOpenHouse = {}) }
    @Test fun export() = shoot("export", readyText = countHouses(2)) { ExportScreen(onBack = {}) }
    @Test fun import() = shoot("import", STATIC) { ImportScreen(onBack = {}) }

    /**
     * The screens that hide what the iPhone app does not have yet ([PlatformFeatures.Ios]), as the iOS shell provides
     * it: since CMP-8c the Map's chrome without the Hunt card (the map view itself is an empty box here, as in a
     * preview: MapLibre cannot run under Robolectric, which is also why Android has no Map shot), Settings without the
     * language choice, the copies, the weekly backup and Hunt mode (and with the iPhone's privacy note), a house with no
     * photos without its Photos section; the empty list, Compare's empty state and the Assistant's "off" state as on
     * Android, pointing to the map. Android provides nothing, so the screens above are unchanged.
     */
    private fun shootIos(screen: String, readyText: String?, content: @Composable () -> Unit) = shoot("ios_$screen", readyText) {
        CompositionLocalProvider(LocalPlatformFeatures provides PlatformFeatures.Ios, content = content)
    }

    @Test fun iosMap() = shootIos("map", STATIC) {
        CompositionLocalProvider(LocalInspectionMode provides true) { MapScreen(onOpenHouse = {}, onNewHouse = { _, _ -> }) }
    }
    @Test fun iosSettings() = shootIos("settings", STATIC) { SettingsScreen() }
    @Test fun iosHousesEmpty() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking { listOf("a", "b").forEach { repo.deleteHouse(it) } }
        shootIos("houses_empty", readyText = text(Res.string.houses_empty)) { HouseListScreen(onOpenHouse = {}) }
    }
    // One house: fewer than Compare needs, so its empty state, without "Add a house on the map".
    @Test fun iosCompareEmpty() {
        val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
        runBlocking { repo.deleteHouse("b") }
        shootIos("compare_empty", readyText = text(Res.string.compare_empty)) {
            val houses by LocalAppServices.current.repository.houses.collectAsState(initial = null)
            val counts by LocalAppServices.current.repository.visitCounts.collectAsState(initial = emptyList())
            CompareScreen(houses, counts, onOpenHouse = {})
        }
    }
    // The Assistant with AI off (as in the Android shot): Try again, without "Go to the map".
    @Test fun iosAssistant() = shootIos("assistant", readyText = text(Res.string.common_try_again)) { AssistantScreen(onOpenHouse = {}) }
    // The form's last band, from Contact to the end: the listing and Visits with no Photos section between them (the
    // house has no photos). The bands above it are the same as Android's.
    @Test fun iosHouseEdit() = shootForm("ios_house_edit", readyText = "Green View 2BHK", only = setOf("contact")) {
        CompositionLocalProvider(LocalPlatformFeatures provides PlatformFeatures.Ios) {
            HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {})
        }
    }

    /** Export's count of houses as it reads in the shot's language (two houses are saved in [setUp]). */
    private fun countHouses(n: Int): String = runBlocking { getPluralString(Res.plurals.count_houses, n, n) }

    companion object {
        /** No stored data on this screen (or its defaults are the shot): nothing to wait for but the stable frame. */
        private val STATIC: String? = null

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun params(): List<Array<Any>> = listOf("en", "hi", "ta", "te").flatMap { l -> listOf(false, true).map { arrayOf<Any>(l, it) } }
    }
}
