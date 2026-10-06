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

import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DeleteConfirmInfo
import app.doorprints.drive.connect.DeleteFactor
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.photo.PhotoNetworkStatus

/*
 * What the Google Drive screens show, as pure functions of the controller's state, results and reasons (S4b-BL-116 to
 * -119, -126; docs/15 section 2 to 5 and 10). The composables only draw what these say, so every rule that matters
 * (the recovery key is on screen once and only on the key screen, no "Try again" where the website has none, a delete
 * button stays off until the box is ticked and the countdown is over, no recovery-key or passkey field on a phone) is
 * a function that DriveScreenStateTest runs on the JVM.
 */

/** The card the Connect section shows (the website's `@if (state() === ...)` blocks of drive-connect.html). */
enum class DriveCard {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,

    /** The recovery key, shown once, with Copy. */
    RECOVERY_KEY,

    /** A key set this phone has no pin for: join with the recovery key, or enrol by QR or 8-digit code. */
    JOIN,

    /** This phone was revoked: say so, and offer only the recovery key (no enrolment, as on the website). */
    REVOKED,
    READY,
    ERROR,
}

fun driveCardOf(state: ConnectState, notice: DriveReason?): DriveCard = when (state) {
    ConnectState.UNAVAILABLE -> DriveCard.UNAVAILABLE
    ConnectState.DISCONNECTED -> DriveCard.DISCONNECTED
    ConnectState.CONNECTING -> DriveCard.CONNECTING
    ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY -> DriveCard.RECOVERY_KEY
    ConnectState.NEEDS_ENROLMENT, ConnectState.NEEDS_RECOVERY_KEY ->
        if (notice == DriveReason.DEVICE_REVOKED) DriveCard.REVOKED else DriveCard.JOIN
    ConnectState.READY -> DriveCard.READY
    ConnectState.ERROR -> DriveCard.ERROR
}

/** A button of the Connect card. */
enum class DriveControl {
    CONNECT,

    /** After *The folder was deleted*: connect again (the website's one extra button). */
    CONNECT_AGAIN,
    RETRY,
    COPY_KEY,
    KEY_NEXT,
    KEY_SKIP,
    JOIN_WITH_KEY,
    ENROL,
    DISCONNECT,
}

data class ControlSpec(val control: DriveControl, val enabled: Boolean)

/**
 * The buttons of [card], with whether each is on. [error] is the reason the card is showing (the ERROR card, or the
 * disconnected card after a failed connect), [keySaved] the *I have saved my recovery key* tick.
 */
fun connectControls(card: DriveCard, error: DriveReason?, keySaved: Boolean, busy: Boolean): List<ControlSpec> = when (card) {
    DriveCard.UNAVAILABLE, DriveCard.CONNECTING -> emptyList()
    DriveCard.DISCONNECTED ->
        if (error == null) listOf(ControlSpec(DriveControl.CONNECT, !busy)) else errorControls(error, busy)
    DriveCard.RECOVERY_KEY -> listOf(
        ControlSpec(DriveControl.COPY_KEY, !busy),
        ControlSpec(DriveControl.KEY_NEXT, keySaved && !busy),
        ControlSpec(DriveControl.KEY_SKIP, !busy),
    )
    DriveCard.JOIN -> listOf(ControlSpec(DriveControl.JOIN_WITH_KEY, !busy), ControlSpec(DriveControl.ENROL, !busy), ControlSpec(DriveControl.DISCONNECT, !busy))
    DriveCard.REVOKED -> listOf(ControlSpec(DriveControl.JOIN_WITH_KEY, !busy), ControlSpec(DriveControl.DISCONNECT, !busy))
    DriveCard.READY -> listOf(ControlSpec(DriveControl.DISCONNECT, !busy))
    DriveCard.ERROR -> errorControls(error, busy)
}

/** The website's error box: Connect after a deleted folder, *Try again* except where it cannot help (no encryption), enrol for a folder without a key. */
private fun errorControls(error: DriveReason?, busy: Boolean): List<ControlSpec> = buildList {
    if (error == DriveReason.FOLDER_GONE) add(ControlSpec(DriveControl.CONNECT_AGAIN, !busy))
    if (retryOffered(error)) add(ControlSpec(DriveControl.RETRY, !busy))
    if (error == DriveReason.NO_RECOVERY_KEY) add(ControlSpec(DriveControl.ENROL, !busy))
}

/** `showRetry()` of the website's drive-connect.ts: every error but "this device cannot encrypt". */
fun retryOffered(error: DriveReason?): Boolean = error != DriveReason.CRYPTO_UNAVAILABLE

/** The recovery key is drawn only on the key screen, and only while there is one: it is never kept for a later screen. */
fun recoveryKeyVisible(card: DriveCard, key: Any?): Boolean = card == DriveCard.RECOVERY_KEY && key != null

/** The recovery key of a revoke (the new one) is shown in the devices card until it is ticked as saved. */
fun revokeKeyVisible(key: Any?): Boolean = key != null

/** Whether the recovery-key input of *Join this folder* is offered: the only recovery-key field on a phone. */
fun joinKeyFieldVisible(card: DriveCard): Boolean = card == DriveCard.JOIN || card == DriveCard.REVOKED

/** *Join this folder* is on when something is typed and nothing is running. */
fun joinEnabled(typed: String, busy: Boolean): Boolean = !busy && typed.isNotBlank()

/** The recovery key never reaches a string that is logged or printed. A line for a log says only that one exists. */
fun logLine(card: DriveCard, hasKey: Boolean, error: DriveReason?): String =
    "drive card=$card key=${if (hasKey) "<shown once>" else "none"} error=${error?.name ?: "none"}"

/** The text a screen shows for a reason: the dictionary key (the website's name where it has one). */
fun reasonKey(reason: DriveReason): String = reason.key

// ---- Backups -------------------------------------------------------------------------------------------------------

/** Whether a row's *Import a backup* is on: nothing else is running (the website disables it while any backup or import runs). */
fun importEnabled(importBusy: Boolean, backupBusy: Boolean): Boolean = !importBusy && !backupBusy

fun backUpNowEnabled(backupBusy: Boolean): Boolean = !backupBusy

/** The shrink question (a much smaller backup) has two buttons, both off while busy; the older backups are kept until the person confirms. */
fun shrinkQuestionVisible(shrinkHoldBackupId: String?): Boolean = shrinkHoldBackupId != null

// ---- Sync and photos -----------------------------------------------------------------------------------------------

/** The status line of sync: a dictionary key and its arguments. */
data class SyncLine(val key: String, val args: List<Any> = emptyList(), val isError: Boolean = false)

fun syncLine(info: SyncInfo, formatTime: (Long) -> String): SyncLine = when (info.state) {
    SyncState.NOT_RUN -> SyncLine("driveSync.notRun")
    SyncState.SYNCED -> info.lastSyncAt?.let { SyncLine("driveSync.syncedAt", listOf(formatTime(it))) } ?: SyncLine("driveSync.synced")
    SyncState.WAITING -> SyncLine("driveSync.waiting")
    SyncState.PAUSED -> SyncLine("driveSync.paused")
    SyncState.OFFLINE -> SyncLine("driveSync.offline")
    SyncState.NEEDS_CONFIRMATION -> SyncLine("driveSync.needsConfirmation", listOf(info.housesToDelete ?: 0, info.liveHouses ?: 0))
    SyncState.SKIPPED_FILES -> SyncLine("driveSync.skippedFiles", listOf(info.skipped.size))
    SyncState.ERROR -> info.error?.let { SyncLine(reasonKey(it), isError = true) } ?: SyncLine("driveSync.error", isError = true)
}

/** The shrink guard of sync: two buttons, only while the controller says *needs confirmation*. */
fun syncConfirmVisible(info: SyncInfo): Boolean = info.state == SyncState.NEEDS_CONFIRMATION

fun syncNowEnabled(busy: Boolean): Boolean = !busy

/** *Upload photos only on Wi-Fi* is the default: the switch is on while mobile data is not allowed. */
fun photosWifiOnly(uploadOnMobileData: Boolean): Boolean = !uploadOnMobileData

/** *Upload photos now over mobile data* is offered only while Wi-Fi only is on and some photos wait. */
fun uploadNowOffered(wifiOnly: Boolean, pendingBytes: Long?): Boolean = wifiOnly && (pendingBytes ?: 0L) > 0L

fun photoStatusKey(status: PhotoNetworkStatus): String = when (status) {
    PhotoNetworkStatus.WAITING_FOR_WIFI -> "drivePhotos.waitingForWifi"
    PhotoNetworkStatus.UPLOADING -> "drivePhotos.uploading"
    PhotoNetworkStatus.PAUSED_OFFLINE -> "drivePhotos.pausedOffline"
    PhotoNetworkStatus.DONE -> "drivePhotos.done"
}

// ---- Devices -------------------------------------------------------------------------------------------------------

/** Revoke is offered for every device but this one. */
fun revokeOffered(device: ListedDevice): Boolean = !device.self

// ---- Deleting ------------------------------------------------------------------------------------------------------

enum class DeleteChoice { OLDER_BACKUPS, ALL_BACKUPS, EVERYTHING, ONE_BACKUP }

/** The menu: older, all, everything, and *Delete this backup* only when a backup is chosen (the website's order). */
fun deleteMenu(oneBackupId: String?): List<DeleteChoice> = buildList {
    add(DeleteChoice.OLDER_BACKUPS)
    add(DeleteChoice.ALL_BACKUPS)
    add(DeleteChoice.EVERYTHING)
    if (oneBackupId != null) add(DeleteChoice.ONE_BACKUP)
}

fun deleteChoiceKey(choice: DeleteChoice): String = when (choice) {
    DeleteChoice.OLDER_BACKUPS -> "driveDelete.olderBackups"
    DeleteChoice.ALL_BACKUPS -> "driveDelete.allBackups"
    DeleteChoice.EVERYTHING -> "driveDelete.everything"
    DeleteChoice.ONE_BACKUP -> "driveDelete.oneBackup"
}

enum class DeletePhase { MENU, PLAN, CONFIRM, RUNNING, PARTIAL, DONE, ERROR }

/** What the confirm step shows besides the buttons. */
enum class DeleteField { TICK_BOX, COUNTDOWN, DEVICE_CHECK_NOTE }

/**
 * The fields of the confirm step for [info]: the tick box when the policy asks for one, the countdown when it has a
 * delay (5 seconds for *Delete everything*), and the device-check sentence for level 2 and 3 (screen lock, fingerprint
 * or face). Never a recovery-key or passkey field: a phone's factor is the device check (docs/15 section 10.4a).
 */
fun deleteConfirmFields(info: DeleteConfirmInfo): Set<DeleteField> = buildSet {
    if (info.tickBoxRequired) add(DeleteField.TICK_BOX)
    if (info.delayMs > 0) add(DeleteField.COUNTDOWN)
    if (info.level != DeletionLevel.L1 || info.factor == DeleteFactor.DEVICE_AUTH) add(DeleteField.DEVICE_CHECK_NOTE)
}

/** Whole seconds still to wait (rounded up, so "5" shows for the whole first second), 0 when over. */
fun countdownSecondsLeft(delayMs: Long, elapsedMs: Long): Int {
    val left = delayMs - elapsedMs.coerceAtLeast(0L)
    return if (left <= 0L) 0 else ((left + 999L) / 1000L).toInt()
}

/** The final button is on only when the box is ticked (if asked), the countdown is over and nothing is running. */
fun deleteConfirmEnabled(info: DeleteConfirmInfo, ticked: Boolean, elapsedMs: Long, busy: Boolean): Boolean =
    !busy && (!info.tickBoxRequired || ticked) && countdownSecondsLeft(info.delayMs, elapsedMs) == 0

/** *Delete for good* when a tick box was asked, else the plain *Delete* (the website's wording). */
fun deleteForGoodLabel(info: DeleteConfirmInfo): Boolean = info.tickBoxRequired

/** A size for a backup row or the plan: "812 B", "1.4 MB" (binary units, one decimal, Latin digits). */
fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

/** *Try again* exists only after a delete that stopped with files left (the website's partial step). */
fun deleteTryAgainOffered(phase: DeletePhase): Boolean = phase == DeletePhase.PARTIAL

/** The device check's own heading and sentence show for the two stronger levels. */
fun deviceCheckWordingShown(level: DeletionLevel): Boolean = level == DeletionLevel.L2 || level == DeletionLevel.L3

/** The prompt text (the system sheet's line) of the delete's device check. */
const val DELETE_PROMPT_KEY = "driveDelete.deviceCheckPrompt"

/** What is shown after a delete ran: finished, or `left` of `total` files still in Drive. */
sealed interface DeleteResultLine {
    data object Done : DeleteResultLine
    data class Left(val left: Int, val total: Int) : DeleteResultLine
}

fun deleteResultLine(finished: Boolean, left: Int, total: Int): DeleteResultLine =
    if (finished) DeleteResultLine.Done else DeleteResultLine.Left(left, total)

/** The plan's lines for the dialog: counts per kind (never a file name), and the total. Order is fixed. */
enum class PlanKind(val key: String) {
    BACKUP("driveDelete.plan.backup"),
    SYNC("driveDelete.plan.sync"),
    PHOTO("driveDelete.plan.photo"),
    SHARED("driveDelete.plan.shared"),
    OTHER("driveDelete.plan.other"),
}

// ---- The 8-digit code -----------------------------------------------------------------------------------------------------

/** "12345678" as "1234 5678" for the eye; a screen reader gets the digits one by one ([codeSpoken]). */
fun codeGrouped(code: String): String = code.chunked(4).joinToString(" ")

fun codeSpoken(code: String): String = code.filter { !it.isWhitespace() }.toList().joinToString(" ")

// ---- Strings by key ------------------------------------------------------------------------------------------------

/** The Compose resource name of a dictionary key: camelCase and dots to snake_case ("driveDelete.reason.AUTH_FAILED" -> "drive_delete_reason_auth_failed"). */
fun driveResName(key: String): String = key.split('.').joinToString("_") { segment ->
    if (segment.contains('_') || segment.all { !it.isLowerCase() }) {
        segment.lowercase()
    } else {
        buildString {
            segment.forEachIndexed { i, c ->
                if (c.isUpperCase() && i > 0 && (segment[i - 1].isLowerCase() || segment[i - 1].isDigit())) append('_')
                append(c.lowercaseChar())
            }
        }
    }
}
