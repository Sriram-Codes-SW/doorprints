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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.AiProviderConfig
import app.doorprints.shared.ai.BaseUrlCheck
import app.doorprints.shared.ai.BaseUrlValidator
import app.doorprints.shared.voice.VoiceChoice
import app.doorprints.shared.voice.VoiceProvider
import app.doorprints.shared.voice.VoiceInput
import app.doorprints.shared.voice.VoicePhase
import app.doorprints.shared.voice.VoiceProblem
import app.doorprints.shared.voice.VoiceSession
import app.doorprints.shared.voice.VoiceStatus
import app.doorprints.shared.voice.VoiceVerdict
import app.doorprints.shared.voice.appendTranscript
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The host the audio goes to for [config], for the disclosure: Google for Gemini, else the address' host (empty while the
 * address is not valid, which makes the own AI unusable anyway).
 */
internal fun voiceHost(config: AiProviderConfig): String = when (config.kind) {
    AiKind.GEMINI -> AiProviderForm.GEMINI_HOST
    else -> (BaseUrlValidator.check(config.baseUrl) as? BaseUrlCheck.Valid)?.host.orEmpty()
}

/** Tag of the line under the microphone: the usual hint, or the plan rule's reason when the microphone cannot be used; for tests. */
const val VOICE_TIER_REASON_TAG = "voice-tier-reason"

/** The microphone is offered, where the audio would go, and the hard stop of a recording (Sarvam's is the short one, 30 s). */
internal class VoiceOffer(val host: String, val price: String?, val maxMs: Long, val short: Boolean, val tier: VoiceTierState)

/**
 * Whether the microphone is offered now (docs/03 ADR-37): the *Voice input* switch is on, *AI features* are on with the
 * person's **own** AI, that AI takes audio (Gemini, OpenAI, Groq, OpenRouter) and this phone can record. Nothing is
 * decided from the server: with the server's AI, Anthropic, Ollama, LM Studio or a custom address there is no microphone.
 * Reads the switch and the provider choice (no key) and two flags that already exist; null when not offered.
 */
@Composable
internal fun rememberVoiceOffer(): VoiceOffer? {
    val services = LocalAppServices.current
    val repo = services.repository
    val choice by repo.settings.voiceChoice.collectAsStateWithLifecycle(VoiceChoice())
    val aiOn by repo.aiEnabled.collectAsStateWithLifecycle()
    val ownKey by repo.aiOwnKey.collectAsStateWithLifecycle()
    if (!VoiceInput.available(choice, aiOn, ownKey, services.voice.recordable)) return null
    // The plan rule (ADR-37): the microphone is drawn, but not usable, until it says ALLOW. Nothing records before that.
    val tier = rememberVoiceTier(choice, ownKey)
    return VoiceOffer(
        VoiceInput.host(choice, voiceHost(choice.config)), VoiceInput.price(choice), VoiceInput.maxDurationMs(choice),
        short = choice.provider == VoiceProvider.SARVAM, tier = tier,
    )
}

/**
 * The microphone under a text box (voice input, docs/03 ADR-37; the twin of the website's `VoiceButton`), built once and
 * placed under the three AI boxes: *Ask a question*, *Plan notes* and *Fill in from listing text*. Drawn only when
 * [rememberVoiceOffer] says so; otherwise nothing is drawn and the microphone is never touched.
 *
 * One tap starts recording (the first time, after a rationale and Android's own prompt), a second tap stops it, *Cancel*
 * throws it away. The words land at the end of the box as ordinary text ([appendTranscript], cut at [max]) and nothing is
 * sent: the screen's own Ask, Plan or Fill in button does that later, after the person has read and edited the words.
 * Recording is foreground only: pausing the screen (home, another app, the lock screen, a call, a rotation), leaving it or
 * turning the setting off lets the microphone go with nothing kept. The audio is held in memory by the recorder for the one
 * call (T-I48).
 *
 * Accessible: a real button whose spoken name starts with its visible word, a polite status ("Recording", "Writing out the
 * words", "Done") that is not repeated each second (the clock is hidden from TalkBack), failures in an assertive live
 * region that is on screen before they come, 48 dp targets, and a row that wraps at large text.
 */
@Composable
fun VoiceMicButton(value: String, onValue: (String) -> Unit, kind: String, modifier: Modifier = Modifier, max: Int? = null) {
    val offer = rememberVoiceOffer() ?: return
    VoiceMic(offer, value, onValue, kind, max, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceMic(offer: VoiceOffer, value: String, onValue: (String) -> Unit, kind: String, max: Int?, modifier: Modifier) {
    val voice = LocalAppServices.current.voice
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    val language = uiLanguage()
    val text by rememberUpdatedState(value)
    val put by rememberUpdatedState(onValue)
    val space by rememberUpdatedState(kind)
    val lang by rememberUpdatedState(language)
    val limit by rememberUpdatedState(max)
    val maxMs by rememberUpdatedState(offer.maxMs)
    var rationale by remember { mutableStateOf(false) }
    val session = remember {
        VoiceSession(
            scope = scope,
            newRecorder = voice::newRecorder,
            permitted = voice::microphoneGranted,
            transcribe = { clip -> voice.transcribe(clip, space, lang) },
            maxDurationMs = { maxMs },
            onWords = { words ->
                val inserted = appendTranscript(text, words, limit)
                put(inserted.text)
                inserted.cut
            },
        )
    }
    // Foreground only (T-I47): pausing the screen (home, another app, the lock screen, a call, a rotation) and leaving it
    // both let the microphone go, with nothing kept. The permission prompt pauses the screen too, before anything records.
    LifecycleResumeEffect(session) { onPauseOrDispose { session.release() } }
    // The plan turned out to be a free one (or could not be read) while a recording is on: it ends, with nothing kept.
    LaunchedEffect(offer.tier.allowed) { if (!offer.tier.allowed) session.release() }
    val request = voice.rememberMicrophoneRequest { session.start() }
    val ui by session.ui.collectAsStateWithLifecycle()

    val word = stringResource(
        when (ui.phase) {
            VoicePhase.RECORDING -> Res.string.voice_stop
            VoicePhase.STARTING, VoicePhase.TRANSCRIBING -> Res.string.voice_working
            VoicePhase.IDLE -> Res.string.voice_mic
        },
    )
    val label = stringResource(
        when (ui.phase) {
            VoicePhase.RECORDING -> Res.string.voice_stop_desc
            VoicePhase.STARTING, VoicePhase.TRANSCRIBING -> Res.string.voice_working
            VoicePhase.IDLE -> Res.string.voice_mic_desc
        },
    )
    val busy = ui.phase == VoicePhase.STARTING || ui.phase == VoicePhase.TRANSCRIBING
    val recording = ui.phase == VoicePhase.RECORDING
    val cancelLabel = stringResource(Res.string.voice_cancel_desc)
    val errorText = aiErrorText(if (offer.host == AiProviderForm.GEMINI_HOST) "" else offer.host)
    val tier = offer.tier
    val allowed = tier.allowed
    val tierReason = when (tier.verdict) {
        VoiceVerdict.ALLOW -> null
        VoiceVerdict.CHECKING -> stringResource(Res.string.voice_tier_checking)
        VoiceVerdict.REFUSE_FREE -> stringResource(Res.string.voice_tier_free, tier.providerName)
        VoiceVerdict.REFUSE_UNKNOWN -> stringResource(Res.string.voice_tier_unknown, tier.providerName)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // One call site for Speak, Stop and Writing out, so TalkBack's focus stays on it through the states.
            StateButton(
                text = word,
                onClick = {
                    when (ui.phase) {
                        // The plan rule first: no rationale, no permission prompt, no microphone until it says ALLOW.
                        VoicePhase.IDLE -> if (!allowed) {} else if (voice.microphoneGranted()) session.start() else rationale = true
                        VoicePhase.RECORDING -> session.stop()
                        else -> {}
                    }
                },
                style = BarButtonStyle.OUTLINED,
                enabled = !busy && (allowed || recording),
                icon = if (recording) StopIcon else MicIcon,
                modifier = Modifier.semantics {
                    contentDescription = label
                    // A refused microphone names its reason as its description (the website's aria-describedby).
                    tierReason?.let { stateDescription = it }
                },
            )
            if (recording || busy) {
                TextButton(
                    onClick = session::cancel,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cancelLabel },
                ) { Text(stringResource(Res.string.voice_cancel)) }
            }
            if (recording) {
                // The clock is for the eyes; the status line says "Recording" once (TalkBack is not read the seconds).
                Text(
                    "${ui.seconds / 60}:${(ui.seconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Text(
            tierReason ?: stringResource(
                when {
                    offer.short -> if (voice.iphoneWording) Res.string.voice_hint_ios_short else Res.string.voice_hint_short
                    else -> if (voice.iphoneWording) Res.string.voice_hint_ios else Res.string.voice_hint
                },
                offer.host,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(VOICE_TIER_REASON_TAG),
        )
        val statusText = ui.status?.let { stringResource(it.res(offer.short)) }
        LiveMessage {
            statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LiveMessage(assertive = true) {
            ui.problem?.let { problem ->
                val message = when (problem) {
                    VoiceProblem.PROVIDER -> errorText(ui.error ?: IllegalStateException())
                    VoiceProblem.PLAN_FREE -> stringResource(Res.string.voice_tier_free, tier.providerName)
                    VoiceProblem.PLAN_UNKNOWN -> stringResource(Res.string.voice_tier_unknown, tier.providerName)
                    else -> stringResource(problem.res())
                }
                ResultCard(ResultTone.ERROR, message) {
                    if (problem == VoiceProblem.DENIED) {
                        TextButton(
                            onClick = platform::openAppSettings,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(Res.string.voice_open_settings)) }
                    }
                }
            }
        }
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(Res.string.voice_rationale_title)) },
            text = {
                Text(
                    stringResource(
                        when {
                            offer.short -> if (voice.iphoneWording) Res.string.voice_rationale_body_ios_short else Res.string.voice_rationale_body_short
                            else -> if (voice.iphoneWording) Res.string.voice_rationale_body_ios else Res.string.voice_rationale_body
                        },
                        offer.host,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { rationale = false; request() }) { Text(stringResource(Res.string.voice_rationale_go)) }
            },
            dismissButton = { TextButton(onClick = { rationale = false }) { Text(stringResource(Res.string.voice_rationale_no)) } },
        )
    }
}

private fun VoiceStatus.res(short: Boolean) = when (this) {
    VoiceStatus.STARTING -> Res.string.voice_status_starting
    VoiceStatus.RECORDING -> Res.string.voice_status_recording
    VoiceStatus.WORKING -> Res.string.voice_status_working
    VoiceStatus.DONE -> Res.string.voice_status_done
    VoiceStatus.DONE_LIMIT -> if (short) Res.string.voice_status_done_limit_short else Res.string.voice_status_done_limit
    VoiceStatus.DONE_CUT -> Res.string.voice_status_done_cut
    VoiceStatus.DONE_EARLY -> Res.string.voice_status_done_early
    VoiceStatus.CANCELLED -> Res.string.voice_status_cancelled
}

private fun VoiceProblem.res() = when (this) {
    VoiceProblem.DENIED -> Res.string.voice_denied
    VoiceProblem.NO_DEVICE -> Res.string.voice_no_device
    VoiceProblem.FAILED, VoiceProblem.PROVIDER, VoiceProblem.PLAN_FREE, VoiceProblem.PLAN_UNKNOWN -> Res.string.voice_failed
    VoiceProblem.NOTHING_HEARD -> Res.string.voice_nothing_heard
    VoiceProblem.TOO_LONG -> Res.string.voice_too_long
}

/** Material's microphone and stop glyphs (the core icon set has neither), drawn from their path data. */
private val MicIcon: ImageVector by lazy {
    ImageVector.Builder("mic", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M12,14c1.66,0 3,-1.34 3,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.91,11c-0.49,0 -0.9,0.36 -0.98,0.85C16.52,14.2 14.47,16 12,16s-4.52,-1.8 -4.93,-4.15c-0.08,-0.49 -0.49,-0.85 -0.98,-0.85 -0.61,0 -1.09,0.54 -1,1.14 0.49,3 2.89,5.35 5.91,5.78L11,20c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2.08c3.02,-0.43 5.42,-2.78 5.91,-5.78 0.1,-0.6 -0.39,-1.14 -1,-1.14z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

private val StopIcon: ImageVector by lazy {
    ImageVector.Builder("stop", 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes("M6,6h12v12H6z"), fill = SolidColor(Color.Black)).build()
}
