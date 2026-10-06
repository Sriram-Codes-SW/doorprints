# Android Drive enrolment, pairing and revocation: notes (S4b-BL-126, Kotlin)

Kotlin twin of the website's enrolment code. Everything is in `android/shared` common code (no `java.*`); tests run on
the host (`:shared:testAndroidHostTest`) and the pure ones in `commonTest`.

## Final signatures (for the controller in `drive/connect`)

Package `app.doorprints.drive.backup`, on `DriveBackupService` (additions only):

```kotlin
fun devicePublicKey(): ByteArray
fun deviceKid(): ByteArray                                   // a copy
suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): ApproveDeviceOutcome
suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): ApproveDeviceOutcome
suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection
suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection
suspend fun revokeDevice(kid: ByteArray): RevokeDeviceOutcome
suspend fun verifyRecoveryKey(recoveryKey: RecoveryKey): Boolean   // read-only, never moves a pin

sealed interface ApproveDeviceOutcome {                      // web: { kind: 'approved' | 'error' }
    class Approved(val connection: DriveConnection.Ready, val wrapEnc: ByteArray, val wrapCt: ByteArray, val epoch: Int)
    data class Error(val problem: DriveProblem)
}
class RevokeDeviceOutcome(val connection: DriveConnection, val recoveryKey: RecoveryKey?)  // key shown once, never stored
```

`openWithFolderKey` and `openWithRecoveryKey` are unchanged. `joinFrom*` return `DriveConnection.Ready` or
`DriveConnection.Error(problem)` (the web's `READY` / `ERROR`).

Package `app.doorprints.drive.enrol`:

```kotlin
class PairingCode(p: CryptoProvider) {
    fun commitNonce(nonce: ByteArray): ByteArray
    fun pairingCode(nNew: ByteArray, nApprover: ByteArray, pkNew: ByteArray, pkApprover: ByteArray): String  // 8 digits
    fun commitHolds(nonce: ByteArray, commit: ByteArray): Boolean
    companion object { const val PAIRING_TTL_MS: Long; fun pairingExpired(createdAtMs: Long, nowMs: Long): Boolean }
}
data class PairingMessage(phase: Phase /* COMMIT, APPROVER, REVEAL */, createdAtMs: Long, pkNew: String?, commit: String?,
    pkApprover: String?, nApprover: String?, nNew: String?, wrapEnc: String?, wrapCt: String?, epoch: Int?)   // binary fields: standard base64
sealed interface PairingOutcome { data class Ok(code: String, message: PairingMessage? = null); data class Refused(reason: Reason /* EXPIRED, COMMIT_MISMATCH, INCOMPLETE */) }
fun withWrap(message: PairingMessage, wrapEnc: String, wrapCt: String, epoch: Int): PairingMessage
class PairingFlow(p: CryptoProvider) {
    fun newcomerCommit(pkNew: ByteArray, nNew: ByteArray, nowMs: Long): PairingMessage
    fun approverReply(commit: PairingMessage, pkApprover: ByteArray, nApprover: ByteArray, nowMs: Long): PairingOutcome  // Ok.message is the approver message, Ok.code is ""
    fun revealAndCode(approver: PairingMessage, nNew: ByteArray, nowMs: Long): PairingOutcome   // Ok.code + Ok.message (the reveal)
    fun codeOf(revealed: PairingMessage, nowMs: Long): PairingOutcome
}
```

Package `app.doorprints.crypto`:

```kotlin
const val QR_PREFIX = "dp1."; const val QR_PSK_LEN = 32; const val QR_PUBLIC_LEN = 65; val QR_PSK_ID: ByteArray
class QrOffer(val publicKey: ByteArray, val psk: ByteArray)
fun qrOfferText(publicKey: ByteArray, psk: ByteArray): String     // IllegalArgumentException on a wrong shape
fun parseQrOffer(text: String): QrOffer?                          // accepts pasted text or a scanned URL holding "dp1...."
fun Hpke.sealPsk(pkR, info, aad, plaintext, psk, pskId): Hpke.Sealed      // HPKE mode 1 (also setupPskS / setupPskR)
fun Hpke.openPsk(enc, skR, info, aad, ciphertext, psk, pskId): ByteArray
fun encodeQr(bytes: ByteArray): Array<BooleanArray>               // [y][x], true = dark; ECC M, versions 1..10, mask 0
fun qrSvg(bytes: ByteArray): String                               // same symbol, four-module quiet zone
fun qrVersionBits(version: Int): Int; val QR_GF_EXP8: Int
```

`encodeQr(qrOfferText(...).encodeToByteArray())` is what the "this is a new device" screen draws (49 x 49 modules,
version 8). A payload over 213 bytes throws `IllegalArgumentException`. The scanner side gives the controller the
scanned text, which goes to `parseQrOffer`.

## How the flows go (and who checks what)

8-digit code. Newcomer: `newcomerCommit` (shows nothing yet) -> approver `approverReply` -> newcomer `revealAndCode` shows
the code -> approver `codeOf` shows the code -> the two people compare. Only after the approver's person says "they
match" does the controller call `approveDevice` (the approver writes the wrap only for a transcript it has just shown;
`approveDevice` itself does not know the transcript, as on the web, so the controller must keep that order). The approver
then `withWrap`s the result into the message; the newcomer calls `joinFromWrap` with it.

QR. The new device makes a 32-byte `psk`, shows `qrOfferText(devicePublicKey(), psk)` as a QR and as copyable text. The
enrolled device scans or pastes it (`parseQrOffer`), shows the device name for confirmation, calls
`approveDevicePsk(offer.publicKey, name, platform, offer.psk)`, and returns `wrapEnc/wrapCt/epoch` to the new device over
whatever channel it uses; the new device calls `joinFromPsk(enc, ct, epoch, psk)`.

Trust rules kept (tests in `DriveEnrolmentTest`, `PairingTest`, `QrEnrolTest`): the first pin only comes from
`createFolder`, `openWithRecoveryKey` or `openWithFolderKey` (via `joinFrom*`, which opens the wrap first); nothing read
from Drive alone is ever adopted (a valid wrap from another folder fails the MAC check and writes no pin); a device with
no pin cannot approve or revoke (no write); `verifyRecoveryKey` reads the pin and verifies against it but its
compare-and-set is a no-op, so it never moves it, even against a newer list.

## Decisions for the lead to confirm

- `Hpke.sealPsk`/`openPsk` did not exist in Kotlin (the PSK key schedule did). They are extension functions in
  `crypto/QrEnrol.kt` over the internal `encap`/`decap`/`keySchedule`, so `Hpke.kt` is unchanged.
- The outcome types are declared at the end of `DriveBackupService.kt` (package `drive.backup`, as the web declares them
  in the service file).
- The encoder is a faithful port of the website's: mask 0 only (no mask scoring), as `qr-code.ts`. It is a valid QR (the
  web's matrices, byte-identical to these, decode with jsQR for 15 payload sizes across versions 1..10; checked once,
  outside the repo). Mask scoring would change the matrices and break byte parity with the web; it is not needed for the
  scanner to read it.
- `joinFromWrap`/`joinFromPsk` also refuse an epoch below 1 and `approveDevice` maps a folder without a key list to
  `FOLDER_WITHOUT_KEYS` (the web returns its generic problem there).
- Pairing functions take a `CryptoProvider` (class constructor) because Kotlin's SHA-256 comes from the provider; the
  web calls its own `Sha256` directly. `pairingExpired` and `PAIRING_TTL_MS` need none (`commonTest` runs them on iOS too).
- No shared JSON vector file was added: the web has none for these (the QR known answers are digests inside
  `QrCodeKnownAnswerTest`, made by running the website's `encodeQr`).
