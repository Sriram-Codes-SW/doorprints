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
import app.doorprints.shared.ai.AiPreset
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.BaseUrlCheck
import app.doorprints.shared.ai.BaseUrlReason
import app.doorprints.shared.ai.BaseUrlValidator
import app.doorprints.shared.api.ApiException

/**
 * The AI services the person can choose on this phone (S4b-BL-150, docs/03 §13.2, ADR-35): Gemini and the
 * OpenAI-compatible presets. [modelExample] is a placeholder only (the person always types the model) and [keyPage]
 * is where a hosted service hands out keys.
 */
enum class AiService(val preset: AiPreset?, val modelExample: String = "", val keyPage: String = "") {
    GEMINI(null),
    OPENAI(AiPreset.OPENAI, "gpt-4o-mini", "https://platform.openai.com/api-keys"),
    OPENROUTER(AiPreset.OPENROUTER, "openai/gpt-4o-mini", "https://openrouter.ai/keys"),
    GROQ(AiPreset.GROQ, "llama-3.3-70b-versatile", "https://console.groq.com/keys"),
    OLLAMA(AiPreset.OLLAMA, "llama3.1"),
    LM_STUDIO(AiPreset.LM_STUDIO, "qwen2.5-7b-instruct"),
    CUSTOM(AiPreset.CUSTOM),
}

/** The three fields a problem can be in, in the order the first one is focused. */
enum class AiField { BASE_URL, MODEL, KEY }

/** What [AiProviderForm.validate] found: [config] when the form can be saved or tested, else what is wrong. */
data class AiFormCheck(
    val config: AiProviderConfig?,
    val urlReason: BaseUrlReason?,
    val modelMissing: Boolean,
    val keyMissing: Boolean,
) {
    val first: AiField? get() = when {
        urlReason != null -> AiField.BASE_URL
        modelMissing -> AiField.MODEL
        keyMissing -> AiField.KEY
        else -> null
    }
}

/**
 * The form of Settings > AI features > *Use my own AI on this phone*, as plain data and rules, so that every target
 * runs the same tests (the screen only draws it). [emulatorHost] is the platform's: Android lets the emulator's
 * computer, `10.0.2.2`, use `http`; the iPhone does not ([PlatformFeatures.emulatorHost]).
 *
 * A saved key has one slot and belongs to the service and address it was saved for: [savedHere] and [keySavedHere] say
 * whether the settings on the screen are those, and [keyForCall] never hands the saved key to another service.
 */
data class AiProviderForm(
    val service: AiService = AiService.GEMINI,
    val baseUrl: String = "",
    val model: String = "",
    val key: String = "",
    val emulatorHost: Boolean = false,
) {
    val isGemini: Boolean get() = service == AiService.GEMINI

    /** Only *Custom* lets the person type the address; the presets show theirs. */
    val urlEditable: Boolean get() = service == AiService.CUSTOM

    private fun check(): BaseUrlCheck = BaseUrlValidator.check(baseUrl, emulatorHost)

    /** The host the text and the key would go to with what is on the screen; empty while the address is not valid. */
    val host: String get() = if (isGemini) GEMINI_HOST else (check() as? BaseUrlCheck.Valid)?.host.orEmpty()

    /** A service on this computer or phone usually needs no key. */
    val keyOptional: Boolean get() = (service.preset?.keyOptional ?: false) || isLocalHost(host)

    /** The saved settings are the ones on the screen (so the key, if any, is this service's). */
    fun savedHere(saved: AiProviderConfig, hasKey: Boolean): Boolean {
        if (isGemini) return saved.kind == AiKind.GEMINI && hasKey
        val mine = check() as? BaseUrlCheck.Valid ?: return false
        val theirs = BaseUrlValidator.check(saved.baseUrl, emulatorHost) as? BaseUrlCheck.Valid ?: return false
        return saved.kind == AiKind.OPENAI_COMPATIBLE && mine.normalised == theirs.normalised
    }

    fun keySavedHere(saved: AiProviderConfig, hasKey: Boolean): Boolean = hasKey && savedHere(saved, hasKey)

    /** The service list's choice: the preset's address, or the saved address and model when the choice returns to them. */
    fun choose(next: AiService, saved: AiProviderConfig): AiProviderForm {
        val keep = saved.kind == AiKind.OPENAI_COMPATIBLE && serviceOf(saved, emulatorHost) == next
        return copy(
            service = next,
            baseUrl = if (keep) saved.baseUrl else next.preset?.baseUrl.orEmpty(),
            model = if (keep) saved.model else "",
            key = "",
        )
    }

    /** What is wrong with an OpenAI-compatible form, or the config it stands for. A missing key is not one when one is saved here. */
    fun validate(saved: AiProviderConfig, hasKey: Boolean): AiFormCheck {
        val url = check()
        val trimmed = model.trim()
        val keyMissing = key.isBlank() && !keyOptional && !keySavedHere(saved, hasKey)
        val reason = (url as? BaseUrlCheck.Invalid)?.reason
        val ok = reason == null && trimmed.isNotEmpty() && !keyMissing
        return AiFormCheck(
            config = if (ok) AiProviderConfig(AiKind.OPENAI_COMPATIBLE, baseUrl, trimmed) else null,
            urlReason = reason,
            modelMissing = trimmed.isEmpty(),
            keyMissing = keyMissing,
        )
    }

    /** The key a call for what is on the screen uses: the typed one, else the saved one if it is this service's, else none. */
    fun keyForCall(saved: AiProviderConfig, savedKey: String): String = when {
        key.isNotBlank() -> key.trim()
        keySavedHere(saved, savedKey.isNotBlank()) -> savedKey
        else -> ""
    }

    companion object {
        const val GEMINI_HOST = "generativelanguage.googleapis.com"

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")

        /** Whether [host] is this phone (or, for the emulator, the computer it runs on): a local server. */
        fun isLocalHost(host: String): Boolean = host in LOCAL_HOSTS

        /** The form as Settings opens it for the [saved] settings. */
        fun initial(saved: AiProviderConfig, emulatorHost: Boolean): AiProviderForm {
            val compat = saved.kind == AiKind.OPENAI_COMPATIBLE
            return AiProviderForm(
                service = serviceOf(saved, emulatorHost),
                baseUrl = if (compat) saved.baseUrl else "",
                model = if (compat) saved.model else "",
                emulatorHost = emulatorHost,
            )
        }

        /** The list's choice for saved settings: Gemini, the preset whose address was saved, or *Custom*. */
        fun serviceOf(config: AiProviderConfig, emulatorHost: Boolean): AiService {
            if (config.kind != AiKind.OPENAI_COMPATIBLE) return AiService.GEMINI
            val check = BaseUrlValidator.check(config.baseUrl, emulatorHost) as? BaseUrlCheck.Valid ?: return AiService.CUSTOM
            return AiService.entries.firstOrNull { it != AiService.CUSTOM && it.preset?.baseUrl == check.normalised } ?: AiService.CUSTOM
        }

        /** Whether saved settings can answer: a Gemini key, or for an OpenAI-compatible service a model and a valid address (the key is optional). */
        fun usable(config: AiProviderConfig, key: String, emulatorHost: Boolean): Boolean = when (config.kind) {
            AiKind.GEMINI -> key.isNotBlank()
            AiKind.OPENAI_COMPATIBLE -> config.model.isNotBlank() && BaseUrlValidator.check(config.baseUrl, emulatorHost) is BaseUrlCheck.Valid
            AiKind.ANTHROPIC -> false
        }

        /**
         * The host the disclosure names where text goes straight from this phone, or null while the server answers
         * (its own sentence). Empty when the person's own AI is chosen but has no valid address yet.
         */
        fun disclosureHost(settings: AppSettings, emulatorHost: Boolean): String? {
            val own = settings.aiProvider == AiProviderChoice.DEVICE || !settings.serverConfigured
            if (!own) return null
            return when (settings.aiProviderConfig.kind) {
                AiKind.GEMINI -> GEMINI_HOST
                AiKind.OPENAI_COMPATIBLE ->
                    (BaseUrlValidator.check(settings.aiProviderConfig.baseUrl, emulatorHost) as? BaseUrlCheck.Valid)?.host.orEmpty()
                AiKind.ANTHROPIC -> ""
            }
        }
    }
}

/** Why an AI call failed, in the words the person needs (the Settings *Test*, the Assistant and *Paste a listing*). */
sealed interface AiFailure {
    /** [host] is empty for Gemini, whose words name Google. */
    data class KeyRejected(val host: String) : AiFailure
    data object ModelNotFound : AiFailure
    data class RateLimited(val seconds: Long) : AiFailure
    data class Unreachable(val host: String, val local: Boolean) : AiFailure
    data object Down : AiFailure
    data class Other(val code: Int) : AiFailure
    data object Offline : AiFailure

    companion object {
        /** [host] is the own AI's host when the call went to one, else empty. */
        fun of(e: Throwable, host: String): AiFailure {
            if (e !is ApiException) return Offline
            return when (e.kind) {
                ApiException.Kind.AI_KEY_REJECTED -> KeyRejected(host)
                ApiException.Kind.AI_MODEL_NOT_FOUND -> ModelNotFound
                ApiException.Kind.RATE_LIMITED -> RateLimited(e.retryAfterSeconds ?: 60L)
                // A network failure is code 0 (OpenAiCompatClient.UNREACHABLE).
                ApiException.Kind.AI_UNAVAILABLE ->
                    if (e.code == 0 && host.isNotEmpty()) Unreachable(host, AiProviderForm.isLocalHost(host)) else Down
                else -> Other(e.code)
            }
        }
    }
}
