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

package app.doorprints.drive.backup

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.DevicePlatform
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.Hpke
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.KeysFile
import app.doorprints.crypto.KeysGuard
import app.doorprints.crypto.KeysWatermark
import app.doorprints.crypto.KeysWatermarkStore
import app.doorprints.crypto.QR_PSK_ID
import app.doorprints.crypto.QR_PSK_LEN
import app.doorprints.crypto.WrapAad
import app.doorprints.crypto.openPsk
import app.doorprints.crypto.sealPsk
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.RecoveryKey
import app.doorprints.crypto.kidOf
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveCode
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveQuery
import app.doorprints.drive.FOLDER_MIME
import app.doorprints.drive.MetadataChange
import app.doorprints.drive.NewFile
import app.doorprints.drive.UploadTarget
import app.doorprints.drive.downloadVerified
import app.doorprints.drive.ensureFolder
import app.doorprints.drive.listAll
import app.doorprints.drive.markComplete
import app.doorprints.drive.uploadResumable
import app.doorprints.shared.export.BackupFormat
import kotlinx.coroutines.CancellationException

/**
 * Backups in Google Drive (S4b-BL-116; docs/15 §1.4, §5.1, §7 phase 3, §9.3), in common code over a signed-in
 * [DriveClient] (its `TokenProvider` holds the grant) and the encryption core. Web twin: `drive-backup.service.ts`.
 *
 * **The trust rule** (docs/15 §9.9): this device opens nothing from Drive before it is pinned to the folder's key set,
 * and the first pin comes only from [createFolder] (this device made the folder: `KeysGuard.pinCreated`),
 * [openWithRecoveryKey] (the recovery anchor) or [openWithFolderKey] (the key received over S4b-BL-126's QR
 * enrolment). [connect] never writes, and never adopts a folder it is not pinned to.
 *
 * **A backup** is the app's existing *Full backup* ZIP ([BackupSource]), encrypted as `dpx/1` (inner the ZIP's
 * format), uploaded with `state=partial` and its authenticated metadata ([BackupMeta]) under a `partial-` name, then
 * marked `state=complete` and renamed only after Drive's `sha256Checksum` equals the one computed here
 * (`markComplete`). Then retention (7 daily, 4 weekly, 6 monthly, the shrink guard: [BackupRetention]) prunes to Drive's
 * bin. Every Drive call keeps its own retries ([DriveClient.retry]); a run that still fails returns
 * [BackupOutcome.Failed] with a [DriveProblem] and leaves the schedule's failure in [DriveDeviceState].
 *
 * One run at a time per device (the platform's unique work or tab lock).
 */
class DriveBackupService(
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val device: DeviceIdentity,
    private val state: DriveStateStore,
    private val trust: FolderTrustStores,
    private val clock: () -> Long,
    /** The device's offset from UTC now, in minutes (file names and retention's days). */
    private val utcOffsetMinutes: () -> Int,
    private val scratch: ScratchSpace = MemoryScratchSpace,
) {
    private val keysFile = KeysFile(p)
    private val controlFile = ControlFile(p)
    private val lister = BackupLister(drive, p)
    private val myKid: ByteArray by lazy { kidOf(p, device.key.publicKey) }

    /** The import side, over the same Drive, crypto and scratch space. */
    val imports: DriveImportService by lazy { DriveImportService(drive, p, scratch) }

    // ---- Where the folder stands ----------------------------------------------------------------------------------

    /** Reads where the folder stands for this device. Writes nothing in Drive (a missing control file excepted, below). */
    suspend fun connect(): DriveConnection = connection("connect") {
        val st = state.load()
        val root = drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = st.rootId)
            ?: return@connection if (st.rootId != null && st.creatingRootId == null) DriveConnection.FolderGone else DriveConnection.NoFolder
        if (root.id != st.rootId) state.save(st.copy(rootId = root.id, keysId = null, controlId = null, backupsId = null))
        val keys = findKind(root.id, KIND_KEYS, st.keysId.takeIf { root.id == st.rootId })
            ?: return@connection if (st.creatingRootId == root.id) DriveConnection.NoFolder else DriveConnection.Error(DriveProblem(DriveProblem.Kind.FOLDER_WITHOUT_KEYS))
        val bytes = drive.downloadVerified(keys.id)
        val guard = KeysGuard(p, trust.keys(root.id))
        if (guard.watermark() == null) {
            // Never adopted silently (docs/15 §9.3): this device's own unfinished create starts again, anything else is joined.
            return@connection if (st.creatingRootId == root.id) DriveConnection.NoFolder else DriveConnection.NeedsEnrolment(recoveryHint(bytes))
        }
        val opened = try {
            keysFile.open(bytes, device.key, guard)
        } catch (e: KeysException) {
            when (e.kind) {
                KeysException.Kind.NOT_ENROLLED, KeysException.Kind.REVOKED, KeysException.Kind.UNWRAP_FAILED ->
                    return@connection DriveConnection.NeedsRecoveryKey(recoveryHint(bytes), e.kind)
                else -> throw e
            }
        }
        ready(root.id, keys.id, opened)
    }

    /**
     * The first connect to a Drive with no Doorprints folder ([DriveConnection.NoFolder]), or *Start again* after
     * [DriveConnection.FolderGone]: makes `Doorprints/`, the key set (`KeysFile.createFirstDevice`), `doorprints.json`
     * and `Backups/`, then pins this device to it. [withRecoveryKey] false is the person's *Skip* after the warning
     * (docs/15 §9.4). The recovery key comes back in [CreateOutcome.recoveryKey] to be shown **once**; it is never stored.
     * The pin is the last step, so a run that stops half way leaves nothing this device trusts, and the next run starts
     * again (what it left in the folder goes to the bin).
     */
    suspend fun createFolder(withRecoveryKey: Boolean): CreateOutcome {
        var recovery: RecoveryKey? = null
        val connection = connection("create") {
            val st = state.load()
            val existing = drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = st.rootId)
            if (existing != null && st.creatingRootId != existing.id) {
                return@connection DriveConnection.Error(DriveProblem(DriveProblem.Kind.FOLDER_EXISTS))
            }
            val root = existing ?: drive.createFile(NewFile(DriveLayout.ROOT.name, FOLDER_MIME, emptyList(), DriveLayout.ROOT.appProperties))
            state.save(st.copy(rootId = root.id, creatingRootId = root.id, keysId = null, controlId = null, backupsId = null))
            val guard = KeysGuard(p, trust.keys(root.id))
            if (guard.watermark() != null) return@connection DriveConnection.Error(DriveProblem(DriveProblem.Kind.FOLDER_EXISTS))
            for (f in drive.listAll(DriveQuery(parentId = root.id))) {
                val kind = f.appProperties[DriveLayout.KIND]
                if (kind == KIND_KEYS || kind == KIND_CONTROL) trashQuietly(f.id)
            }
            val now = clock()
            val key = if (withRecoveryKey) RecoveryKey.generate(p) else null
            val written = keysFile.createFirstDevice(KeysFile.NewDevice(device.key.publicKey, device.name, device.platform), key, now)
            val keysId = uploadSmall(root.id, KEYS_NAME, KIND_KEYS, written.bytes)
            val control = controlFile.create(written.opened, now)
            val controlId = uploadSmall(root.id, CONTROL_NAME, KIND_CONTROL, control.bytes)
            val backups = drive.ensureFolder(DriveLayout.BACKUPS, root.id, create = true)!!
            controlFile.accept(control.body, trust.control(root.id))
            guard.pinCreated(written)
            state.save(
                state.load().copy(
                    rootId = root.id, keysId = keysId, controlId = controlId, backupsId = backups.id, creatingRootId = null,
                    deviceId = st.deviceId ?: newDeviceId(), newestSeenAt = null, lastBackupId = null,
                ),
            )
            recovery = key
            DriveConnection.Ready(ReadyFolder(root.id, keysId, controlId, backups.id, written.opened, control.body))
        }
        return CreateOutcome(connection, recovery.takeIf { connection is DriveConnection.Ready })
    }

    /**
     * Opens the folder with the typed recovery key (docs/15 §9.4, §9.5 iii): the recovery anchor makes the first pin
     * (or checks the one there is), then this device lists itself in `keys.json` (approved by the recovery key, so its
     * backups are accepted by the others; docs/15 §9.9 `RevokedEpochRule`) unless it is listed already.
     */
    suspend fun openWithRecoveryKey(recoveryKey: RecoveryKey): DriveConnection = connection("join-locate") {
        // The step names move on as the join does, so a DriveCode says where an unexpected failure was (S4b-BL-146).
        val (rootId, keys, bytes) = locateKeys() ?: return@connection DriveConnection.NoFolder
        val guard = KeysGuard(p, trust.keys(rootId))
        // This device's key is made on first use, and once the folder is pinned a missing key counts as lost (never remade,
        // `KeystoreDeviceIdentity`): so make it now, before the recovery anchor pins the folder (S4b-BL-146).
        step = "join-key"
        device.key.publicKey
        step = "join-recover"
        var opened = keysFile.openWithRecovery(bytes, recoveryKey, guard)
        if (opened.body.device(myKid) == null) {
            step = "join-add"
            val approver = opened.body.recovery!!.kid
            val written = keysFile.addDevice(opened, approver, KeysFile.NewDevice(device.key.publicKey, device.name, device.platform), clock())
            step = "join-write"
            writeKeys(keys.id, written.bytes)
            guard.acceptWritten(written)
            opened = written.opened
        }
        step = "join-ready"
        ready(rootId, keys.id, opened)
    }

    // ---- Enrolment, pairing and revocation (S4b-BL-126; docs/15 §9.5; web twin: `drive-backup.service.ts`) -----------

    /** This device's public key, the one a pairing request or a QR code must carry (not a key made up on the screen). */
    fun devicePublicKey(): ByteArray = device.key.publicKey

    /** This device's key id, so a screen can mark its own row. */
    fun deviceKid(): ByteArray = myKid.copyOf()

    /**
     * After the 8-digit codes match (docs/15 §9.5 i): re-read `keys.json`, list the new device, and return the wrap of
     * the current folder key made for exactly that public key. The newcomer opens that wrap (it came over the pairing
     * messages, not from a file they chose in Drive) and pins with it. A QR enrolment uses [approveDevicePsk]. Call it
     * only for a transcript this device has just shown: the approver's screen has displayed the code and the person
     * said it matches.
     */
    suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): ApproveDeviceOutcome = try {
        val (rootId, keys, bytes) = locateKeys() ?: return ApproveDeviceOutcome.Error(DriveProblem(DriveProblem.Kind.FOLDER_WITHOUT_KEYS))
        val guard = KeysGuard(p, trust.keys(rootId))
        val opened = keysFile.open(bytes, device.key, guard)
        val written = keysFile.addDevice(opened, myKid, KeysFile.NewDevice(publicKey, name, platform), clock())
        writeKeys(keys.id, written.bytes)
        guard.acceptWritten(written)
        val entry = written.opened.body.device(kidOf(p, publicKey))
        if (entry == null) {
            ApproveDeviceOutcome.Error(DriveProblem(DriveProblem.Kind.KEYS_UNREADABLE))
        } else {
            when (val connection = ready(rootId, keys.id, written.opened)) {
                is DriveConnection.Ready -> ApproveDeviceOutcome.Approved(connection, entry.wrap.enc.copyOf(), entry.wrap.ct.copyOf(), written.opened.epoch)
                is DriveConnection.Error -> ApproveDeviceOutcome.Error(connection.problem)
                else -> ApproveDeviceOutcome.Error(DriveProblem(DriveProblem.Kind.DRIVE))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: FolderWithoutKeys) {
        ApproveDeviceOutcome.Error(DriveProblem(DriveProblem.Kind.FOLDER_WITHOUT_KEYS))
    } catch (e: Throwable) {
        ApproveDeviceOutcome.Error(DriveProblem.of(e, "approve"))
    }

    /**
     * After the connected device has seen `pk_new ‖ s` (the QR or the pasted code): list the device as [approveDevice]
     * does, then return a second wrap of the same folder key in HPKE PSK mode. The newcomer accepts only that wrap.
     * The base-mode wrap stays in `keys.json` and is not handed back. A [psk] that is not 32 bytes writes nothing.
     */
    suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): ApproveDeviceOutcome {
        if (psk.size != QR_PSK_LEN) return ApproveDeviceOutcome.Error(DriveProblem(DriveProblem.Kind.KEYS_UNREADABLE))
        val approved = approveDevice(publicKey, name, platform)
        if (approved !is ApproveDeviceOutcome.Approved) return approved
        val kid = kidOf(p, publicKey)
        val folderKey = approved.connection.folder.keys.currentFolderKey()
        return try {
            val sealed = Hpke(p).sealPsk(publicKey, WrapAad.HPKE_INFO, WrapAad.folderKey(approved.epoch, kid), folderKey, psk, QR_PSK_ID)
            ApproveDeviceOutcome.Approved(approved.connection, sealed.enc, sealed.ciphertext, approved.epoch)
        } catch (e: Throwable) {
            ApproveDeviceOutcome.Error(DriveProblem.of(e, "approve"))
        } finally {
            folderKey.fill(0)
        }
    }

    /**
     * The newcomer's first pin from the wrap the enrolled device handed over after the codes matched. The wrap is
     * opened here; the folder key is never taken from a Drive download alone.
     */
    suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection {
        if (epoch < 1) return DriveConnection.Error(DriveProblem(DriveProblem.Kind.KEYS_UNREADABLE))
        val folderKey = try {
            Hpke(p).open(enc, device.key, WrapAad.HPKE_INFO, WrapAad.folderKey(epoch, myKid), ct)
        } catch (e: Throwable) {
            return DriveConnection.Error(DriveProblem.of(e, "join"))
        }
        return try {
            openWithFolderKey(folderKey)
        } finally {
            folderKey.fill(0)
        }
    }

    /** The newcomer's first pin from the PSK wrap. A wrong PSK, or a wrap made for another public key, does not open. */
    suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection {
        if (psk.size != QR_PSK_LEN || epoch < 1) return DriveConnection.Error(DriveProblem(DriveProblem.Kind.KEYS_UNREADABLE))
        val folderKey = try {
            Hpke(p).openPsk(enc, device.key, WrapAad.HPKE_INFO, WrapAad.folderKey(epoch, myKid), ct, psk, QR_PSK_ID)
        } catch (e: Throwable) {
            return DriveConnection.Error(DriveProblem.of(e, "join"))
        }
        return try {
            openWithFolderKey(folderKey)
        } finally {
            folderKey.fill(0)
        }
    }

    /**
     * Revoke one listed device (docs/15 §9.5 iv): a new epoch and a new recovery key, shown once and never stored.
     * Does not revoke Google's grant. A keys error is reported; it does not revoke, re-key or wipe by itself.
     */
    suspend fun revokeDevice(kid: ByteArray): RevokeDeviceOutcome {
        val recovery = RecoveryKey.generate(p)
        val connection = connection("revoke") {
            val (rootId, keys, bytes) = locateKeys() ?: return@connection DriveConnection.NoFolder
            val guard = KeysGuard(p, trust.keys(rootId))
            val opened = keysFile.open(bytes, device.key, guard)
            val written = keysFile.newEpoch(opened, clock(), revokeKid = kid, newRecovery = recovery)
            writeKeys(keys.id, written.bytes)
            guard.acceptWritten(written)
            ready(rootId, keys.id, written.opened)
        }
        return RevokeDeviceOutcome(connection, recovery.takeIf { connection is DriveConnection.Ready })
    }

    /**
     * Checks a typed recovery key against Drive's `keys.json` without changing anything (docs/15 §10.4a). Without a pin
     * there is nothing to check against (a read-only check never makes the first pin), so it is false. It reads the
     * pin and verifies against it but never moves it, even when another device wrote a newer list since.
     */
    suspend fun verifyRecoveryKey(recoveryKey: RecoveryKey): Boolean {
        val located = try {
            locateKeys()
        } catch (_: FolderWithoutKeys) {
            return false
        } ?: return false
        val pinned = trust.keys(located.first)
        if (pinned.load() == null) return false
        val readOnly = object : KeysWatermarkStore {
            override fun load() = pinned.load()
            override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark) = true
        }
        return try {
            keysFile.openWithRecovery(located.third, recoveryKey, KeysGuard(p, readOnly))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The first pin from a folder key received over an authenticated channel (S4b-BL-126's QR enrolment: the approver
     * already listed this device; HPKE PSK mode). Never call it with a key read from Drive.
     */
    suspend fun openWithFolderKey(trustedFolderKey: ByteArray): DriveConnection = connection("open") {
        val (rootId, keys, bytes) = locateKeys() ?: return@connection DriveConnection.NoFolder
        val opened = keysFile.openFirstPin(bytes, device.key, KeysGuard(p, trust.keys(rootId)), trustedFolderKey)
        ready(rootId, keys.id, opened)
    }

    // ---- Backups ---------------------------------------------------------------------------------------------------

    /**
     * One backup (*Back up to Google Drive now*, or the schedule's daily run): encrypt, upload `partial`, check
     * Drive's checksum, mark `complete`, then tidy (heal unfinished uploads, bin stale partial files, retention).
     */
    suspend fun backUp(folder: ReadyFolder, source: BackupSource): BackupOutcome {
        val now = clock()
        var st = state.load().let { it.copy(deviceId = it.deviceId ?: newDeviceId(), lastAttemptAt = now) }
        state.save(st)
        val done: DriveFile
        val backupsId: String
        val meta: BackupMeta
        try {
            backupsId = drive.ensureFolder(DriveLayout.BACKUPS, folder.rootId, create = true, knownId = st.backupsId ?: folder.backupsId)!!.id
            val payload = try {
                source.open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failed(DriveProblem(DriveProblem.Kind.SOURCE_FAILED, code = DriveCode.of(STEP_BACKUP, e)))
            }
            if (!BackupFormat.accepts(payload.format)) {
                payload.close()
                return failed(DriveProblem(DriveProblem.Kind.SOURCE_FAILED))
            }
            val file = scratch.create()
            try {
                val key = folder.keys.currentFolderKey()
                val mac: ByteArray
                try {
                    val written = try {
                        Dpx(p).encrypt(key, folder.keys.epoch, myKid, payload.format, payload.source, file, MAX_PLAINTEXT)
                    } finally {
                        payload.close()
                    }
                    meta = BackupMeta(now, payload.houses, folder.keys.epoch, myKid, written.ciphertextSha256)
                    mac = meta.mac(p, key)
                } finally {
                    key.fill(0)
                }
                val name = BackupNames.of(now, utcOffsetMinutes())
                val target = UploadTarget.New(
                    NewFile(BackupNames.PARTIAL_PREFIX + name, BackupNames.MIME, listOf(backupsId), meta.appProperties(mac, st.deviceId!!)),
                )
                val uploaded = if (file.size <= DriveClient.MULTIPART_LIMIT) {
                    drive.upload(target, file.readAll())
                } else {
                    drive.uploadResumable(target, file.size, { offset, length -> file.readRange(offset, length) })
                }
                done = try {
                    drive.markComplete(uploaded.id, Bytes.hex(meta.ciphertextSha256), name, uploaded)
                } catch (e: DriveException) {
                    // Drive holds other bytes than were sent: never a backup; put it in the bin now (else the clean-up does).
                    if (e.kind == DriveException.Kind.CORRUPT) trashQuietly(uploaded.id)
                    throw e
                }
            } finally {
                file.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failed(DriveProblem.of(e, STEP_BACKUP))
        }
        st = state.load().copy(
            backupsId = backupsId, lastBackupId = done.id, lastSuccessAt = now, lastFailure = null,
            newestSeenAt = maxOf(st.newestSeenAt ?: 0, now),
        )
        state.save(st)
        val backup = DriveBackup(done.id, done.name, now, meta.houses, meta.epoch, myKid, Bytes.hex(meta.ciphertextSha256), done.size)
        val tidy = tidy(folder, backupsId, st)
        return BackupOutcome.Done(backup, tidy.first, tidy.second)
    }

    /** The backups in the folder, newest first, each checked (*Backups in Google Drive*, *Import a backup*). */
    suspend fun listBackups(folder: ReadyFolder): BackupListing {
        val st = state.load()
        val backupsId = st.backupsId ?: folder.backupsId
            ?: drive.ensureFolder(DriveLayout.BACKUPS, folder.rootId, create = false)?.id
        val listing = lister.list(folder, backupsId, listOfNotNull(st.lastBackupId), st.newestSeenAt)
        val newest = listing.newest?.createdAt
        if (newest != null && newest > (st.newestSeenAt ?: Long.MIN_VALUE)) state.save(state.load().copy(newestSeenAt = newest, backupsId = backupsId))
        return listing
    }

    /** The person confirmed the drop the shrink guard held on ([ShrinkHold.backupId]); pruning goes on at the next run. */
    suspend fun confirmShrink(backupId: String) {
        val st = state.load()
        state.save(st.copy(confirmedDrops = (st.confirmedDrops + backupId).toList().takeLast(MAX_CONFIRMED).toSet()))
    }

    /** Downloads and opens the newest backup (docs/15 §1.4 item 3: once a week, [BackupSchedule.Decision.verify]). */
    suspend fun verifyNewest(folder: ReadyFolder): DriveProblem? {
        val newest = try {
            listBackups(folder).newest
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DriveProblem.of(e, STEP_BACKUP)
        } ?: return null
        val sink = object : StagingSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            override fun discard() = Unit
        }
        return when (val r = imports.download(folder, newest, sink)) {
            is ImportDownload.Verified -> {
                state.save(state.load().copy(lastVerifyAt = clock()))
                null
            }
            is ImportDownload.Refused -> r.problem
        }
    }

    /** The schedule's decision for now, from this device's state ([BackupSchedule]). */
    suspend fun schedule(enabled: Boolean, ready: Boolean, manual: Boolean = false): BackupSchedule.Decision {
        val st = state.load()
        return BackupSchedule.decide(
            BackupSchedule.Input(clock(), enabled, ready, manual, st.lastSuccessAt, st.lastAttemptAt, st.lastFailure, st.lastVerifyAt),
        )
    }

    // ---- Inside ----------------------------------------------------------------------------------------------------

    private suspend fun failed(problem: DriveProblem): BackupOutcome.Failed {
        state.save(state.load().copy(lastFailure = problem.scheduleFailure))
        return BackupOutcome.Failed(problem)
    }

    /** Heals unfinished uploads, bins stale partial files and duplicates, then retention. Its failure is reported only. */
    private suspend fun tidy(folder: ReadyFolder, backupsId: String, st: DriveDeviceState): Pair<TidyReport, Boolean> {
        val trashed = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var hold: ShrinkHold? = null
        var missing = false
        val problem = try {
            val listing = lister.list(folder, backupsId, listOfNotNull(st.lastBackupId), st.newestSeenAt)
            missing = listing.missingNewer
            val backups = listing.backups.toMutableList()
            for (u in listing.unfinished) {
                // Verified over Drive's checksum, so it is exactly what its writer uploaded: finish its last step.
                drive.updateMetadata(
                    u.fileId,
                    MetadataChange(name = BackupNames.of(u.createdAt, utcOffsetMinutes()), appProperties = mapOf(DriveLayout.STATE to DriveLayout.STATE_COMPLETE)),
                )
                completed += u.fileId
                backups += u
            }
            val now = clock()
            for (j in listing.junk) if (now - j.createdTime >= STALE_PARTIAL_MS && trashQuietly(j.id)) trashed += j.id
            for (d in listing.duplicates) if (trashQuietly(d)) trashed += d
            val result = BackupRetention.select(
                backups.map { RetentionEntry(it.fileId, it.createdAt, it.houses) }, utcOffsetMinutes(), st.confirmedDrops,
            )
            hold = result.hold
            for (id in result.prune) if (trashQuietly(id)) trashed += id
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DriveProblem.of(e, STEP_BACKUP)
        }
        return TidyReport(trashed, completed, hold, problem) to missing
    }

    /** Moves [fileId] to Drive's bin; one already gone counts as done. Other failures propagate. */
    private suspend fun trashQuietly(fileId: String): Boolean = try {
        drive.trash(fileId)
        true
    } catch (e: DriveException) {
        if (e.kind != DriveException.Kind.NOT_FOUND) throw e
        true
    }

    private suspend fun ready(rootId: String, keysId: String, opened: OpenedKeys): DriveConnection {
        val st = state.load()
        val known = st.controlId.takeIf { st.rootId == rootId }
        val controlStore = trust.control(rootId)
        val controlMeta = findKind(rootId, KIND_CONTROL, known)
        val (controlId, body) = if (controlMeta == null) {
            // Deleted by hand (or never written): this pinned device writes it again, keeping what it knew of it.
            val seen = controlStore.load()
            val base = ControlBody(seen?.revision ?: 0, opened.epoch, clock(), ControlFile.ENCRYPTION, seen?.backupsDeletedAt)
            val written = controlFile.next(opened, base)
            val id = uploadSmall(rootId, CONTROL_NAME, KIND_CONTROL, written.bytes)
            controlFile.accept(written.body, controlStore)
            id to written.body
        } else {
            controlMeta.id to controlFile.open(drive.downloadVerified(controlMeta.id), opened, controlStore)
        }
        val backupsId = (st.backupsId.takeIf { st.rootId == rootId })
            ?: drive.ensureFolder(DriveLayout.BACKUPS, rootId, create = false)?.id
        state.save(
            state.load().copy(rootId = rootId, keysId = keysId, controlId = controlId, backupsId = backupsId, creatingRootId = null,
                deviceId = st.deviceId ?: newDeviceId()),
        )
        return DriveConnection.Ready(ReadyFolder(rootId, keysId, controlId, backupsId, opened, body))
    }

    private suspend fun locateKeys(): Triple<String, DriveFile, ByteArray>? {
        val st = state.load()
        val root = drive.ensureFolder(DriveLayout.ROOT, null, create = false, knownId = st.rootId) ?: return null
        val keys = findKind(root.id, KIND_KEYS, st.keysId.takeIf { root.id == st.rootId })
            ?: throw FolderWithoutKeys()
        return Triple(root.id, keys, drive.downloadVerified(keys.id))
    }

    /** A file of [kind] in the folder: the remembered id first (a listing may lag), else the oldest listed. */
    private suspend fun findKind(rootId: String, kind: String, knownId: String?): DriveFile? {
        if (knownId != null) {
            val f = try {
                drive.getFile(knownId)
            } catch (e: DriveException) {
                if (e.kind != DriveException.Kind.NOT_FOUND) throw e
                null
            }
            if (f != null && !f.trashed && rootId in f.parents && f.appProperties[DriveLayout.KIND] == kind) return f
        }
        return drive.list(DriveQuery(parentId = rootId, appProperties = mapOf(DriveLayout.KIND to kind))).files.firstOrNull { !it.isFolder }
    }

    /** Uploads a small file of [kind] into the folder and reads it back: Drive must hold exactly [bytes]. */
    private suspend fun uploadSmall(rootId: String, name: String, kind: String, bytes: ByteArray): String {
        val f = drive.upload(UploadTarget.New(NewFile(name, JSON_MIME, listOf(rootId), mapOf(DriveLayout.KIND to kind))), bytes)
        readBack(f.id, bytes)
        return f.id
    }

    private suspend fun writeKeys(keysId: String, bytes: ByteArray) {
        drive.upload(UploadTarget.Existing(keysId, JSON_MIME), bytes)
        readBack(keysId, bytes)
    }

    private suspend fun readBack(fileId: String, bytes: ByteArray) {
        if (!drive.downloadVerified(fileId).contentEquals(bytes)) throw DriveException(DriveException.Kind.CORRUPT, reason = "readBack")
    }

    private fun recoveryHint(bytes: ByteArray): Boolean =
        try {
            keysFile.parse(bytes).first.recovery != null
        } catch (_: KeysException) {
            false
        }

    private fun newDeviceId(): String = Bytes.hex(p.randomBytes(8))

    private class FolderWithoutKeys : Exception()

    /** Runs [block], turning every failure (but a cancellation) into [DriveConnection.Error]; an unexpected one carries its [DriveCode] for [step]. */
    private suspend fun connection(first: String, block: suspend Steps.() -> DriveConnection): DriveConnection {
        val steps = Steps(first)
        return try {
            steps.block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: FolderWithoutKeys) {
            DriveConnection.Error(DriveProblem(DriveProblem.Kind.FOLDER_WITHOUT_KEYS))
        } catch (e: Throwable) {
            // An Error too (a missing class, a failed static initialiser, no memory): the screen shows a code, it never hangs.
            DriveConnection.Error(DriveProblem.of(e, steps.step))
        }
    }

    /** The step an unexpected failure is reported under: [connection] starts it, the block moves it on. */
    private class Steps(var step: String)

    companion object {
        private const val STEP_BACKUP = "backup"
        const val KIND_KEYS = "keys"
        const val KIND_CONTROL = "control"
        const val KEYS_NAME = "keys.json"
        const val CONTROL_NAME = "doorprints.json"
        const val JSON_MIME = "application/json"

        /** A backup ZIP is at most 1 GiB of contents (`BackupFormat.MAX_UNCOMPRESSED_BYTES`), plus its directory. */
        const val MAX_PLAINTEXT = BackupFormat.MAX_UNCOMPRESSED_BYTES + 64L * 1024 * 1024

        /** A partial file that does not verify is put in the bin after a day (docs/15 §1.4 item 2). */
        const val STALE_PARTIAL_MS = 24L * 60 * 60 * 1000

        const val MAX_CONFIRMED = 32
    }
}

/** The enrolled device listed the newcomer and wrapped the folder key for that public key alone (web: `ApproveDeviceOutcome`). */
sealed interface ApproveDeviceOutcome {
    /**
      * The newcomer's folder key wrapped to its public key ([wrapEnc], [wrapCt]) under [epoch]; [connection] is this
      * device's open folder.
     */
    class Approved(val connection: DriveConnection.Ready, val wrapEnc: ByteArray, val wrapCt: ByteArray, val epoch: Int) : ApproveDeviceOutcome
    /** Nothing was approved; [problem] says why. */
    data class Error(val problem: DriveProblem) : ApproveDeviceOutcome
}

/** A revoke that finished: the new recovery key is shown once and is not stored (web: `RevokeDeviceOutcome`). */
class RevokeDeviceOutcome(val connection: DriveConnection, val recoveryKey: RecoveryKey?)
