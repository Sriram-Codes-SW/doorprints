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
import app.doorprints.drive.sync.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules of the Google Drive screens, as pure functions (S4b-BL-116 to -119, -126). */
class DriveScreenStateTest {
    private fun controls(card: DriveCard, error: DriveReason? = null, saved: Boolean = false, busy: Boolean = false) =
        connectControls(card, error, saved, busy).associate { it.control to it.enabled }

    private fun info(level: DeletionLevel, tick: Boolean, delayMs: Long = 0, factor: DeleteFactor = if (level == DeletionLevel.L1) DeleteFactor.NONE else DeleteFactor.DEVICE_AUTH) =
        DeleteConfirmInfo(level, factor, tick, delayMs)

    // ---- cards -----------------------------------------------------------------------------------------------------

    @Test fun everyConnectStateHasItsCard() {
        assertEquals(DriveCard.UNAVAILABLE, driveCardOf(ConnectState.UNAVAILABLE, null))
        assertEquals(DriveCard.DISCONNECTED, driveCardOf(ConnectState.DISCONNECTED, null))
        assertEquals(DriveCard.CONNECTING, driveCardOf(ConnectState.CONNECTING, null))
        assertEquals(DriveCard.RECOVERY_KEY, driveCardOf(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, null))
        assertEquals(DriveCard.JOIN, driveCardOf(ConnectState.NEEDS_ENROLMENT, null))
        assertEquals(DriveCard.JOIN, driveCardOf(ConnectState.NEEDS_RECOVERY_KEY, null))
        assertEquals(DriveCard.READY, driveCardOf(ConnectState.READY, null))
        assertEquals(DriveCard.ERROR, driveCardOf(ConnectState.ERROR, null))
    }

    @Test fun aRevokedPhoneGetsTheRevokedCardWithoutEnrolment() {
        assertEquals(DriveCard.REVOKED, driveCardOf(ConnectState.NEEDS_RECOVERY_KEY, DriveReason.DEVICE_REVOKED))
        assertEquals(setOf(DriveControl.JOIN_WITH_KEY, DriveControl.DISCONNECT), controls(DriveCard.REVOKED).keys)
    }

    // ---- recovery key shown once ------------------------------------------------------------------------------------

    @Test fun theRecoveryKeyIsDrawnOnlyOnTheKeyCardAndOnlyWhileThereIsOne() {
        for (card in DriveCard.entries) {
            assertEquals(card == DriveCard.RECOVERY_KEY, recoveryKeyVisible(card, "KEY"), "$card with a key")
            assertFalse(recoveryKeyVisible(card, null), "$card without a key")
        }
    }

    @Test fun nextOnTheKeyScreenNeedsTheSavedTickButSkipAndCopyDoNot() {
        val untick = controls(DriveCard.RECOVERY_KEY, saved = false)
        assertEquals(false, untick[DriveControl.KEY_NEXT])
        assertEquals(true, untick[DriveControl.KEY_SKIP])
        assertEquals(true, untick[DriveControl.COPY_KEY])
        assertEquals(true, controls(DriveCard.RECOVERY_KEY, saved = true)[DriveControl.KEY_NEXT])
        assertEquals(false, controls(DriveCard.RECOVERY_KEY, saved = true, busy = true)[DriveControl.KEY_NEXT])
    }

    @Test fun theKeyIsNeverInALoggedLine() {
        val line = logLine(DriveCard.RECOVERY_KEY, hasKey = true, error = null)
        assertFalse(line.contains("ABCD-EFGH"))
        assertTrue(line.contains("<shown once>"))
        assertEquals("ShownKey(<shown once>)", ShownKey("ABCD-EFGH-JKLM").toString())
    }

    // ---- no recovery key or passkey field beyond joining --------------------------------------------------------------

    @Test fun theOnlyRecoveryKeyFieldIsOnTheJoinCards() {
        for (card in DriveCard.entries) {
            assertEquals(card == DriveCard.JOIN || card == DriveCard.REVOKED, joinKeyFieldVisible(card), "$card")
        }
    }

    @Test fun theDeleteConfirmStepNeverAsksForAKeyOrPasskey() {
        val allowed = setOf(DeleteField.TICK_BOX, DeleteField.COUNTDOWN, DeleteField.DEVICE_CHECK_NOTE)
        for (level in DeletionLevel.entries) for (factor in DeleteFactor.entries) for (tick in listOf(true, false)) for (delay in listOf(0L, 5000L)) {
            val fields = deleteConfirmFields(DeleteConfirmInfo(level, factor, tick, delay))
            assertTrue(allowed.containsAll(fields), "$level $factor $tick $delay: $fields")
        }
        assertTrue(DeleteField.entries.none { it.name.contains("KEY") || it.name.contains("PASSKEY") })
        assertTrue(DriveControl.entries.none { it.name.contains("PASSKEY") })
    }

    // ---- try again where the website has it ----------------------------------------------------------------------------

    @Test fun noTryAgainWhenThisPhoneCannotEncrypt() {
        assertFalse(retryOffered(DriveReason.CRYPTO_UNAVAILABLE))
        assertFalse(DriveControl.RETRY in controls(DriveCard.ERROR, DriveReason.CRYPTO_UNAVAILABLE))
        assertTrue(DriveControl.RETRY in controls(DriveCard.ERROR, DriveReason.SERVER))
        assertTrue(DriveControl.RETRY in controls(DriveCard.DISCONNECTED, DriveReason.SIGNIN_CLOSED))
    }

    @Test fun aDeletedFolderIsItsOwnCardWithStartAgainAndDisconnectAndNoConnect() {
        assertEquals(DriveCard.FOLDER_GONE, driveCardOf(ConnectState.DISCONNECTED, null, DriveReason.FOLDER_GONE))
        assertEquals(DriveCard.DISCONNECTED, driveCardOf(ConnectState.DISCONNECTED, null, DriveReason.SIGNIN_CLOSED))
        assertEquals(DriveCard.READY, driveCardOf(ConnectState.READY, null, DriveReason.FOLDER_GONE))
        assertEquals(listOf(DriveControl.START_AGAIN, DriveControl.DISCONNECT), connectControls(DriveCard.FOLDER_GONE, DriveReason.FOLDER_GONE, false, false).map { it.control })
        assertTrue(controls(DriveCard.FOLDER_GONE, busy = true).values.none { it })
        val all = controls(DriveCard.FOLDER_GONE, DriveReason.FOLDER_GONE).keys
        assertFalse(DriveControl.CONNECT in all || DriveControl.RETRY in all, "Connect and Try again would only ask the same question again")
    }

    @Test fun aFolderWithoutAKeyOffersEnrolment() {
        assertTrue(DriveControl.ENROL in controls(DriveCard.ERROR, DriveReason.NO_RECOVERY_KEY))
        assertFalse(DriveControl.ENROL in controls(DriveCard.ERROR, DriveReason.SERVER))
    }

    @Test fun theDisconnectedCardWithoutAnErrorOffersOnlyConnect() {
        assertEquals(setOf(DriveControl.CONNECT), controls(DriveCard.DISCONNECTED).keys)
        assertEquals(false, controls(DriveCard.DISCONNECTED, busy = true)[DriveControl.CONNECT])
        assertTrue(controls(DriveCard.UNAVAILABLE).isEmpty())
        assertTrue(controls(DriveCard.CONNECTING).isEmpty())
    }

    @Test fun tryAgainInTheDeleteFlowIsOnlyAfterAPartialRun() {
        for (phase in DeletePhase.entries) assertEquals(phase == DeletePhase.PARTIAL, deleteTryAgainOffered(phase), "$phase")
    }

    // ---- delete: tick box, countdown, device check ----------------------------------------------------------------------

    @Test fun deleteIsOffUntilTheBoxIsTicked() {
        val i = info(DeletionLevel.L2, tick = true)
        assertFalse(deleteConfirmEnabled(i, ticked = false, elapsedMs = 10_000, busy = false))
        assertTrue(deleteConfirmEnabled(i, ticked = true, elapsedMs = 0, busy = false))
        assertTrue(deleteConfirmEnabled(info(DeletionLevel.L1, tick = false), ticked = false, elapsedMs = 0, busy = false))
    }

    @Test fun deleteEverythingWaitsFiveSecondsAsTheControllerSays() {
        val i = info(DeletionLevel.L3, tick = true, delayMs = 5_000)
        assertFalse(deleteConfirmEnabled(i, ticked = true, elapsedMs = 4_999, busy = false))
        assertTrue(deleteConfirmEnabled(i, ticked = true, elapsedMs = 5_000, busy = false))
        assertFalse(deleteConfirmEnabled(i, ticked = true, elapsedMs = 9_000, busy = true))
    }

    @Test fun theCountdownRoundsUpAndEndsAtZero() {
        assertEquals(5, countdownSecondsLeft(5_000, 0))
        assertEquals(5, countdownSecondsLeft(5_000, 1))
        assertEquals(1, countdownSecondsLeft(5_000, 4_001))
        assertEquals(0, countdownSecondsLeft(5_000, 5_000))
        assertEquals(0, countdownSecondsLeft(0, 0))
        assertEquals(5, countdownSecondsLeft(5_000, -300))
    }

    @Test fun levelsTwoAndThreeShowTheDeviceCheckWording() {
        assertFalse(deviceCheckWordingShown(DeletionLevel.L1))
        assertTrue(deviceCheckWordingShown(DeletionLevel.L2))
        assertTrue(deviceCheckWordingShown(DeletionLevel.L3))
        assertTrue(DeleteField.DEVICE_CHECK_NOTE in deleteConfirmFields(info(DeletionLevel.L2, tick = true)))
        assertTrue(DeleteField.DEVICE_CHECK_NOTE in deleteConfirmFields(info(DeletionLevel.L3, tick = true, delayMs = 5_000)))
        assertFalse(DeleteField.DEVICE_CHECK_NOTE in deleteConfirmFields(info(DeletionLevel.L1, tick = false)))
    }

    @Test fun confirmFieldsFollowThePolicy() {
        assertEquals(setOf(DeleteField.TICK_BOX, DeleteField.COUNTDOWN, DeleteField.DEVICE_CHECK_NOTE), deleteConfirmFields(info(DeletionLevel.L3, true, 5_000)))
        assertEquals(setOf(DeleteField.TICK_BOX, DeleteField.DEVICE_CHECK_NOTE), deleteConfirmFields(info(DeletionLevel.L2, true)))
        assertEquals(emptySet(), deleteConfirmFields(info(DeletionLevel.L1, false)))
    }

    @Test fun theFinalButtonSaysDeleteForGoodOnlyWithATickBox() {
        assertTrue(deleteForGoodLabel(info(DeletionLevel.L2, true)))
        assertFalse(deleteForGoodLabel(info(DeletionLevel.L1, false)))
    }

    @Test fun theMenuListsOlderAllEverythingAndOneBackupOnlyWhenChosen() {
        assertEquals(listOf(DeleteChoice.OLDER_BACKUPS, DeleteChoice.ALL_BACKUPS, DeleteChoice.EVERYTHING), deleteMenu(null))
        assertEquals(DeleteChoice.ONE_BACKUP, deleteMenu("id").last())
        assertEquals("driveDelete.everything", deleteChoiceKey(DeleteChoice.EVERYTHING))
    }

    @Test fun aRunThatStoppedShowsWhatIsLeft() {
        assertEquals(DeleteResultLine.Done, deleteResultLine(true, 0, 9))
        assertEquals(DeleteResultLine.Left(4, 9), deleteResultLine(false, 4, 9))
    }

    // ---- backups -----------------------------------------------------------------------------------------------------------

    @Test fun importAndBackUpAreOffWhileSomethingRuns() {
        assertTrue(importEnabled(importBusy = false, backupBusy = false))
        assertFalse(importEnabled(importBusy = true, backupBusy = false))
        assertFalse(importEnabled(importBusy = false, backupBusy = true))
        assertFalse(backUpNowEnabled(true))
        assertTrue(backUpNowEnabled(false))
        assertTrue(shrinkQuestionVisible("b1"))
        assertFalse(shrinkQuestionVisible(null))
    }

    @Test fun sizesReadInBinaryUnits() {
        assertEquals("812 B", sizeText(812))
        assertEquals("1.0 KB", sizeText(1024))
        assertEquals("1.5 MB", sizeText(1_572_864))
        assertEquals("1.4 GB", sizeText(1_503_238_554))
    }

    // ---- sync, photos -------------------------------------------------------------------------------------------------------

    @Test fun everySyncStateHasItsLine() {
        val t = { ms: Long -> "T$ms" }
        assertEquals(SyncLine("driveSync.notRun"), syncLine(SyncInfo(SyncState.NOT_RUN, null), t))
        assertEquals(SyncLine("driveSync.syncedAt", listOf("T9")), syncLine(SyncInfo(SyncState.SYNCED, 9), t))
        assertEquals(SyncLine("driveSync.synced"), syncLine(SyncInfo(SyncState.SYNCED, null), t))
        assertEquals(SyncLine("driveSync.waiting"), syncLine(SyncInfo(SyncState.WAITING, null), t))
        assertEquals(SyncLine("driveSync.paused"), syncLine(SyncInfo(SyncState.PAUSED, null), t))
        assertEquals(SyncLine("driveSync.offline"), syncLine(SyncInfo(SyncState.OFFLINE, null), t))
        assertEquals(SyncLine("driveSync.needsConfirmation", listOf(3, 40)), syncLine(SyncInfo(SyncState.NEEDS_CONFIRMATION, null, housesToDelete = 3, liveHouses = 40), t))
        assertEquals(SyncLine("driveSync.skippedFiles", listOf(2)), syncLine(SyncInfo(SyncState.SKIPPED_FILES, null, skipped = listOf(SkipReason.entries.first(), SkipReason.entries.first())), t))
        assertEquals(SyncLine("driveSync.error", isError = true), syncLine(SyncInfo(SyncState.ERROR, null), t))
        assertEquals(SyncLine("driveProblem.OFFLINE", isError = true), syncLine(SyncInfo(SyncState.ERROR, null, error = DriveReason.OFFLINE), t))
    }

    @Test fun theShrinkGuardAsksOnlyWhenTheControllerSaysSo() {
        for (s in SyncState.entries) assertEquals(s == SyncState.NEEDS_CONFIRMATION, syncConfirmVisible(SyncInfo(s, null)), "$s")
        assertTrue(syncNowEnabled(false))
        assertFalse(syncNowEnabled(true))
    }

    @Test fun photosAreWifiOnlyByDefaultAndTheMobileDataOneOffNeedsWaitingPhotos() {
        assertTrue(photosWifiOnly(uploadOnMobileData = false))
        assertFalse(photosWifiOnly(uploadOnMobileData = true))
        assertTrue(uploadNowOffered(wifiOnly = true, pendingBytes = 10))
        assertFalse(uploadNowOffered(wifiOnly = true, pendingBytes = 0))
        assertFalse(uploadNowOffered(wifiOnly = true, pendingBytes = null))
        assertFalse(uploadNowOffered(wifiOnly = false, pendingBytes = 10))
    }

    @Test fun everyPhotoStatusHasItsKey() {
        assertEquals("drivePhotos.waitingForWifi", photoStatusKey(PhotoNetworkStatus.WAITING_FOR_WIFI))
        assertEquals("drivePhotos.uploading", photoStatusKey(PhotoNetworkStatus.UPLOADING))
        assertEquals("drivePhotos.pausedOffline", photoStatusKey(PhotoNetworkStatus.PAUSED_OFFLINE))
        assertEquals("drivePhotos.done", photoStatusKey(PhotoNetworkStatus.DONE))
    }

    // ---- devices, keys ---------------------------------------------------------------------------------------------------------

    @Test fun thisPhoneCannotBeRevokedFromItself() {
        assertFalse(revokeOffered(ListedDevice("aa", "Pixel", "android", self = true)))
        assertTrue(revokeOffered(ListedDevice("bb", "Laptop", "web", self = false)))
    }

    @Test fun reasonsKeepTheirDictionaryKeys() {
        for (r in DriveReason.entries) assertEquals(r.key, reasonKey(r))
        assertEquals("drive_delete_reason_auth_failed", driveResName("driveDelete.reason.AUTH_FAILED"))
        assertEquals("drive_connect_not_configured", driveResName("driveConnect.notConfigured"))
        assertEquals("drive_problem_no_recovery_key", driveResName("driveProblem.NO_RECOVERY_KEY"))
        assertEquals("drive_backups_error_not_connected", driveResName("driveBackups.error.notConnected"))
        assertEquals("drive_common_next", driveResName("drive.common.next"))
    }

    @Test fun joinNeedsTextAndNoRunningOperation() {
        assertFalse(joinEnabled("   ", busy = false))
        assertFalse(joinEnabled("KEY", busy = true))
        assertTrue(joinEnabled("KEY", busy = false))
    }

    // ---- Delete this backup, the clipboard seam ----------------------------------------------------------------------------

    @Test fun deleteThisBackupIsOnOnlyWhileNothingElseRuns() {
        assertTrue(deleteBackupEnabled(busy = false, backupBusy = false, phase = DeletePhase.MENU))
        assertFalse(deleteBackupEnabled(busy = true, backupBusy = false, phase = DeletePhase.MENU))
        assertFalse(deleteBackupEnabled(busy = false, backupBusy = true, phase = DeletePhase.MENU))
        assertFalse(deleteBackupEnabled(busy = false, backupBusy = false, phase = DeletePhase.CONFIRM))
    }

    @Test fun theDeleteOfOneBackupIsDrawnUnderTheBackupsListAndNoOtherDeleteIs() {
        assertTrue(deleteFlowInBackups(DeleteChoice.ONE_BACKUP, DeletePhase.PLAN))
        assertTrue(deleteFlowInBackups(DeleteChoice.ONE_BACKUP, DeletePhase.DONE))
        assertFalse(deleteFlowInBackups(DeleteChoice.ONE_BACKUP, DeletePhase.MENU))
        assertFalse(deleteFlowInBackups(DeleteChoice.EVERYTHING, DeletePhase.PLAN))
        assertFalse(deleteFlowInBackups(null, DeletePhase.PLAN))
    }

    private class RecordingClipboard(val fail: Boolean = false) : ClipboardSeam {
        val copied = mutableListOf<String>()
        override fun copySensitive(text: String) {
            if (fail) throw IllegalStateException("no clipboard")
            copied += text
        }
    }

    @Test fun theRecoveryKeyIsCopiedOnlyThroughTheSensitiveSeam() {
        val seam = RecordingClipboard()
        assertTrue(copyToClipboard(seam, "ABCD-EFGH"))
        assertEquals(listOf("ABCD-EFGH"), seam.copied)
    }

    @Test fun aRefusedCopyIsReportedNotThrown() {
        assertFalse(copyToClipboard(RecordingClipboard(fail = true), "ABCD-EFGH"))
    }
}
