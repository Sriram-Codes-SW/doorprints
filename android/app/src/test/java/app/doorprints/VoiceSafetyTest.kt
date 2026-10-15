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
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.data.KeystoreSecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiPreset
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.voice.OwnKeyTranscription
import app.doorprints.shared.voice.PcmSource
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.RecordedClip
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorderException
import app.doorprints.shared.voice.VoicePhase
import app.doorprints.shared.voice.VoiceSession
import app.doorprints.shared.voice.VoiceStatus
import app.doorprints.voice.AndroidVoiceServices
import app.doorprints.voice.AudioRecordPcmSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TC-S-52 (docs/06), the safety proof of voice input on Android (S4b-BL-222, docs/02 T-I45..T-I48, T-T20, T-D14, AB-17;
 * docs/03 ADR-37), the twin of the website's TC-S-51. A whole spoken turn runs through the real recorder, session,
 * own-key transcription and settings store, with a fake microphone whose samples are a recognisable pattern and a stubbed
 * provider whose answer is a recognisable sentence. Then:
 *  - the only request goes to the provider's host, the key is in a header and the audio is in the body (so the pattern is
 *    searched for in the body, which proves the search works);
 *  - no file appears in the app's data, cache, no-backup or external folders or the temp folder, and no file, database,
 *    preference or DataStore file holds the audio, its base64 or the words; the settings file is byte-for-byte unchanged;
 *  - nothing reaches the log or the console;
 *  - the sources of the voice code write no file, preference, log line or share, and no export, backup, sync, Drive, Room or
 *    crypto source reads the voice code or its switch; `AppSettings` has no voice field;
 *  - the manifest asks for `RECORD_AUDIO` as a runtime permission in one place only, the microphone is not a required
 *    feature, Android's cloud backup is off, and device transfer takes the database and the photos and nothing else;
 *  - the own-key path is the only road: with the setting off, *AI features* off, the server's AI, a provider that takes no
 *    audio or no key, no request is made.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class VoiceSafetyTest {
    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val job = SupervisorJob()
    private val dir = File(app.filesDir, "datastore").also { it.mkdirs() }
    private val settingsFile = File(dir, "voice-safety.preferences_pb")
    private val seal: (String) -> String = { "v1:" + it.reversed() }
    private val open: (String?) -> String? = { s -> s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.reversed() }
    private val store = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { settingsFile },
        KeystoreSecretStore(seal, open = open),
        geminiSecrets = KeystoreSecretStore(seal, KeystoreSecretStore.GEMINI_ENTRY, open),
    )

    @After fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        settingsFile.delete()
    }

    private val magic = "DPVOICE!"
    private val words = "WORDS-SAFETY-MARKER nine eight four five zero"
    private val key = "sk-voice-safety-test-0001"

    /** What no file may hold: the raw samples, their base64 at the three alignments, and the words. */
    private val needles: List<ByteArray> = buildList {
        add((magic + magic).encodeToByteArray())
        for (shift in 0..2) {
            val text = java.util.Base64.getEncoder().encodeToString(magic.repeat(8).encodeToByteArray().copyOfRange(shift, 8 * 8))
            add(text.substring(8, 32).encodeToByteArray())
        }
        add("WORDS-SAFETY-MARKER".encodeToByteArray())
    }

    private class MagicMic(private val pattern: ByteArray) : PcmSource {
        @Volatile var opened = 0
        @Volatile var closed = 0
        @Volatile var given = 0
        override fun open() { opened++ }
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(10)
            if (given >= 20) return 0
            given++
            val n = minOf(3_200, length)
            for (i in 0 until n) into[offset + i] = pattern[i % pattern.size]
            return n
        }
        override fun close() { closed++ }
    }

    private class Provider {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<ByteArray>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            bodies += request.body.toByteArray()
            respond("""{"text":"WORDS-SAFETY-MARKER nine eight four five zero"}""", HttpStatusCode.OK, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        })
    }

    private fun configure(config: AiProviderConfig = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OPENAI.baseUrl, "chat-model"), voice: Boolean = true, features: Boolean = true, provider: AiProviderChoice = AiProviderChoice.DEVICE, apiKey: String = key) =
        runBlocking {
            store.saveAiProviderConfig(config, apiKey)
            store.saveAiFeatures(features)
            store.saveAiProvider(provider)
            store.saveVoiceInput(voice)
        }

    // --- the whole turn ----------------------------------------------------------------------------------------------------

    /** Every file under the folders the app can write to, by path, and the temp folder's files newer than [since]. */
    private fun roots(): List<File> = listOfNotNull(app.dataDir, app.externalCacheDir, app.getExternalFilesDir(null), app.cacheDir, app.codeCacheDir)

    private fun filesUnder(root: File): List<File> = if (root.exists()) root.walkTopDown().filter { it.isFile }.toList() else emptyList()

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun aWholeSpokenTurnLeavesNoTraceAndMakesOneRequestToTheProvider() {
        configure()
        val before = roots().flatMap(::filesUnder).map { it.absolutePath }.toSet()
        val settingsBefore = settingsFile.readBytes()
        val tmp = File(System.getProperty("java.io.tmpdir")!!)
        val since = System.currentTimeMillis() - 1_000
        ShadowLog.clear()
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))

        val mic = MagicMic((magic).encodeToByteArray())
        val provider = Provider()
        val heard = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = VoiceSession(
            scope = scope,
            newRecorder = { PcmVoiceRecorder(mic, Dispatchers.IO) },
            permitted = { true },
            transcribe = { clip -> OwnKeyTranscription(store, provider.client).transcribe(clip, "house", "hi") },
            onWords = { heard += it; false },
        )
        try {
            session.start()
            runBlocking { withTimeout(10_000) { session.ui.first { it.phase == VoicePhase.RECORDING } } }
            runBlocking { withTimeout(10_000) { while (mic.given < 5) kotlinx.coroutines.delay(20) } }
            session.stop()
            runBlocking { withTimeout(10_000) { session.ui.first { it.status == VoiceStatus.DONE } } }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        // The words reached the box callback and nothing else; the microphone was opened once and let go.
        assertEquals(listOf(words), heard)
        assertEquals(1, mic.opened)
        assertTrue(mic.closed >= 1)

        // One request, to the provider, the key in a header, the audio in the body.
        val request = provider.requests.single()
        assertEquals("api.openai.com", request.url.host)
        assertTrue(request.url.encodedPath.endsWith("/audio/transcriptions"))
        assertEquals("Bearer $key", request.headers[HttpHeaders.Authorization])
        assertFalse(request.url.toString().contains(key))
        val body = provider.bodies.single()
        assertTrue("the search works: the samples are in the request", contains(body, needles[0]))
        assertTrue(contains(body, "RIFF".encodeToByteArray()))
        assertFalse("the key is not in the body", contains(body, key.encodeToByteArray()))

        // No new file anywhere the app can write, and none holds the audio, its base64 or the words.
        val after = roots().flatMap(::filesUnder)
        assertEquals("no file was written", emptySet<String>(), after.map { it.absolutePath }.toSet() - before)
        for (f in after) if (f.length() < 20_000_000) {
            val bytes = f.readBytes()
            for (n in needles) assertFalse("${f.name} holds ${n.decodeToString()}", contains(bytes, n))
        }
        assertTrue("the settings file is unchanged", settingsBefore.contentEquals(settingsFile.readBytes()))
        val strays = tmp.walkTopDown().maxDepth(2).filter { it.isFile && it.lastModified() >= since && it.length() < 20_000_000 }.toList()
        for (f in strays) if (f != settingsFile) for (n in needles) assertFalse("temp file ${f.name}", contains(f.readBytes(), n))

        // Nothing in the log or on the console.
        for (entry in ShadowLog.getLogs()) {
            val line = entry.tag + " " + entry.msg + " " + (entry.throwable?.stackTraceToString() ?: "")
            for (n in needles) assertFalse("log: ${entry.msg}", line.contains(n.decodeToString()))
        }
        for (n in needles) assertFalse("console", out.toString().contains(n.decodeToString()))
        assertFalse(out.toString().contains(key))
    }

    @Test fun theSettingWritesOnlyItsOwnSwitchAndRemovesItWhenOff() {
        runBlocking {
            store.saveVoiceInput(true)
            assertTrue(store.voiceChoice.first().enabled)
            store.saveVoiceInput(false)
            assertFalse(store.voiceChoice.first().enabled)
        }
        assertFalse("off removes the key from the file", settingsFile.readBytes().decodeToString().contains("voice"))
    }

    // --- the own-key path is the only road -------------------------------------------------------------------------------------

    private fun refused(name: String, setup: () -> Unit) {
        setup()
        val provider = Provider()
        val clip = RecordedClip(ByteArray(2_000) { 1 }, "audio/wav", 1_000)
        try {
            runBlocking { OwnKeyTranscription(store, provider.client).transcribe(clip, "house", "en") }
            fail("$name: the call was not refused")
        } catch (e: ApiException) {
            assertEquals(name, ApiException.Kind.AI_OFF, e.kind)
        }
        assertTrue("$name: a request was made", provider.requests.isEmpty())
    }

    @Test fun withoutTheSwitchTheOwnAiTheKeyOrAnAudioProviderNothingIsSent() {
        refused("voice off") { configure(voice = false) }
        refused("AI features off") { configure(features = false) }
        refused("the server answers") { configure(provider = AiProviderChoice.SERVER) }
        refused("anthropic") { configure(AiProviderConfig(AiKind.ANTHROPIC, AiPreset.ANTHROPIC.baseUrl, "claude")) }
        refused("ollama") { configure(AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OLLAMA.baseUrl, "m"), apiKey = "") }
        refused("lm studio") { configure(AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.LM_STUDIO.baseUrl, "m"), apiKey = "") }
        refused("custom address") { configure(AiProviderConfig(AiKind.OPENAI_COMPATIBLE, "https://llm.example.com/v1", "m")) }
        refused("no key") { configure(AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.GROQ.baseUrl, "m"), apiKey = "") }
    }

    @Test fun eachSpeechServiceIsCalledAtItsOwnHostWithTheSavedKeyOnly() {
        val cases = mapOf(
            AiProviderConfig.GEMINI to "generativelanguage.googleapis.com",
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OPENAI.baseUrl, "m") to "api.openai.com",
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.GROQ.baseUrl, "m") to "api.groq.com",
            AiProviderConfig(AiKind.OPENAI_COMPATIBLE, AiPreset.OPENROUTER.baseUrl, "m") to "openrouter.ai",
        )
        for ((config, host) in cases) {
            configure(config)
            val provider = Provider()
            val clip = RecordedClip(ByteArray(2_000) { 1 }, "audio/wav", 1_000)
            // The stub answers in OpenAI's shape, so Gemini's adapter calls it an unusable answer: only the request matters here.
            runCatching { runBlocking { OwnKeyTranscription(store, provider.client).transcribe(clip, "house", "en") } }
            val request = provider.requests.single()
            assertEquals(host, request.url.host)
            val sent = request.headers[HttpHeaders.Authorization] ?: request.headers["x-goog-api-key"]
            assertTrue(sent!!.endsWith(key))
        }
    }

    // --- what the sources do ------------------------------------------------------------------------------------------------

    private fun root(): File {
        var d = File("").absoluteFile
        while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d
    }

    /** Code only: the comments are where the text names these things. */
    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private fun kotlinFiles(path: String): List<File> {
        val f = File(root(), path)
        assertTrue("$path does not exist: the test would be vacuous", f.exists())
        return if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else listOf(f)
    }

    private val shared = "shared/src/commonMain/kotlin/app/doorprints"
    private val appMain = "app/src/main/java/app/doorprints"
    private val uiMain = "ui/src/commonMain/kotlin/app/doorprints/ui"

    private fun voiceSources(): List<File> =
        kotlinFiles("$shared/shared/voice") + kotlinFiles("$appMain/voice") + File(root(), uiMain).listFiles { f -> f.name.startsWith("Voice") && f.extension == "kt" }!!.toList()

    @Test fun theVoiceCodeWritesNoFilePreferenceLogShareOrRow() {
        val sources = voiceSources()
        assertTrue("the scan must read the voice sources (${sources.size})", sources.size >= 11)
        val forbidden = listOf(
            "java.io.File", "File(", "FileOutputStream", "FileInputStream", "createTempFile", "cacheDir", "filesDir", "noBackupFilesDir",
            "externalFilesDir", "getExternal", "openFileOutput", "MediaRecorder(", "setOutputFile", "OutputFormat", "SharedPreferences",
            "getSharedPreferences", "DataStore", "dataStore", "androidx.room", "Dao", "Entity", "Log.", "Logger", "println", "print(",
            "Timber", "Intent", "FileProvider", "ContentResolver", "ZipOutputStream", "ACTION_SEND", "sendBeacon", "Base64",
        )
        for (f in sources) for (token in forbidden) assertFalse("${f.relativeTo(root())} uses `$token`: audio is never stored, logged or shared (T-I48)", code(f).contains(token))
    }

    @Test fun noExportBackupSyncDriveRoomOrCryptoSourceReadsTheVoiceCode() {
        val paths = listOf(
            "$shared/shared/export", "$shared/drive", "$shared/shared/sync", "$shared/crypto", "$shared/data/ServerSyncBackend.kt",
            "$shared/data/ExportMappers.kt", "$shared/data/SyncBackend.kt", "$shared/data/Entities.kt", "$shared/data/AppDatabase.kt",
            "$appMain/export", "$appMain/drive/wiring", "$shared/export",
        )
        val forbidden = listOf(
            "shared.voice", "VoiceChoice", "voiceChoice", "voiceInput", "voice.input", "RecordedClip", "OwnKeyTranscription", "VoiceSession",
            "PcmVoiceRecorder", "AudioRecord", "saveVoiceInput",
        )
        var scanned = 0
        for (p in paths) for (f in kotlinFiles(p)) {
            scanned++
            for (token in forbidden) assertFalse("${f.relativeTo(root())} mentions `$token`: voice is in no export, backup, sync or Drive path", code(f).contains(token))
        }
        assertTrue("the scan must read the files ($scanned)", scanned > 40)
    }

    @Test fun theSwitchIsInTheSettingsStoreAloneAndNotInTheExportedSettings() {
        val mentions = File(root(), "shared/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { code(it).contains("\"voice.input\"") }.map { it.name }.toList()
        assertEquals(listOf("Settings.kt"), mentions)
        assertTrue(AppSettings::class.java.declaredFields.none { it.name.contains("voice", ignoreCase = true) })
        assertFalse(AppSettings().toString().contains("voice", ignoreCase = true))
    }

    // --- the manifest and Android's backup ------------------------------------------------------------------------------------

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root(), path)).documentElement

    @Test fun theMicrophonePermissionIsDeclaredOnceAsARuntimePermissionAndTheMicrophoneIsNotRequired() {
        val manifest = xml("app/src/main/AndroidManifest.xml")
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }
        assertEquals(1, names.count { it == "android.permission.RECORD_AUDIO" })
        val features = manifest.getElementsByTagName("uses-feature")
        val mic = (0 until features.length).map { features.item(it) as Element }.single { it.getAttribute("android:name") == "android.hardware.microphone" }
        assertEquals("false", mic.getAttribute("android:required"))
    }

    @Test fun theMicrophonePermissionIsRequestedFromOnePlaceAndNeverAtStartUp() {
        val mentioning = File(root(), "app/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { code(it).contains("RECORD_AUDIO") }.map { it.name }.sorted().toList()
        assertEquals(listOf("AndroidVoiceServices.kt", "AudioRecordPcmSource.kt"), mentioning)
        val launching = kotlinFiles("$appMain/voice/AndroidVoiceServices.kt").single()
        assertEquals(1, Regex("launcher\\.launch\\(").findAll(code(launching)).count())
        for (start in listOf("$appMain/MainActivity.kt", "$appMain/DoorprintsApp.kt")) {
            val text = code(kotlinFiles(start).single())
            assertFalse("$start touches the microphone", text.contains("RECORD_AUDIO") || text.contains("microphoneGranted") || text.contains("rememberMicrophoneRequest"))
        }
        for (f in File(root(), "app/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            assertFalse("${f.name} calls requestPermissions", code(f).contains("requestPermissions("))
        }
    }

    @Test fun androidBackupIsOffAndDeviceTransferTakesOnlyTheDatabaseAndThePhotos() {
        val manifest = xml("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("", application.getAttribute("android:fullBackupContent"))
        assertEquals("", application.getAttribute("android:backupAgent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        val rules = xml("app/src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.getElementsByTagName("cloud-backup").item(0) as Element
        assertEquals("cloud backup takes nothing", 0, cloud.getElementsByTagName("include").length)
        assertTrue(cloud.getElementsByTagName("exclude").length >= 5)
        val transfer = rules.getElementsByTagName("device-transfer").item(0) as Element
        val included = transfer.getElementsByTagName("include")
        val taken = (0 until included.length).map { (included.item(it) as Element).let { e -> e.getAttribute("domain") + ":" + e.getAttribute("path") } }
        assertEquals(listOf("database:.", "file:photos/"), taken)
    }

    // --- the permission and the services -----------------------------------------------------------------------------------------

    @Test fun withoutThePermissionTheMicrophoneIsNotOpenedAndTheReasonIsDenied() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val services = AndroidVoiceServices(app, store) { Provider().client }
        assertFalse(services.microphoneGranted())
        val e = runCatching { AudioRecordPcmSource(app).open() }.exceptionOrNull()
        assertEquals(VoiceFailure.DENIED, (e as VoiceRecorderException).reason)
    }

    @Test fun theGrantIsReadNotAskedAndTheMicrophoneFeatureDecidesRecordable() {
        val services = AndroidVoiceServices(app, store) { Provider().client }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(services.microphoneGranted())
        assertTrue(services.supported)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE, false)
        assertFalse("a phone without a microphone cannot record", AndroidVoiceServices(app, store) { Provider().client }.recordable)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE, true)
        assertTrue(AndroidVoiceServices(app, store) { Provider().client }.recordable)
    }

    @Test fun anOpenThatCannotWorkIsAClassifiedFailureNeverACrash() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val source = AudioRecordPcmSource(app)
        try {
            source.open()
        } catch (e: VoiceRecorderException) {
            assertNotNull(e.reason)
        } finally {
            source.close()
            source.close()
        }
        assertEquals(-1, source.read(ByteArray(10), 0, 10))
    }

    @Test fun theLogScanCanSeeALogLine() {
        ShadowLog.clear()
        Log.d("probe", "DPVOICE!DPVOICE!")
        assertTrue(ShadowLog.getLogs().single().msg.contains(needles[0].decodeToString()))
        ShadowLog.clear()
    }
}
