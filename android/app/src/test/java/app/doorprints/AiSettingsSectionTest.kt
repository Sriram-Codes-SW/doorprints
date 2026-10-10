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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.AiQuality
import app.doorprints.shared.api.ApiException
import app.doorprints.ui.AI_QUALITY_TAG
import app.doorprints.ui.AiDisclosure
import app.doorprints.ui.AiSettingsSection
import app.doorprints.ui.AppServices
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings > AI features > *Use my own AI on this phone* (S4b-BL-150, docs/03 §13.2, ADR-35, TC-U-171), in English on the
 * real repository, but for the two calls that would leave the phone ([FakeAiRepository.testAiProvider] and
 * `testGeminiKey`): what each service prefills, what Custom may edit, what is refused and read out, what Save keeps,
 * what Test says per failure, that Remove clears, that Gemini works as before, that a saved key stays with its service,
 * and that the disclosure names the host.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class AiSettingsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val real = app.container.repository

    // Robolectric has no Android Keystore: the settings of these tests are a store of their own with a stand-in cipher
    // (as SettingsUpgradeTest does), which the fake repository hands to the screen and saves into.
    private val job = SupervisorJob()
    private val file = File.createTempFile("ai-settings", ".preferences_pb").also { it.delete() }
    private val seal: (String) -> String = { "v1:" + it.reversed() }
    private val open: (String?) -> String? = { s -> s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.reversed() }
    private val store = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file },
        KeystoreSecretStore(seal, open = open),
        geminiSecrets = KeystoreSecretStore(seal, KeystoreSecretStore.GEMINI_ENTRY, open),
    )
    private val fake = FakeAiRepository(real, store)

    @After fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        file.delete()
    }

    private fun settings(): AppSettings = runBlocking { store.settings.first() }

    private fun provide(content: @Composable () -> Unit) {
        compose.setContent {
            ProvideAppServices {
                val services = LocalAppServices.current
                val wrapped = object : AppServices by services { override val repository: Repository = fake }
                CompositionLocalProvider(LocalAppServices provides wrapped, content = content)
            }
        }
    }

    private fun show() {
        runBlocking {
            store.saveAiFeatures(true)
            store.saveAiProvider(AiProviderChoice.DEVICE)
        }
        provide {
            val s by store.settings.collectAsState(AppSettings())
            val off by real.aiOff.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(s, off) }
        }
        waitFor("AI service")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String) =
        assertTrue("'$text' is on screen", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())

    private fun choose(service: String) {
        compose.onNodeWithContentDescription("AI service").performScrollTo().performClick()
        compose.onNodeWithText(service).performClick()
        compose.waitForIdle()
    }

    private fun field(label: String) = hasText(label, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private fun valueOf(label: String): String =
        compose.onNode(field(label)).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun type(label: String, text: String) {
        compose.onNode(field(label)).performScrollTo().performTextInput(text)
    }

    private fun press(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun waitSettings(done: (AppSettings) -> Boolean) = compose.waitUntil(5_000) { done(settings()) }

    private fun inLiveRegion(text: String, mode: LiveRegionMode? = null) = compose.onNode(
        hasText(text, substring = true) and hasAnyAncestor(
            SemanticsMatcher("live region $mode") {
                SemanticsProperties.LiveRegion in it.config && (mode == null || it.config[SemanticsProperties.LiveRegion] == mode)
            },
        ),
    ).assertExists()

    private fun compat(url: String, model: String) = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, url, model)

    // --- the services -----------------------------------------------------------------------------------------

    @Test fun geminiIsTheDefaultWithTodaysFieldsAndNeitherAddressNorModel() {
        show()
        waitFor("Gemini API key")
        waitFor("Save key")
        gone("Base URL")
        gone("Model")
        waitFor("generativelanguage.googleapis.com")
    }

    @Test fun eachServicePrefillsItsAddressAndLeavesTheModelEmpty() {
        show()
        listOf(
            "OpenAI" to "https://api.openai.com/v1",
            "OpenRouter" to "https://openrouter.ai/api/v1",
            "Groq" to "https://api.groq.com/openai/v1",
            "Ollama (on this device)" to "http://localhost:11434/v1",
            "LM Studio (on this device)" to "http://localhost:1234/v1",
            "Anthropic" to "https://api.anthropic.com",
            "Custom (OpenAI-compatible)" to "",
        ).forEach { (name, url) ->
            choose(name)
            assertEquals(name, url, valueOf("Base URL"))
            assertEquals(name, "", valueOf("Model"))
        }
    }

    @Test fun onlyCustomLetsTheAddressBeTyped() {
        show()
        choose("OpenAI")
        compose.onNode(field("Base URL")).assert(!hasSetTextAction())
        choose("Anthropic")
        compose.onNode(field("Base URL")).assert(!hasSetTextAction())
        choose("Custom (OpenAI-compatible)")
        compose.onNode(field("Base URL")).assert(hasSetTextAction())
    }

    @Test fun theDisclosureNamesTheHostOfWhatIsOnTheScreen() {
        show()
        choose("OpenAI")
        waitFor("sent from this phone straight to api.openai.com with your own key")
        choose("Anthropic")
        waitFor("sent from this phone straight to api.anthropic.com with your own key")
        choose("Ollama (on this device)")
        waitFor("straight to localhost with your own key")
        choose("Custom (OpenAI-compatible)")
        gone("straight to")
        type("Base URL", "https://LLM.Example.org:8443/v1")
        waitFor("straight to llm.example.org with your own key")
    }

    // --- what is refused --------------------------------------------------------------------------------------

    @Test fun anAddressThatIsNotHttpsIsRefusedWithItsReasonAndTheHintAndIsReadOut() {
        show()
        choose("Custom (OpenAI-compatible)")
        type("Base URL", "http://192.168.1.20:11434/v1")
        type("Model", "llama3.1")
        type("API key", "sk-test")
        press("Save")
        waitFor("http:// works only for this device (localhost)")
        inLiveRegion("http:// works only for this device (localhost)")
        waitFor("Use https, or run it on this device.")
        assertEquals(AiProviderConfig.GEMINI, settings().aiProviderConfig)
    }

    @Test fun anEmptyAddressAndAMissingModelAndKeyAreEachSaid() {
        show()
        choose("Custom (OpenAI-compatible)")
        press("Save")
        inLiveRegion("Enter the service address.")
        inLiveRegion("Enter the model name first.")
        inLiveRegion("Paste your API key first.")
        assertEquals(AiProviderConfig.GEMINI, settings().aiProviderConfig)
    }

    @Test fun anAnEmulatorAddressIsAcceptedOnAndroid() {
        show()
        choose("Custom (OpenAI-compatible)")
        type("Base URL", "http://10.0.2.2:11434/v1")
        type("Model", "llama3.1")
        press("Save")
        waitSettings { it.aiProviderConfig.baseUrl == "http://10.0.2.2:11434/v1" }
        gone("works only for this device")
        waitFor("straight to 10.0.2.2")
    }

    @Test fun aLocalServiceNeedsNoKeyAHostedOneDoes() {
        show()
        choose("Ollama (on this device)")
        type("Model", "llama3.1")
        waitFor("usually needs no key")
        press("Save")
        waitSettings { it.aiProviderConfig.model == "llama3.1" }
        choose("Groq")
        type("Model", "llama-3.3-70b-versatile")
        press("Save")
        inLiveRegion("Paste your API key first.")
    }

    // --- Save, Test, Remove -----------------------------------------------------------------------------------

    @Test fun savePersistsTheKindTheAddressTheModelAndTheKeyAndClearsTheField() {
        show()
        choose("OpenAI")
        type("Model", " gpt-4o-mini ")
        type("API key", "sk-test-key-1234")
        press("Save")
        waitSettings { it.aiProviderConfig.kind == AiKind.OPENAI_COMPATIBLE }
        val s = settings()
        assertEquals(compat("https://api.openai.com/v1", "gpt-4o-mini"), s.aiProviderConfig)
        assertEquals("sk-test-key-1234", s.geminiKey)
        assertEquals(AiProviderChoice.DEVICE, s.aiProvider)
        waitFor("Saved on this phone")
        assertEquals("", valueOf("API key"))
        waitFor("A key ending in 1234 is saved")
    }

    @Test fun anthropicNeedsAModelAndAKeyAndSavesKindAnthropicWithItsAddress() {
        show()
        choose("Anthropic")
        press("Save")
        inLiveRegion("Enter the model name first.")
        inLiveRegion("Paste your API key first.")
        assertEquals(AiProviderConfig.GEMINI, settings().aiProviderConfig)
        type("Model", "my-model")
        type("API key", "test-key-not-real")
        press("Save")
        waitSettings { it.aiProviderConfig.kind == AiKind.ANTHROPIC }
        val s = settings()
        assertEquals(AiProviderConfig(AiKind.ANTHROPIC, "https://api.anthropic.com", "my-model"), s.aiProviderConfig)
        assertEquals("test-key-not-real", s.geminiKey)
        waitFor("A key ending in real is saved")
    }

    @Test fun aKeySavedForAnthropicIsNotOfferedToAnotherService() {
        runBlocking { store.saveAiProviderConfig(AiProviderConfig(AiKind.ANTHROPIC, "https://api.anthropic.com", "my-model"), "test-key-not-real") }
        show()
        waitFor("A key ending in real is saved")
        choose("OpenAI")
        gone("A key ending in")
        type("Model", "gpt-4o-mini")
        press("Save")
        inLiveRegion("Paste your API key first.")
    }

    @Test fun aSuccessfulTestSaysWhichHostAcceptedTheKeyOrAnsweredWithoutOne() {
        show()
        choose("OpenAI")
        type("Model", "gpt-4o-mini")
        type("API key", "sk-typed-key")
        press("Test")
        waitFor("api.openai.com accepted this key.")
        assertEquals(compat("https://api.openai.com/v1", "gpt-4o-mini") to "sk-typed-key", fake.tested.single())
        assertEquals(AiProviderConfig.GEMINI, settings().aiProviderConfig)
        choose("Ollama (on this device)")
        type("Model", "llama3.1")
        press("Test")
        waitFor("localhost answered with these settings.")
        assertEquals("", fake.tested.last().second)
    }

    private fun failWith(error: Throwable, expected: String) {
        fake.testResult = Result.failure(error)
        press("Test")
        waitFor(expected)
        inLiveRegion(expected, LiveRegionMode.Assertive)
    }

    @Test fun eachFailureOfATestIsNamedAndAnnouncedAtOnce() {
        show()
        choose("OpenAI")
        type("Model", "gpt-4o-mini")
        type("API key", "sk-typed-key")
        failWith(ApiException(ApiException.Kind.AI_KEY_REJECTED, 401), "api.openai.com did not accept your key.")
        failWith(ApiException(ApiException.Kind.AI_MODEL_NOT_FOUND, 404), "The AI service does not know this model.")
        failWith(ApiException(ApiException.Kind.AI_BLOCKED, 200), "The AI service declined this text. Nothing was changed. Edit the wording and try again.")
        failWith(ApiException(ApiException.Kind.RATE_LIMITED, 429, retryAfterSeconds = 12), "Try again in 12 s.")
        failWith(ApiException(ApiException.Kind.AI_UNAVAILABLE, 0), "Could not reach api.openai.com. Check the address")
        failWith(ApiException(ApiException.Kind.AI_UNAVAILABLE, 503), "The AI provider is unavailable")
        choose("Ollama (on this device)")
        type("Model", "llama3.1")
        failWith(ApiException(ApiException.Kind.AI_UNAVAILABLE, 0), "Could not reach localhost. Check that it is running")
    }

    @Test fun removeKeyClearsTheKeyAndTheKindAddressAndModelAndTheFieldsStartAgain() {
        show()
        choose("OpenAI")
        type("Model", "gpt-4o-mini")
        type("API key", "sk-test-key-1234")
        press("Save")
        waitSettings { it.aiProviderConfig.kind == AiKind.OPENAI_COMPATIBLE }
        press("Remove key")
        waitSettings { it.geminiKey.isEmpty() }
        val s = settings()
        assertEquals(AiProviderConfig.GEMINI, s.aiProviderConfig)
        assertEquals("", s.geminiKey)
        assertEquals("https://api.openai.com/v1", valueOf("Base URL"))
        assertEquals("", valueOf("Model"))
        gone("Remove key")
    }

    // --- Gemini as before -------------------------------------------------------------------------------------

    @Test fun geminiTestsTheKeyBeforeSavingItAndWritesKindGemini() {
        runBlocking { store.saveAiProviderConfig(compat("http://localhost:11434/v1", "llama3.1"), "") }
        show()
        choose("Google Gemini")
        type("Gemini API key", "AIzaOwnKeyForTests1234")
        fake.geminiResult = Result.failure(ApiException(ApiException.Kind.AI_KEY_REJECTED, 400))
        press("Save key")
        waitFor("Google did not accept this key.")
        assertEquals("", settings().geminiKey)
        fake.geminiResult = Result.success(Unit)
        press("Save key")
        waitSettings { it.geminiKey == "AIzaOwnKeyForTests1234" }
        assertEquals(AiProviderConfig.GEMINI, settings().aiProviderConfig)
        waitFor("Google accepted this key.")
        waitFor("A key ending in 1234 is saved")
        press("Test key")
        assertEquals(listOf("AIzaOwnKeyForTests1234", "AIzaOwnKeyForTests1234", "AIzaOwnKeyForTests1234"), fake.geminiTested)
    }

    // --- a key stays with its service -------------------------------------------------------------------------

    @Test fun aSavedKeyIsNotOfferedToAnotherServiceAndIsNotSentThere() {
        runBlocking { store.saveAiProviderConfig(compat("https://api.openai.com/v1", "gpt-4o-mini"), "sk-openai-9876") }
        show()
        waitFor("A key ending in 9876 is saved")
        choose("Groq")
        gone("A key ending in")
        type("Model", "llama-3.3-70b-versatile")
        press("Test")
        inLiveRegion("Paste your API key first.")
        assertTrue("nothing was sent", fake.tested.isEmpty())
        choose("OpenAI")
        waitFor("A key ending in 9876 is saved")
        press("Test")
        waitFor("api.openai.com accepted this key.")
        assertEquals("sk-openai-9876", fake.tested.single().second)
    }

    // --- the disclosure on the screens that send text ----------------------------------------------------------

    @Test fun theDisclosureOnTheScreensNamesTheSavedHostAndTheServerSentenceWhenTheServerAnswers() {
        runBlocking { store.saveAiProviderConfig(compat("https://openrouter.ai/api/v1", "m"), "sk-or-1") }
        provide { AiDisclosure(androidx.compose.material3.LocalTextStyle.current, androidx.compose.ui.graphics.Color.Unspecified) }
        waitFor("sent from this phone straight to openrouter.ai with your own key")
        runBlocking {
            store.saveServer("https://sync.example", "test-server-key-12345")
            store.saveAiProvider(AiProviderChoice.SERVER)
        }
        waitFor("the AI provider set up on your server")
        assertFalse(compose.onAllNodesWithText("straight to", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // --- AI speed and cost (S4b-BL-198 step 2, TC-U-193) -------------------------------------------------------

    private fun group() = compose.onNodeWithTag(AI_QUALITY_TAG)

    private fun groupIsComposed() = compose.onAllNodesWithTag(AI_QUALITY_TAG).fetchSemanticsNodes().isNotEmpty()

    @Test fun theGroupIsShownForGeminiWithTheWebsitesThreeChoicesAndHelpAndQualityChosen() {
        show()
        group().performScrollTo().assertExists()
        waitFor("AI speed and cost")
        waitFor("The model's own default setting. Nothing is changed.")
        waitFor("Thinks a medium amount: between Quality and Economy on speed and cost.")
        waitFor("About 40 percent cheaper and about twice as fast in our tests, with answers as good.")
        compose.onNodeWithText("Quality").assertIsSelected()
        compose.onNodeWithText("Balanced").assertIsNotSelected()
        compose.onNodeWithText("Economy").assertIsNotSelected()
    }

    @Test fun theGroupIsAHeadingedRadioGroupOfThreeRowsInReadingOrderEachAtLeast48dpHigh() {
        show()
        compose.onNode(hasText("AI speed and cost") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        val radios = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasAnyAncestor(hasTestTag(AI_QUALITY_TAG))
        val rows = compose.onAllNodes(radios)
        rows.assertCountEquals(3)
        assertEquals(
            listOf("Quality", "Balanced", "Economy"),
            rows.fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text },
        )
        for (i in 0 until 3) rows[i].performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and hasAnyAncestor(hasTestTag(AI_QUALITY_TAG))).assertExists()
    }

    @Test fun choosingARowKeepsItAtOnceWithoutSave() {
        show()
        press("Economy")
        waitSettings { it.aiQuality == AiQuality.ECONOMY }
        compose.onNodeWithText("Economy").assertIsSelected()
        compose.onNodeWithText("Quality").assertIsNotSelected()
        press("Balanced")
        waitSettings { it.aiQuality == AiQuality.BALANCED }
        press("Quality")
        waitSettings { it.aiQuality == AiQuality.QUALITY }
        compose.onNodeWithText("Quality").assertIsSelected()
    }

    @Test fun aSavedChoiceShowsOnOpening() {
        runBlocking { store.saveAiQuality(AiQuality.BALANCED) }
        show()
        waitFor("AI speed and cost")
        group().performScrollTo()
        compose.onNodeWithText("Balanced").assertIsSelected()
        compose.onNodeWithText("Quality").assertIsNotSelected()
    }

    @Test fun theGroupIsNotComposedForAnotherServiceAndIsBackWithItsChoiceOnReturningToGemini() {
        runBlocking { store.saveAiQuality(AiQuality.ECONOMY) }
        show()
        compose.onNodeWithText("Economy").assertIsSelected()
        for (service in listOf("OpenAI", "Anthropic", "Ollama (on this device)", "Custom (OpenAI-compatible)")) {
            choose(service)
            assertFalse("the group is composed for $service", groupIsComposed())
            gone("AI speed and cost")
        }
        // Kept while hidden, ignored by every other service, and shown again for Gemini.
        assertEquals(AiQuality.ECONOMY, settings().aiQuality)
        choose("Google Gemini")
        group().performScrollTo().assertExists()
        compose.onNodeWithText("Economy").assertIsSelected()
    }

    @Test fun theGroupIsNotComposedWithAiFeaturesOffOrWithTheServerAnswering() {
        runBlocking { store.saveAiQuality(AiQuality.ECONOMY) }
        // AI features off: the whole own-AI part is hidden, the choice is kept.
        runBlocking {
            store.saveAiFeatures(false)
            store.saveAiProvider(AiProviderChoice.DEVICE)
        }
        provide {
            val s by store.settings.collectAsState(AppSettings())
            val off by real.aiOff.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) { AiSettingsSection(s, off) }
        }
        waitFor("AI features")
        assertFalse(groupIsComposed())
        assertEquals(AiQuality.ECONOMY, settings().aiQuality)
        // Features on and a server connected that answers: the own AI is not the one answering, so no group.
        runBlocking {
            store.saveAiFeatures(true)
            store.saveServer("https://sync.example", "test-server-key-12345")
            store.saveAiProvider(AiProviderChoice.SERVER)
        }
        waitFor("Use my server")
        assertFalse(groupIsComposed())
        gone("AI speed and cost")
        // Choosing the own AI brings it back with the kept choice.
        press("Use my own AI on this phone")
        waitSettings { it.aiProvider == AiProviderChoice.DEVICE }
        waitFor("AI speed and cost")
        group().performScrollTo().assertExists()
        compose.onNodeWithText("Economy").assertIsSelected()
    }

    @Test fun removeKeyForgetsTheChoice() {
        runBlocking { store.saveGeminiKey("AIzaOwnKeyForTests1234") }
        show()
        press("Economy")
        waitSettings { it.aiQuality == AiQuality.ECONOMY }
        press("Remove key")
        waitSettings { it.geminiKey.isEmpty() }
        assertEquals(AiQuality.QUALITY, settings().aiQuality)
        compose.onNodeWithText("Quality").assertIsSelected()
    }

    // --- targets ----------------------------------------------------------------------------------------------

    @Test fun theButtonsAreAtLeast48dpHigh() {
        show()
        choose("OpenAI")
        listOf("Save", "Test").forEach { compose.onNodeWithText(it).performScrollTo().assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithContentDescription("AI service").assertHeightIsAtLeast(48.dp)
        compose.onNode(hasContentDescription("Show the API key")).performScrollTo().assertHeightIsAtLeast(48.dp)
    }
}

/** The real repository, except that nothing leaves the phone (the two tests of a key answer as the test says) and the settings are the test's own. */
class FakeAiRepository(real: Repository, private val store: SettingsStore) : Repository by real {
    override val settings: SettingsStore get() = store

    override suspend fun saveAiProviderConfig(config: AiProviderConfig, key: String) = store.saveAiProviderConfig(config, key)
    override suspend fun saveGeminiKey(key: String) = store.saveGeminiKey(key)
    override suspend fun removeGeminiKey() = store.removeGeminiKey()
    override suspend fun setAiProvider(choice: AiProviderChoice) { store.saveAiProvider(choice) }
    override suspend fun setAiQuality(quality: AiQuality) { store.saveAiQuality(quality) }

    var testResult: Result<Unit> = Result.success(Unit)
    var geminiResult: Result<Unit> = Result.success(Unit)
    val tested = mutableListOf<Pair<AiProviderConfig, String>>()
    val geminiTested = mutableListOf<String>()

    override suspend fun testAiProvider(config: AiProviderConfig, key: String): Result<Unit> {
        tested += config to key
        return testResult
    }

    override suspend fun testGeminiKey(key: String): Result<Unit> {
        geminiTested += key
        return geminiResult
    }
}
