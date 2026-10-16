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

package app.doorprints.drive

import app.doorprints.shared.export.Sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One person's Drive in memory, as Doorprints sees it through `drive.file` (S4b-BL-115; web: `FakeDriveServer` in
 * `in-memory-fake-drive.ts`): folders and files with `appProperties`, content with Drive's `sha256Checksum`, revisions,
 * the bin and permanent deletes (a folder takes its contents), a storage quota, resumable sessions, `modifiedTime` from
 * a [FakeClock], a listing that lags behind writes by [listingLagMs], and the [faults] script. Each request is counted
 * ([requests]) and may get a fault; the hand-edit functions (`…ByHand`, [rollBack], [expireSessions]) are the person or
 * another writer acting in Drive and are never counted.
 *
 * Two [InMemoryFakeDrive] clients on one server are two devices of one account. Requests are handled one at a time
 * (a [Mutex]), as Drive orders writes; there is no compare-and-swap, so the last write wins, as in Drive. The hand
 * edits are not locked: call them between requests or from a [DriveFault.Interleave].
 *
 * Test code, never shipped (`:shared` commonTest); a later ticket whose `:ui` or `:app` tests need it moves it to a small
 * test-fixtures module.
 */
class FakeDriveServer(
    val clock: FakeClock = FakeClock(),
    /** The account's limit in bytes (content of every file, the bin included); null is unlimited. */
    var quotaBytes: Long? = 15L * 1024 * 1024 * 1024,
    /** A file created less than this many ms ago is left out of listings (Drive's index lag); 0 is none. */
    var listingLagMs: Long = 0,
    val email: String = "person@example.com",
    private val sha256: () -> Sha256 = ::driveSha256,
) {
    val faults = FaultScript()

    /** One line per request, in order: the [DriveOp] and the file id or query it was about. */
    val requests: List<Pair<DriveOp, String?>> get() = log.toList()

    /** Tokens refused with 401 (an expired or revoked grant). */
    val refusedTokens = mutableSetOf<String>()

    /** The token each request carried, in order. */
    val tokensSeen: List<String> get() = tokens.toList()

    private class Stored(
        var file: DriveFile,
        var content: ByteArray?,
        val revisions: MutableList<Pair<DriveRevision, ByteArray>> = mutableListOf(),
    )

    private class Session(val uri: String, val target: UploadTarget, val size: Long) {
        var buffer = ByteArray(0)
        var done: String? = null
        var corrupt = false
    }

    private val mutex = Mutex()
    private val files = LinkedHashMap<String, Stored>()
    private val sessions = mutableMapOf<String, Session>()
    private val log = mutableListOf<Pair<DriveOp, String?>>()
    private val tokens = mutableListOf<String>()
    private val opCounts = mutableMapOf<DriveOp, Int>()
    private var ids = 0

    // --- requests (counted, faults applied) ---

    suspend fun about(token: String): DriveAbout = request(DriveOp.ABOUT, null, token) {
        DriveAbout(email, "Test Person", quotaBytes, usage(), usage())
    }

    suspend fun list(token: String, query: DriveQuery, pageToken: String?, pageSize: Int): DrivePage =
        request(DriveOp.LIST, query.toQ(), token) {
            val start = when (pageToken) {
                null -> 0
                else -> pageToken.removePrefix("o").toIntOrNull()?.takeIf { pageToken.startsWith("o") && it >= 0 }
                    ?: throw DriveException(DriveException.Kind.BAD_REQUEST, 400, reason = "invalidPageToken")
            }
            val now = clock.now()
            val all = files.values.map { view(it.file) }
                .filter { now - it.createdTime >= listingLagMs && matches(it, query) }
                .sortedWith(compareBy({ it.createdTime }, { it.id }))
            val page = all.drop(start).take(pageSize)
            DrivePage(page, if (start + page.size < all.size) "o${start + page.size}" else null)
        }

    suspend fun get(token: String, fileId: String): DriveFile = request(DriveOp.GET, fileId, token) {
        view(stored(fileId).file)
    }

    suspend fun create(token: String, file: NewFile): DriveFile = request(DriveOp.CREATE, file.name, token) {
        add(file, null)
    }

    suspend fun upload(token: String, target: UploadTarget, content: ByteArray): DriveFile =
        request(DriveOp.UPLOAD, idOf(target), token) { write(target, content) }

    suspend fun startUpload(token: String, target: UploadTarget, size: Long): UploadSession =
        request(DriveOp.UPLOAD_START, idOf(target), token) {
            when (target) {
                is UploadTarget.New -> checkNew(target.file)
                is UploadTarget.Existing -> stored(target.fileId)
            }
            val uri = "${HttpDriveClient.UPLOAD}/files?uploadType=resumable&upload_id=${nextId("s")}"
            sessions[uri] = Session(uri, target, size)
            UploadSession(uri, size, (target as? UploadTarget.Existing)?.fileId)
        }

    suspend fun uploadChunk(token: String, uri: String, offset: Long, bytes: ByteArray): UploadProgress =
        request(DriveOp.UPLOAD_CHUNK, null, token) {
            val s = session(uri)
            s.done?.let { return@request UploadProgress.Complete(view(stored(it).file)) }
            val have = s.buffer.size.toLong()
            if (offset > have || offset + bytes.size > s.size) throw DriveException(DriveException.Kind.BAD_REQUEST, 400)
            val last = offset + bytes.size == s.size
            if (!last && bytes.size % DriveClient.CHUNK_UNIT != 0) {
                throw DriveException(DriveException.Kind.BAD_REQUEST, 400, reason = "chunkNotAligned")
            }
            val drop = pendingDrop
            // A resent chunk that overlaps what arrived: the overlap is skipped.
            val fresh = bytes.copyOfRange(minOf(have - offset, bytes.size.toLong()).toInt(), bytes.size)
            if (drop != null) {
                s.buffer += fresh.copyOfRange(0, minOf(drop, fresh.size))
                throw DriveException(DriveException.Kind.OFFLINE, reason = "dropped")
            }
            s.buffer += fresh
            if (pendingCorrupt) s.corrupt = true
            finish(s)
        }

    suspend fun uploadStatus(token: String, uri: String): UploadProgress = request(DriveOp.UPLOAD_STATUS, null, token) {
        val s = session(uri)
        s.done?.let { UploadProgress.Complete(view(stored(it).file)) } ?: UploadProgress.Incomplete(s.buffer.size.toLong())
    }

    suspend fun update(token: String, fileId: String, change: MetadataChange): DriveFile =
        request(DriveOp.UPDATE, fileId, token) { view(applyChange(stored(fileId), change).file) }

    suspend fun download(token: String, fileId: String, range: LongRange?): ByteArray =
        request(DriveOp.DOWNLOAD, fileId, token) {
            val content = stored(fileId).content
                ?: throw DriveException(DriveException.Kind.FORBIDDEN, 403, reason = "fileNotDownloadable")
            if (range == null) return@request content.copyOf()
            if (range.first >= content.size) throw DriveException(DriveException.Kind.BAD_REQUEST, 416)
            content.copyOfRange(range.first.toInt(), minOf(range.last + 1, content.size.toLong()).toInt())
        }

    suspend fun delete(token: String, fileId: String): Unit = request(DriveOp.DELETE, fileId, token) {
        stored(fileId)
        deleteByHand(fileId)
    }

    suspend fun trash(token: String, fileId: String): DriveFile = request(DriveOp.TRASH, fileId, token) {
        val s = stored(fileId)
        s.file = s.file.copy(trashed = true)
        view(s.file)
    }

    suspend fun revisions(token: String, fileId: String): List<DriveRevision> = request(DriveOp.REVISIONS, fileId, token) {
        stored(fileId).revisions.map { it.first }
    }

    // --- the person or another writer, in Drive (not counted, no faults) ---

    /** A file as it stands (null when deleted for good), with `trashed` as Drive reports it. */
    fun fileOrNull(fileId: String): DriveFile? = files[fileId]?.let { view(it.file) }

    /** The stored content (null for a folder or a deleted file). */
    fun contentOf(fileId: String): ByteArray? = files[fileId]?.content?.copyOf()

    /** Every file and folder, the bin included, in creation order. */
    fun allFiles(): List<DriveFile> = files.values.map { view(it.file) }

    /** Adds a file or folder as if made earlier (by Doorprints or by hand). */
    fun putByHand(file: NewFile, content: ByteArray? = null): DriveFile = add(file, content)

    /** New content, as an edit by hand or another program: a new revision. */
    fun editByHand(fileId: String, content: ByteArray) {
        setContent(files.getValue(fileId), content)
    }

    fun renameByHand(fileId: String, name: String) {
        val s = files.getValue(fileId)
        s.file = s.file.copy(name = name)
    }

    fun moveByHand(fileId: String, parentId: String?) {
        val s = files.getValue(fileId)
        s.file = s.file.copy(parents = listOfNotNull(parentId))
    }

    fun trashByHand(fileId: String) {
        val s = files.getValue(fileId)
        s.file = s.file.copy(trashed = true)
    }

    /** Takes a file back out of the bin. */
    fun untrashByHand(fileId: String) {
        val s = files.getValue(fileId)
        s.file = s.file.copy(trashed = false)
    }

    /** Deletes for good, a folder with everything in it (and empties those from the bin). */
    fun deleteByHand(fileId: String) {
        val doomed = mutableSetOf(fileId)
        var grew = true
        while (grew) {
            grew = files.values.filter { it.file.id !in doomed && it.file.parents.any(doomed::contains) }
                .onEach { doomed += it.file.id }.isNotEmpty()
        }
        doomed.forEach(files::remove)
    }

    /** Drive's "manage versions": [revisionId]'s content becomes the head again, as a new revision. */
    fun rollBack(fileId: String, revisionId: String) {
        val s = files.getValue(fileId)
        val old = s.revisions.first { it.first.id == revisionId }.second
        setContent(s, old)
    }

    /** Drive forgets every open resumable session (they live a week): the next chunk gets 404. */
    fun expireSessions() {
        sessions.values.removeAll { it.done == null }
    }

    // --- inside ---

    private var pendingDrop: Int? = null
    private var pendingCorrupt = false
    private var lateChecksum = false

    private suspend fun <T> request(op: DriveOp, about: String?, token: String, block: () -> T): T = mutex.withLock {
        log += op to about
        tokens += token
        val opCall = (opCounts[op] ?: 0) + 1
        opCounts[op] = opCall
        val fault = faults.take(op, log.size, opCall)
        if (fault is DriveFault.Interleave) fault.action(this)
        if (token.isEmpty() || token in refusedTokens) throw DriveException(DriveException.Kind.UNAUTHORIZED, 401)
        when (fault) {
            null, is DriveFault.Interleave -> Unit
            DriveFault.ResponseLost -> {
                block()
                throw DriveException(DriveException.Kind.OFFLINE, reason = "responseLost")
            }
            is DriveFault.DropAfter -> pendingDrop = fault.bytes
            DriveFault.CorruptContent -> pendingCorrupt = true
            DriveFault.LateChecksum -> lateChecksum = true
            else -> throw exceptionFor(fault)
        }
        try {
            block()
        } finally {
            pendingDrop = null
            pendingCorrupt = false
            lateChecksum = false
        }
    }

    private fun exceptionFor(fault: DriveFault): DriveException = when (fault) {
        DriveFault.Offline -> DriveException(DriveException.Kind.OFFLINE)
        DriveFault.TokenExpired -> DriveException(DriveException.Kind.UNAUTHORIZED, 401)
        DriveFault.Forbidden -> DriveException(DriveException.Kind.FORBIDDEN, 403, reason = "insufficientPermissions")
        DriveFault.QuotaExceeded -> quotaError()
        is DriveFault.RateLimited -> if (fault.as403) {
            DriveException(DriveException.Kind.RATE_LIMITED, 403, fault.retryAfterMs, "userRateLimitExceeded")
        } else {
            DriveException(DriveException.Kind.RATE_LIMITED, 429, fault.retryAfterMs, "rateLimitExceeded")
        }
        is DriveFault.Server -> DriveException(DriveException.Kind.SERVER, fault.status, fault.retryAfterMs)
        DriveFault.NotFound -> DriveException(DriveException.Kind.NOT_FOUND, 404, reason = "notFound")
        DriveFault.Conflict -> DriveException(DriveException.Kind.CONFLICT, 409)
        DriveFault.Cancelled -> DriveException(DriveException.Kind.CANCELLED, 499)
        else -> error("handled in request")
    }

    private fun quotaError() = DriveException(DriveException.Kind.QUOTA_EXCEEDED, 403, reason = "storageQuotaExceeded")

    private fun nextId(prefix: String) = prefix + (++ids).toString().padStart(6, '0')

    private fun idOf(target: UploadTarget) = when (target) {
        is UploadTarget.New -> target.file.name
        is UploadTarget.Existing -> target.fileId
    }

    private fun stored(fileId: String): Stored =
        files[fileId] ?: throw DriveException(DriveException.Kind.NOT_FOUND, 404, reason = "notFound")

    private fun session(uri: String): Session =
        sessions[uri] ?: throw DriveException(DriveException.Kind.NOT_FOUND, 404, reason = "uploadSessionGone")

    private fun usage(): Long = files.values.sumOf { it.content?.size?.toLong() ?: 0 }

    private fun isTrashed(file: DriveFile): Boolean {
        var current: DriveFile? = file
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            if (current.trashed) return true
            current = current.parents.firstOrNull()?.let { files[it]?.file }
        }
        return false
    }

    private fun view(file: DriveFile): DriveFile {
        val shown = file.copy(trashed = isTrashed(file))
        return if (lateChecksum) shown.copy(sha256Checksum = null) else shown
    }

    private fun matches(file: DriveFile, q: DriveQuery): Boolean =
        (q.parentId == null || q.parentId in file.parents) &&
            (q.name == null || q.name == file.name) &&
            (q.mimeType == null || q.mimeType == file.mimeType) &&
            q.appProperties.all { (k, v) -> file.appProperties[k] == v } &&
            (q.trashed == null || q.trashed == file.trashed)

    private fun checkProperties(props: Map<String, String?>) {
        for ((k, v) in props) {
            if (k.isEmpty() || k.encodeToByteArray().size + (v?.encodeToByteArray()?.size ?: 0) > DriveLayout.MAX_PROPERTY_BYTES) {
                throw DriveException(DriveException.Kind.BAD_REQUEST, 400, reason = "invalidAppProperty")
            }
        }
    }

    private fun checkParents(parents: List<String>) {
        if (parents.size > 1) throw DriveException(DriveException.Kind.BAD_REQUEST, 400, reason = "multipleParents")
        parents.forEach { if (files[it]?.file?.isFolder != true) throw DriveException(DriveException.Kind.NOT_FOUND, 404, reason = "notFound") }
    }

    private fun checkNew(file: NewFile) {
        checkParents(file.parents)
        checkProperties(file.appProperties)
    }

    private fun checkQuota(extra: Long) {
        val limit = quotaBytes ?: return
        if (usage() + extra > limit) throw quotaError()
    }

    private fun add(file: NewFile, content: ByteArray?): DriveFile {
        checkNew(file)
        if (content != null) checkQuota(content.size.toLong())
        val now = clock.now()
        val folder = file.mimeType == FOLDER_MIME
        val stored = Stored(
            DriveFile(nextId("f"), file.name, file.mimeType, file.parents, file.appProperties, createdTime = now, modifiedTime = now),
            if (folder) null else ByteArray(0),
        )
        files[stored.file.id] = stored
        if (!folder) setContent(stored, content ?: ByteArray(0))
        return view(stored.file)
    }

    private fun write(target: UploadTarget, content: ByteArray, corrupt: Boolean = pendingCorrupt): DriveFile {
        val bytes = if (corrupt && content.isNotEmpty()) content.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else content
        return when (target) {
            is UploadTarget.New -> add(target.file, bytes)
            is UploadTarget.Existing -> {
                val s = stored(target.fileId)
                checkQuota(bytes.size.toLong() - (s.content?.size ?: 0))
                applyChange(s, target.change)
                setContent(s, bytes)
                view(s.file)
            }
        }
    }

    private fun finish(s: Session): UploadProgress {
        if (s.buffer.size.toLong() < s.size) return UploadProgress.Incomplete(s.buffer.size.toLong())
        val file = write(s.target, s.buffer, s.corrupt)
        s.done = file.id
        return UploadProgress.Complete(file)
    }

    private fun applyChange(s: Stored, change: MetadataChange): Stored {
        checkProperties(change.appProperties)
        val parents = (s.file.parents - change.removeParents.toSet() + change.addParents).distinct()
        if (change.addParents.isNotEmpty()) checkParents(parents)
        val props = s.file.appProperties.toMutableMap()
        change.appProperties.forEach { (k, v) -> if (v == null) props.remove(k) else props[k] = v }
        s.file = s.file.copy(name = change.name ?: s.file.name, appProperties = props, parents = parents)
        return s
    }

    private fun setContent(s: Stored, content: ByteArray) {
        val now = clock.now()
        val revision = DriveRevision(nextId("r"), now, content.size.toLong())
        s.revisions += revision to content.copyOf()
        s.content = content.copyOf()
        s.file = s.file.copy(
            size = content.size.toLong(),
            sha256Checksum = sha256HexOf(content, sha256),
            modifiedTime = now,
            headRevisionId = revision.id,
        )
    }
}

/**
 * The fake Drive as a [DriveClient] (S4b-BL-115): each call one request to [server], with the same 401 rule and
 * [retry] as [HttpDriveClient], so a test that passes on it passes on the real client (`DriveClientContract`). By
 * default the retry waits on the server's clock ([FakeClock.slept]) with the middle of the jitter, so tests never sleep.
 */
class InMemoryFakeDrive(
    val server: FakeDriveServer = FakeDriveServer(),
    val tokens: TokenProvider = FakeTokenProvider(),
    override val retry: DriveRetry = DriveRetry(random = { 0.5 }, sleep = { server.clock.sleep(it) }),
) : DriveClient {

    private suspend fun <T> call(block: suspend (String) -> T): T = retry.run { authorized(tokens, block) }

    override suspend fun about(): DriveAbout = call { server.about(it) }

    override suspend fun list(query: DriveQuery, pageToken: String?, pageSize: Int): DrivePage {
        require(pageSize in 1..1000)
        return call { server.list(it, query, pageToken, pageSize) }
    }

    override suspend fun getFile(fileId: String): DriveFile = call { server.get(it, HttpDriveClient.checkId(fileId)) }

    override suspend fun createFile(file: NewFile): DriveFile = call { server.create(it, file) }

    override suspend fun upload(target: UploadTarget, content: ByteArray): DriveFile {
        require(content.size <= DriveClient.MULTIPART_LIMIT) { "use uploadResumable above 5 MiB" }
        return call { server.upload(it, target, content) }
    }

    override suspend fun startUpload(target: UploadTarget, size: Long): UploadSession {
        require(size > 0)
        return call { server.startUpload(it, target, size) }
    }

    override suspend fun uploadChunk(session: UploadSession, offset: Long, bytes: ByteArray): UploadProgress {
        require(bytes.isNotEmpty() && offset >= 0 && offset + bytes.size <= session.size)
        return authorized(tokens) { server.uploadChunk(it, session.uri, offset, bytes) }
    }

    override suspend fun uploadStatus(session: UploadSession): UploadProgress =
        authorized(tokens) { server.uploadStatus(it, session.uri) }

    override suspend fun updateMetadata(fileId: String, change: MetadataChange): DriveFile =
        call { server.update(it, HttpDriveClient.checkId(fileId), change) }

    override suspend fun download(fileId: String, range: LongRange?): ByteArray {
        if (range != null) require(range.first >= 0 && range.last >= range.first)
        return call { server.download(it, HttpDriveClient.checkId(fileId), range) }
    }

    override suspend fun delete(fileId: String) {
        try {
            call { server.delete(it, HttpDriveClient.checkId(fileId)) }
        } catch (e: DriveException) {
            if (e.kind != DriveException.Kind.NOT_FOUND) throw e
        }
    }

    override suspend fun trash(fileId: String): DriveFile = call { server.trash(it, HttpDriveClient.checkId(fileId)) }

    override suspend fun revisions(fileId: String): List<DriveRevision> =
        call { server.revisions(it, HttpDriveClient.checkId(fileId)) }
}
