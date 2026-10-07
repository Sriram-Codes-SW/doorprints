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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [QrScanSession] against a fake camera: permission, the one result, the limits, cancelling, and the text staying secret. */
class QrScanSessionTest {
    private class FakeCamera(
        override val hasCamera: Boolean = true,
        var permission: CameraPermission = CameraPermission.Authorized,
        var startWorks: Boolean = true,
    ) : QrCameraBackend {
        var prompts = 0
        var starts = 0
        var stops = 0
        private var answer: ((Boolean) -> Unit)? = null
        private var code: ((String) -> Unit)? = null

        override fun permission() = permission
        override fun requestPermission(onAnswer: (Boolean) -> Unit) { prompts++; answer = onAnswer }
        override fun start(onCode: (String) -> Unit): Boolean { starts++; code = onCode; return startWorks }
        override fun stop() { stops++ }

        fun person(allows: Boolean) = answer!!.invoke(allows)
        fun read(text: String) = code!!.invoke(text)
    }

    private val good = "dp1.AAAA"
    private fun session(camera: FakeCamera, log: MutableList<QrScanSession.State> = mutableListOf()) =
        QrScanSession(camera, accepts = { it.startsWith("dp1.") }, onState = { log += it })

    @Test
    fun noCameraIsUnavailableAndNothingStarts() {
        val camera = FakeCamera(hasCamera = false)
        val s = session(camera).apply { begin() }
        assertEquals(QrScanSession.State.Unavailable, s.state)
        assertEquals(QrScan.NoCamera, s.outcome)
        assertEquals(0, camera.starts)
        assertEquals(0, camera.prompts)
    }

    @Test
    fun deniedIsDeniedWithoutAPromptOrCapture() {
        val camera = FakeCamera(permission = CameraPermission.Denied)
        val s = session(camera).apply { begin() }
        assertEquals(QrScanSession.State.Denied, s.state)
        assertEquals(QrScan.Denied, s.outcome, "a refused camera is told apart: the screen offers Settings (S4b-BL-141)")
        assertEquals(0, camera.prompts)
        assertEquals(0, camera.starts)
    }

    @Test
    fun restrictedIsRestrictedNotAskedAndNotAvailable() {
        val camera = FakeCamera(permission = CameraPermission.Restricted)
        val s = session(camera).apply { begin() }
        assertEquals(QrScanSession.State.Restricted, s.state)
        assertEquals(QrScan.NoCamera, s.outcome, "parental controls cannot be lifted in the app's Settings: no Settings button")
        assertEquals(0, camera.prompts)
        assertFalse(QrScanSession.isAvailable(true, CameraPermission.Restricted))
        assertTrue(QrScanSession.isAvailable(true, CameraPermission.Denied))
        assertTrue(QrScanSession.isAvailable(true, CameraPermission.NotDetermined))
        assertFalse(QrScanSession.isAvailable(false, CameraPermission.Authorized))
    }

    @Test
    fun notDeterminedAsksThenScansWhenAllowed() {
        val camera = FakeCamera(permission = CameraPermission.NotDetermined)
        val s = session(camera).apply { begin() }
        assertEquals(QrScanSession.State.RequestingPermission, s.state)
        assertEquals(1, camera.prompts)
        assertEquals(0, camera.starts, "the camera does not start before the answer")
        camera.person(true)
        assertEquals(QrScanSession.State.Scanning(), s.state)
        assertEquals(1, camera.starts)
    }

    @Test
    fun notDeterminedThenRefusedIsDenied() {
        val camera = FakeCamera(permission = CameraPermission.NotDetermined)
        val s = session(camera).apply { begin() }
        camera.person(false)
        assertEquals(QrScanSession.State.Denied, s.state)
        assertEquals(QrScan.Denied, s.outcome)
        assertEquals(0, camera.starts)
    }

    @Test
    fun anAnswerAfterCancellingChangesNothing() {
        val camera = FakeCamera(permission = CameraPermission.NotDetermined)
        val s = session(camera).apply { begin() }
        s.cancel()
        camera.person(true)
        assertEquals(QrScanSession.State.Cancelled, s.state)
        assertEquals(0, camera.starts, "the camera never starts for a person who left")
    }

    @Test
    fun aCaptureThatCannotStartFails() {
        val camera = FakeCamera(startWorks = false)
        val s = session(camera).apply { begin() }
        assertEquals(QrScanSession.State.Failed, s.state)
        assertEquals(QrScan.NoCamera, s.outcome)
        assertTrue(camera.stops >= 1, "a half-made capture is let go")
    }

    @Test
    fun theFirstAcceptedCodeIsTheResultAndTheCameraStopsOnce() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        camera.read(good)
        assertEquals(QrScan.Scanned(good), s.outcome)
        assertEquals(1, camera.stops)
        assertTrue(s.finished)
    }

    @Test
    fun aSecondCodeIsIgnoredAndDeliversNothing() {
        val camera = FakeCamera()
        val log = mutableListOf<QrScanSession.State>()
        val s = session(camera, log).apply { begin() }
        camera.read(good)
        camera.read("dp1.BBBB")
        assertEquals(QrScan.Scanned(good), s.outcome)
        assertEquals(1, log.count { it is QrScanSession.State.Result }, "the result is delivered once")
        assertEquals(1, camera.stops)
    }

    @Test
    fun aCodeThatIsNotDoorprintsIsReportedAndScanningGoesOn() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        camera.read("https://example.com/")
        assertEquals(QrScanSession.State.Scanning(QrScanSession.Problem.NotADoorprintsCode), s.state)
        assertEquals(0, camera.stops, "the camera stays on")
        camera.read(good)
        assertEquals(QrScan.Scanned(good), s.outcome)
    }

    @Test
    fun aCodeOverTheLimitIsRefusedAndOneAtTheLimitIsKept() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        camera.read("dp1." + "A".repeat(QrScanSession.MAX_TEXT_LENGTH))
        assertEquals(QrScanSession.State.Scanning(QrScanSession.Problem.TooLong), s.state)
        val atLimit = "dp1." + "A".repeat(QrScanSession.MAX_TEXT_LENGTH - 4)
        assertEquals(QrScanSession.MAX_TEXT_LENGTH, atLimit.length)
        camera.read(atLimit)
        assertEquals(QrScan.Scanned(atLimit), s.outcome)
    }

    @Test
    fun tooLongIsRefusedBeforeTheReaderSeesIt() {
        val camera = FakeCamera()
        var asked = 0
        val s = QrScanSession(camera, accepts = { asked++; true }).apply { begin() }
        camera.read("x".repeat(QrScanSession.MAX_TEXT_LENGTH + 1))
        assertEquals(0, asked)
        assertIs<QrScanSession.State.Scanning>(s.state)
    }

    @Test
    fun cancelWhileScanningReleasesTheCameraAndLaterCodesAreIgnored() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        s.cancel()
        assertEquals(QrScanSession.State.Cancelled, s.state)
        assertEquals(QrScan.Cancelled, s.outcome)
        assertEquals(1, camera.stops)
        camera.read(good)
        assertEquals(QrScan.Cancelled, s.outcome)
    }

    @Test
    fun cancelAfterTheResultKeepsTheResult() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        camera.read(good)
        s.cancel()
        assertEquals(QrScan.Scanned(good), s.outcome)
        assertEquals(1, camera.stops)
    }

    @Test
    fun openOutcomeIsNullWhileScanning() {
        val s = session(FakeCamera()).apply { begin() }
        assertNull(s.outcome)
        assertFalse(s.finished)
    }

    @Test
    fun beginTwiceStartsOnce() {
        val camera = FakeCamera()
        val s = session(camera).apply { begin(); begin() }
        assertEquals(1, camera.starts)
        assertIs<QrScanSession.State.Scanning>(s.state)
    }

    @Test
    fun theScannedTextIsNotInAnyToString() {
        val secret = "dp1.SECRETSECRETSECRET"
        val camera = FakeCamera()
        val s = session(camera).apply { begin() }
        camera.read(secret)
        assertFalse(s.state.toString().contains("SECRET"))
        assertFalse(s.toString().contains("SECRET"))
        assertFalse(QrScanSession.State.Scanning(QrScanSession.Problem.TooLong).toString().contains("SECRET"))
    }
}
