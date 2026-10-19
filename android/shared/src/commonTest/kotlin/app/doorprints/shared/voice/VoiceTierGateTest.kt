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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.api.ApiHttp
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportFixture
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-U-287 (docs/06): the free-plan rule wired to a phone's settings and HTTP client (owner decision 2026-10-19, ADR-37): fake
 * providers per tier (Gemini 429 with a free-tier quota id, 429 without, 200; OpenRouter key information true and false; Groq and
 * Sarvam with no signal), the confirmation per key, the lifetimes, the probe's request, the second check before a request
 * ([OwnKeyTranscription]), a later free-tier 429 on an ordinary request, and that what is kept holds no key, no reply and no
 * speech and is in no backup (the TC-U-170 pattern).
 */
class VoiceTierGateTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private class FakeSecrets(name: String) : SecretStore {
        val slot = stringPreferencesKey(name)
        override fun get(settings: Preferences) = settings[slot]?.removePrefix("s:")?.reversed()
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[slot] = "s:" + apiKey.reversed()
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(slot)
        }
    }

    private class Call(val method: HttpMethod, val url: String, val headers: Headers, val body: String)

    /** Built at run time: no key-looking literal is committed. */
    private fun placeholder(n: String) = listOf("plan", "rule", "placeholder", n).joinToString("-")

    private val dataStore = MemoryDataStore()
    private var clock = 10_000_000_000L
    private val settings = SettingsStore(dataStore, FakeSecrets("sealed"), FakeSecrets("gemini"), sarvamSecrets = FakeSecrets("sarvam")) { clock }
    private val calls = mutableListOf<Call>()
    private var reply: () -> Pair<Int, String> = { 200 to "{}" }
    private val engine = MockEngine { request ->
        calls += Call(request.method, request.url.toString(), request.headers, String(request.body.toByteArray()))
        val (status, body) = reply()
        respond(body, HttpStatusCode.fromValue(status), Headers.build { append("Content-Type", "application/json") })
    }
    private val http = ApiHttp.client(engine)
    private val gate = VoiceTierGate(settings, http, { clock }, null)

    private val free429 = """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
    private val paid429 = """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateContentPaidTierInputTokensPerModelPerMinute"}]}]}}"""

    private suspend fun own(config: AiProviderConfig, key: String = placeholder("1111")) {
        settings.saveAiProviderConfig(config, key)
        settings.saveAiProvider(AiProviderChoice.DEVICE)
    }

    private suspend fun choice(enabled: Boolean = true) = VoiceChoice(enabled, settings.current().aiProviderConfig)

    private suspend fun subject(): TierSubject = assertNotNull(gate.subjectFor(choice()))

    private suspend fun entries(): Map<String, String> =
        dataStore.data.first().asMap().filterKeys { it.name.startsWith("voice.tier") }.mapKeys { it.key.name }.mapValues { it.value.toString() }

    private val compat = { url: String -> AiProviderConfig(AiKind.OPENAI_COMPATIBLE, url, "chat-model") }

    @Test
    fun geminiWithAFreeTierQuotaIdIsRefusedWithNoWayRoundAndKeptForADay() = runTest {
        own(AiProviderConfig.GEMINI)
        reply = { 429 to free429 }
        assertEquals(VoiceVerdict.REFUSE_FREE, gate.verdict(subject()))
        assertEquals(1, calls.size)
        // Confirming does not open a free plan, and nothing is offered to confirm.
        gate.acknowledge(subject(), true)
        assertEquals(VoiceVerdict.REFUSE_FREE, gate.verdict(subject()))
        assertFalse(gate.canConfirmPaid(subject(), VoiceVerdict.REFUSE_FREE))
        assertEquals(1, calls.size, "kept: no second call")
        val (name, value) = entries().entries.first { "tier-ack" !in it.key }
        assertEquals("voice.tier.${VoiceTier.fingerprint(placeholder("1111"))}", name)
        assertTrue(value.startsWith("REFUSE_FREE|$clock|gemini:probe"))
        // Older than a day it is asked again.
        clock += VoiceTier.REFUSE_FREE_TTL_MS + 1
        reply = { 200 to "{}" }
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertEquals(2, calls.size)
    }

    @Test
    fun geminiPaidIsAllowedAnd429WithoutAFreeTierIdIsToo() = runTest {
        own(AiProviderConfig.GEMINI)
        for (answer in listOf(200 to "{}", 429 to paid429)) {
            dataStore.edit { it.clear() }
            own(AiProviderConfig.GEMINI)
            calls.clear()
            reply = { answer }
            assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()), answer.first.toString())
            assertEquals(1, calls.size)
            assertTrue(entries().values.single().startsWith("ALLOW|"))
        }
    }

    @Test
    fun theGeminiProbeCarriesTheKeyInTheHeaderAndOneFixedWordAndNoAudioOrData() = runTest {
        own(AiProviderConfig.GEMINI)
        reply = { 429 to free429 }
        gate.verdict(subject())
        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro-preview:generateContent", call.url)
        assertEquals(placeholder("1111"), call.headers["x-goog-api-key"])
        assertFalse(placeholder("1111") in call.url)
        assertEquals(VoiceTier.geminiProbeBody().toString(), call.body)
    }

    @Test
    fun geminiThatCannotBeReadIsRefusedAsUnknownUntilThePersonConfirmsAndAKeyChangeAsksAgain() = runTest {
        own(AiProviderConfig.GEMINI)
        reply = { 403 to "{}" }
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()))
        assertTrue(gate.canConfirmPaid(subject(), VoiceVerdict.REFUSE_UNKNOWN))
        assertTrue(entries().isEmpty(), "an unknown is never kept: ${entries()}")
        gate.acknowledge(subject(), true)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertTrue(gate.isAcknowledged(subject()))
        val oldFp = VoiceTier.fingerprint(placeholder("1111"))
        assertNotNull(settings.voiceTier.ack(oldFp))
        own(AiProviderConfig.GEMINI, placeholder("2222"))
        reply = { 403 to "{}" }
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()), "a new key asks again")
        assertFalse(gate.isAcknowledged(subject()))
        assertNull(settings.voiceTier.ack(oldFp), "the old key's confirmation is dropped")
    }

    @Test
    fun noAnswerAtAllIsUnknownAndAskedAgainAfterAMinute() = runTest {
        own(AiProviderConfig.GEMINI)
        reply = { throw java.io.IOException("offline") }
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()))
        assertEquals(1, calls.size)
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()))
        assertEquals(1, calls.size, "not again at once")
        clock += 61_000
        reply = { 200 to "{}" }
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertEquals(2, calls.size)
    }

    @Test
    fun aPaidVerdictLastsAWeekAndThenIsAskedAgain() = runTest {
        own(AiProviderConfig.GEMINI)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        clock += VoiceTier.ALLOW_TTL_MS - 60_000
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertEquals(1, calls.size)
        clock += 120_000
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertEquals(2, calls.size)
    }

    @Test
    fun openRouterIsReadFromItsKeyInformation() = runTest {
        for ((flag, expected) in listOf(true to VoiceVerdict.REFUSE_FREE, false to VoiceVerdict.ALLOW)) {
            dataStore.edit { it.clear() }
            calls.clear()
            own(compat("https://openrouter.ai/api/v1"))
            reply = { 200 to """{"data":{"is_free_tier":$flag}}""" }
            assertEquals(expected, gate.verdict(subject()))
            val call = calls.single()
            assertEquals(HttpMethod.Get, call.method)
            assertEquals("https://openrouter.ai/api/v1/key", call.url)
            assertEquals("Bearer ${placeholder("1111")}", call.headers["Authorization"])
            assertEquals("", call.body)
        }
    }

    @Test
    fun groqAndSarvamCannotBeReadSoTheyAreRefusedUntilConfirmedAndNoCallIsMade() = runTest {
        own(compat("https://api.groq.com/openai/v1"))
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()))
        assertTrue(gate.canConfirmPaid(subject(), VoiceVerdict.REFUSE_UNKNOWN))
        gate.acknowledge(subject(), true)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        gate.acknowledge(subject(), false)
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(subject()))
        // Sarvam, with its own key in its own slot.
        settings.saveSarvamKey(placeholder("sarvam-1"))
        val sarvam = assertNotNull(gate.subjectFor(VoiceChoice(true, AiProviderConfig.GEMINI, VoiceProvider.SARVAM, true)))
        assertEquals("sarvam", sarvam.provider)
        assertEquals(VoiceVerdict.REFUSE_UNKNOWN, gate.verdict(sarvam))
        gate.acknowledge(sarvam, true)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(sarvam))
        assertTrue(calls.isEmpty(), "no call can tell a Groq or Sarvam plan")
    }

    @Test
    fun openAiHasNoFreeApiTierSoItIsAllowedWithNoCallAndNothingToConfirm() = runTest {
        own(compat("https://api.openai.com/v1"))
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        assertFalse(gate.canConfirmPaid(subject(), VoiceVerdict.ALLOW))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun noKeyNoSubject() = runTest {
        assertNull(gate.subjectFor(choice()))
        settings.saveAiProvider(AiProviderChoice.DEVICE)
        assertNull(gate.subjectFor(choice()))
    }

    @Test
    fun theSecondCheckInTheTranscriptionRefusesAFreeKeyBeforeAnyRequestIsBuilt() = runTest {
        own(AiProviderConfig.GEMINI)
        settings.saveAiFeatures(true)
        settings.saveVoiceInput(true)
        reply = { 429 to free429 }
        val transcription = OwnKeyTranscription(settings, http, gate)
        val clip = RecordedClip(ByteArray(2_000) { 7 }, "audio/wav", 3_000)
        val refused = assertFailsWith<VoiceTierRefused> { transcription.transcribe(clip, "house", "en") }
        assertEquals(VoiceVerdict.REFUSE_FREE, refused.verdict)
        assertEquals(1, calls.size, "only the probe went out")
        assertTrue(calls.none { "inlineData" in it.body })
    }

    @Test
    fun aLaterFreeTier429OnAnOrdinaryRequestDowngradesTheKeptVerdictAtOnce() = runTest {
        own(AiProviderConfig.GEMINI)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        VoiceTierGate.noteGeminiAnswer(settings, placeholder("1111"), 429, paid429, clock)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()), "a rate limit on a paid quota changes nothing")
        VoiceTierGate.noteGeminiAnswer(settings, placeholder("1111"), 429, free429, clock)
        assertEquals(VoiceVerdict.REFUSE_FREE, gate.verdict(subject()))
        assertEquals(1, calls.size)
    }

    @Test
    fun theGeminiClientTellsTheRuleOfAFreeTier429OnAnOrdinaryRequest() = runTest {
        own(AiProviderConfig.GEMINI)
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        val key = placeholder("1111")
        val client = app.doorprints.shared.ai.GeminiClient(http, key, timeoutMs = null).also {
            it.onFailedAnswer = { status, body -> VoiceTierGate.noteGeminiAnswer(settings, key, status, body, clock) }
        }
        reply = { 429 to paid429 }
        assertFailsWith<app.doorprints.shared.api.ApiException> { client.generateJson("s", "u", app.doorprints.shared.ai.OnDeviceAi.PING_SCHEMA, 0.0) }
        assertEquals(VoiceVerdict.ALLOW, gate.verdict(subject()))
        reply = { 429 to free429 }
        assertFailsWith<app.doorprints.shared.api.ApiException> { client.generateJson("s", "u", app.doorprints.shared.ai.OnDeviceAi.PING_SCHEMA, 0.0) }
        assertEquals(VoiceVerdict.REFUSE_FREE, gate.verdict(subject()))
    }

    @Test
    fun whatIsKeptHoldsNoKeyNoReplyAndNoSpeechAndIsInNoBackupOrPrintedSettings() = runTest {
        own(AiProviderConfig.GEMINI)
        val marker = "DISTINCTIVE-PROVIDER-BODY-text-5150"
        reply = { 429 to free429.replace("\"code\":429", "\"code\":429,\"message\":\"$marker\"") }
        gate.verdict(subject())
        gate.acknowledge(subject(), true)
        val kept = dataStore.data.first().asMap().entries.filter { it.key.name.startsWith("voice.tier") }
        assertEquals(2, kept.size)
        for ((k, v) in kept) {
            assertFalse(placeholder("1111") in k.name + v, "the key is not kept")
            assertFalse(marker in v.toString(), "no reply text is kept")
        }
        val entry = kept.first { "tier-ack" !in it.key.name }.value.toString().split('|')
        assertEquals(3, entry.size, "verdict, time, evidence")
        // TC-U-170 pattern: not in the settings as printed, not in a backup file.
        val printed = settings.current().toString() + settings.voiceChoice.first().toString()
        assertFalse("voice.tier" in printed || VoiceTier.fingerprint(placeholder("1111")) in printed)
        val backup = BackupFormat.json.encodeToString(BackupData.serializer(), BackupData.of(ExportFixture.bundle())).lowercase()
        for (word in listOf("voice.tier", "voicetier", "refuse_free", VoiceTier.fingerprint(placeholder("1111")))) {
            assertFalse(word in backup, "the backup JSON mentions '$word'")
        }
    }
}
