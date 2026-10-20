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

import app.doorprints.shared.ai.AudioClipRejected
import app.doorprints.shared.ai.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one microphone button is: nothing, waiting for the microphone, recording, or writing out the words. */
enum class VoicePhase { IDLE, STARTING, RECORDING, TRANSCRIBING }

/** The polite status line (announced by TalkBack, not repeated each second); null when there is none. */
enum class VoiceStatus { STARTING, RECORDING, WORKING, DONE, DONE_LIMIT, DONE_CUT, DONE_EARLY, CANCELLED }

/** Why the last try did not give words; [PROVIDER] carries the service's own failure in [VoiceUi.error]. */
enum class VoiceProblem { DENIED, NO_DEVICE, FAILED, NOTHING_HEARD, TOO_LONG, PROVIDER, PLAN_FREE, PLAN_UNKNOWN }

/** What the button shows: [seconds] is the recording's length so far; at most one of [status] and [problem] is set. */
data class VoiceUi(
    val phase: VoicePhase = VoicePhase.IDLE,
    val seconds: Int = 0,
    val status: VoiceStatus? = null,
    val problem: VoiceProblem? = null,
    val error: Throwable? = null,
)

/**
 * One microphone button's recording, from the tap to the words (voice input, docs/03 ADR-37; the twin of the website's
 * `VoiceButton`). Tap to talk: [start] opens the recorder, a second tap ([stop]) or a limit ends it, the clip goes to
 * [transcribe] (the person's own AI) and the words are handed to [onWords], which puts them in the box and says whether
 * the end was cut. Nothing is sent from here and nothing is auto-run: the screen's own button does that later.
 *
 * **Every exit lets the microphone go** and drops a late answer (T-I47, T-I48): [stop], [cancel], [release] (the screen
 * paused or left, the setting turned off), a recorder that fails, a limit (60 s, 2 MB), the permission lost during the
 * recording, and a failure to start. The audio lives in the recorder and in [transcribe]'s argument, and is stored,
 * logged and copied nowhere; the exceptions that reach [ui] are classified, never shown with a clip.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val newRecorder: () -> VoiceRecorder,
    /** Whether the microphone permission is held now; read at [start] and on every tick (it can be taken away). */
    private val permitted: () -> Boolean,
    private val transcribe: suspend (RecordedClip) -> Transcript,
    /** Puts [String] in the box; true when the box could not hold all of it. */
    private val onWords: (String) -> Boolean,
    private val tickMs: Long = 200,
    /** The hard stop of a recording, read on every tick (60 s, or Sarvam's 30 s). */
    private val maxDurationMs: () -> Long = { AudioLimits.MAX_DURATION_MS },
) {
    private val state = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = state.asStateFlow()

    private var recorder: VoiceRecorder? = null
    private var ticker: Job? = null
    private var work: Job? = null

    /** Bumped by every start and every exit, so a late answer of an earlier run is thrown away, never written into the box. */
    private var run = 0

    /** The tap that starts: the microphone is opened here and nowhere else. Ignored while a run is on. */
    fun start() {
        if (state.value.phase != VoicePhase.IDLE) return
        val mine = ++run
        if (!permitted()) {
            state.value = VoiceUi(problem = VoiceProblem.DENIED)
            return
        }
        state.value = VoiceUi(VoicePhase.STARTING, status = VoiceStatus.STARTING)
        val r = newRecorder()
        recorder = r
        try {
            r.start()
        } catch (e: VoiceRecorderException) {
            fail(mine, e.reason.problem())
            return
        } catch (e: Exception) {
            fail(mine, VoiceProblem.FAILED)
            return
        }
        state.value = VoiceUi(VoicePhase.RECORDING, status = VoiceStatus.RECORDING)
        ticker = scope.launch {
            while (true) {
                delay(tickMs)
                tick(mine)
            }
        }
    }

    /** The second tap: the recording ends and its words are asked for. */
    fun stop() {
        if (state.value.phase == VoicePhase.RECORDING) finishRecording(run, null)
    }

    /** Cancel (or Back): the recording or the pending words are thrown away; the microphone is let go. */
    fun cancel() {
        if (state.value.phase == VoicePhase.IDLE) return
        discard()
        state.value = VoiceUi(status = VoiceStatus.CANCELLED)
    }

    /** The screen paused, was left or the setting turned off: everything ends quietly with nothing kept. */
    fun release() {
        discard()
        state.value = VoiceUi()
    }

    private fun tick(mine: Int) {
        if (mine != run) return
        val r = recorder ?: return
        val failure = r.failure()
        when {
            // Cut short from outside (a call, a changed microphone, the app leaving the front): what was said so far is kept.
            failure == VoiceFailure.ENDED_EARLY -> finishRecording(mine, null, early = true)
            failure != null -> fail(mine, failure.problem())
            !permitted() -> fail(mine, VoiceProblem.DENIED)
            r.elapsedMs() >= maxDurationMs() -> finishRecording(mine, VoiceLimit.TIME)
            r.sizeBytes() >= AudioLimits.MAX_BYTES -> finishRecording(mine, VoiceLimit.SIZE)
            else -> state.value = state.value.copy(seconds = (r.elapsedMs() / 1000).toInt())
        }
    }

    /**
     * Ends the recording and asks for its words. The recorder's [VoiceRecorder.stop] suspends for the last buffered words
     * (S4b-BL-317), so it runs in [work] and the screen shows *writing out* from the tap; a cancel or a release meanwhile
     * ends the run and the recorder lets everything go. [early]: the recorder was cut short from outside; its clip is kept and
     * transcribed, and a clip of zero length ends quietly (nothing is shown).
     */
    private fun finishRecording(mine: Int, limit: VoiceLimit?, early: Boolean = false) {
        val r = recorder ?: return
        ticker?.cancel()
        ticker = null
        state.value = VoiceUi(VoicePhase.TRANSCRIBING, status = VoiceStatus.WORKING)
        work = scope.launch {
            try {
                val clip = try {
                    r.stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (mine != run) return@launch
                    if (early) {
                        discard()
                        state.value = VoiceUi()
                    } else {
                        fail(mine, VoiceProblem.FAILED)
                    }
                    return@launch
                }
                if (mine != run) return@launch
                recorder = null
                val heard = if (limit == null || clip.limit != null) clip else RecordedClip(clip.bytes, clip.mime, clip.durationMs, limit)
                val words = transcribe(heard).text.trim()
                if (mine != run) return@launch
                if (words.isEmpty()) {
                    state.value = VoiceUi(problem = VoiceProblem.NOTHING_HEARD)
                    return@launch
                }
                val cut = onWords(words)
                state.value = VoiceUi(status = when {
                    cut -> VoiceStatus.DONE_CUT
                    heard.limit != null -> VoiceStatus.DONE_LIMIT
                    early -> VoiceStatus.DONE_EARLY
                    else -> VoiceStatus.DONE
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (mine != run) return@launch
                state.value = when {
                    e is AudioClipRejected -> VoiceUi(problem = when (e.reason) {
                        AudioClipRejected.Reason.EMPTY -> VoiceProblem.NOTHING_HEARD
                        AudioClipRejected.Reason.TOO_LARGE, AudioClipRejected.Reason.TOO_LONG -> VoiceProblem.TOO_LONG
                        AudioClipRejected.Reason.UNSUPPORTED_TYPE -> VoiceProblem.FAILED
                    })
                    e is VoiceTierRefused -> VoiceUi(problem = if (e.verdict == VoiceVerdict.REFUSE_FREE) VoiceProblem.PLAN_FREE else VoiceProblem.PLAN_UNKNOWN)
                    else -> VoiceUi(problem = VoiceProblem.PROVIDER, error = e)
                }
            } finally {
                if (mine == run) work = null
            }
        }
    }

    private fun fail(mine: Int, problem: VoiceProblem) {
        if (mine != run) return
        discard()
        state.value = VoiceUi(problem = problem)
    }

    /** Every exit: the run ends (a late answer is dropped), the recorder lets the microphone go, the clock stops. */
    private fun discard() {
        run++
        ticker?.cancel()
        ticker = null
        work?.cancel()
        work = null
        recorder?.release()
        recorder = null
    }

    private fun VoiceFailure.problem(): VoiceProblem = when (this) {
        VoiceFailure.DENIED -> VoiceProblem.DENIED
        VoiceFailure.NO_DEVICE -> VoiceProblem.NO_DEVICE
        VoiceFailure.FAILED, VoiceFailure.ENDED_EARLY -> VoiceProblem.FAILED
    }
}
