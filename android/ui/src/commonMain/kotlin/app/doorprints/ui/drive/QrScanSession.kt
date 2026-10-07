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

package app.doorprints.ui.drive

/** What the phone says about the camera: whether the person has been asked and what they answered. */
enum class CameraPermission { NotDetermined, Authorized, Denied, Restricted }

/**
 * The camera behind a [QrScanSession] (AVFoundation on the iPhone; a fake in the tests). Every call and every callback
 * comes on one thread (the iPhone's main thread); the backend moves the slow parts (starting and stopping the capture)
 * off it itself.
 */
interface QrCameraBackend {
    /** False when the device has no camera (the simulator, an iPad without one). */
    val hasCamera: Boolean

    fun permission(): CameraPermission

    /** Asks the system prompt; [onAnswer] gets true when the person allowed. Only called while [CameraPermission.NotDetermined]. */
    fun requestPermission(onAnswer: (Boolean) -> Unit)

    /** Sets the capture up and starts it; each code read goes to [onCode]. False when it could not be set up. */
    fun start(onCode: (String) -> Unit): Boolean

    /** Stops the capture and lets go of the camera. Safe to call twice. */
    fun stop()
}

/**
 * One scan from the first look at the camera to its end, as a state machine with no platform code (so it is tested on the
 * JVM). The first accepted code ends it: the camera stops then and any later code is ignored. A code that is too long
 * or is not a Doorprints code does not end it: the person is told and the camera keeps looking.
 *
 * [accepts] is the shared reader of the enrolment code (`parseQrOffer` through the `dp1.` prefix), never a parser of
 * this class's own. The scanned text is a secret (it holds the new device's one-time key): it is never logged and no
 * `toString` here prints it.
 */
class QrScanSession(
    private val camera: QrCameraBackend,
    private val accepts: (String) -> Boolean,
    private val onState: (State) -> Unit = {},
) {
    sealed interface State {
        data object Idle : State
        data object RequestingPermission : State

        /** Looking; [problem] is what was wrong with the last code the camera read, if anything. */
        data class Scanning(val problem: Problem? = null) : State

        /** The one result. The text is the person's secret: not in `toString`. */
        class Result(val text: String) : State {
            override fun toString() = "Result(<scanned text>)"
        }

        data object Cancelled : State
        data object Denied : State
        data object Restricted : State
        data object Unavailable : State
        data object Failed : State
    }

    enum class Problem { NotADoorprintsCode, TooLong }

    var state: State = State.Idle
        private set

    /** True once nothing more will happen: the camera is released. */
    val finished: Boolean get() = state.isFinal()

    /** The answer for the screen: the code, or [QrScan.Cancelled]; null while the scan is open. */
    val outcome: QrScan?
        get() = when (val s = state) {
            is State.Result -> QrScan.Scanned(s.text)
            State.Cancelled -> QrScan.Cancelled
            State.Denied -> QrScan.Denied
            State.Restricted, State.Unavailable, State.Failed -> QrScan.NoCamera
            else -> null
        }

    /** Begins: checks the camera, asks the person when they have not been asked, then looks. Only from [State.Idle]. */
    fun begin() {
        if (state != State.Idle) return
        if (!camera.hasCamera) return enter(State.Unavailable)
        when (camera.permission()) {
            CameraPermission.Authorized -> startCapture()
            CameraPermission.NotDetermined -> {
                enter(State.RequestingPermission)
                camera.requestPermission { granted -> permissionAnswered(granted) }
            }
            CameraPermission.Denied -> enter(State.Denied)
            CameraPermission.Restricted -> enter(State.Restricted)
        }
    }

    private fun permissionAnswered(granted: Boolean) {
        // The person may have left while the prompt was up: then the answer changes nothing.
        if (state != State.RequestingPermission) return
        if (granted) startCapture() else enter(State.Denied)
    }

    private fun startCapture() {
        if (camera.start(::codeRead)) enter(State.Scanning()) else enter(State.Failed)
    }

    private fun codeRead(text: String) {
        if (state !is State.Scanning) return
        when {
            text.length > MAX_TEXT_LENGTH -> enter(State.Scanning(Problem.TooLong))
            !accepts(text) -> enter(State.Scanning(Problem.NotADoorprintsCode))
            else -> enter(State.Result(text))
        }
    }

    /** The person left (the Cancel button, a system dismissal, the screen going away). Releases the camera. */
    fun cancel() {
        if (state.isFinal()) return
        enter(State.Cancelled)
    }

    private fun enter(next: State) {
        state = next
        // Every way out of a scan ends with the camera let go; stop() is safe on a camera never started.
        if (next.isFinal()) camera.stop()
        onState(next)
    }

    private fun State.isFinal() = this !is State.Idle && this !is State.RequestingPermission && this !is State.Scanning

    companion object {
        /** A `dp1.` offer is about 150 characters; anything near this is not one (and a huge code is not worth decoding). */
        const val MAX_TEXT_LENGTH = 1024

        /** What the screens may show: a camera that is there and may yet be allowed. Restricted can never be allowed. */
        fun isAvailable(hasCamera: Boolean, permission: CameraPermission): Boolean =
            hasCamera && permission != CameraPermission.Restricted
    }
}
