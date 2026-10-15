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

import app.doorprints.shared.voice.PcmSource
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorderException
import app.doorprints.shared.voice.pcm.ByteQueue
import app.doorprints.shared.voice.pcm.CaptureEndRules
import app.doorprints.shared.voice.pcm.Pcm16Resampler
import app.doorprints.shared.voice.pcm.mixToMono
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.currentRoute
import platform.AVFAudio.inputAvailable
import platform.AVFAudio.setActive
import platform.Foundation.NSCondition
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillResignActiveNotification
import kotlin.concurrent.Volatile

/**
 * The iPhone's microphone for voice input as a stream of 16 kHz mono 16-bit PCM (S4b-BL-223, docs/03 ADR-37): an
 * `AVAudioEngine` input tap whose Float32 buffers go through [Pcm16Resampler] into a small in-memory [ByteQueue], which
 * [read] hands to `PcmVoiceRecorder`. **Nothing is written anywhere** (T-I48): no `AVAudioRecorder`, no `AVAudioFile`, no
 * URL, no temp file, no preference, no log line; the only copy of the audio besides the recorder's own array is the queue
 * of at most [QUEUE_BYTES], zeroed on [close].
 *
 * `AVAudioSession` is set to the *record* category and made active in [open] (after the permission is known to be given),
 * and made inactive again in [close], so other apps' audio resumes. Recording is **foreground only** and ends, with
 * the capture stops when the app resigns active (home, the app switcher, Control Center, a call, Siri, the lock button), an
 * interruption begins, the microphone in use changes (the route), the engine's configuration changes or the media
 * services are reset ([CaptureEndRules]). That is an *early end* (S4b-BL-318): the tap is removed and the engine stopped at
 * once (the microphone is let go), the queue is **kept** and handed out by [read], then [read] returns a negative number and
 * [endedEarly] is true, so the recorder reports `VoiceFailure.ENDED_EARLY` and the session keeps and writes out what was said
 * so far. The audio stays in memory; the session soon calls [close], which discards the rest and makes the audio session
 * inactive. A release (the screen paused or left) still discards everything.
 *
 * Threads: [open] and [close] may be called from any thread; the tap runs on an audio thread and [read] on the
 * recorder's capture thread; a lock (an `NSCondition`) guards the queue and the end flags.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPcmSource : PcmSource {
    private val lock = NSCondition()
    private val queue = ByteQueue(QUEUE_BYTES)
    private var ended = false // guarded by lock: read() reports the end and the tap drops what comes
    private var tornDown = false // guarded by lock: close() has run its clean-up
    private var started = false // guarded by lock: open() got far enough that close() has something to undo
    private var early = false // guarded by lock: something other than the person ended the capture; the queue is kept

    private var engine: AVAudioEngine? = null // guarded by lock (set in open before the tap starts)
    private var resampler: Pcm16Resampler? = null
    private var mono = FloatArray(0)
    private var inputAtStart: String? = null
    private val observers = mutableListOf<Any>()

    override fun open() {
        if (microphonePermission() != MicrophonePermission.Granted) throw VoiceRecorderException(VoiceFailure.DENIED)
        // Foreground only: not from a scene that is not in front (the tap is always from the screen, so this is a guard).
        IosForeground.install()
        if (!IosForeground.active) throw VoiceRecorderException(VoiceFailure.FAILED)
        val session = AVAudioSession.sharedInstance()
        if (!session.inputAvailable) throw VoiceRecorderException(VoiceFailure.NO_DEVICE)
        try {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!session.setCategory(AVAudioSessionCategoryRecord, error.ptr)) throw VoiceRecorderException(VoiceFailure.FAILED)
                lock.lock()
                started = true
                lock.unlock()
                if (!session.setActive(true, error.ptr)) throw VoiceRecorderException(VoiceFailure.FAILED)
            }
            inputAtStart = currentInputUid(session)
            val audio = AVAudioEngine()
            val input = audio.inputNode
            val format = input.outputFormatForBus(0u)
            // The simulator without a microphone, or an input being taken away, reports a zero format; tapping it would throw.
            if (format.sampleRate <= 0.0 || format.channelCount == 0u) throw VoiceRecorderException(VoiceFailure.NO_DEVICE)
            val channels = format.channelCount.toInt()
            resampler = Pcm16Resampler(format.sampleRate)
            lock.lock()
            engine = audio
            lock.unlock()
            watch(session, audio)
            input.installTapOnBus(0u, TAP_FRAMES, format) { buffer, _ -> if (buffer != null) take(buffer, channels) }
            audio.prepare()
            val running = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                audio.startAndReturnError(error.ptr)
            }
            if (!running) throw VoiceRecorderException(VoiceFailure.FAILED)
        } catch (e: Throwable) {
            close()
            if (e is VoiceRecorderException) throw e
            throw VoiceRecorderException(VoiceFailure.FAILED)
        }
    }

    /** One buffer from the tap (an audio thread): to mono, to 16 kHz, into the queue, and a wake-up for [read]. */
    private fun take(buffer: AVAudioPCMBuffer, channels: Int) {
        try {
            val frames = buffer.frameLength.toInt()
            val data = buffer.floatChannelData
            if (frames <= 0 || data == null) return
            if (mono.size < frames) mono = FloatArray(frames)
            val usable = minOf(channels, buffer.format.channelCount.toInt()).coerceAtLeast(1)
            mixToMono(usable, frames, mono) { c, i -> data[c]!![i] }
            lock.lock()
            try {
                if (ended) return
                resampler?.pushTo(mono, frames, queue)
                lock.broadcast()
            } finally {
                lock.unlock()
            }
        } catch (e: Throwable) {
            // An exception must not leave this callback (it would end the app): the source ends, [read] reports it, and the
            // recorder lets everything go from the main thread.
            lock.lock()
            ended = true
            lock.broadcast()
            lock.unlock()
        }
    }

    override fun endedEarly(): Boolean {
        lock.lock()
        try {
            return early
        } finally {
            lock.unlock()
        }
    }

    /** Something other than the person ended the recording (main thread): capture stops now, the queue is kept for [read]. */
    private fun endEarly() {
        lock.lock()
        val live = !tornDown && !ended
        if (live) early = true
        lock.unlock()
        if (!live) return
        stopCapture()
        lock.lock()
        ended = true
        lock.broadcast()
        lock.unlock()
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        lock.lock()
        try {
            while (queue.available == 0 && !ended) lock.wait()
            // After [end] the bytes already queued are still handed out (the tail of the last words); [close] discards them.
            if (queue.available == 0) return -1
            return queue.read(into, offset, length)
        } finally {
            lock.unlock()
        }
    }

    /**
     * The person's Stop: the tap is removed and the engine stopped first (so the buffer in flight still lands), then the
     * source is marked ended **with the queue kept**: [read] hands out what is left and then returns a negative number. The
     * recorder calls [close] after that. The audio session stays active until [close].
     */
    override fun end(): Boolean {
        stopCapture()
        lock.lock()
        ended = true
        lock.broadcast()
        lock.unlock()
        return true
    }

    /** Removes the tap and stops the engine, once; any thread. */
    private fun stopCapture() {
        lock.lock()
        val audio = engine
        engine = null
        lock.unlock()
        audio?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
    }

    override fun close() {
        lock.lock()
        val first = !tornDown
        tornDown = true
        ended = true
        queue.clear()
        val undo = started
        val watching = observers.toList()
        observers.clear()
        lock.broadcast()
        lock.unlock()
        if (!first) return
        val center = NSNotificationCenter.defaultCenter
        watching.forEach { center.removeObserver(it) }
        stopCapture()
        resampler = null
        if (undo) {
            // Let other apps' audio come back. A failure here has nothing to be done about it.
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error.ptr)
            }
        }
    }

    /** Ends the capture early, from the main thread, when something other than the person changes under it (see the class comment). */
    private fun watch(session: AVAudioSession, audio: AVAudioEngine) {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        fun add(name: String?, from: Any?, handle: (NSNotification) -> Unit) {
            val token = center.addObserverForName(name, from, main) { n -> if (n != null) handle(n) }
            lock.lock()
            observers += token
            lock.unlock()
        }
        add(UIApplicationWillResignActiveNotification, null) { endEarly() }
        add(AVAudioSessionMediaServicesWereResetNotification, null) { endEarly() }
        add(AVAudioEngineConfigurationChangeNotification, audio) { endEarly() }
        add(AVAudioSessionInterruptionNotification, null) { n ->
            if (CaptureEndRules.endsOnInterruption(numberIn(n, AVAudioSessionInterruptionTypeKey))) endEarly()
        }
        add(AVAudioSessionRouteChangeNotification, null) { n ->
            if (CaptureEndRules.endsOnRouteChange(numberIn(n, AVAudioSessionRouteChangeReasonKey), inputAtStart, currentInputUid(session))) endEarly()
        }
    }

    private fun numberIn(notification: NSNotification, key: String?): Long =
        (notification.userInfo?.get(key) as? NSNumber)?.longValue ?: -1L

    /** The unique id of the microphone in use, or null when there is none. */
    private fun currentInputUid(session: AVAudioSession): String? =
        (session.currentRoute.inputs.firstOrNull() as? platform.AVFAudio.AVAudioSessionPortDescription)?.UID

    private companion object {
        /** The tap's buffer size hint in frames; iOS may deliver another size. */
        const val TAP_FRAMES = 2048u

        /** A few seconds of 16 kHz audio: far more than the reader leaves waiting, far less than the recorder's 2 MB. */
        const val QUEUE_BYTES = 256 * 1024
    }
}
