# The iPhone's QR scanner (S4b-BL-136): notes for the lead

Built 2026-10-06 on `feat/qr-scanner-s2-ios-scan` (owner decision: build the iPhone scan now, no library).

## What is where

| File | What |
| --- | --- |
| `android/ui/src/commonMain/.../ui/drive/QrScanSession.kt` | `QrScanSession` (pure state machine), `QrCameraBackend` (the camera seam), `CameraPermission`. No platform code; JVM-tested. |
| `android/ui/src/iosMain/.../ui/IosQrScanner.kt` | `iosQrScanner(labels, accepts): QrScanner`, `IosQrScanner`, `AvfQrCamera` (AVFoundation), `QrScannerViewController`, `QrScannerLabels`, `cameraPermissionOf`. |
| `android/ui/src/commonTest/.../QrScanSessionTest.kt` | 17 JVM tests (`:ui:testAndroidHostTest`). |
| `android/ui/src/iosTest/.../IosQrScannerTest.kt` | The `AVAuthorizationStatus` mapping (runs only on the macOS job). |
| `ios/Doorprints/Info.plist`, `*.lproj/InfoPlist.strings` | `NSCameraUsageDescription` in en, hi, ta, te (hi, ta, te marked under review, as the file does). |

## How it works

`QrScanSession.begin()`: no camera is *Unavailable*; permission *Authorized* starts the capture; *NotDetermined* asks the
system prompt first (the camera does not start before the answer, and an answer after the person left changes nothing);
*Denied* and *Restricted* end the scan without a prompt. A code over 1024 characters or one the reader refuses shows a
message ("not a Doorprints code") and the camera keeps looking; the first accepted code ends the scan, the camera stops
once, and a later metadata callback is ignored. Every way out (result, cancel, denied, failed) calls `stop()`.
The reader is the shared one: `iosQrScanner()` defaults `accepts` to `parseQrOffer(it) != null` (`:shared`,
`app.doorprints.crypto`), the `dp1.` prefix and the canonical encoding; no parser is re-implemented. The scanned text is
never logged and `State.Result.toString()` prints `Result(<scanned text>)`.

The iPhone side: `AVCaptureSession` with the default video device, `AVCaptureMetadataOutput` set to
`AVMetadataObjectTypeQRCode` only (after `addOutput`), delegate on the main queue, `AVCaptureVideoPreviewLayer`
(aspect fill), a full-screen `UIViewController`. `startRunning` and `stopRunning` run on a private serial queue
(`app.doorprints.qr-capture`), never on the main thread. On the end of a scan the delegate is cleared first, the session
is stopped and its input and output removed on that queue, the preview layer is detached when the screen is dismissed,
and the coroutine is resumed after the dismissal. Cancelling the calling coroutine closes the screen and the camera.
VoiceOver: the Cancel button has a label and the button trait, the hint line carries the viewfinder description, and a
rejected code is announced (`UIAccessibilityAnnouncementNotification`). Dynamic Type is on (text styles).
Not built: the torch (optional; one `lockForConfiguration` call when wanted).

## Behaviours the lead must know

- `isAvailable` is false without a camera (the simulator: `defaultDeviceWithMediaType` is nil) and under
  parental controls (*Restricted*); it stays true after a refusal, so the person can tap and be told.
- `QrScan` has no *Denied* case, so denied, restricted, no camera and a capture that fails to start all return
  `QrScan.NoCamera`. The session keeps the exact state (`QrScanSession.State.Denied`), but `IosQrScanner` does not expose
  it. To show "allow the camera in Settings" the Drive screen needs `QrScan.Denied` (a small change in
  `DriveActions.kt`, a `PlatformServices.openAppSettings()` already exists). Decision for the lead.
- The labels are parameters (`QrScannerLabels`, English defaults), because the repo localises native iOS strings only in
  `InfoPlist.strings`. The Drive composition should pass the Compose-resource strings: *Cancel*, the hint, "That is not a
  Doorprints code.", "That code is too long to be a Doorprints code." and the viewfinder description. hi, ta, te
  translations of those five new strings are not written (they belong with the Compose resources, marked under review).

## Registering it (the iPhone's Drive screen)

```kotlin
val scanner = iosQrScanner(QrScannerLabels(cancel = ..., hint = ..., notDoorprints = ..., tooLong = ..., viewfinder = ...))
DriveSettings(vm, scanner = scanner, host = ...)   // or NoQrScanner when scanner.isAvailable is false (the screens already hide Scan then)
```

`iosQrScanner()` is in package `app.doorprints.ui` (iosMain). `scan()` must be called from a coroutine; it switches to the
main thread itself. Nothing else is registered; nothing in the shared UI changed.

## What CI proves, and what only an iPhone proves

CI (`.github/workflows/shared-ios.yml`, macOS): the code compiles for `iosArm64` and `iosSimulatorArm64`
(`:ui:compileKotlinIos*`, `:ui:compileTestKotlinIosSimulatorArm64`), and `:ui:iosSimulatorArm64Test` runs
`IosQrScannerTest` (the status mapping). The state machine is proven on the JVM (17 tests, 15 named mutations).
Only a real iPhone proves: the permission prompt text and flow (including a refusal and a later change in Settings), that
the camera starts and the preview fills the screen in both orientations and on the notch, that a real QR is decoded
(and one on a screen, in sun, small), the Cancel button and VoiceOver reading, that the camera indicator turns off at
dismissal (no retained session), behaviour when the app is backgrounded or a call interrupts the capture, and the
first-launch latency of `startRunning`. Add to `docs/ops/manual-test-checklist.md` (the lead does it).
The simulator has no camera: the scan button is hidden there.
