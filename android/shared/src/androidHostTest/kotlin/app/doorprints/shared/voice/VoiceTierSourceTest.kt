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

package app.doorprints.shared.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The source-level half of the free-plan rule's safety proofs (TC-S-51, TC-S-52, TC-S-53; owner decision 2026-10-19, ADR-37, RR-33):
 * what is kept is a verdict, a time and the name of the evidence under the key's hash (never the key, a reply or speech); the second
 * check sits before every request; the probe carries no audio or data; no export, backup, sync, Drive or AI-prompt path reads the rule.
 * (`VoiceTierGateTest` is the behavioural half.)
 */
class VoiceTierSourceTest {
    private val repo: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/voice-tier-vectors.json").exists()) dir = dir.parentFile
        checkNotNull(dir) { "the repository root was not found" }
    }

    private val main = "android/shared/src/commonMain/kotlin/app/doorprints"

    private fun read(path: String): String = File(repo, path).also { assertTrue(it.exists(), "$path does not exist: the test would be vacuous") }.readText()

    /** Code only: comments are the place the text names these things. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun theSecondCheckComesBeforeEveryRequestInTheTranscription() {
        val src = code(read("$main/shared/voice/OwnKeyTranscription.kt"))
        val sarvam = src.indexOf("confirmAllowed(choice)")
        assertTrue(sarvam in 1 until src.indexOf("SarvamTranscriber(http, key).transcribe("), "Sarvam: the rule first")
        val own = src.indexOf("confirmAllowed(choice)", sarvam + 1)
        assertTrue(own in 1 until src.indexOf("return transcriber.transcribe("), "own AI: the rule first")
        assertEquals(3, Regex("confirmAllowed\\(").findAll(src).count(), "two calls and the definition")
        assertTrue("if (verdict != VoiceVerdict.ALLOW) throw VoiceTierRefused(verdict, subject.provider)" in src)
    }

    @Test
    fun theMicrophoneIsNotStartedByTheScreenBeforeTheRuleAllowsIt() {
        val src = code(read("android/ui/src/commonMain/kotlin/app/doorprints/ui/VoiceMicButton.kt"))
        val tap = src.indexOf("VoicePhase.IDLE -> if (!allowed) {}")
        assertTrue(tap > 0, "the tap checks the rule before the rationale, the prompt or the recorder")
        assertTrue("else if (voice.microphoneGranted()) session.start() else rationale = true" in src.substring(tap).lineSequence().first())
        assertTrue("enabled = !busy && (allowed || recording)" in src)
    }

    @Test
    fun whatIsKeptIsTheVerdictATimeAndTheEvidenceUnderTheKeysHash() {
        val gate = code(read("$main/shared/voice/VoiceTierGate.kt"))
        val writes = Regex("voiceTier\\.saveEntry\\(([^\n]*)").findAll(gate).map { it.groupValues[1] }.toList()
        assertEquals(3, writes.size, "the probe's two answers and the ordinary-request note")
        for (w in writes) {
            assertTrue(w.startsWith("fp, entry(") || w.startsWith("VoiceTier.keyHash(key), \"\${VoiceVerdict.REFUSE_FREE.name}|\$at|gemini:ordinary-429\""), w)
            assertFalse(Regex("\\bbody\\b|\\bkey\\(\\)|subject\\.key").containsMatchIn(w.substringAfter(",")), "nothing from a reply or the key: $w")
        }
        assertFalse(Regex("println|Log\\.|logger|\\.toString\\(\\)\\s*\\)").containsMatchIn(gate), "nothing is logged")
        assertTrue("override fun toString(): String = \"TierSubject(\$provider)\"" in gate, "the subject never prints its key")
    }

    @Test
    fun theAiStoreWiresTheOrdinaryRequestHookToTheRule() {
        val store = code(read("$main/data/AiStore.kt"))
        assertTrue("client.onFailedAnswer = { status, body -> VoiceTierGate.noteGeminiAnswer(settings, key, status, body) }" in store)
        val client = code(read("$main/shared/ai/GeminiClient.kt"))
        assertTrue("onFailedAnswer?.invoke(status, text)" in client)
    }

    @Test
    fun theProbeCarriesNoAudioAndNoData() {
        val gate = code(read("$main/shared/voice/VoiceTierGate.kt"))
        val ask = gate.substring(gate.indexOf("private suspend fun ask("), gate.indexOf("companion object"))
        assertTrue("private suspend fun read(" in ask, "the probe's request is built in one place")
        assertFalse(Regex("inlineData|audio|clip|bytes|house|candidate|Settings|location", RegexOption.IGNORE_CASE).containsMatchIn(ask), ask)
        assertTrue("VoiceTier.geminiProbeBody()" in ask)
        val body = code(read("$main/shared/voice/VoiceTier.kt"))
        assertTrue("GEMINI_PROBE_PROMPT = \"Hi\"" in body)
    }

    @Test
    fun noExportBackupSyncDriveOrPromptPathReadsTheRule() {
        val outPaths = listOf(
            "$main/shared/export", "$main/drive", "$main/shared/sync", "$main/crypto",
            "$main/data/ServerSyncBackend.kt", "$main/data/ExportMappers.kt", "$main/data/SyncBackend.kt",
            "android/app/src/main/java/app/doorprints/export", "android/app/src/main/java/app/doorprints/drive/wiring",
        )
        for (path in outPaths) {
            val f = File(repo, path)
            assertTrue(f.exists(), "$path does not exist: the test would be vacuous")
            for (file in f.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                assertFalse(Regex("VoiceTier|voice\\.tier|voiceTier|TierSubject").containsMatchIn(file.readText()), file.path)
            }
        }
        // The AI prompts and adapters know nothing of it either, except the one failed-answer hook of GeminiClient.
        for (file in File(repo, "$main/shared/ai").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            assertFalse(Regex("VoiceTier|voice\\.tier|voiceTier").containsMatchIn(file.readText()), file.path)
        }
    }
}
