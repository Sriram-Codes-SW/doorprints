package app.doorprints.drive.connect

import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.RecoveryKeyException
import app.doorprints.deviceauth.AuthPlatform
import app.doorprints.deviceauth.ConnectDecision
import app.doorprints.deviceauth.DeletionContext
import app.doorprints.deviceauth.DeletionDecision
import app.doorprints.deviceauth.DeletionPolicy
import app.doorprints.deviceauth.DriveGate
import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.NewFile
import app.doorprints.drive.UploadTarget
import app.doorprints.drive.backup.BackupListing
import app.doorprints.drive.backup.BackupOutcome
import app.doorprints.drive.backup.BackupSource
import app.doorprints.drive.backup.CreateOutcome
import app.doorprints.drive.backup.DriveBackup
import app.doorprints.drive.backup.DriveBackupService
import app.doorprints.drive.backup.DriveConnection
import app.doorprints.drive.backup.DriveProblem
import app.doorprints.drive.backup.DriveStateStore
import app.doorprints.drive.backup.ImportDownload
import app.doorprints.drive.backup.ReadyFolder
import app.doorprints.drive.backup.StagingSink
import app.doorprints.drive.delete.AuthorizationToken
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.delete.DeletionOutcome
import app.doorprints.drive.delete.DeletionPlan
import app.doorprints.drive.delete.DeletionStore
import app.doorprints.drive.delete.DriveDeletionService
import app.doorprints.drive.delete.PlanResult
import app.doorprints.drive.delete.ReconnectPath
import app.doorprints.drive.delete.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Non-secret settings of the Drive screens, on this device only. */
interface DriveConnectPrefs {
    var autoBackup: Boolean
    var photosWifiOnly: Boolean
    var recoveryStatus: RecoveryStatus
}

class MemoryDriveConnectPrefs(
    override var autoBackup: Boolean = false,
    override var photosWifiOnly: Boolean = true,
    override var recoveryStatus: RecoveryStatus = RecoveryStatus.UNKNOWN,
) : DriveConnectPrefs

/** Where a verified Drive backup goes to the existing *Import a backup* preview (Android: a cache file, then the Import screen). */
interface DriveImportHandoff {
    fun staging(): StagingSink

    /** The staged ZIP is complete and proven; open the import preview with it. */
    suspend fun open(download: ImportDownload.Verified)
}

/** Everything the controller needs, as seams, so a test builds it from fakes and the apps from their own services. */
class DriveDeps(
    val signIn: GoogleSignIn,
    val gate: DriveGate,
    val authorizer: DriveGateAuthorizer,
    val drive: DriveClient,
    val service: DriveBackupService,
    val deletion: DriveDeletionService,
    val deletionStore: DeletionStore,
    val stateStore: DriveStateStore,
    val backupSource: BackupSource,
    val importHandoff: DriveImportHandoff,
    val prefs: DriveConnectPrefs,
    val platform: AuthPlatform,
    /** Phones: a screen lock is set. Ignored on the website. */
    val deviceLock: () -> Boolean,
    /** Website: a passkey whose PRF seals the key is enrolled. */
    val webPrf: () -> Boolean,
    val isOnline: () -> Boolean,
    val clock: () -> Long,
    /** The title of the phone's own check, in the app's language, for the action being confirmed. */
    val authReason: (DriveDeleteChoice) -> String,
    /** Random bytes for choosing which recovery-key groups to ask back. */
    val randomBytes: (Int) -> ByteArray,
)

/**
 * The state holder of Settings > Google Drive (S4b-BL-117; docs/15 §5.7, §9.4, §3.2, §10). Common code: the screens in
 * `:ui` only draw [state] and call the functions below; the website has the same machine in TypeScript.
 *
 * **What it never does** (the crypto review's rules, docs/15 §9.9): no problem from `keys.json` or Drive triggers a
 * revoke, a re-key, a wipe or a re-create; the folder is created only by the person's explicit first-connect or *Start
 * again* (with its tick) and only through [DriveBackupService.createFolder]; the recovery key is shown once and kept
 * in memory only until the person confirms (or leaves) and is never stored or logged; a deletion goes only through
 * [DriveDeletionService] with a token from [DriveGateAuthorizer]; every Drive answer shown comes from the verified
 * service results, never from raw listings.
 */
class DriveSettingsController(
    private val deps: DriveDeps,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DriveEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DriveEvent> = _events.asSharedFlow()

    private val ops = Mutex()
    private var folder: ReadyFolder? = null
    private var listing: BackupListing? = null
    private var pendingPlan: DeletionPlan? = null
    private var pendingChoice: DriveDeleteChoice? = null

    private fun initial() = DriveUiState(
        stage = if (deps.signIn.configured) DriveStage.DISCONNECTED else DriveStage.HIDDEN,
        autoBackup = deps.prefs.autoBackup,
        photosWifiOnly = deps.prefs.photosWifiOnly,
        recoveryStatus = deps.prefs.recoveryStatus,
    )

    /** The screen is shown: a device that was connected reads the folder again. */
    fun start() {
        if (_state.value.stage == DriveStage.HIDDEN) return
        if (_state.value.busy != null || _state.value.stage != DriveStage.DISCONNECTED) return
        if (deps.signIn.isConnected()) launchOp(DriveBusy.READING) { readFolder() }
    }

    // ---- Connect ---------------------------------------------------------------------------------------------------

    fun connect() {
        val s = _state.value
        if (s.stage == DriveStage.HIDDEN || s.busy != null) return
        when (deps.gate.canConnect()) {
            ConnectDecision.NeedsScreenLock -> {
                set { it.copy(stage = DriveStage.NEEDS_SCREEN_LOCK, message = null) }
                return
            }
            ConnectDecision.Allowed -> Unit
        }
        launchOp(DriveBusy.CONNECTING) {
            set { it.copy(stage = DriveStage.WORKING, message = null) }
            when (val r = deps.signIn.connect()) {
                SignInResult.Connected -> readFolder()
                SignInResult.Cancelled -> set { it.copy(stage = DriveStage.DISCONNECTED) }
                is SignInResult.Failed -> set { it.copy(stage = DriveStage.DISCONNECTED, message = messageOf(r.reason)) }
            }
        }
    }

    fun openLockSettings() {
        _events.tryEmit(DriveEvent.OpenLockSettings)
    }

    /** Back from the phone's settings (or any retry): the lock is read again. */
    fun lockMayHaveChanged() {
        if (_state.value.stage == DriveStage.NEEDS_SCREEN_LOCK && deps.gate.canConnect() == ConnectDecision.Allowed) {
            set { it.copy(stage = DriveStage.DISCONNECTED) }
        } else if (_state.value.stage == DriveStage.PAUSED && _state.value.busy == null) {
            launchOp(DriveBusy.READING) { readFolder() }
        }
    }

    /** Reads where the folder stands and moves to the stage for it. Runs inside an operation. */
    private suspend fun readFolder() {
        if (!pausedOrGo()) return
        set { it.copy(stage = DriveStage.WORKING, busy = DriveBusy.READING) }
        apply(deps.service.connect())
    }

    /** False (and the stage set) when the lock is gone or unreadable: nothing is uploaded, read or deleted. */
    private fun pausedOrGo(): Boolean {
        if (deps.platform != AuthPlatform.PHONE) return true
        return when (deps.gate.beforeRun()) {
            RunDecision.Run -> true
            RunDecision.PausedNoLock -> {
                folder = null
                set { it.copy(stage = DriveStage.PAUSED, message = DriveMessage.PAUSED_NO_LOCK, ready = null, dialog = null) }
                false
            }
            RunDecision.PausedUnknown -> {
                set { it.copy(stage = DriveStage.PAUSED, message = DriveMessage.PAUSED_UNKNOWN) }
                false
            }
        }
    }

    private suspend fun apply(c: DriveConnection) {
        when (c) {
            DriveConnection.NoFolder -> {
                folder = null
                set { it.copy(stage = DriveStage.FIRST_CONNECT, ready = null) }
            }
            DriveConnection.FolderGone -> {
                folder = null
                set { it.copy(stage = DriveStage.FOLDER_GONE, ready = null) }
            }
            is DriveConnection.NeedsEnrolment -> {
                folder = null
                set { it.copy(stage = DriveStage.NEEDS_ENROLMENT, recoveryAvailable = c.recoveryAvailable, ready = null) }
            }
            is DriveConnection.NeedsRecoveryKey -> {
                folder = null
                set { it.copy(stage = DriveStage.NEEDS_RECOVERY_KEY, recoveryAvailable = c.recoveryAvailable, ready = null) }
            }
            is DriveConnection.Ready -> becomeReady(c.folder)
            is DriveConnection.Error -> problem(c.problem)
        }
    }

    private fun problem(p: DriveProblem) {
        if (p.kind == DriveProblem.Kind.UNAUTHORIZED) {
            // "Google Drive disconnected. Your houses are safe on this device. Connect again?" Nothing local is deleted.
            folder = null
            set { it.copy(stage = DriveStage.DISCONNECTED, message = DriveMessage.DISCONNECTED_BY_GOOGLE, ready = null, dialog = null) }
        } else {
            val keep = _state.value.stage == DriveStage.READY
            set { it.copy(stage = if (keep) DriveStage.READY else DriveStage.PROBLEM, message = messageOf(p), dialog = null) }
        }
    }

    // ---- First connect and the recovery key ------------------------------------------------------------------------

    /**
     * *Make my backup folder*: the folder, the key set and the recovery key (docs/15 §9.4). [skipRecoveryKey] is true only
     * after the plain warning and its tick ([skipAcknowledged] must be true too, else nothing happens).
     */
    fun createFirstFolder(skipRecoveryKey: Boolean = false, skipAcknowledged: Boolean = false) {
        if (_state.value.stage != DriveStage.FIRST_CONNECT || _state.value.busy != null) return
        if (skipRecoveryKey && !skipAcknowledged) return
        launchOp(DriveBusy.CONNECTING) { create(withKey = !skipRecoveryKey) }
    }

    /** *Start again* after the folder was found gone (docs/15 §3.4); needs the person's tick. */
    fun startAgain(acknowledged: Boolean) {
        if (_state.value.stage != DriveStage.FOLDER_GONE || _state.value.busy != null || !acknowledged) return
        launchOp(DriveBusy.CONNECTING) { create(withKey = true) }
    }

    private suspend fun create(withKey: Boolean) {
        if (!pausedOrGo()) return
        val out: CreateOutcome = deps.service.createFolder(withRecoveryKey = withKey)
        when (val c = out.connection) {
            is DriveConnection.Ready -> {
                val key = out.recoveryKey
                if (key == null) {
                    deps.prefs.recoveryStatus = RecoveryStatus.SKIPPED
                    becomeReady(c.folder)
                    writeReadme(c.folder)
                } else {
                    // Held in memory for this step only. If the app dies now the key is gone: UNCONFIRMED says so.
                    deps.prefs.recoveryStatus = RecoveryStatus.UNCONFIRMED
                    folder = c.folder
                    val groups = key.display.split('-').size
                    set {
                        it.copy(
                            stage = DriveStage.RECOVERY_KEY,
                            recoveryKey = RecoveryKeyStep(key.display, pickGroups(groups)),
                            recoveryStatus = RecoveryStatus.UNCONFIRMED,
                            message = null,
                            busy = null,
                        )
                    }
                    writeReadme(c.folder)
                }
            }
            else -> apply(c)
        }
    }

    /** Two different group positions, chosen at random, ascending. */
    private fun pickGroups(total: Int): List<Int> {
        val bytes = deps.randomBytes(2)
        val a = (bytes[0].toInt() and 0xFF) % total
        var b = (bytes[1].toInt() and 0xFF) % (total - 1)
        if (b >= a) b++
        return listOf(a + 1, b + 1).sorted()
    }

    /** *I have saved it*: the two asked groups typed back. Right: the key is dropped from memory and the folder opens. */
    fun confirmRecoveryKey(answers: List<String>) {
        val s = _state.value
        val step = s.recoveryKey ?: return
        if (s.stage != DriveStage.RECOVERY_KEY || s.busy != null) return
        val groups = step.display.split('-')
        val ok = step.askGroups.size == answers.size && step.askGroups.indices.all { i ->
            normalise(answers[i]) == groups[step.askGroups[i] - 1]
        }
        if (!ok) {
            set { it.copy(recoveryKey = step.copy(wrongTries = step.wrongTries + 1), message = DriveMessage.RECOVERY_GROUPS_WRONG) }
            return
        }
        deps.prefs.recoveryStatus = RecoveryStatus.SAVED
        val f = folder
        set { it.copy(recoveryKey = null, recoveryStatus = RecoveryStatus.SAVED, message = null) }
        if (f != null) launchOp(DriveBusy.READING) { becomeReady(f) }
    }

    private fun normalise(text: String) = text.trim().uppercase().filter { it.isLetterOrDigit() }

    /** NEEDS_ENROLMENT / NEEDS_RECOVERY_KEY: the typed recovery key. A wrong one changes nothing and says so. */
    fun enterRecoveryKey(text: String) {
        val s = _state.value
        if (s.busy != null || (s.stage != DriveStage.NEEDS_RECOVERY_KEY && s.stage != DriveStage.NEEDS_ENROLMENT)) return
        val key = try {
            RecoveryKey.parse(text)
        } catch (_: RecoveryKeyException) {
            set { it.copy(message = DriveMessage.WRONG_RECOVERY_KEY) }
            return
        }
        launchOp(DriveBusy.CONNECTING) {
            if (!pausedOrGo()) return@launchOp
            when (val c = deps.service.openWithRecoveryKey(key)) {
                is DriveConnection.Error -> {
                    // A wrong recovery key stays on this screen so it can be typed again; nothing else is touched.
                    val msg = messageOf(c.problem)
                    if (c.problem.kind == DriveProblem.Kind.WRONG_RECOVERY_KEY || c.problem.kind == DriveProblem.Kind.NO_RECOVERY_KEY) {
                        set { it.copy(message = msg) }
                    } else {
                        problem(c.problem)
                    }
                }
                is DriveConnection.Ready -> {
                    deps.prefs.recoveryStatus = RecoveryStatus.SAVED
                    set { it.copy(recoveryStatus = RecoveryStatus.SAVED) }
                    becomeReady(c.folder)
                }
                else -> apply(c)
            }
        }
    }

    private suspend fun writeReadme(f: ReadyFolder) {
        // Best effort: a missing Read me.txt costs nothing, and the app never reads it back.
        try {
            deps.drive.upload(
                UploadTarget.New(NewFile(DriveReadme.NAME, DriveReadme.MIME, listOf(f.rootId), mapOf(DriveLayout.KIND to DriveReadme.KIND))),
                DriveReadme.bytes(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---- Ready -----------------------------------------------------------------------------------------------------

    private suspend fun becomeReady(f: ReadyFolder) {
        folder = f
        val account = try {
            deps.drive.about()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        set {
            it.copy(
                stage = DriveStage.READY,
                account = account?.email ?: it.account,
                recoveryKey = null,
                dialog = null,
                message = null,
                busy = DriveBusy.LISTING,
                ready = (it.ready ?: ReadyView()).copy(storageUsedBytes = account?.quotaUsage, storageLimitBytes = account?.quotaLimit),
                autoBackup = deps.prefs.autoBackup,
                photosWifiOnly = deps.prefs.photosWifiOnly,
                recoveryStatus = deps.prefs.recoveryStatus,
            )
        }
        loadList(f)
    }

    private suspend fun loadList(f: ReadyFolder) {
        try {
            val st = deps.stateStore.load()
            val l = deps.service.listBackups(f)
            listing = l
            val newestId = l.newest?.fileId
            val pending = deps.deletionStore.pending()
            set {
                it.copy(
                    busy = null,
                    ready = (it.ready ?: ReadyView()).copy(
                        backups = l.backups.map { b -> rowOf(b, b.fileId == newestId) },
                        lastBackupAt = st.lastSuccessAt ?: l.newest?.createdAt,
                        unfinished = l.unfinished.size,
                        ignored = l.ignored.size + l.junk.size,
                        missingNewer = l.missingNewer,
                        pendingDeletionLeft = pending?.items?.size,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            set { it.copy(busy = null) }
            problem(DriveProblem.of(e))
        }
    }

    /** *Try again* on a stage that failed ([DriveStage.PROBLEM], [DriveStage.PAUSED]): reads the folder again. */
    fun retry() {
        val s = _state.value
        if (s.busy != null || (s.stage != DriveStage.PROBLEM && s.stage != DriveStage.PAUSED)) return
        launchOp(DriveBusy.READING) { readFolder() }
    }

    fun refresh() {
        val f = folder ?: return
        if (_state.value.stage != DriveStage.READY || _state.value.busy != null) return
        launchOp(DriveBusy.LISTING) {
            if (!pausedOrGo()) return@launchOp
            set { it.copy(busy = DriveBusy.LISTING) }
            loadList(f)
        }
    }

    fun backUpNow() {
        val f = folder ?: return
        if (_state.value.stage != DriveStage.READY || _state.value.busy != null) return
        launchOp(DriveBusy.BACKING_UP) {
            if (!pausedOrGo()) return@launchOp
            set { it.copy(busy = DriveBusy.BACKING_UP, message = null) }
            when (val r = deps.service.backUp(f, deps.backupSource)) {
                is BackupOutcome.Done -> {
                    set {
                        it.copy(
                            message = if (r.tidy.hold != null) DriveMessage.SHRINK_HOLD else DriveMessage.BACKUP_DONE,
                            ready = it.ready?.copy(shrinkHoldBackupId = r.tidy.hold?.backupId),
                        )
                    }
                    loadList(f)
                }
                is BackupOutcome.Failed -> problem(r.problem)
            }
        }
    }

    /** The person agreed that the drop in houses is right: the older backups may be pruned again (docs/15 §1.4 item 4). */
    fun confirmShrink() {
        val id = _state.value.ready?.shrinkHoldBackupId ?: return
        if (_state.value.busy != null) return
        launchOp(DriveBusy.LISTING) {
            deps.service.confirmShrink(id)
            set { it.copy(busy = null, ready = it.ready?.copy(shrinkHoldBackupId = null), message = null) }
        }
    }

    /** *Import a backup* > *From Google Drive*: the verified, decrypted ZIP goes to the existing import preview. */
    fun importBackup(fileId: String) {
        val f = folder ?: return
        val backup: DriveBackup = listing?.backups?.firstOrNull { it.fileId == fileId } ?: return
        if (_state.value.stage != DriveStage.READY || _state.value.busy != null) return
        launchOp(DriveBusy.IMPORTING) {
            if (!pausedOrGo()) return@launchOp
            set { it.copy(busy = DriveBusy.IMPORTING, message = null) }
            val staging = deps.importHandoff.staging()
            when (val r = deps.service.imports.download(f, backup, staging)) {
                is ImportDownload.Verified -> {
                    deps.importHandoff.open(r)
                    set { it.copy(busy = null, message = DriveMessage.IMPORT_READY) }
                    _events.tryEmit(DriveEvent.OpenImportPreview)
                }
                is ImportDownload.Refused -> {
                    staging.discard()
                    problem(r.problem)
                }
            }
        }
    }

    // ---- Switches --------------------------------------------------------------------------------------------------

    fun setAutoBackup(on: Boolean) {
        if (_state.value.stage != DriveStage.READY) return
        deps.prefs.autoBackup = on
        set { it.copy(autoBackup = on) }
    }

    fun setPhotosWifiOnly(on: Boolean) {
        deps.prefs.photosWifiOnly = on
        set { it.copy(photosWifiOnly = on) }
    }

    /** *Disconnect this device* (L1, always allowed): forgets the grant here, revokes it at Google, keeps the houses. */
    fun disconnectThisDevice() {
        val s = _state.value
        if (s.stage == DriveStage.HIDDEN || s.busy != null) return
        launchOp(DriveBusy.DISCONNECTING) {
            deps.signIn.disconnect()
            deps.prefs.autoBackup = false
            folder = null
            listing = null
            pendingPlan = null
            set {
                it.copy(
                    stage = DriveStage.DISCONNECTED, message = DriveMessage.DISCONNECTED_DONE, ready = null, account = null,
                    dialog = null, recoveryKey = null, autoBackup = false, busy = null,
                )
            }
        }
    }

    fun dismissMessage() {
        set { it.copy(message = null) }
    }

    // ---- Deleting (docs/15 §3) -------------------------------------------------------------------------------------

    /** Opens the confirmation for [choice]: the policy first (lock, passkey, offline), then Drive's plan. Nothing is deleted. */
    fun openDelete(choice: DriveDeleteChoice) {
        val f = folder ?: return
        val s = _state.value
        if (s.stage != DriveStage.READY || s.busy != null || s.dialog != null) return
        val action = choice.toAction()
        val policyAction = DriveGateAuthorizer.policyActionOf(action)
        val ctx = context(backupsLeft = if (choice is DriveDeleteChoice.OneBackup) listing?.backups?.size else null)
        val decision = DeletionPolicy.decide(policyAction, ctx)
        if (decision is DeletionDecision.Refused) {
            set { it.copy(message = messageOf(decision.reason)) }
            return
        }
        launchOp(DriveBusy.LISTING) {
            if (!pausedOrGo()) return@launchOp
            set { it.copy(busy = DriveBusy.LISTING, message = null) }
            when (val r = deps.deletion.preflight(f.rootId, action)) {
                is PlanResult.Refused -> set { it.copy(busy = null, message = messageOf(r.reason)) }
                is PlanResult.Ready -> {
                    val plan = r.plan
                    pendingPlan = plan
                    pendingChoice = choice
                    val req = (DeletionPolicy.decide(policyAction, ctx.copy(backupsLeft = if (choice is DriveDeleteChoice.OneBackup) (if (plan.level == DeletionLevel.L1) 2 else 1) else null)) as? DeletionDecision.Allowed)?.requirements
                    set {
                        it.copy(
                            busy = null,
                            dialog = DeleteDialog(
                                choice = choice,
                                level = plan.level,
                                counts = plan.totals.mapValues { (_, v) -> v.count },
                                totalFiles = plan.fileCount,
                                totalBytes = plan.totalBytes,
                                needsTick = req?.tickBox ?: (plan.level != DeletionLevel.L1),
                                delaySeconds = req?.delaySeconds ?: if (plan.level == DeletionLevel.L3) DeletionPolicy.DELAY_SECONDS_L3 else 0,
                                openedAtMs = deps.clock(),
                                foreignKept = plan.foreignKept,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun setDeleteTick(ticked: Boolean) {
        set { s -> s.dialog?.takeIf { it.phase == DeletePhase.CONFIRM }?.let { d -> s.copy(dialog = d.copy(ticked = ticked)) } ?: s }
    }

    fun cancelDelete() {
        val d = _state.value.dialog ?: return
        if (d.phase != DeletePhase.CONFIRM) return
        pendingPlan = null
        pendingChoice = null
        set { it.copy(dialog = null, message = null) }
    }

    /** *Save a copy first* in the dialog: opens *Save a copy*; the dialog stays. */
    fun saveCopyFirst() {
        if (_state.value.dialog?.phase == DeletePhase.CONFIRM) _events.tryEmit(DriveEvent.OpenSaveCopy)
    }

    /** *Delete for good*: only when the box is ticked (if asked) and the delay has passed; then the phone's own check. */
    fun confirmDelete() {
        val d = _state.value.dialog ?: return
        val plan = pendingPlan ?: return
        val choice = pendingChoice ?: return
        if (_state.value.busy != null || !d.confirmEnabled(deps.clock())) return
        set { it.copy(dialog = d.copy(phase = DeletePhase.AUTHORIZING)) }
        launchOp(DriveBusy.DELETING) {
            val token = when (val o = deps.authorizer.authorize(plan.action, plan.level, plan.operationId, context(null), deps.authReason(choice))) {
                DriveGateAuthorizer.Outcome.NoneNeeded -> null
                is DriveGateAuthorizer.Outcome.Token -> o.token
                is DriveGateAuthorizer.Outcome.Refused -> return@launchOp stopAsking(messageOf(o.reason))
                is DriveGateAuthorizer.Outcome.Denied -> return@launchOp stopAsking(DriveMessage.DELETE_DENIED)
                is DriveGateAuthorizer.Outcome.Paused -> {
                    set { it.copy(dialog = null) }
                    pausedOrGo()
                    return@launchOp
                }
            }
            set { s -> s.copy(dialog = s.dialog?.copy(phase = DeletePhase.DELETING)) }
            run(deps.deletion.delete(plan, token), token)
        }
    }

    private fun stopAsking(message: DriveMessage) {
        pendingPlan = null
        pendingChoice = null
        set { it.copy(dialog = null, message = message, busy = null) }
    }

    /** *Try again* on a deletion that stopped half way: a fresh authorization, then the rest. */
    fun resumeDeletion() {
        val f = folder ?: return
        val s = _state.value
        if (s.stage != DriveStage.READY || s.busy != null || s.ready?.pendingDeletionLeft == null) return
        launchOp(DriveBusy.DELETING) {
            if (!pausedOrGo()) return@launchOp
            val pending = deps.deletionStore.pending() ?: return@launchOp set { it.copy(busy = null, ready = it.ready?.copy(pendingDeletionLeft = null)) }
            val choice = when (pending.action) {
                is app.doorprints.drive.delete.DeletionAction.OneBackup -> DriveDeleteChoice.OneBackup((pending.action as app.doorprints.drive.delete.DeletionAction.OneBackup).fileId)
                app.doorprints.drive.delete.DeletionAction.OlderBackups -> DriveDeleteChoice.OlderBackups
                app.doorprints.drive.delete.DeletionAction.AllBackups -> DriveDeleteChoice.AllBackups
                app.doorprints.drive.delete.DeletionAction.Everything -> DriveDeleteChoice.Everything
            }
            val token = when (val o = deps.authorizer.authorize(pending.action, pending.level, pending.operationId, context(null), deps.authReason(choice))) {
                DriveGateAuthorizer.Outcome.NoneNeeded -> null
                is DriveGateAuthorizer.Outcome.Token -> o.token
                is DriveGateAuthorizer.Outcome.Refused -> return@launchOp set { it.copy(busy = null, message = messageOf(o.reason)) }
                is DriveGateAuthorizer.Outcome.Denied -> return@launchOp set { it.copy(busy = null, message = DriveMessage.DELETE_DENIED) }
                is DriveGateAuthorizer.Outcome.Paused -> return@launchOp set { it.copy(busy = null) }
            }
            run(deps.deletion.resume(token), token, f)
        }
    }

    private suspend fun run(outcome: DeletionOutcome, token: AuthorizationToken?, f: ReadyFolder? = folder) {
        pendingPlan = null
        pendingChoice = null
        when (outcome) {
            is DeletionOutcome.Refused -> {
                token?.let { deps.authorizer.forget(it) }
                set { it.copy(dialog = null, busy = null, message = messageOf(outcome.reason)) }
            }
            is DeletionOutcome.Ran -> {
                val marker = outcome.marker
                if (marker != null) {
                    // Automatic backup stays off after deleting backups, and after everything (docs/15 §3.4).
                    deps.prefs.autoBackup = false
                    set { it.copy(autoBackup = false) }
                }
                if (outcome.finished && marker?.reconnectPath == ReconnectPath.FULL_FIRST_CONNECT) {
                    folder = null
                    listing = null
                    deps.prefs.recoveryStatus = RecoveryStatus.UNKNOWN
                    set {
                        it.copy(
                            stage = DriveStage.FIRST_CONNECT, dialog = null, ready = null, busy = null,
                            message = DriveMessage.DELETE_DONE_EVERYTHING, recoveryStatus = RecoveryStatus.UNKNOWN,
                        )
                    }
                    return
                }
                val msg = when {
                    outcome.stopped == StopReason.AUTHORIZATION_LOST -> DriveMessage.DELETE_LOCK_LOST
                    !outcome.finished -> DriveMessage.DELETE_STOPPED
                    marker?.action == "allBackups" -> DriveMessage.DELETE_DONE_ALL
                    outcome.keptIds.isNotEmpty() -> DriveMessage.DELETE_FOLDER_KEPT
                    else -> DriveMessage.DELETE_DONE_BACKUP
                }
                set { it.copy(dialog = null, message = msg) }
                if (f != null) loadList(f)
            }
        }
    }

    private fun context(backupsLeft: Int?) = DeletionContext(
        platform = deps.platform,
        deviceLock = deps.deviceLock(),
        webPrf = deps.webPrf(),
        online = deps.isOnline(),
        backupsLeft = backupsLeft,
    )

    // ---- Plumbing --------------------------------------------------------------------------------------------------

    private fun set(f: (DriveUiState) -> DriveUiState) = _state.update(f)

    /** One operation at a time. Any exception ends as a plain problem, never as a crash or a half-set stage. */
    private fun launchOp(busy: DriveBusy, block: suspend () -> Unit) {
        if (!ops.tryLock()) return
        set { it.copy(busy = busy) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem(DriveProblem.of(e))
            } finally {
                set { it.copy(busy = null) }
                ops.unlock()
            }
        }
    }

    private fun messageOf(f: SignInFailure): DriveMessage = when (f) {
        SignInFailure.NOT_CONFIGURED, SignInFailure.NOT_AVAILABLE -> DriveMessage.SIGNIN_NOT_AVAILABLE
        SignInFailure.OFFLINE -> DriveMessage.SIGNIN_OFFLINE
        SignInFailure.DENIED, SignInFailure.BAD_ANSWER -> DriveMessage.SIGNIN_FAILED
        SignInFailure.STATE_MISMATCH -> DriveMessage.SIGNIN_STATE
        SignInFailure.WRONG_SCOPE -> DriveMessage.SIGNIN_WRONG_SCOPE
        SignInFailure.STORE_UNAVAILABLE -> DriveMessage.SIGNIN_STORE
    }
}
