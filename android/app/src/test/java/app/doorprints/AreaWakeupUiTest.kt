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

package app.doorprints

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.AppServices
import app.doorprints.ui.AreaFormScreen
import app.doorprints.ui.AreaWakeupFlow
import app.doorprints.ui.AreaWakeupRationale
import app.doorprints.ui.AreaWakeupServices
import app.doorprints.ui.AreaWakeupStep
import app.doorprints.ui.AreasEditor
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalPlatformFeatures
import app.doorprints.ui.PlatformFeatures
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The area wake-up's screens (docs/11 "Design of slice 4b", 5.18), in English on the real repository with a fake
 * [AreaWakeupServices] for Play services and the permission prompts: *Wake me in my hunting areas* hidden without Play
 * services (and where the platform has no wake-up), the rationale's steps (Continue asks precise location, then
 * background location; Not now leaves the switch off; the state after returning with and without the grant), the
 * "switched off because…" card shown once, the area form's new hint, and the new strings in all four languages.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class AreaWakeupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val settings = app.container.repository.settings

    /** Play services and the two prompts, as the test decides. */
    private inner class FakeWakeup(
        override val available: Boolean = true,
        override val iphoneWording: Boolean = false,
    ) : AreaWakeupServices {
        var background = false
        val asked = mutableListOf<String>()
        /** What the background request does: answer at once (the Android 10 dialog) or nothing (a settings page). */
        var onBackground: (answer: () -> Unit) -> Unit = { answer -> answer() }
        var onForeground: (answer: () -> Unit) -> Unit = { answer -> answer() }

        override fun backgroundGranted(): Boolean = background
        override fun resumed() {}

        @Composable
        override fun rememberBackgroundLocationRequest(onResult: () -> Unit): () -> Unit = {
            asked += "background"
            onBackground(onResult)
        }

        @Composable
        override fun rememberForegroundLocationRequest(onResult: () -> Unit): () -> Unit = {
            asked += "foreground"
            onForeground(onResult)
        }
    }

    @Before fun setUp() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun tearDown() {
        runBlocking {
            settings.setAreaWakeup(false)
            settings.clearAreaWakeupOffNotice()
        }
    }

    @Composable
    private fun WithWakeup(fake: AreaWakeupServices, features: PlatformFeatures = PlatformFeatures(), content: @Composable () -> Unit) {
        ProvideAppServices {
            val real = LocalAppServices.current
            val wrapped = remember(real, fake) { object : AppServices by real { override val areaWakeup: AreaWakeupServices = fake } }
            CompositionLocalProvider(LocalAppServices provides wrapped, LocalPlatformFeatures provides features) { content() }
        }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun absent(text: String) =
        assertTrue(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())

    private fun wakeupOn() = runBlocking { settings.areaWakeup().first() }

    private val switchText = "Wake me in my hunting areas"
    private val stillOff = "Area wake-up is still off"

    // ---- the setting ----

    @Test
    fun theSettingIsHiddenWithoutPlayServicesAndWhereThePlatformHasNoWakeup() {
        var variant by mutableIntStateOf(0)
        compose.setContent {
            when (variant) {
                0 -> WithWakeup(FakeWakeup(available = false)) { AreasEditor(onOpenArea = {}) }
                1 -> WithWakeup(FakeWakeup(), PlatformFeatures(areaWakeup = false)) { AreasEditor(onOpenArea = {}) }
                else -> WithWakeup(FakeWakeup()) { AreasEditor(onOpenArea = {}) }
            }
        }
        waitFor("Add area")
        absent(switchText)
        variant = 1
        compose.waitForIdle()
        waitFor("Add area")
        absent(switchText)
        variant = 2
        waitFor(switchText)
        waitFor("within a few minutes")
    }

    @Test
    fun turningTheSwitchOnOpensTheRationaleAndOffIsAtOnce() {
        var opened = 0
        compose.setContent { WithWakeup(FakeWakeup()) { AreasEditor(onOpenArea = {}, onTurnOnWakeup = { opened++ }) } }
        waitFor(switchText)
        compose.onNodeWithText(switchText).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
        assertFalse(wakeupOn())
        runBlocking { settings.setAreaWakeup(true) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(switchText).assertIsOn() }.isSuccess }
        compose.onNodeWithText(switchText).performClick()
        compose.waitUntil(5_000) { !wakeupOn() }
        compose.onNodeWithText(switchText).assertIsOff()
    }

    // ---- the rationale ----

    @Test
    fun theRationaleSaysWhyWhatBatteryHowToStopAndWhichOptionWithEqualButtons() {
        compose.setContent { WithWakeup(FakeWakeup()) { AreaWakeupRationale(onDone = {}) } }
        waitFor("Why: so Doorprints notices")
        waitFor("Google Play services compares")
        waitFor("keeps no location history")
        waitFor("Battery: small")
        waitFor("To stop it")
        waitFor("Choose Allow all the time")
        val cont = compose.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot
        val notNow = compose.onNodeWithText("Not now").fetchSemanticsNode().boundsInRoot
        assertEquals(cont.height, notNow.height, 0.5f)
    }

    /** The iPhone's rationale (S4b-BL-96) names iOS and its two prompts instead of Play services and Android. */
    @Test
    fun theIphoneRationaleNamesIosAndItsTwoPrompts() {
        compose.setContent { WithWakeup(FakeWakeup(iphoneWording = true)) { AreaWakeupRationale(onDone = {}) } }
        waitFor("iOS compares where the iPhone is")
        waitFor("Choose Allow While Using App first, then Change to Always Allow")
        waitFor("Battery: small")
        absent("Google Play services")
        absent("Android")
    }

    @Test
    fun continueAsksForPreciseLocationFirstThenBackgroundAndTurnsItOn() {
        val fake = FakeWakeup()
        fake.onForeground = { answer ->
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            answer()
        }
        fake.onBackground = { answer ->
            fake.background = true
            answer()
        }
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(5_000) { done }
        assertEquals(listOf("foreground", "background"), fake.asked)
        assertTrue(wakeupOn())
    }

    @Test
    fun withPreciseLocationAlreadyContinueAsksOnlyForBackground() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fake = FakeWakeup()
        fake.onBackground = { answer -> fake.background = true; answer() }
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(5_000) { done }
        assertEquals(listOf("background"), fake.asked)
        assertTrue(wakeupOn())
    }

    @Test
    fun notNowLeavesTheSwitchOffAndAsksNothing() {
        val fake = FakeWakeup()
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Not now")
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntil(5_000) { done }
        assertTrue(fake.asked.isEmpty())
        assertFalse(wakeupOn())
    }

    @Test
    fun refusingPreciseLocationLeavesItOffWithALineAndAsksNoBackground() {
        val fake = FakeWakeup()
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        waitFor(stillOff)
        assertEquals(listOf("foreground"), fake.asked)
        assertFalse(done)
        assertFalse(wakeupOn())
    }

    @Test
    fun returningFromTheSettingsPageWithoutTheGrantLeavesItOffWithALine() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fake = FakeWakeup()
        // Android 11+: the settings page opens; no answer comes back, only the return (a resume).
        fake.onBackground = { }
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        absent(stillOff)
        leaveAndReturn()
        waitFor(stillOff)
        assertFalse(done)
        assertFalse(wakeupOn())
    }

    @Test
    fun returningFromTheSettingsPageWithTheGrantTurnsItOn() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fake = FakeWakeup()
        fake.onBackground = { }
        var done = false
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = { done = true }) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        // The person picked *Allow all the time* on the page and came back.
        fake.background = true
        leaveAndReturn()
        compose.waitUntil(5_000) { done }
        assertTrue(wakeupOn())
    }

    @Test
    fun theDialogRefusedLeavesItOffWithALine() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fake = FakeWakeup()
        compose.setContent { WithWakeup(fake) { AreaWakeupRationale(onDone = {}) } }
        waitFor("Continue")
        compose.onNodeWithText("Continue").performClick()
        waitFor(stillOff)
        assertFalse(wakeupOn())
    }

    @Test
    fun theFlowRules() {
        assertEquals(AreaWakeupStep.FOREGROUND, AreaWakeupFlow.onContinue(precise = false, backgroundGranted = false))
        assertEquals(AreaWakeupStep.BACKGROUND, AreaWakeupFlow.onContinue(precise = true, backgroundGranted = false))
        assertNull(AreaWakeupFlow.onContinue(precise = true, backgroundGranted = true))
        assertTrue(AreaWakeupFlow.keepOn(precise = true, backgroundGranted = true))
        assertFalse(AreaWakeupFlow.keepOn(precise = true, backgroundGranted = false))
        assertFalse(AreaWakeupFlow.keepOn(precise = false, backgroundGranted = true))
    }

    // ---- the card ----

    @Test
    fun theSwitchedOffCardIsShownOnce() {
        runBlocking {
            settings.setAreaWakeup(true)
            assertTrue(settings.switchAreaWakeupOffForPermission())
        }
        var round by mutableIntStateOf(0)
        compose.setContent {
            // A new composition each round, as opening My areas again.
            if (round % 2 == 0) WithWakeup(FakeWakeup()) { AreasEditor(onOpenArea = {}) }
        }
        val card = "Area wake-up is off because Doorprints no longer has location access all the time."
        waitFor(card)
        compose.waitUntil(5_000) { !runBlocking { settings.areaWakeupOffNotice().first() } }
        compose.onNodeWithText(switchText).assertIsOff()
        round = 1
        compose.waitForIdle()
        round = 2
        waitFor(switchText)
        absent(card)
    }

    @Test
    fun theAreaFormHintNamesTheSetting() {
        compose.setContent { WithWakeup(FakeWakeup()) { AreaFormScreen(null, onDone = {}) } }
        waitFor("Used by Wake me in my hunting areas.")
        absent("later update")
    }

    // ---- the strings ----

    @Test
    fun theNewStringsAreInAllFourLanguages() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        fun keys(file: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.getElementsByTagName("string")
            return (0 until nodes.length).associate { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent } }
        }
        val ui = listOf(
            "area_wakeup_switch", "area_wakeup_hint", "area_wakeup_why", "area_wakeup_what", "area_wakeup_battery",
            "area_wakeup_how_off", "area_wakeup_pick", "area_wakeup_continue", "area_wakeup_not_now", "area_wakeup_still_off",
            "area_wakeup_off_card", "area_wake_hint",
            // The iPhone's (S4b-BL-96).
            "area_wakeup_what_ios", "area_wakeup_pick_ios", "notif_area_wakeup",
        )
        val android = listOf("notif_channel_area_wakeup", "notif_channel_area_wakeup_desc", "notif_area_wakeup")
        val english = keys(File(root, "ui/src/commonMain/composeResources/values/strings.xml"))
        for (lang in listOf("", "-hi", "-ta", "-te")) {
            val c = keys(File(root, "ui/src/commonMain/composeResources/values$lang/strings.xml"))
            val a = keys(File(root, "app/src/main/res/values$lang/strings.xml"))
            for (k in ui) assertTrue("$lang $k", !c[k].isNullOrBlank())
            for (k in android) assertTrue("$lang $k", !a[k].isNullOrBlank())
            assertTrue(lang, a.getValue("notif_area_wakeup").contains("%1\$s"))
            if (lang.isNotEmpty()) for (k in ui) assertTrue("$lang $k is translated", c[k] != english[k])
        }
    }

    /** The person leaves for a system page and comes back: the activity pauses and stops, then resumes. */
    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }
}
