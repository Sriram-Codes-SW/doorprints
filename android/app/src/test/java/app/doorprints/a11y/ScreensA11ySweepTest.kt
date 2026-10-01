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

package app.doorprints.a11y

import android.Manifest
import android.app.KeyguardManager
import android.os.LocaleList
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.DoorprintsApp
import app.doorprints.data.AppSettings
import app.doorprints.data.HouseEntity
import app.doorprints.i18n.AppLocale
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.Viewing
import app.doorprints.ui.AreasEditor
import app.doorprints.ui.AssistantScreen
import app.doorprints.ui.BrokerForm
import app.doorprints.ui.BrokerList
import app.doorprints.ui.CompareScreen
import app.doorprints.ui.CriteriaEditor
import app.doorprints.ui.DoorprintsTheme
import app.doorprints.ui.ExportScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.HuntRemindersSection
import app.doorprints.ui.ImportScreen
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LockScreenContent
import app.doorprints.ui.MapScreen
import app.doorprints.ui.PathTraceSection
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.QuestionsEditor
import app.doorprints.ui.SettingsScreen
import app.doorprints.ui.ShareUpdatesScreen
import app.doorprints.ui.ViewingsHistory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
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
 * The accessibility sweep (Wave D, docs/06 TC-U-A11Y): the main screens of the phone app, with sample data, in English
 * and Tamil (the longest words of the four languages) at a font scale of 200 %, each checked by [A11yAudit] (a name
 * for every control, 48 dp targets, described images, no clipped text, no tofu). A finding fails the test with the
 * list of what to fix. The screens are the shared Compose ones, so the iPhone app has the same semantics. The window is
 * a phone's width and tall, so that lazy lists compose their rows at 200 % too (width is what clips text).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-hdpi", application = ScreenshotTestApp::class)
class ScreensA11ySweepTest(private val lang: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo get() = app.container.repository
    private val timeZone: TimeZone = TimeZone.getDefault()
    private val locales: LocaleList = LocaleList.getDefault()

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        RuntimeEnvironment.setQualifiers("+$lang")
        AppLocale.applyDefault(app)
        runBlocking {
            repo.saveHouse(house("a", "Green View 2BHK", HouseStatus.SHORTLISTED, 28_000))
            repo.saveHouse(house("b", "Lake Road flat", HouseStatus.TAKEN, 22_500))
        }
    }

    @After fun tearDown() {
        TimeZone.setDefault(timeZone)
        LocaleList.setDefault(locales)
    }

    private fun house(id: String, label: String, status: HouseStatus, price: Long) = HouseEntity(
        id = id, label = label, lat = 12.9716, lon = 77.5946, status = status, price = price, bedrooms = 2,
        locality = "Indiranagar", contactPhone = "+91 98400 11111", checklist = mapOf("water" to 4), createdAt = 1_760_000_000_000,
        updatedAt = 1_760_000_000_000,
    )

    /** Shows [content] at 200 % text, waits for [ready] (text the loaded screen shows) and runs [A11yAudit]. */
    private fun sweep(screen: String, ready: String? = null, content: @Composable () -> Unit) {
        compose.setContent {
            ProvideAppServices {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, FONT_SCALE)) {
                    DoorprintsTheme(dark = false) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
                }
            }
        }
        if (ready != null) {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(ready, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
        val found = A11yAudit.violations(compose, lang)
        assertTrue("$screen ($lang, 200 %):\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun houses() = sweep("houses", "Green View") { HouseListScreen(onOpenHouse = {}) }

    @Test fun houseForm() = sweep("house form", "Green View") {
        HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {})
    }

    @Test fun newHouseForm() = sweep("new house form") {
        HouseEditScreen(houseId = null, newLat = 12.9716, newLon = 77.5946, visitId = null, onDone = {})
    }

    @Test fun compare() = sweep("compare", "Green View") {
        val repo = LocalAppServices.current.repository
        val houses by repo.houses.collectAsState(initial = null)
        val counts by repo.visitCounts.collectAsState(initial = emptyList())
        CompareScreen(houses, counts, onOpenHouse = {})
    }

    @Test fun export() = sweep("export") { ExportScreen(onBack = {}) }

    @Test fun import() = sweep("import") { ImportScreen(onBack = {}) }

    @Test fun settings() = sweep("settings") { SettingsScreen() }

    @Test fun brokers() {
        val ravi = Broker(name = "Ravi Kumar", phone = "+91 98400 11111", agency = "Adyar Homes", rating = 4)
        val id = runBlocking {
            val id = repo.saveBroker(ravi)
            repo.saveHouse(repo.getHouse("a")!!.copy(brokerId = id))
            id
        }
        sweep("brokers", "Ravi Kumar") {
            val brokers by repo.observeBrokers().collectAsState(initial = null)
            val houses by repo.brokerHouses(id).collectAsState(initial = emptyList())
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BrokerList(brokers, mapOf(id to houses.size), onOpenBroker = {}, onAdd = {})
                BrokerForm(id, ravi, houses, onDone = {}, onOpenHouse = {})
            }
        }
    }

    @Test fun viewings() {
        val now = 1_760_100_000_000
        runBlocking {
            repo.saveViewing(Viewing("v_00000001", "a", now + 26 * 3_600_000L, kind = "SECOND", withWhom = "Ravi"))
            repo.saveViewing(Viewing("v_00000002", "b", now - 20 * 3_600_000L))
        }
        sweep("viewings", "Green View") {
            Column(Modifier.verticalScroll(rememberScrollState())) { ViewingsHistory(null, onOpenViewing = {}, onPlan = { _, _ -> }, nowMs = now) }
        }
    }

    @Test fun areas() {
        runBlocking {
            repo.saveArea(Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500))
            repo.saveAreaNote(AreaNote("n_11223344", areaId = "a_1f2e3d4c", text = "Water tanker every morning."))
            repo.saveAreaNote(AreaNote("n_55667788", street = "MG Road", text = "Noisy after 9 pm."))
        }
        sweep("areas", "Adyar") { Column(Modifier.verticalScroll(rememberScrollState())) { AreasEditor(onOpenArea = {}) } }
    }

    @Test fun questions() {
        runBlocking { repo.seedQuestions(lang) }
        sweep("questions") { Column(Modifier.verticalScroll(rememberScrollState())) { QuestionsEditor() } }
    }

    @Test fun criteria() = sweep("criteria") { Column(Modifier.verticalScroll(rememberScrollState())) { CriteriaEditor() } }

    /** Hunt mode: the Map's chrome with its Hunt card (the map itself is an empty box under Robolectric), and its settings. */
    @Test fun hunt() = sweep("hunt") {
        CompositionLocalProvider(LocalInspectionMode provides true) { MapScreen(onOpenHouse = {}, onNewHouse = { _, _ -> }) }
    }

    @Test fun huntSettings() = sweep("hunt settings") {
        val settings by repo.settings.settings.collectAsState(AppSettings())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HuntRemindersSection()
            PathTraceSection(settings)
        }
    }

    @Test fun lockScreen() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        sweep("lock screen") { LockScreenContent(failed = true, onUnlock = {}, modifier = Modifier.fillMaxWidth()) }
    }

    @Test fun assistant() = sweep("assistant") { AssistantScreen(onOpenHouse = {}) }

    @Test fun shareUpdates() {
        runBlocking { repo.settings.addShareContact("Priya") }
        sweep("share updates", "Priya") { ShareUpdatesScreen(onBack = {}) }
    }

    companion object {
        /** WCAG 1.4.4: text resized to 200 % without loss of content. */
        const val FONT_SCALE = 2f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf("en", "ta").map { arrayOf<Any>(it) }
    }
}
