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

import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.BaseUrlReason
import app.doorprints.shared.api.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules of Settings > AI features for the AI service of the person's choice (S4b-BL-150, docs/03 §13.2,
 * ADR-35): what each service prefills, what may be edited, what is refused, which saved key belongs to what is on the
 * screen, which failure says what, and which host the disclosure names. The screen's own behaviour is in
 * `AiSettingsSectionTest` (:app).
 */
class AiProviderFormTest {
    private val gemini = AiProviderConfig.GEMINI
    private fun compat(url: String, model: String = "m") = AiProviderConfig(AiKind.OPENAI_COMPATIBLE, url, model)
    private fun anthropic(model: String = "m", url: String = "https://api.anthropic.com") = AiProviderConfig(AiKind.ANTHROPIC, url, model)
    private fun form(service: AiService, saved: AiProviderConfig = gemini, emulator: Boolean = false) =
        AiProviderForm.initial(saved, emulator).choose(service, saved)

    @Test fun eachServicePrefillsItsPresetAddressAndNothingElse() {
        assertEquals("https://api.openai.com/v1", form(AiService.OPENAI).baseUrl)
        assertEquals("https://openrouter.ai/api/v1", form(AiService.OPENROUTER).baseUrl)
        assertEquals("https://api.groq.com/openai/v1", form(AiService.GROQ).baseUrl)
        assertEquals("http://localhost:11434/v1", form(AiService.OLLAMA).baseUrl)
        assertEquals("http://localhost:1234/v1", form(AiService.LM_STUDIO).baseUrl)
        assertEquals("https://api.anthropic.com", form(AiService.ANTHROPIC).baseUrl)
        assertEquals("", form(AiService.CUSTOM).baseUrl)
        assertEquals("", form(AiService.GEMINI).baseUrl)
        AiService.entries.forEach { assertEquals("", form(it).model, "the model is typed, never guessed: $it") }
    }

    @Test fun onlyCustomHasAnEditableAddress() {
        AiService.entries.forEach { assertEquals(it == AiService.CUSTOM, form(it).urlEditable, "$it") }
    }

    @Test fun theChoiceBeginsFromTheSavedSettings() {
        assertEquals(AiService.GEMINI, AiProviderForm.initial(gemini, false).service)
        val claude = AiProviderForm.initial(anthropic("typed-by-me"), false)
        assertEquals(AiService.ANTHROPIC, claude.service)
        assertEquals("typed-by-me", claude.model)
        assertEquals("https://api.anthropic.com", claude.baseUrl)
        val groq = AiProviderForm.initial(compat("https://api.groq.com/openai/v1", "llama"), false)
        assertEquals(AiService.GROQ, groq.service)
        assertEquals("llama", groq.model)
        val custom = AiProviderForm.initial(compat("https://example.com/v1"), false)
        assertEquals(AiService.CUSTOM, custom.service)
        assertEquals("https://example.com/v1", custom.baseUrl)
    }

    @Test fun returningToTheSavedServiceBringsBackItsAddressAndModelAnotherOneStartsEmpty() {
        val saved = compat("https://example.com/v1", "mine")
        val away = AiProviderForm.initial(saved, false).choose(AiService.OPENAI, saved)
        assertEquals("", away.model)
        assertEquals("https://api.openai.com/v1", away.baseUrl)
        val back = away.choose(AiService.CUSTOM, saved)
        assertEquals("https://example.com/v1", back.baseUrl)
        assertEquals("mine", back.model)
    }

    @Test fun returningToAnthropicBringsBackItsModelAndOtherServicesDoNotInheritIt() {
        val saved = anthropic("mine")
        val away = AiProviderForm.initial(saved, false).choose(AiService.OPENAI, saved)
        assertEquals("", away.model)
        assertEquals("mine", away.choose(AiService.ANTHROPIC, saved).model)
        assertEquals("", AiProviderForm.initial(compat("https://api.openai.com/v1", "gpt"), false).choose(AiService.ANTHROPIC, compat("https://api.openai.com/v1", "gpt")).model)
    }

    @Test fun choosingAServiceClearsTheTypedKey() {
        val typed = form(AiService.OPENAI).copy(key = "sk-typed")
        assertEquals("", typed.choose(AiService.GROQ, gemini).key)
    }

    @Test fun theShownHostIsGoogleForGeminiThePresetsHostOrTheTypedOne() {
        assertEquals("generativelanguage.googleapis.com", form(AiService.GEMINI).host)
        assertEquals("api.openai.com", form(AiService.OPENAI).host)
        assertEquals("localhost", form(AiService.OLLAMA).host)
        assertEquals("", form(AiService.CUSTOM).host)
        assertEquals("api.anthropic.com", form(AiService.ANTHROPIC).host)
        assertEquals("llm.example.org", form(AiService.CUSTOM).copy(baseUrl = "HTTPS://LLM.Example.org:8443/v1/").host)
    }

    @Test fun localServicesNeedNoKeyHostedOnesDo() {
        assertTrue(form(AiService.OLLAMA).keyOptional)
        assertTrue(form(AiService.LM_STUDIO).keyOptional)
        assertFalse(form(AiService.OPENAI).keyOptional)
        assertFalse(form(AiService.ANTHROPIC).keyOptional, "Anthropic has no keyless form")
        assertFalse(form(AiService.CUSTOM).keyOptional)
        assertTrue(form(AiService.CUSTOM).copy(baseUrl = "http://localhost:8080/v1").keyOptional, "a custom address on this device")
        assertFalse(form(AiService.CUSTOM).copy(baseUrl = "https://example.com/v1").keyOptional)
    }

    @Test fun anAddressIsRefusedForTheReasonTheValidatorGives() {
        fun reason(url: String, emulator: Boolean = false) =
            form(AiService.CUSTOM, emulator = emulator).copy(baseUrl = url, model = "m", key = "k").validate(gemini, false).urlReason
        assertEquals(BaseUrlReason.EMPTY, reason(""))
        assertEquals(BaseUrlReason.NOT_AN_URL, reason("example.com"))
        assertEquals(BaseUrlReason.SCHEME, reason("ftp://example.com"))
        assertEquals(BaseUrlReason.USERINFO, reason("https://me:pw@example.com"))
        assertEquals(BaseUrlReason.QUERY, reason("https://example.com/v1?x=1"))
        assertEquals(BaseUrlReason.FRAGMENT, reason("https://example.com/v1#x"))
        assertEquals(BaseUrlReason.ENDPOINT, reason("https://example.com/v1/chat/completions"))
        assertEquals(BaseUrlReason.INSECURE_HOST, reason("http://192.168.1.20:11434/v1"))
        assertNull(reason("https://example.com/v1"))
        assertNull(reason("http://localhost:11434/v1"))
    }

    @Test fun theEmulatorsComputerIsAllowedOnAndroidAndRefusedWhereTheFlagIsOff() {
        fun reason(emulator: Boolean) =
            form(AiService.CUSTOM, emulator = emulator).copy(baseUrl = "http://10.0.2.2:11434/v1", model = "m").validate(gemini, false).urlReason
        assertNull(reason(true))
        assertEquals(BaseUrlReason.INSECURE_HOST, reason(false))
    }

    @Test fun aMissingModelIsReportedAndAnOptionalKeyIsNot() {
        val ollama = form(AiService.OLLAMA)
        val noModel = ollama.validate(gemini, false)
        assertTrue(noModel.modelMissing)
        assertFalse(noModel.keyMissing, "Ollama needs no key")
        assertEquals(AiField.MODEL, noModel.first)
        assertNull(noModel.config)
        val ok = ollama.copy(model = " llama3.1 ").validate(gemini, false)
        assertEquals(compat("http://localhost:11434/v1", "llama3.1"), ok.config)
        assertNull(ok.first)
    }

    @Test fun aHostedServiceNeedsAKeyUnlessOneIsSavedForIt() {
        val openai = form(AiService.OPENAI).copy(model = "gpt-4o-mini")
        val missing = openai.validate(gemini, false)
        assertTrue(missing.keyMissing)
        assertEquals(AiField.KEY, missing.first)
        assertNull(missing.config)
        assertNull(openai.copy(key = "sk-x").validate(gemini, false).first)
        val saved = compat("https://api.openai.com/v1", "gpt-4o-mini")
        assertNull(openai.validate(saved, hasKey = true).first, "the saved key is for this service")
        assertTrue(openai.validate(saved, hasKey = false).keyMissing)
    }

    @Test fun anAnthropicFormStandsForAnAnthropicConfigAndNeedsAModelAndAKey() {
        val claude = form(AiService.ANTHROPIC)
        assertFalse(claude.urlEditable)
        val empty = claude.validate(gemini, false)
        assertTrue(empty.modelMissing && empty.keyMissing)
        assertEquals(AiField.MODEL, empty.first)
        assertNull(empty.config)
        val typed = claude.copy(model = " my-model ", key = "test-key-not-real").validate(gemini, false)
        assertEquals(anthropic("my-model"), typed.config)
        // A key saved for Anthropic counts; one saved for another service does not.
        assertNull(claude.copy(model = "m").validate(anthropic(), hasKey = true).first)
        assertEquals(AiField.KEY, claude.copy(model = "m").validate(compat("https://api.openai.com/v1"), hasKey = true).first)
    }

    @Test fun theFirstProblemIsTheOneToFocus() {
        val all = form(AiService.CUSTOM).validate(gemini, false)
        assertEquals(AiField.BASE_URL, all.first)
        assertTrue(all.modelMissing && all.keyMissing)
        assertEquals(AiField.MODEL, form(AiService.OPENAI).validate(gemini, false).first)
    }

    @Test fun aSavedKeyBelongsOnlyToTheServiceAndAddressItWasSavedFor() {
        val saved = compat("https://api.openai.com/v1", "gpt-4o-mini")
        assertTrue(form(AiService.OPENAI, saved).savedHere(saved, true))
        assertTrue(form(AiService.OPENAI, saved).keySavedHere(saved, true))
        assertFalse(form(AiService.OPENAI, saved).keySavedHere(saved, false), "settings without a key")
        assertTrue(form(AiService.OPENAI, saved).savedHere(saved, false), "settings without a key are still these settings")
        assertFalse(form(AiService.GROQ, saved).savedHere(saved, true))
        assertFalse(form(AiService.GEMINI, saved).savedHere(saved, true))
        assertFalse(form(AiService.CUSTOM, saved).savedHere(saved, true), "another address")
        assertFalse(form(AiService.ANTHROPIC, saved).savedHere(saved, true))
        assertTrue(form(AiService.ANTHROPIC, anthropic()).savedHere(anthropic(), true))
        assertFalse(form(AiService.OPENAI, anthropic()).savedHere(anthropic(), true))
        assertFalse(form(AiService.GEMINI, anthropic()).savedHere(anthropic(), true))
        val typed = form(AiService.OPENAI, saved).copy(baseUrl = "https://API.openai.com/v1/")
        assertTrue(typed.savedHere(saved, true), "the same address once normalised")
        assertTrue(form(AiService.GEMINI).savedHere(gemini, true))
        assertFalse(form(AiService.GEMINI).savedHere(gemini, false))
    }

    @Test fun aKeySavedForAnthropicIsNotOfferedToAnOpenAiCompatibleServiceAtTheSameAddress() {
        val saved = anthropic()
        val custom = form(AiService.CUSTOM, saved).copy(baseUrl = "https://api.anthropic.com")
        assertFalse(custom.savedHere(saved, true))
        assertEquals("", custom.keyForCall(saved, "test-key-not-real"))
    }

    @Test fun aSavedKeyIsOnlyHandedToACallForTheSameService() {
        val saved = compat("https://api.openai.com/v1", "gpt-4o-mini")
        val openai = form(AiService.OPENAI, saved)
        assertEquals("sk-saved", openai.keyForCall(saved, "sk-saved"))
        assertEquals("sk-typed", openai.copy(key = " sk-typed ").keyForCall(saved, "sk-saved"))
        assertEquals("", form(AiService.GROQ, saved).keyForCall(saved, "sk-saved"), "never sent to another service")
        assertEquals("", form(AiService.GEMINI, saved).keyForCall(saved, "sk-saved"))
        assertEquals("", form(AiService.ANTHROPIC, saved).keyForCall(saved, "sk-saved"), "an OpenAI key never goes to Anthropic")
        assertEquals("", form(AiService.OPENAI, anthropic()).keyForCall(anthropic(), "test-key-not-real"), "an Anthropic key never goes to OpenAI")
        assertEquals("test-key-not-real", form(AiService.ANTHROPIC, anthropic()).keyForCall(anthropic(), "test-key-not-real"))
        assertEquals("", form(AiService.CUSTOM, saved).copy(baseUrl = "https://evil.example/v1").keyForCall(saved, "sk-saved"))
    }

    @Test fun whatTheSavedSettingsCanAnswerWith() {
        assertTrue(AiProviderForm.usable(gemini, "AIzaKey", false))
        assertFalse(AiProviderForm.usable(gemini, "", false))
        assertTrue(AiProviderForm.usable(compat("http://localhost:11434/v1", "llama3.1"), "", false), "a local model needs no key")
        assertFalse(AiProviderForm.usable(compat("http://localhost:11434/v1", " "), "", false))
        assertFalse(AiProviderForm.usable(compat("http://192.168.1.2/v1", "m"), "k", false))
        assertTrue(AiProviderForm.usable(compat("http://10.0.2.2:11434/v1", "m"), "", true))
        assertTrue(AiProviderForm.usable(anthropic(), "test-key-not-real", false))
        assertFalse(AiProviderForm.usable(anthropic(), "", false), "Anthropic needs a key")
        assertFalse(AiProviderForm.usable(anthropic(model = " "), "k", false))
        assertFalse(AiProviderForm.usable(anthropic(url = "http://example.com"), "k", false))
    }

    @Test fun theDisclosureHostIsTheSavedServicesAndNothingWhenTheServerAnswers() {
        fun host(s: AppSettings, emulator: Boolean = false) = AiProviderForm.disclosureHost(s, emulator)
        val own = AppSettings(aiProvider = AiProviderChoice.DEVICE, geminiKey = "AIzaKey1234")
        assertEquals("generativelanguage.googleapis.com", host(own))
        val groq = own.copy(aiProviderConfig = compat("https://api.groq.com/openai/v1"))
        assertEquals("api.groq.com", host(groq))
        assertEquals("api.anthropic.com", host(own.copy(aiProviderConfig = anthropic())))
        assertEquals("10.0.2.2", host(own.copy(aiProviderConfig = compat("http://10.0.2.2:11434/v1")), emulator = true))
        assertNull(host(AppSettings(serverUrl = "https://s.example", apiKey = "serverkey1234", aiProvider = AiProviderChoice.SERVER)))
        assertEquals("api.groq.com", host(groq.copy(aiProvider = AiProviderChoice.SERVER, serverUrl = "")), "no server, so own AI")
        assertEquals("", host(own.copy(aiProviderConfig = compat("not a url"))), "own AI chosen with nothing valid yet")
    }

    private fun failure(kind: ApiException.Kind, code: Int = 0, host: String = "api.openai.com", retry: Long? = null) =
        AiFailure.of(ApiException(kind, code, retry), host)

    @Test fun eachFailureOfATestHasItsOwnWords() {
        assertEquals(AiFailure.KeyRejected("api.openai.com"), failure(ApiException.Kind.AI_KEY_REJECTED, 401))
        assertEquals(AiFailure.ModelNotFound, failure(ApiException.Kind.AI_MODEL_NOT_FOUND, 404))
        assertEquals(AiFailure.RateLimited(12), failure(ApiException.Kind.RATE_LIMITED, 429, retry = 12))
        assertEquals(AiFailure.RateLimited(60), failure(ApiException.Kind.RATE_LIMITED, 429))
        assertEquals(AiFailure.Unreachable("api.openai.com", local = false), failure(ApiException.Kind.AI_UNAVAILABLE, 0))
        assertEquals(AiFailure.Unreachable("localhost", local = true), failure(ApiException.Kind.AI_UNAVAILABLE, 0, host = "localhost"))
        assertEquals(AiFailure.Unreachable("10.0.2.2", local = true), failure(ApiException.Kind.AI_UNAVAILABLE, 0, host = "10.0.2.2"))
        assertEquals(AiFailure.Down, failure(ApiException.Kind.AI_UNAVAILABLE, 503))
        assertEquals(AiFailure.Down, failure(ApiException.Kind.AI_UNAVAILABLE, 0, host = ""), "no host to name")
        assertEquals(AiFailure.Other(500), failure(ApiException.Kind.SERVER, 500))
        assertEquals(AiFailure.Offline, AiFailure.of(IllegalStateException("no provider"), "x"))
    }

    @Test fun geminiKeepsItsOwnRejectionWords() {
        assertEquals(AiFailure.KeyRejected(""), failure(ApiException.Kind.AI_KEY_REJECTED, 400, host = ""))
    }
}
