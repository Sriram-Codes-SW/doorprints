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
import app.doorprints.shared.api.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-U-251 (docs/06): one microphone button's recording (voice PR 5, S4b-BL-222), through `VoiceSession` with a fake
 * recorder and a canned transcriber, on virtual time: the microphone is opened only by `start()`; Speak, Stop, Cancel; the
 * words go to the box and nothing else; the 60 s and 2 MB limits; and **every exit lets the recorder go** (stop, cancel,
 * release, a failed start, a dead recorder, a limit, the permission lost mid-recording, the setting turned off), with a late
 * answer dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    private class FakeRecorder(
        var failOnStart: VoiceFailure? = null,
        var ms: Long = 0,
        var bytes: Int = 1_000,
        var failure: VoiceFailure? = null,
    ) : VoiceRecorder {
        var started = 0
        var stopped = 0
        var released = 0
        var stopFails = false
        var clipLimit: VoiceLimit? = null

        override fun start() {
            started++
            failOnStart?.let { throw VoiceRecorderException(it) }
        }

        override fun elapsedMs() = ms
        override fun sizeBytes() = bytes
        override fun failure() = failure

        override fun stop(): RecordedClip {
            stopped++
            released++
            if (stopFails) throw VoiceRecorderException(VoiceFailure.FAILED)
            return RecordedClip(ByteArray(10), "audio/wav", ms, clipLimit)
        }

        override fun release() {
            released++
        }
    }

    private class Rig(scope: TestScope) {
        val recorders = mutableListOf<FakeRecorder>()
        var next = { FakeRecorder() }
        var granted = true
        var answer: suspend (RecordedClip) -> Transcript = { Transcript("two bhk near Baner", "en-IN") }
        val clips = mutableListOf<RecordedClip>()
        val box = mutableListOf<String>()
        var boxCuts = false
        var maxMs = AudioLimits.MAX_DURATION_MS
        val session = VoiceSession(
            scope = scope.backgroundScope,
            newRecorder = { next().also { recorders += it } },
            permitted = { granted },
            transcribe = { clip -> clips += clip; answer(clip) },
            onWords = { box += it; boxCuts },
            maxDurationMs = { maxMs },
        )
        val last get() = recorders.last()
    }

    @Test
    fun theMicrophoneIsOpenedOnlyByStartAndAnIdleSessionTouchesNothing() = runTest {
        val r = Rig(this)
        runCurrent()
        assertTrue(r.recorders.isEmpty())
        assertEquals(VoicePhase.IDLE, r.session.ui.value.phase)
    }

    @Test
    fun speakStopGivesTheWordsToTheBoxAndNothingElse() = runTest {
        val r = Rig(this)
        r.session.start()
        assertEquals(VoicePhase.RECORDING, r.session.ui.value.phase)
        assertEquals(VoiceStatus.RECORDING, r.session.ui.value.status)
        r.last.ms = 7_400
        advanceTimeBy(250); runCurrent()
        assertEquals(7, r.session.ui.value.seconds)
        r.session.stop()
        assertEquals(VoicePhase.TRANSCRIBING, r.session.ui.value.phase)
        runCurrent()
        assertEquals(listOf("two bhk near Baner"), r.box)
        assertEquals(VoiceStatus.DONE, r.session.ui.value.status)
        assertEquals(VoicePhase.IDLE, r.session.ui.value.phase)
        assertEquals(1, r.clips.size)
        assertEquals(1, r.last.stopped)
    }

    @Test
    fun theWordsAreTrimmedAndEmptySpeechIsNothingHeard() = runTest {
        val r = Rig(this)
        r.answer = { Transcript("  ", "und") }
        r.session.start(); r.session.stop(); runCurrent()
        assertTrue(r.box.isEmpty())
        assertEquals(VoiceProblem.NOTHING_HEARD, r.session.ui.value.problem)
        assertNull(r.session.ui.value.status)
    }

    @Test
    fun aBoxThatCouldNotHoldAllTheWordsSaysSo() = runTest {
        val r = Rig(this)
        r.boxCuts = true
        r.session.start(); r.session.stop(); runCurrent()
        assertEquals(VoiceStatus.DONE_CUT, r.session.ui.value.status)
    }

    @Test
    fun theSixtySecondLimitStopsTheRecordingByItselfAndTheWordsFollow() = runTest {
        val r = Rig(this)
        r.session.start()
        r.last.ms = 60_000
        r.last.clipLimit = null
        advanceTimeBy(250); runCurrent()
        assertEquals(1, r.last.stopped)
        assertEquals(VoiceLimit.TIME, r.clips.single().limit)
        assertEquals(VoiceStatus.DONE_LIMIT, r.session.ui.value.status)
        assertEquals(1, r.box.size)
    }

    @Test
    fun sarvamsThirtySecondLimitStopsTheRecordingByItselfWhereTheMinuteWouldNot() = runTest {
        val r = Rig(this)
        r.maxMs = AudioLimits.SARVAM_MAX_DURATION_MS
        r.session.start()
        r.last.ms = 29_000
        advanceTimeBy(250); runCurrent()
        assertEquals(0, r.last.stopped, "29 s is inside the limit")
        r.last.ms = 30_000
        advanceTimeBy(250); runCurrent()
        assertEquals(1, r.last.stopped)
        assertEquals(VoiceLimit.TIME, r.clips.single().limit)
        assertEquals(VoiceStatus.DONE_LIMIT, r.session.ui.value.status)
        assertEquals(30_000L, AudioLimits.SARVAM_MAX_DURATION_MS)
    }

    @Test
    fun theSizeCapStopsTheRecordingByItself() = runTest {
        val r = Rig(this)
        r.session.start()
        r.last.bytes = 2 * 1024 * 1024
        advanceTimeBy(250); runCurrent()
        assertEquals(1, r.last.stopped)
        assertEquals(VoiceLimit.SIZE, r.clips.single().limit)
    }

    @Test
    fun withoutThePermissionNothingIsOpenedAndTheProblemSaysDenied() = runTest {
        val r = Rig(this)
        r.granted = false
        r.session.start()
        assertTrue(r.recorders.isEmpty())
        assertEquals(VoiceProblem.DENIED, r.session.ui.value.problem)
    }

    @Test
    fun aFailedStartIsToldApartAndReleasesTheRecorder() = runTest {
        for ((failure, problem) in listOf(
            VoiceFailure.DENIED to VoiceProblem.DENIED, VoiceFailure.NO_DEVICE to VoiceProblem.NO_DEVICE, VoiceFailure.FAILED to VoiceProblem.FAILED,
        )) {
            val r = Rig(this)
            r.next = { FakeRecorder(failOnStart = failure) }
            r.session.start()
            assertEquals(problem, r.session.ui.value.problem)
            assertEquals(VoicePhase.IDLE, r.session.ui.value.phase)
            assertEquals(1, r.last.released, "$failure")
        }
    }

    @Test
    fun aRecorderThatDiesMidRecordingEndsItAndReleasesIt() = runTest {
        val r = Rig(this)
        r.session.start()
        r.last.failure = VoiceFailure.FAILED
        advanceTimeBy(250); runCurrent()
        assertEquals(VoiceProblem.FAILED, r.session.ui.value.problem)
        assertEquals(1, r.last.released)
        assertTrue(r.clips.isEmpty())
    }

    @Test
    fun thePermissionTakenAwayMidRecordingEndsItAndReleasesIt() = runTest {
        val r = Rig(this)
        r.session.start()
        r.granted = false
        advanceTimeBy(250); runCurrent()
        assertEquals(VoiceProblem.DENIED, r.session.ui.value.problem)
        assertEquals(1, r.last.released)
        assertTrue(r.clips.isEmpty())
    }

    @Test
    fun cancelWhileRecordingThrowsTheAudioAwayAndSendsNothing() = runTest {
        val r = Rig(this)
        r.session.start()
        r.session.cancel()
        runCurrent()
        assertEquals(1, r.last.released)
        assertEquals(0, r.last.stopped)
        assertEquals(VoiceStatus.CANCELLED, r.session.ui.value.status)
        assertTrue(r.clips.isEmpty())
        advanceTimeBy(5_000); runCurrent() // the clock is stopped too
        assertEquals(VoicePhase.IDLE, r.session.ui.value.phase)
    }

    @Test
    fun cancelWhileTheWordsAreBeingWrittenDropsTheLateAnswer() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Transcript>()
        r.answer = { gate.await() }
        r.session.start(); r.session.stop(); runCurrent()
        r.session.cancel()
        gate.complete(Transcript("late words", "en"))
        runCurrent()
        assertTrue(r.box.isEmpty())
        assertEquals(VoiceStatus.CANCELLED, r.session.ui.value.status)
    }

    @Test
    fun releaseOnPauseOrLeavingOrSettingOffLetsTheMicrophoneGoQuietly() = runTest {
        val r = Rig(this)
        r.session.start()
        r.session.release()
        assertEquals(1, r.last.released)
        assertEquals(VoiceUi(), r.session.ui.value)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.clips.isEmpty())
        r.session.release() // safe to repeat
    }

    @Test
    fun releaseWhileTranscribingDropsTheAnswer() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Transcript>()
        r.answer = { gate.await() }
        r.session.start(); r.session.stop(); runCurrent()
        r.session.release()
        gate.complete(Transcript("late words", "en"))
        runCurrent()
        assertTrue(r.box.isEmpty())
        assertEquals(VoiceUi(), r.session.ui.value)
    }

    @Test
    fun aStopThatFindsNoClipIsAFailureAndReleases() = runTest {
        val r = Rig(this)
        r.session.start()
        r.last.stopFails = true
        r.session.stop()
        assertEquals(VoiceProblem.FAILED, r.session.ui.value.problem)
        assertEquals(2, r.last.released)
    }

    @Test
    fun aSecondTapWhileBusyDoesNotOpenASecondMicrophone() = runTest {
        val r = Rig(this)
        r.session.start()
        r.session.start()
        assertEquals(1, r.recorders.size)
        r.session.stop()
        r.session.start() // while writing out
        assertEquals(1, r.recorders.size)
    }

    @Test
    fun aProviderFailureIsKeptForTheScreenToWord() = runTest {
        val r = Rig(this)
        val failure = ApiException(ApiException.Kind.AI_UNAVAILABLE, 502)
        r.answer = { throw failure }
        r.session.start(); r.session.stop(); runCurrent()
        assertEquals(VoiceProblem.PROVIDER, r.session.ui.value.problem)
        assertEquals(failure, r.session.ui.value.error)
        assertTrue(r.box.isEmpty())
    }

    @Test
    fun aClipTheCoreRefusesIsToldApart() = runTest {
        val cases = mapOf(
            AudioClipRejected.Reason.EMPTY to VoiceProblem.NOTHING_HEARD,
            AudioClipRejected.Reason.TOO_LARGE to VoiceProblem.TOO_LONG,
            AudioClipRejected.Reason.TOO_LONG to VoiceProblem.TOO_LONG,
            AudioClipRejected.Reason.UNSUPPORTED_TYPE to VoiceProblem.FAILED,
        )
        for ((reason, problem) in cases) {
            val r = Rig(this)
            r.answer = { throw AudioClipRejected(reason) }
            r.session.start(); r.session.stop(); runCurrent()
            assertEquals(problem, r.session.ui.value.problem, "$reason")
        }
    }

    @Test
    fun aNewRunStartsCleanAfterAProblem() = runTest {
        val r = Rig(this)
        r.granted = false
        r.session.start()
        assertEquals(VoiceProblem.DENIED, r.session.ui.value.problem)
        r.granted = true
        r.session.start()
        assertNull(r.session.ui.value.problem)
        assertEquals(VoicePhase.RECORDING, r.session.ui.value.phase)
    }

    @Test
    fun theWordsNeverAutoRunAnythingTheOnlyOutputIsTheBoxCallback() = runTest {
        val r = Rig(this)
        r.session.start(); r.session.stop(); runCurrent()
        // The session has no other output: one clip went to transcribe, one string to the box.
        assertEquals(1, r.clips.size)
        assertEquals(1, r.box.size)
    }
}
