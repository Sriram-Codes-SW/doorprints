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

package app.doorprints.data

import app.doorprints.shared.ai.AiHouse
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.AnthropicClient
import app.doorprints.shared.ai.BaseUrlCheck
import app.doorprints.shared.ai.BaseUrlValidator
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.ai.JsonChatModel
import app.doorprints.shared.ai.OnDeviceAi
import app.doorprints.shared.api.ApiClient
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Whether AI is offered on this phone and where its three calls go (S4b-BL-168, slice 8): the server's answer to
 * *is AI on for this device*, the person's own key and provider, the one on-device AI kept per key (so its rate limit
 * holds), and the choice between that and the server for *Add a shared listing*, *Ask* and *Plan*. It is separate from
 * [CommonRepository] because the six setters that change the choice all publish the same state, and the three calls all
 * make the same choice. The Assistant's house document ([houses]) is read from every kind of data, so the repository
 * builds it and hands it over; the prompts, the checks and the safeguards stay in `shared/ai`, untouched.
 */
internal class AiStore(
    private val settings: SettingsStore,
    private val apiFor: (serverUrl: String, apiKey: String) -> ApiClient,
    private val geminiFor: ((apiKey: String) -> GeminiClient)?,
    private val openAiFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)?,
    private val anthropicFor: ((baseUrl: String, model: String, apiKey: String) -> JsonChatModel)?,
    private val emulatorHostAllowed: Boolean,
    private val houses: suspend () -> List<AiHouse>,
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _off = MutableStateFlow<AiOff?>(AiOff.NO_SERVER)
    val off: StateFlow<AiOff?> = _off.asStateFlow()

    /** What the server said last: AI on for this device, or why not (NO_SERVER, SERVER, DEVICE). */
    private var serverAi: AiOff? = AiOff.NO_SERVER

    /**
     * Asks the server whether AI features are on for this device. No server set up means off, and so does a real
     * `enabled: false` answer. A failed request (offline, a timeout, a server error) keeps what was known (UX review,
     * whole-app audit): turning the Assistant tab off on every network error removed it while the user was on it.
     * AI is then offered only when this phone's *AI features* switch is on too ([AppSettings.aiFeatures]).
     */
    suspend fun refreshStatus(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        serverAi = if (!s.serverConfigured) {
            AiOff.NO_SERVER
        } else {
            runCatching { apiFor(s.serverUrl, s.apiKey).aiStatus() }.fold(
                { status ->
                    when {
                        status.offForDevice -> AiOff.DEVICE
                        !status.enabled -> AiOff.SERVER
                        else -> null
                    }
                },
                { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    serverAi
                },
            )
        }
        publish(s)
    }

    suspend fun setFeatures(on: Boolean) {
        settings.saveAiFeatures(on)
        publish(settings.current())
    }

    /** On-device AI with the person's own key (docs/03 §13.1): chosen, and a key saved. */
    private fun usesOwnKey(s: AppSettings) = s.aiProvider == AiProviderChoice.DEVICE && when (s.aiProviderConfig.kind) {
        AiKind.GEMINI -> s.geminiKey.isNotBlank() && geminiFor != null
        // The key is optional here (a model on the person's own computer needs none).
        AiKind.OPENAI_COMPATIBLE -> openAiFor != null && s.aiProviderConfig.model.isNotBlank() &&
            BaseUrlValidator.check(s.aiProviderConfig.baseUrl, emulatorHostAllowed) is BaseUrlCheck.Valid
        // Anthropic has no keyless form: the key is required.
        AiKind.ANTHROPIC -> anthropicFor != null && s.geminiKey.isNotBlank() && s.aiProviderConfig.model.isNotBlank() &&
            BaseUrlValidator.check(s.aiProviderConfig.baseUrl, emulatorHostAllowed) is BaseUrlCheck.Valid
    }

    /** The model for the saved choice, or null when nothing here can build it. */
    private fun chatModel(config: AiProviderConfig, key: String): JsonChatModel? = when (config.kind) {
        AiKind.GEMINI -> geminiFor?.invoke(key)
        AiKind.OPENAI_COMPATIBLE -> openAiFor?.invoke(config.baseUrl, config.model, key)
        AiKind.ANTHROPIC -> anthropicFor?.invoke(config.baseUrl, config.model, key)
    }

    /**
     * Works out whether AI is offered and why not, from the settings and the server's report, and publishes it to
     * [off] and [enabled]. With the person's own key only this phone's switch counts.
     */
    private fun publish(s: AppSettings): Boolean {
        // With the person's own key nothing depends on a server: only this phone's switch counts.
        val off = if (usesOwnKey(s)) (if (s.aiFeatures) null else AiOff.OPT_IN) else serverAi ?: if (s.aiFeatures) null else AiOff.OPT_IN
        _off.value = off
        _enabled.value = off == null
        return off == null
    }

    suspend fun saveKey(key: String) {
        settings.saveGeminiKey(key)
        publish(settings.current())
    }

    suspend fun setProvider(choice: AiProviderChoice) {
        settings.saveAiProvider(choice)
        publish(settings.current())
    }

    suspend fun removeKey() {
        settings.removeGeminiKey()
        publish(settings.current())
    }

    suspend fun saveConfig(config: AiProviderConfig, key: String) {
        val checked = checkedConfig(config)
        require(checked.kind != AiKind.ANTHROPIC || key.isNotBlank()) { "key" }
        settings.saveAiProviderConfig(checked, key)
        publish(settings.current())
    }

    suspend fun testProvider(config: AiProviderConfig, key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = chatModel(checkedConfig(config), key.trim()) ?: throw IllegalStateException("no provider")
            client.ping()
        }
    }

    /** [config] with its base URL normalised, or an [IllegalArgumentException] saying what is wrong with it. */
    private fun checkedConfig(config: AiProviderConfig): AiProviderConfig = when (config.kind) {
        AiKind.GEMINI -> AiProviderConfig.GEMINI
        AiKind.OPENAI_COMPATIBLE, AiKind.ANTHROPIC -> {
            // Anthropic has one address; a config saved without one means that.
            val address = if (config.kind == AiKind.ANTHROPIC && config.baseUrl.isBlank()) AnthropicClient.BASE_URL else config.baseUrl
            val url = when (val check = BaseUrlValidator.check(address, emulatorHostAllowed)) {
                is BaseUrlCheck.Valid -> check.normalised
                is BaseUrlCheck.Invalid -> throw IllegalArgumentException(check.reason.wire)
            }
            require(config.model.isNotBlank()) { "model" }
            config.copy(baseUrl = url, model = config.model.trim())
        }
    }

    /** On-device AI for the saved key, or null when AI goes through the server. One per key, so its rate limit holds. */
    private var onDevice: Pair<Pair<AiProviderConfig, String>, OnDeviceAi>? = null

    /**
     * The on-device AI for the saved key, or null when AI goes through the server; the instance is reused while
     * the key and provider stay the same.
     */
    private suspend fun ownKeyAi(): OnDeviceAi? {
        val s = settings.current()
        if (!usesOwnKey(s)) return null
        val key = s.aiProviderConfig to s.geminiKey
        onDevice?.let { (saved, ai) -> if (saved == key) return ai }
        return OnDeviceAi(chatModel(s.aiProviderConfig, s.geminiKey)!!, houses).also { onDevice = key to it }
    }

    /**
     * Runs [block] against the configured server on the IO dispatcher; throws `IllegalStateException` when no
     * server is set.
     */
    private suspend fun <T> withApi(block: suspend (ApiClient) -> T): T = withContext(Dispatchers.IO) {
        val s = settings.current()
        check(s.serverConfigured) { "Server not configured" }
        block(apiFor(s.serverUrl, s.apiKey))
    }

    // The same three calls, answered by the server or on this device (ADR-26): the screens do not know which.
    suspend fun extractListing(text: String): HouseDraftDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.extractListing(text) } } ?: withApi { it.extractListing(text) }

    suspend fun ask(question: String): AskResponseDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.ask(question) } } ?: withApi { it.ask(question) }

    suspend fun planVisits(request: PlanRequest): PlanResponseDto =
        ownKeyAi()?.let { withContext(Dispatchers.IO) { it.planVisits(request) } } ?: withApi { it.planVisits(request) }
}
