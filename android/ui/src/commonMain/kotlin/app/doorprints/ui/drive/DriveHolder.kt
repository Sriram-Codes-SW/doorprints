package app.doorprints.ui.drive

import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.ConnectResult
import app.doorprints.drive.connect.ConnectState
import app.doorprints.drive.connect.DeleteConfirmInfo
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.connect.Outcome
import app.doorprints.drive.connect.SyncInfo
import app.doorprints.drive.connect.SyncState
import app.doorprints.drive.delete.DeletionAction
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.photo.PhotoNetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recovery key on its way to the screen: shown once, and its text is never printed (`toString` says only that it exists). */
class ShownKey(val text: String) {
    override fun toString() = "ShownKey(<shown once>)"
}

/** The system prompt lines of the device check (translated by the screen, read by the controller). */
data class DrivePrompts(val delete: String, val revoke: String, val approve: String, val disconnectAll: String)

enum class ListState { LOADING, ERROR, EMPTY, LIST }

data class BackupsUi(
    val listState: ListState = ListState.LOADING,
    val list: List<BackupSummary> = emptyList(),
    val listError: DriveReason? = null,
    val missingNewer: Boolean = false,
    val busy: Boolean = false,
    /** "N houses backed up at T": the last backup this session made, or the newest in the list. */
    val lastBackupAt: Long? = null,
    val lastBackupHouses: Int? = null,
    val error: DriveReason? = null,
    val shrinkHoldId: String? = null,
    val auto: Boolean = false,
    val noSource: Boolean = false,
)

data class SyncUi(
    val info: SyncInfo = SyncInfo(SyncState.NOT_RUN, null),
    val busy: Boolean = false,
    val confirmDismissed: Boolean = false,
    val wifiOnly: Boolean = true,
    val pendingBytes: Long? = null,
    val photoStatus: PhotoNetworkStatus = PhotoNetworkStatus.DONE,
    val photoError: Boolean = false,
)

data class DevicesUi(
    val devices: List<ListedDevice> = emptyList(),
    val account: String? = null,
    val revoking: ListedDevice? = null,
    val confirmingDisconnectAll: Boolean = false,
    /** The new recovery key after a revoke: shown once, until it is ticked as saved. */
    val newKey: ShownKey? = null,
    val newKeySaved: Boolean = false,
    val error: DriveReason? = null,
)

data class DeleteUi(
    val phase: DeletePhase = DeletePhase.MENU,
    val choice: DeleteChoice? = null,
    val plan: DeletionPlan? = null,
    val info: DeleteConfirmInfo? = null,
    val ticked: Boolean = false,
    val tickHint: Boolean = false,
    /** When the confirm step opened (the countdown counts from it). */
    val confirmShownAtMs: Long = 0L,
    val left: Int = 0,
    val total: Int = 0,
    val error: DriveReason? = null,
)

/** The enrolment dialogs. */
sealed interface EnrolUi {
    data object Idle : EnrolUi

    /** This phone shows its QR code and the 8-digit code, and waits for the reply. */
    data class Newcomer(val offer: NewcomerOffer, val error: DriveReason? = null, val cameraMissing: Boolean = false) : EnrolUi

    /** The connected phone reads (scans or pastes) the new device's code. */
    data class ApproverInput(val error: DriveReason? = null, val cameraMissing: Boolean = false) : EnrolUi

    /** The numbers are compared; approving asks for the device check. */
    data class ApproverCheck(val offer: ApproverOffer, val error: DriveReason? = null) : EnrolUi

    /** Approved: the reply to show to the new device (QR and text). */
    data class ApproverReply(val replyText: String) : EnrolUi
}

/** Everything the Google Drive section draws. The recovery keys are [ShownKey]s: they print as "shown once". */
data class DriveUiState(
    val connect: ConnectState = ConnectState.DISCONNECTED,
    val notice: DriveReason? = null,
    val error: DriveReason? = null,
    val busy: Boolean = false,
    val connectKey: ShownKey? = null,
    val keySaved: Boolean = false,
    val backups: BackupsUi = BackupsUi(),
    val sync: SyncUi = SyncUi(),
    val devices: DevicesUi = DevicesUi(),
    val delete: DeleteUi = DeleteUi(),
    val enrol: EnrolUi = EnrolUi.Idle,
) {
    val card: DriveCard get() = driveCardOf(connect, notice)
}

/**
 * The state holder of the Google Drive screens (a ViewModel's job, without Android): it wraps [DriveActions], keeps the
 * one [DriveUiState] and does what the buttons do. [DriveViewModel] gives it the view model's scope so it survives a
 * rotation; the tests give it a test scope. Nothing here is Android-only.
 *
 * Rules kept here (each has a test in DriveHolderTest):
 *  - the recovery key lives in [DriveUiState.connectKey] from the controller's answer until the person leaves the key
 *    screen, and nowhere else; the typed key of *Join* is a parameter of [join], never a field;
 *  - a delete runs only through the order the controller gives: confirm info, plan, the confirm step with its tick box
 *    and countdown, the device check, the run;
 *  - nothing writes to Drive from the card while another operation of the same kind is running.
 */
class DriveHolder(
    private val actions: DriveActions,
    private val scope: CoroutineScope,
    private val prompts: () -> DrivePrompts,
    private val codec: EnrolmentCodec? = null,
    private val deviceName: () -> String = { "Android phone" },
    private val platform: DevicePlatform = DevicePlatform.ANDROID,
    private val clock: () -> Long,
) {
    private val _ui = MutableStateFlow(DriveUiState(connect = actions.state.value, notice = actions.enrolmentNotice.value))
    val ui: StateFlow<DriveUiState> = _ui.asStateFlow()

    init {
        // The screen may open while the controller is already connected (a rotation, coming back to Settings).
        if (actions.state.value == ConnectState.READY) loadReady()
        scope.launch {
            actions.state.collect { s ->
                val before = _ui.value.connect
                _ui.update { it.copy(connect = s, connectKey = if (s == ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY) it.connectKey else null) }
                if (s == ConnectState.READY && before != ConnectState.READY) loadReady()
                if (s == ConnectState.DISCONNECTED || s == ConnectState.UNAVAILABLE) _ui.update { it.copy(delete = DeleteUi(), enrol = EnrolUi.Idle, devices = DevicesUi(), backups = BackupsUi(), sync = SyncUi()) }
            }
        }
        scope.launch { actions.enrolmentNotice.collect { n -> _ui.update { it.copy(notice = n) } } }
    }

    private fun <T> launchOp(block: suspend () -> T) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(busy = false, error = DriveReason.FAILED) }
            }
        }
    }

    // ---- Connect ---------------------------------------------------------------------------------------------------

    /** *Connect to Google Drive*: sign in, then (with no folder yet) make one and show its recovery key once. */
    fun connect() {
        if (_ui.value.busy || _ui.value.connect == ConnectState.CONNECTING) return
        _ui.update { it.copy(busy = true, error = null) }
        launchOp {
            var result = actions.connect()
            if (result.state == ConnectState.DISCONNECTED && result.error == null) result = actions.createFolder()
            applyConnect(result)
        }
    }

    private fun applyConnect(result: ConnectResult) {
        _ui.update {
            it.copy(
                busy = false, connect = result.state, error = result.error,
                connectKey = result.recoveryKey?.let(::ShownKey), keySaved = false,
            )
        }
    }

    fun setKeySaved(saved: Boolean) = _ui.update { it.copy(keySaved = saved && it.connectKey != null) }

    /** *Next* on the key screen: only with the box ticked. The key is dropped from the state. */
    fun keyNext() {
        val s = _ui.value
        if (s.connect != ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY || !s.keySaved) return
        actions.confirmRecoveryKeySaved()
        _ui.update { it.copy(connectKey = null, keySaved = false) }
    }

    /** *Skip*: the warning is already on the screen; the key is dropped all the same. */
    fun keySkip() {
        if (_ui.value.connect != ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY) return
        actions.skipRecoveryKeyWithWarning()
        _ui.update { it.copy(connectKey = null, keySaved = false) }
    }

    /** *Join this folder* with the typed recovery key. [typed] is cleared by the screen at once; it is not kept here. */
    fun join(typed: String) {
        if (!joinEnabled(typed, _ui.value.busy)) return
        _ui.update { it.copy(busy = true, error = null) }
        launchOp { applyConnect(actions.openWithRecoveryKey(typed)) }
    }

    fun disconnect() {
        _ui.update { it.copy(busy = true) }
        launchOp {
            actions.disconnect()
            _ui.update { DriveUiState(connect = actions.state.value, notice = actions.enrolmentNotice.value) }
        }
    }

    // ---- Ready: loading --------------------------------------------------------------------------------------------

    private fun loadReady() {
        _ui.update { it.copy(backups = it.backups.copy(auto = actions.autoBackupEnabled()), sync = it.sync.copy(info = actions.syncStatus(), wifiOnly = actions.photosWifiOnly())) }
        launchOp {
            val account = actions.accountEmail()
            _ui.update { it.copy(devices = it.devices.copy(account = account, devices = actions.listedDevices())) }
        }
        loadBackups()
        loadPhotos()
    }

    fun loadBackups() {
        _ui.update { it.copy(backups = it.backups.copy(listState = ListState.LOADING, listError = null)) }
        launchOp {
            when (val r = actions.listBackups()) {
                is Outcome.Ok -> _ui.update {
                    val list = r.value.backups
                    it.copy(backups = it.backups.copy(
                        list = list, missingNewer = r.value.missingNewer, listError = null,
                        listState = if (list.isEmpty()) ListState.EMPTY else ListState.LIST,
                        lastBackupAt = list.maxByOrNull { b -> b.createdAt }?.createdAt ?: it.backups.lastBackupAt,
                        lastBackupHouses = list.maxByOrNull { b -> b.createdAt }?.houses ?: it.backups.lastBackupHouses,
                    ))
                }
                is Outcome.Failed -> _ui.update { it.copy(backups = it.backups.copy(listState = ListState.ERROR, listError = r.reason)) }
            }
        }
    }

    private fun loadPhotos() {
        launchOp {
            val bytes = actions.pendingPhotoBytes()
            _ui.update {
                it.copy(sync = it.sync.copy(pendingBytes = bytes, photoStatus = actions.photoStatus(if ((bytes ?: 0L) > 0L) 1 else 0)))
            }
        }
    }

    // ---- Backups ---------------------------------------------------------------------------------------------------

    fun backUpNow() {
        if (_ui.value.backups.busy) return
        _ui.update { it.copy(backups = it.backups.copy(busy = true, error = null, noSource = false)) }
        launchOp {
            when (val r = actions.backUpNow()) {
                is Outcome.Ok -> {
                    _ui.update {
                        it.copy(backups = it.backups.copy(
                            busy = false, shrinkHoldId = r.value.shrinkHoldBackupId, missingNewer = r.value.missingNewer,
                            lastBackupAt = r.value.backup.createdAt, lastBackupHouses = r.value.backup.houses,
                        ))
                    }
                    loadBackups()
                }
                is Outcome.Failed -> _ui.update { it.copy(backups = it.backups.copy(busy = false, error = r.reason)) }
            }
        }
    }

    fun confirmShrink() {
        val id = _ui.value.backups.shrinkHoldId ?: return
        _ui.update { it.copy(backups = it.backups.copy(busy = true)) }
        launchOp {
            actions.confirmShrink(id)
            _ui.update { it.copy(backups = it.backups.copy(busy = false, shrinkHoldId = null)) }
            loadBackups()
        }
    }

    fun keepOlderBackups() = _ui.update { it.copy(backups = it.backups.copy(shrinkHoldId = null)) }

    fun setAutoBackup(enabled: Boolean) {
        actions.setAutoBackup(enabled)
        _ui.update { it.copy(backups = it.backups.copy(auto = actions.autoBackupEnabled())) }
    }

    // ---- Sync and photos -------------------------------------------------------------------------------------------

    fun syncNow(confirmShrink: Boolean = false) {
        if (_ui.value.sync.busy) return
        _ui.update { it.copy(sync = it.sync.copy(busy = true, confirmDismissed = false)) }
        launchOp {
            val info = actions.syncNow(confirmShrink)
            _ui.update { it.copy(sync = it.sync.copy(busy = false, info = info)) }
            loadPhotos()
        }
    }

    fun applySyncShrink() = syncNow(confirmShrink = true)

    fun notNowSyncShrink() = _ui.update { it.copy(sync = it.sync.copy(confirmDismissed = true)) }

    fun setWifiOnly(wifiOnly: Boolean) {
        try {
            actions.setPhotosWifiOnly(wifiOnly)
            _ui.update { it.copy(sync = it.sync.copy(wifiOnly = actions.photosWifiOnly(), photoError = false)) }
            loadPhotos()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _ui.update { it.copy(sync = it.sync.copy(photoError = true)) }
        }
    }

    /** *Upload photos now over mobile data*: a one-off, then a pass. The Wi-Fi only switch stays on. */
    fun uploadPhotosNow() {
        actions.uploadPhotosNowOverMobile()
        syncNow()
    }

    // ---- Devices ---------------------------------------------------------------------------------------------------

    fun askRevoke(device: ListedDevice) {
        if (!revokeOffered(device)) return
        _ui.update { it.copy(devices = it.devices.copy(revoking = device, error = null)) }
    }

    fun cancelRevoke() = _ui.update { it.copy(devices = it.devices.copy(revoking = null)) }

    fun confirmRevoke() {
        val device = _ui.value.devices.revoking ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, devices = it.devices.copy(revoking = null)) }
        launchOp {
            when (val r = actions.revokeListedDevice(device.kidHex, prompts().revoke)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(busy = false, devices = it.devices.copy(newKey = ShownKey(r.value.recoveryKey), newKeySaved = false, devices = actions.listedDevices()))
                }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, devices = it.devices.copy(error = r.reason)) }
            }
        }
    }

    fun setNewKeySaved(saved: Boolean) = _ui.update { it.copy(devices = it.devices.copy(newKeySaved = saved && it.devices.newKey != null)) }

    /** *Next* after a revoke: the new recovery key leaves the screen and the state. */
    fun dismissNewKey() {
        if (!_ui.value.devices.newKeySaved) return
        _ui.update { it.copy(devices = it.devices.copy(newKey = null, newKeySaved = false)) }
    }

    fun askDisconnectAll() = _ui.update { it.copy(devices = it.devices.copy(confirmingDisconnectAll = true, error = null)) }

    fun cancelDisconnectAll() = _ui.update { it.copy(devices = it.devices.copy(confirmingDisconnectAll = false)) }

    fun confirmDisconnectAll() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, devices = it.devices.copy(confirmingDisconnectAll = false)) }
        launchOp {
            when (val r = actions.disconnectAll(prompts().disconnectAll)) {
                is Outcome.Ok -> _ui.update { DriveUiState(connect = actions.state.value, notice = actions.enrolmentNotice.value) }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, devices = it.devices.copy(error = r.reason)) }
            }
        }
    }

    // ---- Enrolment -------------------------------------------------------------------------------------------------

    /** This phone is the new one: show its QR code and the 8-digit code. */
    fun showQr() {
        val c = codec ?: return
        _ui.update { it.copy(enrol = EnrolUi.Newcomer(c.newOffer(actions.devicePublicKey(), deviceName()))) }
    }

    /** The reply (scanned or pasted) of the connected phone: join with it. */
    fun submitReply(text: String) {
        val c = codec ?: return
        val e = _ui.value.enrol as? EnrolUi.Newcomer ?: return
        val reply = c.parseReply(text, e.offer)
        if (reply == null) {
            _ui.update { it.copy(enrol = e.copy(error = DriveReason.ENROL_BAD_MESSAGE)) }
            return
        }
        _ui.update { it.copy(busy = true) }
        launchOp {
            val result = actions.joinFromPsk(reply.enc, reply.ct, reply.epoch, e.offer.psk)
            if (result.error != null) {
                _ui.update { it.copy(busy = false, enrol = e.copy(error = result.error)) }
            } else {
                _ui.update { it.copy(busy = false, connect = result.state, enrol = EnrolUi.Idle, error = null) }
            }
        }
    }

    /** This phone is the connected one: wait for the new device's code. */
    fun startApprove() = _ui.update { it.copy(enrol = EnrolUi.ApproverInput()) }

    fun submitOffer(text: String) {
        val c = codec ?: return
        val offer = c.parseOffer(text)
        _ui.update { it.copy(enrol = if (offer == null) EnrolUi.ApproverInput(error = DriveReason.ENROL_BAD_MESSAGE) else EnrolUi.ApproverCheck(offer)) }
    }

    /** *The numbers match*: the device check, then the wrap for exactly the new device's key. */
    fun confirmCodesMatch() {
        val c = codec ?: return
        val e = _ui.value.enrol as? EnrolUi.ApproverCheck ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        launchOp {
            when (val r = actions.approveJoinedDevicePsk(e.offer.publicKey, e.offer.deviceName, e.offer.platform, e.offer.psk, prompts().approve)) {
                is Outcome.Ok -> _ui.update { it.copy(busy = false, enrol = EnrolUi.ApproverReply(c.encodeReply(r.value)), devices = it.devices.copy(devices = actions.listedDevices())) }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, enrol = e.copy(error = r.reason)) }
            }
        }
    }

    fun scannerMissing() = _ui.update {
        it.copy(enrol = when (val e = it.enrol) {
            is EnrolUi.Newcomer -> e.copy(cameraMissing = true)
            is EnrolUi.ApproverInput -> e.copy(cameraMissing = true)
            else -> e
        })
    }

    fun closeEnrol() = _ui.update { it.copy(enrol = EnrolUi.Idle) }

    // ---- Deleting --------------------------------------------------------------------------------------------------

    private fun actionOf(choice: DeleteChoice, oneBackupId: String?): DeletionAction? = when (choice) {
        DeleteChoice.OLDER_BACKUPS -> DeletionAction.OlderBackups
        DeleteChoice.ALL_BACKUPS -> DeletionAction.AllBackups
        DeleteChoice.EVERYTHING -> DeletionAction.Everything
        DeleteChoice.ONE_BACKUP -> oneBackupId?.let { DeletionAction.OneBackup(it) }
    }

    private var currentAction: DeletionAction? = null

    /** A menu choice: ask for the plan (counts and bytes, no names). */
    fun startDelete(choice: DeleteChoice, oneBackupId: String? = null) {
        val action = actionOf(choice, oneBackupId) ?: return
        if (_ui.value.busy) return
        currentAction = action
        _ui.update { it.copy(busy = true, delete = DeleteUi(choice = choice)) }
        launchOp {
            when (val r = actions.deletePlan(action)) {
                is Outcome.Ok -> _ui.update { it.copy(busy = false, delete = it.delete.copy(phase = DeletePhase.PLAN, plan = r.value)) }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, delete = it.delete.copy(phase = DeletePhase.ERROR, error = r.reason)) }
            }
        }
    }

    /** *Proceed*: the policy's answer (tick box, countdown) and the confirm step. */
    fun proceedToConfirm() {
        val action = currentAction ?: return
        if (_ui.value.delete.phase != DeletePhase.PLAN || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        launchOp {
            when (val r = actions.deleteConfirmInfo(action)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(busy = false, delete = it.delete.copy(phase = DeletePhase.CONFIRM, info = r.value, ticked = false, tickHint = false, confirmShownAtMs = clock()))
                }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, delete = it.delete.copy(phase = DeletePhase.ERROR, error = r.reason)) }
            }
        }
    }

    fun toggleTick() {
        val d = _ui.value.delete
        if (d.phase != DeletePhase.CONFIRM || d.info?.tickBoxRequired != true || _ui.value.busy) return
        _ui.update { it.copy(delete = d.copy(ticked = !d.ticked, tickHint = false)) }
    }

    /** The final button. Without the tick, or before the countdown ends, nothing is asked and nothing is deleted. */
    fun confirmDelete() {
        val d = _ui.value.delete
        val info = d.info ?: return
        val plan = d.plan ?: return
        if (d.phase != DeletePhase.CONFIRM) return
        if (!deleteConfirmEnabled(info, d.ticked, clock() - d.confirmShownAtMs, _ui.value.busy)) {
            if (info.tickBoxRequired && !d.ticked) _ui.update { it.copy(delete = d.copy(tickHint = true)) }
            return
        }
        _ui.update { it.copy(busy = true, delete = d.copy(phase = DeletePhase.RUNNING)) }
        launchOp { applyRun(actions.runDelete(plan, prompts().delete), previous = d.copy(phase = DeletePhase.CONFIRM)) }
    }

    /** *Try again* after a run that stopped with files left: a fresh device check, then the rest. */
    fun tryAgain() {
        val d = _ui.value.delete
        if (!deleteTryAgainOffered(d.phase) || _ui.value.busy) return
        _ui.update { it.copy(busy = true, delete = d.copy(phase = DeletePhase.RUNNING)) }
        launchOp { applyRun(actions.resumeDelete(prompts().delete), previous = d.copy(phase = DeletePhase.PARTIAL)) }
    }

    private fun applyRun(r: Outcome<app.doorprints.drive.connect.DeleteRun>, previous: DeleteUi) {
        when (r) {
            is Outcome.Ok -> _ui.update {
                when (val line = deleteResultLine(r.value.finished, r.value.left, r.value.total)) {
                    DeleteResultLine.Done -> it.copy(busy = false, delete = previous.copy(phase = DeletePhase.DONE, left = 0, total = r.value.total))
                    is DeleteResultLine.Left -> it.copy(busy = false, delete = previous.copy(phase = DeletePhase.PARTIAL, left = line.left, total = line.total))
                }
            }
            // A refused device check leaves the person where they were (the confirm step, or the partial step) with the reason.
            is Outcome.Failed -> _ui.update { it.copy(busy = false, delete = previous.copy(error = r.reason, tickHint = false)) }
        }
    }

    fun cancelDelete() {
        currentAction = null
        _ui.update { it.copy(delete = DeleteUi()) }
    }

    /** The countdown shown for the confirm step at [nowMs]. */
    fun countdownLeft(nowMs: Long): Int {
        val d = _ui.value.delete
        val info = d.info ?: return 0
        return countdownSecondsLeft(info.delayMs, nowMs - d.confirmShownAtMs)
    }
}
