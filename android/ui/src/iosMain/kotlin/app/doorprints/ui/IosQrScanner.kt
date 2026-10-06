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

import app.doorprints.crypto.parseQrOffer
import app.doorprints.ui.drive.CameraPermission
import app.doorprints.ui.drive.QrCameraBackend
import app.doorprints.ui.drive.QrScan
import app.doorprints.ui.drive.QrScanSession
import app.doorprints.ui.drive.QrScanner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSError
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityTraits
import platform.UIKit.isAccessibilityElement
import platform.UIKit.UIAccessibilityPostNotification
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.CoreGraphics.CGRectMake
import kotlin.coroutines.resume

// The iPhone's QR scanner (S4b-BL-136, docs/15 section 9.5 i): AVFoundation's metadata output, QR codes only, no library.
// The decisions (permission, one result, limits, cancelling) are QrScanSession's, tested on the JVM; this file is the
// camera and the screen around it. The scanned text is a secret: it is never logged here.

/**
 * The words on the scanner screen. The app passes its own (from the Compose strings, in the person's language); the
 * English defaults are only what a screen without them shows.
 */
class QrScannerLabels(
    val cancel: String = "Cancel",
    val hint: String = "Point the camera at the Doorprints code on the other device.",
    val notDoorprints: String = "That is not a Doorprints code.",
    val tooLong: String = "That code is too long to be a Doorprints code.",
    val viewfinder: String = "Camera view",
)

/**
 * The factory the iPhone's Drive screen registers: `DriveSettings(vm, scanner = iosQrScanner(labels), ...)`. [accepts]
 * defaults to the shared reader of the enrolment code (`parseQrOffer`: the `dp1.` prefix and the canonical encoding).
 */
fun iosQrScanner(
    labels: QrScannerLabels = QrScannerLabels(),
    accepts: (String) -> Boolean = { parseQrOffer(it) != null },
): QrScanner = IosQrScanner(labels, accepts)

internal fun cameraPermissionOf(status: Long): CameraPermission = when (status) {
    AVAuthorizationStatusAuthorized -> CameraPermission.Authorized
    AVAuthorizationStatusNotDetermined -> CameraPermission.NotDetermined
    AVAuthorizationStatusDenied -> CameraPermission.Denied
    else -> CameraPermission.Restricted
}

private fun defaultCamera(): AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)

private fun currentPermission(): CameraPermission =
    cameraPermissionOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))

internal class IosQrScanner(
    private val labels: QrScannerLabels,
    private val accepts: (String) -> Boolean,
) : QrScanner {
    /** False without a camera (the simulator) and under parental controls; true otherwise, even after a refusal (the scan then says so). */
    override val isAvailable: Boolean
        get() = QrScanSession.isAvailable(defaultCamera() != null, currentPermission())

    override suspend fun scan(): QrScan = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val backend = AvfQrCamera()
            var screen: QrScannerViewController? = null
            lateinit var session: QrScanSession
            session = QrScanSession(backend, accepts) { state ->
                when (state) {
                    is QrScanSession.State.Scanning -> {
                        if (screen == null) {
                            screen = QrScannerViewController(backend.captureSession, labels) { session.cancel() }.also { present(it) }
                        }
                        state.problem?.let { screen?.show(if (it == QrScanSession.Problem.TooLong) labels.tooLong else labels.notDoorprints) }
                    }
                    else -> if (session.finished) {
                        val outcome = session.outcome ?: QrScan.NoCamera
                        val shown = screen
                        screen = null
                        // Leave the screen first, then answer: the caller may present something right away.
                        if (shown != null) shown.dismissViewControllerAnimated(true) { shown.release(); if (continuation.isActive) continuation.resume(outcome) }
                        else if (continuation.isActive) continuation.resume(outcome)
                    }
                }
            }
            continuation.invokeOnCancellation {
                // The caller went away: let the camera go and close the screen (on the main thread, where it lives).
                dispatch_async(dispatch_get_main_queue()) {
                    session.cancel()
                    screen?.let { it.dismissViewControllerAnimated(false, null); it.release() }
                    screen = null
                }
            }
            session.begin()
        }
    }

    private fun present(controller: UIViewController) {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstNotNullOfOrNull { it.keyWindow }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        top?.presentViewController(controller, animated = true, completion = null)
    }
}

/**
 * AVFoundation behind [QrCameraBackend]: one capture session with the back camera and a metadata output for QR codes.
 * `startRunning` and `stopRunning` block, so they run on their own serial queue, never the main thread; the code
 * callbacks come back on the main queue.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvfQrCamera : QrCameraBackend {
    private val queue = dispatch_queue_create("app.doorprints.qr-capture", null)
    private var session: AVCaptureSession? = null
    private var output: AVCaptureMetadataOutput? = null
    private var delegate: CodeDelegate? = null

    /** The running session for the preview; only after [start] returned true. */
    val captureSession: AVCaptureSession get() = checkNotNull(session)

    override val hasCamera: Boolean get() = defaultCamera() != null

    override fun permission(): CameraPermission = currentPermission()

    override fun requestPermission(onAnswer: (Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            // The system calls back on an arbitrary queue.
            dispatch_async(dispatch_get_main_queue()) { onAnswer(granted) }
        }
    }

    override fun start(onCode: (String) -> Unit): Boolean {
        val device = defaultCamera() ?: return false
        val input = memScoped {
            val error = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
            AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
        } ?: return false
        val capture = AVCaptureSession()
        val metadata = AVCaptureMetadataOutput()
        if (!capture.canAddInput(input) || !capture.canAddOutput(metadata)) return false
        capture.addInput(input)
        capture.addOutput(metadata)
        val codeDelegate = CodeDelegate(onCode)
        metadata.setMetadataObjectsDelegate(codeDelegate, dispatch_get_main_queue())
        // Only after addOutput: the supported types are those of the connected session.
        metadata.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        session = capture
        output = metadata
        delegate = codeDelegate
        dispatch_async(queue) { capture.startRunning() }
        return true
    }

    override fun stop() {
        val capture = session ?: return
        val metadata = output
        session = null
        output = null
        delegate = null
        // Silence the callbacks now (main thread), then stop and drop the camera on the capture queue.
        metadata?.setMetadataObjectsDelegate(null, null)
        dispatch_async(queue) {
            capture.stopRunning()
            capture.inputs.toList().forEach { capture.removeInput(it as platform.AVFoundation.AVCaptureInput) }
            metadata?.let { capture.removeOutput(it) }
        }
    }
}

/** The metadata output's callback: the text of each QR code seen (main queue). Held by [AvfQrCamera]: the output keeps its delegate weakly. */
private class CodeDelegate(private val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
            ?.let(onCode)
    }
}

/**
 * The full-screen scanner: the camera preview, a line of guidance, a message when a code was not a Doorprints code, and
 * a Cancel button, all with VoiceOver labels. [onCancel] is called when the person taps Cancel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class QrScannerViewController(
    session: AVCaptureSession,
    private val labels: QrScannerLabels,
    private val onCancel: () -> Unit,
) : UIViewController(nibName = null, bundle = null) {
    private var preview: AVCaptureVideoPreviewLayer? = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }
    private val message = UILabel()
    private val cancelButton = UIButton.buttonWithType(UIButtonTypeSystem)

    override fun viewDidLoad() {
        super.viewDidLoad()
        modalPresentationStyle = UIModalPresentationFullScreen
        view.backgroundColor = UIColor.blackColor
        preview?.let { view.layer.addSublayer(it) }
        view.isAccessibilityElement = false

        message.text = labels.hint
        message.textColor = UIColor.whiteColor
        message.font = UIFont.preferredFontForTextStyle(platform.UIKit.UIFontTextStyleBody)
        message.adjustsFontForContentSizeCategory = true
        message.numberOfLines = 0
        message.textAlignment = NSTextAlignmentCenter
        message.accessibilityLabel = labels.viewfinder + ". " + labels.hint
        view.addSubview(message)

        cancelButton.setTitle(labels.cancel, forState = UIControlStateNormal)
        cancelButton.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        cancelButton.titleLabel?.font = UIFont.preferredFontForTextStyle(platform.UIKit.UIFontTextStyleHeadline)
        cancelButton.accessibilityLabel = labels.cancel
        cancelButton.accessibilityTraits = UIAccessibilityTraitButton
        cancelButton.addAction(UIAction.actionWithHandler { _ -> onCancel() }, forControlEvents = UIControlEventTouchUpInside)
        view.addSubview(cancelButton)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val bounds = view.bounds
        preview?.setFrame(bounds)
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        val top = view.safeAreaInsets.useContents { top }
        val bottom = view.safeAreaInsets.useContents { bottom }
        message.setFrame(CGRectMake(16.0, top + 16.0, width - 32.0, 72.0))
        cancelButton.setFrame(CGRectMake(16.0, height - bottom - 64.0, width - 32.0, 48.0))
    }

    /** Says why the last code was not taken (on screen and to VoiceOver); the camera keeps looking. */
    fun show(text: String) {
        message.text = text
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
    }

    /** Drops the preview layer, so nothing here holds the capture session once the screen is gone. */
    fun release() {
        preview?.removeFromSuperlayer()
        preview?.session = null
        preview = null
    }
}
