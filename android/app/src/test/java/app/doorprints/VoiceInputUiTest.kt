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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiPreset
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.ui.AiSettingsSection
import app.doorprints.ui.AppServices
import app.doorprints.ui.AssistantScreen
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.PasteListingDialog
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.VOICE_SETTING_TAG
import app.doorprints.ui.VoiceMicButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Voice input on the screens (S4b-BL-222, docs/03 ADR-37, TC-U-253): the *Voice input* setting and the microphone in Ask a
 * question, Plan notes and Fill in from listing text, in English on the real app with a fake microphone, a fake permission
 * prompt and a fake repository for the AI state (no provider is called, no cost). What is proved here: the setting is off by
 * default and the microphone is not drawn (nor touched) while it is off; only the own key on Gemini, OpenAI, Groq or
 * OpenRouter gets one and the others read why; the permission is asked only after a tap and a rationale, and a refusal leaves
 * the field usable with a way to the settings; the words join the box and nothing is asked or extracted on its own; and the
 * microphone is let go when the screen pauses, is left, or the setting is turned off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class VoiceInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val real = app.container.repository
    private val job = SupervisorJob()
    private val file = File.createTempFile("voice-ui", ".preferences_pb").also { it.delete() }
    private val seal: (String) -> String = { "v1:" + it.reversed() }
    private val open: (String?) -> String? = { s -> s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.reversed() }
    private val store = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file },
        KeystoreSecretStore(seal, open = open),
        geminiSecrets = KeystoreSecretStore(seal, KeystoreSecretStore.GEMINI_ENTRY, open),
    )
    private val repo = FakeVoiceRepository(real, store)
    private val voice = FakeVoiceServices()

    @After fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        file.delete()
    }

    private fun provide(fontScale: Float? = null, content: @Composable () -> Unit) {
        compose.setContent {
            ProvideAppServices {
                val services = LocalAppServices.current
                val wrapped = object : AppServices by services {
                    override val repository: Repository = repo
                    override val voice = this@VoiceInputUiTest.voice
                }
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppServices provides wrapped,
                    LocalDensity provides (fontScale?.let { Density(density.density, it) } ?: density),
                    content = content,
                )
            }
        }
    }

    private fun setUp(config: AiProviderConfig = AiProviderConfig.GEMINI, on: Boolean = true) = runBlocking {
        store.saveAiFeatures(true)
        store.saveAiProviderConfig(config, "test-key-1234567")
        store.saveAiProvider(AiProviderChoice.DEVICE)
        store.saveVoiceInput(on)
    }

    private fun box(text: String = "Which flat") = provide {
        var value by remember { mutableStateOf(text) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(value, { value = it }, label = { Text("Box") }, modifier = Modifier.fillMaxWidth())
            VoiceMicButton(value, { value = it }, "house", max = 1000)
        }
    }

    private fun boxText(): String = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun present(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(5_000) { present(text) }

    private fun tapSpeak() = compose.onNodeWithContentDescription("Speak instead of typing").performClick()

    private fun inLive(text: String, mode: LiveRegionMode) = compose.onNode(
        hasText(text, substring = true) and hasAnyAncestor(
            SemanticsMatcher("live region $mode") { SemanticsProperties.LiveRegion in it.config && it.config[SemanticsProperties.LiveRegion] == mode },
        ),
    ).assertExists()

    // --- off means off --------------------------------------------------------------------------------------------

    @Test fun withTheSettingOffThereIsNoMicrophoneButtonAndNothingIsTouched() {
        setUp(on = false)
        box()
        compose.waitForIdle()
        assertFalse(present("Speak"))
        assertEquals(0, compose.onAllNodesWithContentDescription("Speak instead of typing").fetchSemanticsNodes().size)
        assertTrue(voice.recorders.isEmpty())
        assertEquals(0, voice.requests)
    }

    @Test fun theSettingIsOffByDefault() {
        runBlocking { assertFalse(store.voiceChoice.first().enabled) }
    }

    @Test fun withAiFeaturesOffOrNoOwnKeyOrNoRecorderThereIsNoMicrophone() {
        setUp()
        repo.on.value = false
        box()
        compose.waitForIdle()
        assertFalse(present("Speak"))
        repo.on.value = true
        waitFor("Speak")
        repo.own.value = false // the server answers AI
        compose.waitUntil(5_000) { !present("Speak") }
        repo.own.value = true
        waitFor("Speak")
    }

    @Test fun aPhoneThatCannotRecordDrawsNoMicrophone() {
        setUp()
        val none = FakeVoiceServices(recordable = false)
        compose.setContent {
            ProvideAppServices {
                val services = LocalAppServices.current
                CompositionLocalProvider(LocalAppServices provides object : AppServices by services {
                    override val repository: Repository = repo
                    override val voice = none
                }) { VoiceMicButton("", {}, "house") }
            }
        }
        compose.waitForIdle()
        assertFalse(present("Speak"))
        assertTrue(none.recorders.isEmpty())
    }

    @Test fun onlyGeminiOpenAiGroqAndOpenRouterGetAMicrophone() {
        setUp()
        box()
        waitFor("Speak")
        for (config in listOf(
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OPENAI.baseUrl, "m"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.GROQ.baseUrl, "m"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OPENROUTER.baseUrl, "m"),
        )) {
            runBlocking { store.saveAiProviderConfig(config, "test-key-1234567") }
            waitFor("Speak")
            waitFor(config.baseUrl.substringAfter("://").substringBefore('/'))
        }
        for (config in listOf(
            AiProviderConfig(AiKind.ANTHROPIC, AiPreset.ANTHROPIC.baseUrl, "claude"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OLLAMA.baseUrl, "m"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "https://llm.example.com/v1", "m"),
        )) {
            runBlocking { store.saveAiProviderConfig(config, "test-key-1234567") }
            compose.waitUntil(5_000) { !present("Speak") }
            runBlocking { store.saveAiProviderConfig(AiProviderConfig.GEMINI, "test-key-1234567") }
            waitFor("Speak")
        }
    }

    // --- the permission ----------------------------------------------------------------------------------------------

    @Test fun thePermissionIsNotAskedUntilATapAndARationale() {
        setUp()
        box()
        waitFor("Speak")
        assertEquals(0, voice.requests)
        assertTrue(voice.recorders.isEmpty())
        tapSpeak()
        waitFor("Use the microphone?")
        assertEquals(0, voice.requests)
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(1, voice.requests)
    }

    @Test fun notNowAsksNothingAndOpensNothing() {
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Use the microphone?")
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertEquals(0, voice.requests)
        assertTrue(voice.recorders.isEmpty())
        assertFalse(present("Use the microphone?"))
    }

    @Test fun aRefusalLeavesTheFieldUsableAndOffersTheSettings() {
        voice.grantOnRequest = false
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Use the microphone?")
        compose.onNodeWithText("Continue").performClick()
        waitFor("cannot use the microphone")
        assertTrue(voice.recorders.isEmpty())
        compose.onNodeWithText("Open settings").assertExists()
        inLive("cannot use the microphone", LiveRegionMode.Assertive)
        compose.onNode(hasSetTextAction()).performTextReplacement("Which flat under 30k")
        assertEquals("Which flat under 30k", boxText())
    }

    @Test fun withThePermissionAlreadyGrantedATapRecordsAtOnce() {
        voice.granted = true
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        assertEquals(0, voice.requests)
        assertEquals(1, voice.recorders.single().started)
    }

    // --- a turn ----------------------------------------------------------------------------------------------------------

    @Test fun speakThenStopPutsTheWordsAfterTheTextAndAnnouncesTheStates() {
        voice.granted = true
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        inLive("Recording.", LiveRegionMode.Polite)
        compose.onNodeWithContentDescription("Stop recording and write out the words").performClick()
        compose.waitUntil(5_000) { boxText().endsWith("two bhk near Baner") }
        assertEquals("Which flat two bhk near Baner", boxText())
        waitFor("Done. The words are in the box.")
        inLive("Done. The words are in the box.", LiveRegionMode.Polite)
        assertEquals(1, voice.recorders.single().stopped)
        assertEquals("house", voice.kinds.single())
        assertEquals(1, voice.clips.size)
    }

    @Test fun cancelThrowsTheRecordingAwayAndTheBoxIsUntouched() {
        voice.granted = true
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        compose.onNodeWithContentDescription("Cancel and throw the recording away").performClick()
        waitFor("Cancelled. Nothing was kept.")
        assertEquals("Which flat", boxText())
        assertEquals(1, voice.recorders.single().released)
        assertEquals(0, voice.recorders.single().stopped)
        assertTrue(voice.clips.isEmpty())
    }

    @Test fun nothingHeardIsSaidAndTheBoxIsUntouched() {
        voice.granted = true
        voice.words = "   "
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        compose.onNodeWithContentDescription("Stop recording and write out the words").performClick()
        waitFor("Nothing was heard")
        assertEquals("Which flat", boxText())
    }

    @Test fun aMicrophoneThatCannotStartIsToldAndReleased() {
        voice.granted = true
        voice.failOnStart = VoiceFailure.NO_DEVICE
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("No microphone was found")
        assertEquals(1, voice.recorders.single().released)
    }

    // --- foreground only and every exit lets the microphone go -------------------------------------------------------

    @Test fun pausingTheScreenWhileRecordingLetsTheMicrophoneGoAndKeepsNothing() {
        voice.granted = true
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // home, the lock screen, another app, a call
        compose.waitForIdle()
        assertEquals(1, voice.recorders.single().released)
        assertTrue(voice.clips.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Speak")
        assertFalse(present("Recording."))
        assertEquals("Which flat", boxText())
    }

    @Test fun turningTheSettingOffWhileRecordingLetsTheMicrophoneGo() {
        voice.granted = true
        setUp()
        box()
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        runBlocking { store.saveVoiceInput(false) }
        compose.waitUntil(5_000) { !present("Recording.") && !present("Speak") }
        assertEquals(1, voice.recorders.single().released)
        assertTrue(voice.clips.isEmpty())
    }

    @Test fun leavingTheScreenWhileRecordingLetsTheMicrophoneGo() {
        voice.granted = true
        setUp()
        var shown by mutableStateOf(true)
        provide { if (shown) VoiceMicButton("", {}, "house") else Text("Elsewhere") }
        waitFor("Speak")
        tapSpeak()
        waitFor("Recording.")
        compose.runOnIdle { shown = false }
        compose.waitUntil(5_000) { present("Elsewhere") }
        assertEquals(1, voice.recorders.single().released)
    }

    // --- the three places ---------------------------------------------------------------------------------------------------

    @Test fun askAddsTheWordsAndAsksNothingUntilAskIsPressed() {
        voice.granted = true
        setUp()
        provide { AssistantScreen(onOpenHouse = {}) }
        waitFor("Speak")
        compose.onNode(hasSetTextAction()).performTextInput("Cheapest 2 BHK")
        tapSpeak()
        waitFor("Recording.")
        compose.onNodeWithContentDescription("Stop recording and write out the words").performClick()
        compose.waitUntil(5_000) { boxText().endsWith("two bhk near Baner") }
        assertEquals("Cheapest 2 BHK two bhk near Baner", boxText())
        assertTrue("nothing is asked on its own", repo.asked.isEmpty())
        compose.onNode(hasText("Ask") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performScrollTo().performClick()
        compose.waitUntil(5_000) { repo.asked.isNotEmpty() }
        assertEquals(listOf("Cheapest 2 BHK two bhk near Baner"), repo.asked)
    }

    @Test fun planNotesHasTheMicrophoneToo() {
        voice.granted = true
        setUp()
        provide { AssistantScreen(onOpenHouse = {}) }
        waitFor("Speak")
        compose.onNode(hasText("Plan visits") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.waitUntil(5_000) { present("Speak") }
        tapSpeak()
        waitFor("Recording.")
        compose.onNodeWithContentDescription("Stop recording and write out the words").performClick()
        compose.waitUntil(5_000) { boxText().contains("two bhk near Baner") }
        assertTrue(repo.asked.isEmpty())
    }

    @Test fun fillInFromListingTextAddsTheWordsAfterThePastedTextAndExtractsNothing() {
        voice.granted = true
        setUp()
        provide { PasteListingDialog(onDismiss = {}, onDraft = { _, _ -> }) }
        waitFor("Speak")
        compose.onNode(hasSetTextAction()).performTextInput("2 BHK Baner, 28000.")
        tapSpeak()
        waitFor("Recording.")
        compose.onNodeWithContentDescription("Stop recording and write out the words").performClick()
        compose.waitUntil(5_000) { boxText().endsWith("two bhk near Baner") }
        assertEquals("2 BHK Baner, 28000. two bhk near Baner", boxText())
        assertTrue("nothing is read on its own", repo.extracted.isEmpty())
    }

    // --- the setting -----------------------------------------------------------------------------------------------------

    private fun settingsSection() = provide {
        val s by store.settings.collectAsState(AppSettings())
        val off by real.aiOff.collectAsState()
        Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(s, off) }
    }

    @Test fun theSwitchIsOffByDefaultShowsTheDisclosureAndTurnsTheSettingOn() {
        setUp(on = false)
        settingsSection()
        waitFor("Voice input")
        waitFor("Audio goes from this phone straight to generativelanguage.googleapis.com, about INR 0.17 a minute")
        waitFor("Anything you say, including numbers and names, is sent as audio")
        waitFor("is not saved on this phone")
        runBlocking { assertFalse(store.voiceChoice.first().enabled) }
        compose.onNodeWithText("Voice input (a microphone button for the AI boxes)").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { store.voiceChoice.first().enabled } }
        compose.onNodeWithText("Voice input (a microphone button for the AI boxes)").performClick()
        compose.waitUntil(5_000) { runBlocking { !store.voiceChoice.first().enabled } }
    }

    @Test fun theDisclosureNamesTheHostAndThePriceOfTheServiceChosen() {
        setUp(AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.GROQ.baseUrl, "m"), on = false)
        settingsSection()
        waitFor("straight to api.groq.com, about INR 0.06 a minute")
    }

    @Test fun anAnthropicOrLocalOrCustomServiceGetsTheReasonAndNoSwitch() {
        setUp(on = false)
        settingsSection()
        waitFor("Voice input (a microphone button")
        for (config in listOf(
            AiProviderConfig(AiKind.ANTHROPIC, AiPreset.ANTHROPIC.baseUrl, "claude"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OLLAMA.baseUrl, "m"),
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "https://llm.example.com/v1", "m"),
        )) {
            runBlocking { store.saveAiProviderConfig(config, "test-key-1234567") }
            waitFor("Voice input needs your own AI key for a service that takes audio")
            assertFalse(present("Voice input (a microphone button"))
            runBlocking { store.saveAiProviderConfig(AiProviderConfig.GEMINI, "test-key-1234567") }
            waitFor("Voice input (a microphone button")
        }
    }

    @Test fun theServersAiGetsTheReasonAndNoSwitch() {
        runBlocking {
            store.saveAiFeatures(true)
            store.saveAiProvider(AiProviderChoice.SERVER)
            store.saveServer("https://example.org", "server-key-123456")
        }
        repo.own.value = false
        settingsSection()
        waitFor("Voice input needs your own AI key for a service that takes audio")
        assertFalse(present("Voice input (a microphone button"))
    }

    @Test fun aPlatformWithoutVoiceDrawsNoSetting() {
        setUp()
        val none = FakeVoiceServices(supported = false)
        compose.setContent {
            ProvideAppServices {
                val services = LocalAppServices.current
                CompositionLocalProvider(LocalAppServices provides object : AppServices by services {
                    override val repository: Repository = repo
                    override val voice = none
                }) { Column { AiSettingsSection(AppSettings(aiFeatures = true), null) } }
            }
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Voice input", substring = true).fetchSemanticsNodes().size)
    }

    // --- TalkBack and large text -------------------------------------------------------------------------------------------

    @Test fun theButtonIsLabelledByItsVisibleWordAndIs48dpHighAtOneHundredThirtyPercentText() {
        voice.granted = true
        setUp()
        provide(fontScale = 1.3f) {
            Column(Modifier.fillMaxWidth()) { VoiceMicButton("", {}, "house") }
        }
        waitFor("Speak")
        // The spoken name starts with the visible word (WCAG 2.5.3), and the target is 48 dp tall at least.
        compose.onNode(hasContentDescription("Speak instead of typing")).assertHeightIsAtLeast(48.dp)
        tapSpeak()
        waitFor("Recording.")
        compose.onNode(hasContentDescription("Stop recording and write out the words")).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("Cancel and throw the recording away")).assertHeightIsAtLeast(48.dp)
    }
}
