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

package app.doorprints.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The safety proof of voice input on the iPhone, as far as a Linux machine can read it (S4b-BL-223, TC-S-53; the twin of
 * the website's TC-S-51; [02](../../docs/02-threat-model.md) T-I45..T-I48, T-D14, docs/03 ADR-37). Xcode and a phone are
 * not here, so this reads the source: the iPhone's voice code (the `AVAudioEngine` source, the permission, the services)
 * can write no audio to disk, a preference, the Keychain, the database or a log; it asks for the microphone in one
 * place only; it sets the audio session up for recording and puts it down; the app declares no background audio and
 * explains the microphone in all four languages; and no copy, backup, sync or Drive code can reach the recorder or a
 * clip. The behaviour of the shared parts (every exit lets the microphone go, the limits, the setting off) is the
 * common tests (`VoiceSessionTest`, `PcmVoiceRecorderTest`, `IosVoiceSessionTest`); the real microphone, the prompt and
 * an interruption are MT-82..MT-87 on a phone.
 */
class IosVoiceSafetyTest {
    private val repo: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/trace-repeat-vectors.json").exists()) dir = dir.parentFile
        checkNotNull(dir) { "the repository root was not found" }
    }

    private fun file(path: String) = File(repo, path)

    private val ios = "android/ui/src/iosMain/kotlin/app/doorprints/ui"
    private val pcm = "android/shared/src/commonMain/kotlin/app/doorprints/shared/voice/pcm"

    /** The iPhone's voice sources: what runs between the microphone and the shared recorder. */
    private val iosVoiceFiles = listOf("$ios/IosPcmSource.kt", "$ios/IosMicrophone.kt", "$ios/IosVoiceServices.kt")

    private fun iosVoiceSources(): List<File> {
        val files = iosVoiceFiles.map(::file) + file(pcm).listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
        files.forEach { assertTrue(it.exists(), "${it.name} is missing: the test would be vacuous") }
        assertTrue(files.size >= 6, "the pcm folder and the three iPhone files must be read (${files.size})")
        return files
    }

    /** Code only: comments name the very things this test keeps out of the code. */
    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun theIphoneVoiceCodeWritesNoAudioToDiskOrAnyStore() {
        val forbidden = listOf(
            // A recorder that needs a file, or any file or URL.
            "AVAudioRecorder", "AVAudioFile", "AVAssetWriter", "AVAssetExportSession", "AVCaptureAudioFileOutput", "AVCaptureMovieFileOutput",
            "NSFileManager", "NSTemporaryDirectory", "NSURL", "fileURLWithPath", "NSOutputStream", "NSFileHandle", "writeToFile", "writeToURL",
            "createFileAtPath", "writeTextFile", "writeBytes", "okio", "kotlinx.io", "File(",
            // A preference, the Keychain, the database, the settings.
            "NSUserDefaults", "SecItem", "Keychain", "CoreData", "Room", "iosSettingsStore", "DataStore", "dataStore", "NSCache", "NSPasteboard", "UIPasteboard",
        )
        for (f in iosVoiceSources()) {
            val text = code(f)
            for (token in forbidden) assertFalse(text.contains(token), "${f.name} uses `$token`: audio is kept in memory only")
        }
    }

    @Test
    fun theIphoneVoiceCodeLogsAndPrintsNothing() {
        val forbidden = listOf("println(", "print(", "NSLog", "logLine(", "os_log", "debugPrint", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Napier", "Logger")
        for (f in iosVoiceSources()) {
            val text = code(f)
            for (token in forbidden) assertFalse(text.contains(token), "${f.name} calls `$token`: voice code logs nothing")
        }
    }

    @Test
    fun noMessageOrStringTemplateCarriesTheAudio() {
        val audio = Regex("""\$\{?(bytes|buffer|pcm|samples|data|clip|chunk|mono|into|queue)\b""")
        for (f in iosVoiceSources()) {
            for (line in code(f).lines()) {
                if (!(line.contains("throw ") || line.contains("Exception(") || line.contains("toString") || line.contains("\"\"\""))) continue
                assertFalse(audio.containsMatchIn(line), "${f.name}: audio in a message: ${line.trim()}")
            }
        }
    }

    @Test
    fun theMicrophoneIsAskedForInOnePlaceAndOnlyFromTheButtonsRequest() {
        val permissionCalls = listOf("requestRecordPermission", "requestAccessForMediaType", "AVMediaTypeAudio", "AVAudioSessionRecordPermission")
        val all = file("android/ui/src").walkTopDown().plus(file("android/shared/src").walkTopDown())
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && !it.path.contains("/androidHostTest/") && !it.path.contains("/iosTest/") }
            .toList()
        assertTrue(all.size > 300, "the scan must read the sources (${all.size})")
        for (token in permissionCalls) {
            val users = all.filter { code(it).contains(token) }.map { it.name }
            assertTrue(users.all { it == "IosMicrophone.kt" }, "`$token` outside IosMicrophone.kt: $users")
        }
        assertEquals(listOf("IosMicrophone.kt"), all.filter { code(it).contains("requestRecordPermission") }.map { it.name })
        // requestMicrophone is reached from the services' rememberMicrophoneRequest only, which the button calls after the rationale.
        val callers = all.filter { Regex("""\brequestMicrophone\(""").containsMatchIn(code(it)) }.map { it.name }.sorted()
        assertEquals(listOf("IosMicrophone.kt", "IosVoiceServices.kt"), callers)
        // And nothing at start-up or in the app container asks.
        for (name in listOf("IosAppServices.kt", "MainViewController.kt", "IosPlatformServices.kt", "PermissionRequests.ios.kt")) {
            val text = code(file("$ios/$name"))
            assertFalse(text.contains("requestMicrophone") || text.contains("microphonePermission()"), "$name must not touch the microphone permission")
        }
    }

    @Test
    fun theAudioSessionIsSetToRecordOnlyAndPutDown() {
        val text = code(file("$ios/IosPcmSource.kt"))
        assertTrue(text.contains("AVAudioSessionCategoryRecord"), "the record category")
        for (other in listOf("AVAudioSessionCategoryPlayAndRecord", "AVAudioSessionCategoryPlayback", "AVAudioSessionCategoryMultiRoute", "AVAudioSessionCategoryAmbient"))
            assertFalse(text.contains(other), "only the record category: $other")
        assertTrue(text.contains("setActive(true"), "the session is made active in open")
        assertTrue(text.contains("setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation"), "and inactive in close, so other audio resumes")
        assertTrue(text.contains("installTapOnBus") && text.contains("removeTapOnBus") && text.contains("audio.stop()"), "the tap is installed and removed, the engine stopped")
        // The permission is checked before the session is touched, and the microphone only starts after it.
        assertTrue(text.indexOf("microphonePermission()") in 0 until text.indexOf("setCategory"), "the permission is read first")
        assertTrue(text.indexOf("setActive(true") < text.indexOf("startAndReturnError"), "the engine starts last")
    }

    @Test
    fun recordingEndsWhenTheAppLeavesTheFrontOrTheMicrophoneIsTakenOrChanged() {
        val text = code(file("$ios/IosPcmSource.kt"))
        for (name in listOf(
            "UIApplicationWillResignActiveNotification", "AVAudioSessionInterruptionNotification", "AVAudioSessionRouteChangeNotification",
            "AVAudioSessionMediaServicesWereResetNotification", "AVAudioEngineConfigurationChangeNotification",
        )) assertTrue(text.contains(name), "$name must end a recording")
        assertTrue(text.contains("CaptureEndRules.endsOnInterruption") && text.contains("CaptureEndRules.endsOnRouteChange"))
        // Background audio is not declared, so iOS itself suspends the microphone in the background too.
        val plist = file("ios/Doorprints/Info.plist").readText()
        val modes = Regex("""<key>UIBackgroundModes</key>\s*<array>(.*?)</array>""", RegexOption.DOT_MATCHES_ALL).find(plist)?.groupValues?.get(1).orEmpty()
        assertEquals(listOf("location"), Regex("<string>(.*?)</string>").findAll(modes).map { it.groupValues[1] }.toList(), "no audio background mode")
    }

    @Test
    fun infoPlistExplainsTheMicrophoneInAllFourLanguagesAndNothingElseIsAdded() {
        val plist = file("ios/Doorprints/Info.plist").readText()
        val english = Regex("""<key>NSMicrophoneUsageDescription</key>\s*<string>(.+?)</string>""", RegexOption.DOT_MATCHES_ALL).find(plist)?.groupValues?.get(1)
        assertTrue(!english.isNullOrBlank(), "NSMicrophoneUsageDescription in Info.plist")
        assertTrue(english!!.contains("not saved") || english.contains("never saved"), "the purpose string says the recording is not kept")
        for (lang in listOf("en", "hi", "ta", "te")) {
            val strings = file("ios/Doorprints/$lang.lproj/InfoPlist.strings").readText()
            val value = Regex("""^"NSMicrophoneUsageDescription"\s*=\s*"(.+)";""", RegexOption.MULTILINE).find(strings)?.groupValues?.get(1)
            assertTrue(!value.isNullOrBlank(), "$lang.lproj/InfoPlist.strings has the microphone purpose string")
        }
        assertEquals(english, Regex("""^"NSMicrophoneUsageDescription"\s*=\s*"(.+)";""", RegexOption.MULTILINE).find(file("ios/Doorprints/en.lproj/InfoPlist.strings").readText())!!.groupValues[1], "the English strings file matches Info.plist")
        assertFalse(plist.contains("NSSpeechRecognitionUsageDescription"), "the cloud recogniser is not used (ADR-37)")
        val project = file("ios/project.yml").readText()
        assertFalse(project.contains("audio-input"), "no audio entitlement")
    }

    @Test
    fun noCopyBackupSyncExportOrDriveCodeCanReachARecorderOrAClip() {
        val voiceTypes = Regex("""\b(RecordedClip|PcmSource|PcmVoiceRecorder|VoiceRecorder|VoiceSession|IosPcmSource|IosVoiceServices|OwnKeyTranscription|AudioRecord)\b""")
        val allowed = setOf(
            "VoiceRecorder.kt", "PcmVoiceRecorder.kt", "VoiceSession.kt", "VoiceInput.kt", "OwnKeyTranscription.kt", "TranscriptInsert.kt",
            "VoiceServices.kt", "VoiceMicButton.kt", "VoiceInputSetting.kt", "IosPcmSource.kt", "IosVoiceServices.kt", "IosAppServices.kt",
            "AndroidVoiceServices.kt", "AudioRecordPcmSource.kt", "AndroidAppServices.kt", "Transcriber.kt", "GeminiTranscriber.kt", "OpenAiAudioTranscriber.kt",
        )
        var scanned = 0
        for (root in listOf("android/shared/src", "android/ui/src", "android/app/src")) {
            file(root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && !it.path.contains("Test/") && !it.path.contains("/pcm/") }
                .forEach { f ->
                    scanned++
                    if (f.name !in allowed) assertFalse(voiceTypes.containsMatchIn(code(f)), "${f.relativeTo(repo)} reaches the voice recorder or a clip")
                }
        }
        assertTrue(scanned > 400, "the scan must read the sources ($scanned)")
        // The files that carry a copy, a backup, a sync file or a Drive upload name no voice type at all.
        for (name in listOf("IosCopies.kt", "IosDriveServices.kt", "IosDriveSection.kt")) {
            assertFalse(voiceTypes.containsMatchIn(code(file("$ios/$name"))), "$name must not mention voice types")
        }
    }
}
