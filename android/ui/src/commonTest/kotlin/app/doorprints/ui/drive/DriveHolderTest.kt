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

import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.backup.BackupSchedule
import app.doorprints.drive.connect.BackUpDone
import app.doorprints.drive.connect.BackupList
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.ConnectResult
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DeleteConfirmInfo
import app.doorprints.drive.connect.DeleteFactor
import app.doorprints.drive.connect.DeleteRun
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.EnrolmentWrap
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.RevokeDone
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.photo.PhotoNetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-234"

/** A [DriveActions] that records what it was asked and answers from fields. */
private class FakeActions : DriveActions {
    override val state = MutableStateFlow(ConnectState.DISCONNECTED)
    override val enrolmentNotice = MutableStateFlow<DriveReason?>(null)
    val calls = mutableListOf<String>()
    override val folderGone = MutableStateFlow(false)
    val deleteActions = mutableListOf<DeletionAction>()
    val approved = mutableListOf<Triple<String, DevicePlatform, ByteArray>>()

    var connectResult = ConnectResult(ConnectState.DISCONNECTED)
    var createResult = ConnectResult(ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY, recoveryKey = KEY)
    var joinResult = ConnectResult(ConnectState.READY)
    var backups: Outcome<BackupList> = Outcome.Ok(BackupList(emptyList(), false))
    var backUp: Outcome<BackUpDone> = Outcome.Ok(BackUpDone(BackupSummary("b1", 1_000, 12, 2048, "n"), null, false))
    var sync = SyncInfo(SyncState.SYNCED, 5)
    var wifiOnly = true
    var pending: Long? = 0
    var devices = listOf(ListedDevice("aa", "This phone", "android", true), ListedDevice("bb", "Laptop", "web", false))
    var revoke: Outcome<RevokeDone> = Outcome.Ok(RevokeDone("NEWK-EYNE-WKEY"))
    var disconnectAll: Outcome<Unit> = Outcome.Ok(Unit)
    var approve: Outcome<EnrolmentWrap> = Outcome.Ok(EnrolmentWrap("enc", "ct", 3))
    var plan: Outcome<DeletionPlan> = Outcome.Ok(plan(DeletionLevel.L3, DeletionAction.Everything))
    var confirmInfo: Outcome<DeleteConfirmInfo> = Outcome.Ok(DeleteConfirmInfo(DeletionLevel.L3, DeleteFactor.DEVICE_AUTH, true, 5_000))
    var run: Outcome<DeleteRun> = Outcome.Ok(DeleteRun(true, 0, 9, null))
    var resume: Outcome<DeleteRun> = Outcome.Ok(DeleteRun(true, 0, 9, null))
    val prompts = mutableListOf<String>()

    override suspend fun connect() = connectResult.also { calls += "connect" }
    override suspend fun createFolder() = createResult.also { calls += "createFolder"; state.value = it.state }
    override suspend fun openWithRecoveryKey(text: String): ConnectResult {
        calls += "open:$text"
        return joinResult.also { state.value = it.state }
    }
    var leavesKeyScreen = true
    override fun confirmRecoveryKeySaved() { calls += "confirmSaved"; if (leavesKeyScreen) state.value = ConnectState.READY }
    override fun skipRecoveryKeyWithWarning() { calls += "skip"; state.value = ConnectState.READY }
    override suspend fun disconnect() { calls += "disconnect"; state.value = ConnectState.DISCONNECTED }
    override suspend fun disconnectAll(promptReason: String): Outcome<Unit> {
        calls += "disconnectAll"; prompts += promptReason
        if (disconnectAll is Outcome.Ok) state.value = ConnectState.DISCONNECTED
        return disconnectAll
    }
    override suspend fun accountEmail() = "me@example.org"
    override suspend fun listBackups() = backups
    override suspend fun backUpNow() = backUp.also { calls += "backUpNow" }
    override suspend fun confirmShrink(backupId: String) { calls += "confirmShrink:$backupId" }
    var auto = false
    override fun autoBackupEnabled() = auto
    override fun setAutoBackup(enabled: Boolean) { auto = enabled }
    override suspend fun syncNow(confirmShrink: Boolean): SyncInfo { calls += "syncNow:$confirmShrink"; return sync }
    override fun syncStatus() = sync
    override fun photosWifiOnly() = wifiOnly
    override fun setPhotosWifiOnly(wifiOnly: Boolean) { calls += "wifiOnly:$wifiOnly"; this.wifiOnly = wifiOnly }
    override fun uploadPhotosNowOverMobile() { calls += "uploadNow" }
    override suspend fun pendingPhotoBytes() = pending
    override fun photoStatus(pendingPhotos: Int) = if (pendingPhotos > 0) PhotoNetworkStatus.WAITING_FOR_WIFI else PhotoNetworkStatus.DONE
    override fun devicePublicKey() = byteArrayOf(1, 2, 3)
    override fun listedDevices() = devices
    override suspend fun approveJoinedDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray, promptReason: String): Outcome<EnrolmentWrap> {
        calls += "approve:$name"; prompts += promptReason; approved += Triple(name, platform, publicKey)
        return approve
    }
    override suspend fun joinFromPsk(wrapEnc: String, wrapCt: String, epoch: Int, psk: ByteArray): ConnectResult {
        calls += "joinPsk:$wrapEnc:$epoch:${psk.joinToString(",")}"
        return joinResult.also { state.value = it.state }
    }
    override suspend fun revokeListedDevice(kidHex: String, promptReason: String): Outcome<RevokeDone> {
        calls += "revoke:$kidHex"; prompts += promptReason
        return revoke
    }
    override suspend fun deletePlan(action: DeletionAction) = plan.also { calls += "plan"; deleteActions += action }
    override suspend fun deleteConfirmInfo(action: DeletionAction) = confirmInfo.also { calls += "confirmInfo"; deleteActions += action }
    override suspend fun runDelete(plan: DeletionPlan, promptReason: String): Outcome<DeleteRun> {
        calls += "runDelete"; prompts += promptReason
        return run
    }
    override suspend fun resumeDelete(promptReason: String): Outcome<DeleteRun> {
        calls += "resumeDelete"; prompts += promptReason
        return resume
    }

    companion object {
        fun plan(level: DeletionLevel, action: DeletionAction) = DeletionPlan(action, level, "root", emptyList(), emptyMap(), 0, 0, "op")
    }
}

private class FakeCodec : EnrolmentCodec {
    override fun newOffer(publicKey: ByteArray, deviceName: String) = NewcomerOffer("dp1.OFFER", "12345678", byteArrayOf(9, 8))
    override fun parseOffer(text: String) = when (text) {
        "dp1.OFFER" -> ApproverOffer(byteArrayOf(1), "New phone", DevicePlatform.ANDROID, byteArrayOf(9, 8), "12345678")
        "dp1.WEB" -> ApproverOffer(byteArrayOf(2), "Browser", DevicePlatform.WEB, byteArrayOf(9, 8), "12345678")
        else -> null
    }
    override fun encodeReply(wrap: EnrolmentWrap) = "dp1r.${wrap.wrapEnc}.${wrap.epoch}"
    override fun parseReply(text: String, offer: NewcomerOffer) = if (text == "dp1r.enc.3") ReplyWrap("enc", "ct", 3) else null
}

@OptIn(ExperimentalCoroutinesApi::class)
class DriveHolderTest {
    private var now = 0L
    private val prompts = DrivePrompts("P-DELETE", "P-REVOKE", "P-APPROVE", "P-ALL")

    private fun TestScope.holder(a: FakeActions) = DriveHolder(a, backgroundScope, { prompts }, FakeCodec(), { "My phone" }, DevicePlatform.ANDROID) { now }.also { runCurrent() }

    private fun TestScope.connectToKeyScreen(a: FakeActions): DriveHolder {
        val h = holder(a)
        h.connect()
        runCurrent()
        return h
    }

    // ---- the folder was deleted (review 1) -------------------------------------------------------------------------------

    private val folderGoneResult = ConnectResult(ConnectState.DISCONNECTED, error = DriveReason.FOLDER_GONE)

    @Test fun connectAfterFolderGoneIsNotCalledAgainAndAgain() = runTest {
        // The reported loop: Connect, "The folder was deleted", Connect again, ... and the folder was never made.
        val a = FakeActions().apply { connectResult = folderGoneResult }
        val h = holder(a)
        h.connect(); runCurrent()
        h.connect(); runCurrent()
        assertEquals(listOf("connect"), a.calls, "a second Connect must not go round again, and must never create a folder")
    }

    @Test fun aDeletedFolderShowsTheQuestionCardAndCreatesNothing() = runTest {
        val a = FakeActions().apply { connectResult = folderGoneResult }
        val h = holder(a)
        h.connect(); runCurrent()
        assertEquals(DriveCard.FOLDER_GONE, h.ui.value.card)
        assertFalse("createFolder" in a.calls, "never auto-create (docs/15 section 3.4)")
        assertEquals(listOf(DriveControl.START_AGAIN, DriveControl.DISCONNECT), connectControls(h.ui.value.card, h.ui.value.error, false, false).map { it.control })
    }

    @Test fun startAgainMakesTheFolderAndShowsTheNewKeyOnce() = runTest {
        val a = FakeActions().apply { connectResult = folderGoneResult }
        val h = holder(a)
        h.connect(); runCurrent()
        h.startAgain(); runCurrent()
        assertEquals(listOf("connect", "createFolder"), a.calls)
        assertEquals(DriveCard.RECOVERY_KEY, h.ui.value.card)
        assertEquals(KEY, h.ui.value.connectKey?.text)
        assertNull(h.ui.value.error)
    }

    @Test fun startAgainOutsideTheDeletedFolderCardDoesNothing() = runTest {
        val a = FakeActions()
        val h = holder(a)
        h.startAgain(); runCurrent()
        assertTrue(a.calls.isEmpty(), "Start again is not a back door to creating a folder")
    }

    @Test fun startAgainWhileBusyIsIgnored() = runTest {
        val a = FakeActions().apply { connectResult = folderGoneResult }
        val h = holder(a)
        h.connect(); runCurrent()
        h.startAgain(); h.startAgain(); runCurrent()
        assertEquals(1, a.calls.count { it == "createFolder" })
    }

    @Test fun disconnectOnTheDeletedFolderCardIsTheWayOut() = runTest {
        val a = FakeActions().apply { connectResult = folderGoneResult }
        val h = holder(a)
        h.connect(); runCurrent()
        h.disconnect(); runCurrent()
        assertEquals(listOf("connect", "disconnect"), a.calls)
        assertEquals(DriveCard.DISCONNECTED, h.ui.value.card)
        assertNull(h.ui.value.error)
    }

    @Test fun aFailedStartAgainLeavesTheErrorCardNotTheQuestion() = runTest {
        val a = FakeActions().apply { connectResult = folderGoneResult; createResult = ConnectResult(ConnectState.ERROR, error = DriveReason.SERVER) }
        val h = holder(a)
        h.connect(); runCurrent()
        h.startAgain(); runCurrent()
        assertEquals(DriveCard.ERROR, h.ui.value.card)
        assertEquals(DriveReason.SERVER, h.ui.value.error)
    }

    @Test fun aFolderTheAppFoundGoneShowsTheQuestionWhenSettingsOpens() = runTest {
        val a = FakeActions().apply { folderGone.value = true }
        val h = holder(a)
        assertEquals(DriveCard.FOLDER_GONE, h.ui.value.card)
        assertTrue(a.calls.isEmpty(), "opening Settings makes no call and creates nothing")
    }

    @Test fun theQuestionIsThereFromTheFirstFrameNotAfterACollector() = runTest {
        val a = FakeActions().apply { folderGone.value = true }
        val h = DriveHolder(a, backgroundScope, { prompts }, FakeCodec(), { "My phone" }, DevicePlatform.ANDROID) { now }
        assertEquals(DriveCard.FOLDER_GONE, h.ui.value.card, "no flash of the Connect card")
    }

    @Test fun aFolderFoundGoneWhileTheScreenIsOpenShowsTheQuestion() = runTest {
        val a = FakeActions()
        val h = holder(a)
        assertEquals(DriveCard.DISCONNECTED, h.ui.value.card)
        a.folderGone.value = true; runCurrent()
        assertEquals(DriveCard.FOLDER_GONE, h.ui.value.card)
    }

    @Test fun aFolderGoneSignalDoesNotCoverAConnectedPhone() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; folderGone.value = true }
        val h = holder(a)
        assertEquals(DriveCard.READY, h.ui.value.card)
    }

    // ---- recovery key shown once ----------------------------------------------------------------------------------------

    @Test fun firstConnectCreatesTheFolderAndShowsTheKeyOnTheKeyCard() = runTest {
        val a = FakeActions()
        val h = connectToKeyScreen(a)
        assertEquals(listOf("connect", "createFolder"), a.calls)
        assertEquals(DriveCard.RECOVERY_KEY, h.ui.value.card)
        assertEquals(KEY, h.ui.value.connectKey?.text)
    }

    @Test fun theKeyIsNotInTheStateTextAndNextNeedsTheTick() = runTest {
        val a = FakeActions()
        val h = connectToKeyScreen(a)
        assertFalse(h.ui.value.toString().contains(KEY))
        h.keyNext()
        runCurrent()
        assertFalse("confirmSaved" in a.calls, "Next without the tick does nothing")
        assertNotNull(h.ui.value.connectKey)
        h.setKeySaved(true)
        h.keyNext()
        runCurrent()
        assertTrue("confirmSaved" in a.calls)
        assertNull(h.ui.value.connectKey, "the key is gone once the screen is left")
        assertEquals(DriveCard.READY, h.ui.value.card)
    }

    @Test fun skipDropsTheKeyToo() = runTest {
        val a = FakeActions()
        val h = connectToKeyScreen(a)
        h.keySkip()
        runCurrent()
        assertTrue("skip" in a.calls)
        assertNull(h.ui.value.connectKey)
    }

    @Test fun theKeyDoesNotComeBackAfterALaterStateChange() = runTest {
        val a = FakeActions()
        val h = connectToKeyScreen(a)
        h.setKeySaved(true)
        h.keyNext()
        runCurrent()
        a.state.value = ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY
        runCurrent()
        assertNull(h.ui.value.connectKey)
    }

    @Test fun theKeyIsDroppedByNextEvenBeforeTheControllerMovesOn() = runTest {
        val a = FakeActions().apply { leavesKeyScreen = false }
        val h = connectToKeyScreen(a)
        h.setKeySaved(true)
        h.keyNext()
        runCurrent()
        assertNull(h.ui.value.connectKey)
    }

    @Test fun theKeyIsDroppedWhenTheStateLeavesTheKeyScreenByItself() = runTest {
        val a = FakeActions()
        val h = connectToKeyScreen(a)
        assertNotNull(h.ui.value.connectKey)
        a.state.value = ConnectState.DISCONNECTED
        runCurrent()
        assertNull(h.ui.value.connectKey)
    }

    @Test fun aFailedSignInShowsItsReasonAndCreatesNothing() = runTest {
        val a = FakeActions().apply { connectResult = ConnectResult(ConnectState.DISCONNECTED, error = DriveReason.SIGNIN_CLOSED) }
        val h = holder(a)
        h.connect()
        runCurrent()
        assertEquals(listOf("connect"), a.calls)
        assertEquals(DriveReason.SIGNIN_CLOSED, h.ui.value.error)
        assertNull(h.ui.value.connectKey)
    }

    @Test fun joiningPassesTheTypedKeyOnAndKeepsNoCopy() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.NEEDS_ENROLMENT }
        val h = holder(a)
        h.join("TYPED-KEY-123")
        runCurrent()
        assertTrue("open:TYPED-KEY-123" in a.calls)
        assertFalse(h.ui.value.toString().contains("TYPED-KEY-123"))
        assertEquals(DriveCard.READY, h.ui.value.card)
    }

    @Test fun anUnexpectedFailureOfTheJoinShowsItsCodeAndTheNextTryClearsIt() = runTest {
        val a = FakeActions().apply {
            state.value = ConnectState.NEEDS_ENROLMENT
            joinResult = ConnectResult(ConnectState.ERROR, error = DriveReason.SOURCE_FAILED, code = "join-recover/java.lang.IllegalStateException")
        }
        val h = holder(a)
        h.join("KEY")
        runCurrent()
        assertEquals(DriveReason.SOURCE_FAILED, h.ui.value.error)
        assertEquals("join-recover/java.lang.IllegalStateException", h.ui.value.errorCode)
        a.joinResult = ConnectResult(ConnectState.READY)
        a.state.value = ConnectState.NEEDS_ENROLMENT
        h.join("KEY")
        runCurrent()
        assertEquals(null, h.ui.value.errorCode)
    }

    @Test fun anErrorThrownByAnActionBecomesTheGenericFailureWithAClassCode() = runTest {
        val a = object : DriveActions by FakeActions() {
            override suspend fun connect(): ConnectResult = throw NoClassDefFoundError("Lsecret;")
        }
        val h = DriveHolder(a, backgroundScope, { DrivePrompts("a", "b", "c", "d") }).also { runCurrent() }
        h.connect()
        runCurrent()
        assertEquals(DriveReason.FAILED, h.ui.value.error)
        assertEquals("screen/java.lang.NoClassDefFoundError", h.ui.value.errorCode)
        assertFalse(h.ui.value.toString().contains("secret"))
    }

    @Test fun aFailedBackUpKeepsItsCodeUntilTheNextRun() = runTest {
        val a = FakeActions().apply { backUp = Outcome.Failed(DriveReason.SOURCE_FAILED, "backup/java.io.IOException") }
        val h = holder(a)
        h.backUpNow()
        runCurrent()
        assertEquals("backup/java.io.IOException", h.ui.value.backups.errorCode)
        a.backUp = Outcome.Ok(BackUpDone(BackupSummary("b1", 1_000, 12, 2048, "n"), null, false))
        h.backUpNow()
        runCurrent()
        assertEquals(null, h.ui.value.backups.errorCode)
    }

    @Test fun aBlankJoinDoesNotReachTheController() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.NEEDS_ENROLMENT }
        val h = holder(a)
        h.join("  ")
        runCurrent()
        assertTrue(a.calls.isEmpty())
    }

    @Test fun aWrongKeyStaysOnTheJoinCardWithItsReason() = runTest {
        val a = FakeActions().apply {
            state.value = ConnectState.NEEDS_ENROLMENT
            joinResult = ConnectResult(ConnectState.NEEDS_ENROLMENT, error = DriveReason.JOIN_WRONG_KEY)
        }
        val h = holder(a)
        h.join("WRONG")
        runCurrent()
        assertEquals(DriveCard.JOIN, h.ui.value.card)
        assertEquals(DriveReason.JOIN_WRONG_KEY, h.ui.value.error)
    }

    // ---- delete flow ----------------------------------------------------------------------------------------------------------

    private fun TestScope.toConfirm(a: FakeActions): DriveHolder {
        a.state.value = ConnectState.READY
        val h = holder(a)
        h.startDelete(DeleteChoice.EVERYTHING)
        runCurrent()
        h.proceedToConfirm()
        runCurrent()
        return h
    }

    @Test fun deleteGoesPlanThenConfirmInTheControllersOrder() = runTest {
        val a = FakeActions()
        val h = toConfirm(a)
        assertEquals(listOf("plan", "confirmInfo"), a.calls.filter { it == "plan" || it == "confirmInfo" })
        assertEquals(DeletePhase.CONFIRM, h.ui.value.delete.phase)
        assertEquals(DeleteField.entries.toSet(), deleteConfirmFields(h.ui.value.delete.info!!))
    }

    @Test fun nothingIsDeletedWithoutTheTick() = runTest {
        val a = FakeActions()
        val h = toConfirm(a)
        now = 60_000
        h.confirmDelete()
        runCurrent()
        assertFalse("runDelete" in a.calls)
        assertTrue(h.ui.value.delete.tickHint)
    }

    @Test fun nothingIsDeletedBeforeTheCountdownEnds() = runTest {
        val a = FakeActions()
        val h = toConfirm(a)
        h.toggleTick()
        now = 4_999
        h.confirmDelete()
        runCurrent()
        assertFalse("runDelete" in a.calls)
        assertEquals(1, h.countdownLeft(now))
    }

    @Test fun afterTheTickAndTheCountdownTheDeviceCheckPromptRunsTheDelete() = runTest {
        val a = FakeActions()
        val h = toConfirm(a)
        h.toggleTick()
        now = 5_000
        h.confirmDelete()
        runCurrent()
        assertEquals(1, a.calls.count { it == "runDelete" })
        assertEquals(listOf("P-DELETE"), a.prompts)
        assertEquals(DeletePhase.DONE, h.ui.value.delete.phase)
    }

    @Test fun aRunThatStoppedShowsLeftOfTotalAndOffersTryAgain() = runTest {
        val a = FakeActions().apply { run = Outcome.Ok(DeleteRun(false, 4, 9, null)) }
        val h = toConfirm(a)
        h.toggleTick()
        now = 5_000
        h.confirmDelete()
        runCurrent()
        assertEquals(DeletePhase.PARTIAL, h.ui.value.delete.phase)
        assertEquals(4, h.ui.value.delete.left)
        assertEquals(9, h.ui.value.delete.total)
        h.tryAgain()
        runCurrent()
        assertEquals(1, a.calls.count { it == "resumeDelete" })
        assertEquals(DeletePhase.DONE, h.ui.value.delete.phase)
    }

    @Test fun tryAgainDoesNothingOutsideThePartialStep() = runTest {
        val a = FakeActions()
        val h = toConfirm(a)
        h.tryAgain()
        runCurrent()
        assertFalse("resumeDelete" in a.calls)
    }

    @Test fun aRefusedDeviceCheckKeepsTheConfirmStepWithItsReason() = runTest {
        val a = FakeActions().apply { run = Outcome.Failed(DriveReason.AUTH_CANCELLED) }
        val h = toConfirm(a)
        h.toggleTick()
        now = 5_000
        h.confirmDelete()
        runCurrent()
        assertEquals(DeletePhase.CONFIRM, h.ui.value.delete.phase)
        assertEquals(DriveReason.AUTH_CANCELLED, h.ui.value.delete.error)
    }

    @Test fun aRefusedPlanShowsTheReasonAndCancelGoesBackToTheMenu() = runTest {
        val a = FakeActions().apply { plan = Outcome.Failed(DriveReason.DELETE_NOTHING_TO_DELETE); state.value = ConnectState.READY }
        val h = holder(a)
        h.startDelete(DeleteChoice.ALL_BACKUPS)
        runCurrent()
        assertEquals(DeletePhase.ERROR, h.ui.value.delete.phase)
        assertEquals(DriveReason.DELETE_NOTHING_TO_DELETE, h.ui.value.delete.error)
        h.cancelDelete()
        assertEquals(DeletePhase.MENU, h.ui.value.delete.phase)
    }

    @Test fun theTickCanBeToggledOnlyWhenTheBoxIsAsked() = runTest {
        val a = FakeActions().apply { confirmInfo = Outcome.Ok(DeleteConfirmInfo(DeletionLevel.L1, DeleteFactor.NONE, false, 0)) }
        val h = toConfirm(a)
        h.toggleTick()
        assertFalse(h.ui.value.delete.ticked)
        h.confirmDelete()
        runCurrent()
        assertTrue("runDelete" in a.calls, "level 1 with no box runs at once")
    }

    // ---- backups, sync, photos ---------------------------------------------------------------------------------------------------

    @Test fun backUpNowKeepsTheShrinkQuestionUntilConfirmedOrKept() = runTest {
        val a = FakeActions().apply { backUp = Outcome.Ok(BackUpDone(BackupSummary("b2", 2_000, 5, null, "n"), shrinkHoldBackupId = "b2", missingNewer = false)); state.value = ConnectState.READY }
        val h = holder(a)
        h.backUpNow()
        runCurrent()
        assertEquals("b2", h.ui.value.backups.shrinkHoldId)
        h.confirmShrink()
        runCurrent()
        assertTrue("confirmShrink:b2" in a.calls)
        assertNull(h.ui.value.backups.shrinkHoldId)
    }

    @Test fun keepingOlderBackupsDoesNotConfirm() = runTest {
        val a = FakeActions().apply { backUp = Outcome.Ok(BackUpDone(BackupSummary("b2", 2_000, 5, null, "n"), "b2", false)); state.value = ConnectState.READY }
        val h = holder(a)
        h.backUpNow()
        runCurrent()
        h.keepOlderBackups()
        assertNull(h.ui.value.backups.shrinkHoldId)
        assertFalse(a.calls.any { it.startsWith("confirmShrink") })
    }

    @Test fun aFailedBackupShowsItsReasonAndLeavesTheListAlone() = runTest {
        val a = FakeActions().apply { backUp = Outcome.Failed(DriveReason.NO_BACKUP_SOURCE); state.value = ConnectState.READY }
        val h = holder(a)
        h.backUpNow()
        runCurrent()
        assertEquals(DriveReason.NO_BACKUP_SOURCE, h.ui.value.backups.error)
        assertFalse(h.ui.value.backups.busy)
    }

    @Test fun theListShowsLoadErrorEmptyAndRows() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; backups = Outcome.Failed(DriveReason.OFFLINE) }
        val h = holder(a)
        assertEquals(ListState.ERROR, h.ui.value.backups.listState)
        a.backups = Outcome.Ok(BackupList(emptyList(), false))
        h.loadBackups(); runCurrent()
        assertEquals(ListState.EMPTY, h.ui.value.backups.listState)
        a.backups = Outcome.Ok(BackupList(listOf(BackupSummary("x", 7, 3, 1, "n")), true))
        h.loadBackups(); runCurrent()
        assertEquals(ListState.LIST, h.ui.value.backups.listState)
        assertTrue(h.ui.value.backups.missingNewer)
        assertEquals(7L, h.ui.value.backups.lastBackupAt)
    }

    @Test fun theSyncShrinkQuestionAppliesOrIsDismissed() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; sync = SyncInfo(SyncState.NEEDS_CONFIRMATION, null, housesToDelete = 2, liveHouses = 9) }
        val h = holder(a)
        h.syncNow(); runCurrent()
        assertTrue(syncConfirmVisible(h.ui.value.sync.info))
        h.applySyncShrink(); runCurrent()
        assertTrue("syncNow:true" in a.calls)
        h.notNowSyncShrink()
        assertTrue(h.ui.value.sync.confirmDismissed)
    }

    @Test fun thePhotosSwitchAndTheMobileOneOff() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; pending = 5_000 }
        val h = holder(a)
        assertTrue(h.ui.value.sync.wifiOnly)
        assertTrue(uploadNowOffered(h.ui.value.sync.wifiOnly, h.ui.value.sync.pendingBytes))
        h.uploadPhotosNow(); runCurrent()
        assertEquals(listOf("uploadNow", "syncNow:false"), a.calls.filter { it == "uploadNow" || it.startsWith("syncNow") })
        assertTrue(h.ui.value.sync.wifiOnly, "the one-off leaves the switch on")
        h.setWifiOnly(false); runCurrent()
        assertTrue("wifiOnly:false" in a.calls)
        assertFalse(h.ui.value.sync.wifiOnly)
    }

    @Test fun automaticBackupFollowsTheController() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.setAutoBackup(true)
        assertTrue(h.ui.value.backups.auto)
    }

    // ---- devices --------------------------------------------------------------------------------------------------------------------

    @Test fun revokeAsksThenShowsTheNewKeyOnceUntilTickedSaved() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.askRevoke(a.devices[0])
        assertNull(h.ui.value.devices.revoking, "this phone cannot revoke itself")
        h.askRevoke(a.devices[1])
        assertEquals("bb", h.ui.value.devices.revoking?.kidHex)
        h.confirmRevoke(); runCurrent()
        assertEquals(listOf("P-REVOKE"), a.prompts)
        assertEquals("NEWK-EYNE-WKEY", h.ui.value.devices.newKey?.text)
        assertFalse(h.ui.value.toString().contains("NEWK-EYNE-WKEY"))
        h.dismissNewKey()
        assertNotNull(h.ui.value.devices.newKey, "not before the tick")
        h.setNewKeySaved(true)
        h.dismissNewKey()
        assertNull(h.ui.value.devices.newKey)
    }

    @Test fun aRefusedRevokeShowsTheReasonAndNoKey() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; revoke = Outcome.Failed(DriveReason.AUTH_FAILED) }
        val h = holder(a)
        h.askRevoke(a.devices[1]); h.confirmRevoke(); runCurrent()
        assertNull(h.ui.value.devices.newKey)
        assertEquals(DriveReason.AUTH_FAILED, h.ui.value.devices.error)
    }

    @Test fun disconnectOnAllDevicesAsksForTheDeviceCheckAndLeaves() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.askDisconnectAll()
        assertTrue(h.ui.value.devices.confirmingDisconnectAll)
        h.confirmDisconnectAll(); runCurrent()
        assertEquals(listOf("P-ALL"), a.prompts)
        assertEquals(DriveCard.DISCONNECTED, h.ui.value.card)
    }

    @Test fun aRefusedDisconnectAllKeepsThePhoneConnected() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; disconnectAll = Outcome.Failed(DriveReason.NO_DEVICE_LOCK) }
        val h = holder(a)
        h.askDisconnectAll(); h.confirmDisconnectAll(); runCurrent()
        assertEquals(DriveCard.READY, h.ui.value.card)
        assertEquals(DriveReason.NO_DEVICE_LOCK, h.ui.value.devices.error)
    }

    // ---- enrolment ----------------------------------------------------------------------------------------------------------------

    @Test fun theNewPhoneShowsItsQrAndJoinsWithTheReplyAndItsOwnSecret() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.NEEDS_ENROLMENT }
        val h = holder(a)
        h.showQr()
        val e = h.ui.value.enrol as EnrolUi.Newcomer
        assertEquals("dp1.OFFER", e.offer.qrText)
        assertFalse(h.ui.value.toString().contains("9, 8"), "the secret is not in the state text")
        h.submitReply("garbage")
        assertEquals(DriveReason.ENROL_BAD_MESSAGE, (h.ui.value.enrol as EnrolUi.Newcomer).error)
        assertFalse(a.calls.any { it.startsWith("joinPsk") })
        h.submitReply("dp1r.enc.3"); runCurrent()
        assertTrue("joinPsk:enc:3:9,8" in a.calls)
        assertEquals(EnrolUi.Idle, h.ui.value.enrol)
    }

    @Test fun theConnectedPhoneComparesTheCodeThenApprovesAndShowsTheReply() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.startApprove()
        h.submitOffer("nonsense")
        assertEquals(DriveReason.ENROL_BAD_MESSAGE, (h.ui.value.enrol as EnrolUi.ApproverInput).error)
        h.submitOffer("dp1.OFFER")
        assertEquals("12345678", (h.ui.value.enrol as EnrolUi.ApproverCheck).offer.code)
        assertFalse(a.calls.any { it.startsWith("approve") }, "nothing is approved before the numbers match")
        h.confirmCodesMatch(); runCurrent()
        assertEquals(listOf("P-APPROVE"), a.prompts)
        assertEquals("dp1r.enc.3", (h.ui.value.enrol as EnrolUi.ApproverReply).replyText)
    }

    @Test fun aRefusedApprovalStaysOnTheCodeCheck() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; approve = Outcome.Failed(DriveReason.AUTH_CANCELLED) }
        val h = holder(a)
        h.startApprove(); h.submitOffer("dp1.OFFER"); h.confirmCodesMatch(); runCurrent()
        assertEquals(DriveReason.AUTH_CANCELLED, (h.ui.value.enrol as EnrolUi.ApproverCheck).error)
    }

    @Test fun aMissingCameraIsSaidOnTheEnrolmentStep() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.startApprove(); h.scannerMissing()
        assertTrue((h.ui.value.enrol as EnrolUi.ApproverInput).cameraMissing)
    }

    @Test fun aRefusedCameraIsSaidOnTheEnrolmentStepAndIsNotTheMissingCameraLine() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.startApprove(); h.scannerDenied()
        val e = h.ui.value.enrol as EnrolUi.ApproverInput
        assertTrue(e.cameraDenied)
        assertFalse(e.cameraMissing)
        h.scannerMissing()
        val m = h.ui.value.enrol as EnrolUi.ApproverInput
        assertTrue(m.cameraMissing)
        assertFalse(m.cameraDenied, "one note at a time")
        h.scannerDenied()
        val d = h.ui.value.enrol as EnrolUi.ApproverInput
        assertTrue(d.cameraDenied)
        assertFalse(d.cameraMissing, "one note at a time, both ways")
    }

    @Test fun disconnectClearsEverything() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.startApprove()
        h.disconnect(); runCurrent()
        assertEquals(DriveCard.DISCONNECTED, h.ui.value.card)
        assertEquals(EnrolUi.Idle, h.ui.value.enrol)
        assertNull(h.ui.value.connectKey)
    }

    // ---- Delete this backup (review 6) ----------------------------------------------------------------------------------------

    private fun TestScope.toBackupConfirm(a: FakeActions): DriveHolder {
        val h = holder(a)
        h.startDelete(DeleteChoice.ONE_BACKUP, "b1"); runCurrent()
        h.proceedToConfirm(); runCurrent()
        return h
    }

    @Test fun deleteThisBackupAsksForTheOneBackupsPlanAndConfirmInfo() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; confirmInfo = Outcome.Ok(DeleteConfirmInfo(DeletionLevel.L1, DeleteFactor.NONE, false, 0)) }
        val h = toBackupConfirm(a)
        assertEquals(listOf<DeletionAction>(DeletionAction.OneBackup("b1"), DeletionAction.OneBackup("b1")), a.deleteActions)
        assertEquals(DeleteChoice.ONE_BACKUP, h.ui.value.delete.choice)
        assertEquals(DeletePhase.CONFIRM, h.ui.value.delete.phase)
        assertTrue(deleteFlowInBackups(h.ui.value.delete.choice, h.ui.value.delete.phase))
    }

    @Test fun theLastBackupIsLevelTwoAndShowsTheDeviceCheckWordingAndRunsWithItsPrompt() = runTest {
        val a = FakeActions().apply {
            state.value = ConnectState.READY
            confirmInfo = Outcome.Ok(DeleteConfirmInfo(DeletionLevel.L2, DeleteFactor.DEVICE_AUTH, true, 0))
        }
        val h = toBackupConfirm(a)
        val info = h.ui.value.delete.info!!
        assertTrue(deviceCheckWordingShown(info.level))
        assertTrue(DeleteField.DEVICE_CHECK_NOTE in deleteConfirmFields(info))
        h.confirmDelete(); runCurrent()
        assertFalse("runDelete" in a.calls, "the tick box comes first")
        h.toggleTick(); h.confirmDelete(); runCurrent()
        assertEquals(listOf("P-DELETE"), a.prompts)
        assertEquals(DeletePhase.DONE, h.ui.value.delete.phase)
    }

    @Test fun anotherBackupStillThereIsLevelOneWithoutTheDeviceCheckWording() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY; confirmInfo = Outcome.Ok(DeleteConfirmInfo(DeletionLevel.L1, DeleteFactor.NONE, false, 0)) }
        val h = toBackupConfirm(a)
        assertFalse(deviceCheckWordingShown(h.ui.value.delete.info!!.level))
        h.confirmDelete(); runCurrent()
        assertTrue("runDelete" in a.calls)
    }

    @Test fun deleteThisBackupNeedsABackupId() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.startDelete(DeleteChoice.ONE_BACKUP, null); runCurrent()
        assertTrue(a.calls.isEmpty())
        assertEquals(DeletePhase.MENU, h.ui.value.delete.phase)
    }

    // ---- naming the new device (review 9) -------------------------------------------------------------------------------------

    private fun TestScope.toApproverCheck(a: FakeActions, offer: String = "dp1.OFFER"): DriveHolder {
        a.state.value = ConnectState.READY
        val h = holder(a)
        h.startApprove(); h.submitOffer(offer)
        return h
    }

    @Test fun theApproverNamesTheNewDeviceAndItGoesToTheControllerWithTheDevicesKey() = runTest {
        val a = FakeActions()
        val h = toApproverCheck(a)
        h.setApproveName("Amma's phone")
        h.confirmCodesMatch(); runCurrent()
        val (name, platform, key) = a.approved.single()
        assertEquals("Amma's phone", name)
        assertEquals(DevicePlatform.ANDROID, platform)
        assertEquals(listOf<Byte>(1), key.toList())
    }

    @Test fun theNameDefaultsToNewDeviceWhenNothingOrBlanksAreTyped() = runTest {
        val a = FakeActions()
        val h = toApproverCheck(a)
        assertEquals("New device", (h.ui.value.enrol as EnrolUi.ApproverCheck).name)
        h.setApproveName("   ")
        h.confirmCodesMatch(); runCurrent()
        assertEquals("New device", a.approved.single().first)
    }

    @Test fun theNameIsTrimmedAndKeptShort() = runTest {
        val a = FakeActions()
        val h = toApproverCheck(a)
        h.setApproveName("  " + "x".repeat(200))
        assertEquals(MAX_DEVICE_NAME, (h.ui.value.enrol as EnrolUi.ApproverCheck).name.length)
        h.setApproveName("  Laptop  ")
        h.confirmCodesMatch(); runCurrent()
        assertEquals("Laptop", a.approved.single().first)
    }

    @Test fun chooseComputerGivesTheWebPlatformAndPhoneKeepsThePhonesOwn() = runTest {
        val a = FakeActions()
        val h = toApproverCheck(a)
        assertEquals(DeviceKind.PHONE, (h.ui.value.enrol as EnrolUi.ApproverCheck).kind)
        h.setApproveKind(DeviceKind.COMPUTER)
        h.confirmCodesMatch(); runCurrent()
        assertEquals(DevicePlatform.WEB, a.approved.single().second)
    }

    @Test fun anOfferFromTheWebsiteStartsAsComputerAndAPhoneChoiceNeverStaysWeb() = runTest {
        val a = FakeActions()
        val h = toApproverCheck(a, "dp1.WEB")
        assertEquals(DeviceKind.COMPUTER, (h.ui.value.enrol as EnrolUi.ApproverCheck).kind)
        h.setApproveKind(DeviceKind.PHONE)
        h.confirmCodesMatch(); runCurrent()
        assertEquals(DevicePlatform.ANDROID, a.approved.single().second, "this holder's own platform, not WEB")
    }

    @Test fun nameAndKindAreIgnoredOutsideTheCodeCheck() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        h.setApproveName("x"); h.setApproveKind(DeviceKind.COMPUTER)
        assertEquals(EnrolUi.Idle, h.ui.value.enrol)
    }

    // ---- the countdown's clock (review 14) ------------------------------------------------------------------------------------

    @Test fun theDefaultClockIsMonotonicNotTheWallClock() = runTest {
        val h = DriveHolder(FakeActions(), backgroundScope, { prompts })
        val first = h.now()
        val second = h.now()
        assertTrue(second >= first)
        assertTrue(first < 10L * 365 * 24 * 3600 * 1000, "a monotonic reading counts from a start, not from 1970: $first")
    }

    @Test fun theCountdownFollowsTheClockTheHolderWasGiven() = runTest {
        val a = FakeActions().apply { state.value = ConnectState.READY }
        val h = holder(a)
        now = 1_000_000_000_000
        h.startDelete(DeleteChoice.EVERYTHING); runCurrent(); h.proceedToConfirm(); runCurrent()
        assertEquals(5, h.countdownLeft(now))
        assertEquals(1_000_000_000_000, h.ui.value.delete.confirmShownAtMs)
        assertEquals(1_000_000_000_000, h.now())
    }
}
