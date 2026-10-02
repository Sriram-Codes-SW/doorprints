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

/*
 * The fault script of the fake Drive (S4b-BL-115): deterministic, so a later ticket's test can say "the 3rd call
 * answers 503" or "the 2nd upload chunk drops after 100 000 bytes" and get exactly that, on both stacks (web:
 * `fake-drive-faults.ts`, the same names).
 */

/** The kinds of request the fake Drive counts, one per [DriveClient] request. */
enum class DriveOp {
    ABOUT, LIST, GET, CREATE, UPLOAD, UPLOAD_START, UPLOAD_CHUNK, UPLOAD_STATUS, UPDATE, DOWNLOAD, DELETE, TRASH, REVISIONS,
}

/** What the fake does instead of (or as well as) answering one request. */
sealed interface DriveFault {
    /** No answer and nothing done: no network ([DriveException.Kind.OFFLINE]). */
    data object Offline : DriveFault

    /** 401 for this request (the token expired); the client asks its [TokenProvider] once more. */
    data object TokenExpired : DriveFault

    /** 403 `insufficientPermissions`. */
    data object Forbidden : DriveFault

    /** 403 `storageQuotaExceeded` (the quota itself is [FakeDriveServer.quotaBytes]). */
    data object QuotaExceeded : DriveFault

    /** 429 with `Retry-After` when [retryAfterMs] is set, or 403 `userRateLimitExceeded` when [as403]. */
    data class RateLimited(val retryAfterMs: Long? = null, val as403: Boolean = false) : DriveFault

    /** A 5xx ([status]), with `Retry-After` when [retryAfterMs] is set. */
    data class Server(val status: Int = 503, val retryAfterMs: Long? = null) : DriveFault

    /** 404 for this request whatever it asked for. */
    data object NotFound : DriveFault

    /** 409. */
    data object Conflict : DriveFault

    /** 499: Drive closed the request. */
    data object Cancelled : DriveFault

    /**
     * The request is done, then the connection drops before the answer ([DriveException.Kind.OFFLINE]): a retried
     * create then makes a second file with the same name (the partial or duplicate create of docs/15 §8).
     */
    data object ResponseLost : DriveFault

    /** An upload chunk: the first [bytes] arrive, then the connection drops (a resumed upload goes on from there). */
    data class DropAfter(val bytes: Int) : DriveFault

    /**
     * An upload (multipart, or a resumable session's last chunk): one byte is changed on the way, so Drive stores, and
     * checksums, other bytes than were sent ([confirmChecksum] then fails: the checksum-mismatch case).
     */
    data object CorruptContent : DriveFault

    /** A metadata answer without `sha256Checksum`, as if Drive had not computed it yet. */
    data object LateChecksum : DriveFault

    /**
     * Another writer acts just before this request is handled (a rename or update race, another device's write, the
     * person deleting a file by hand): [action] runs on the server with the hand-edit functions, then the request
     * goes on normally.
     */
    class Interleave(val action: (FakeDriveServer) -> Unit) : DriveFault
}

/**
 * Which request gets which fault. Requests are counted from 1 since the server was made, overall and per [DriveOp];
 * [FakeDriveServer.requests] lists them. The first rule that matches a request wins, in this order: [onCall], [on],
 * [next], [always], [stopAfter].
 */
class FaultScript {
    private val byCall = mutableMapOf<Int, DriveFault>()
    private val byOp = mutableMapOf<Pair<DriveOp, Int>, DriveFault>()
    private val queued = mutableListOf<Triple<DriveOp?, DriveFault, IntArray>>()
    private val standing = mutableListOf<Pair<DriveOp?, DriveFault>>()
    private var stopAt: Int? = null
    private var stopFault: DriveFault = DriveFault.Offline

    /** The [n]-th request overall (1-based, counted since the server was made) gets [fault]. */
    fun onCall(n: Int, fault: DriveFault) = apply { byCall[n] = fault }

    /** The [nth] request of [op] (1-based, counted since the server was made) gets [fault]. */
    fun on(op: DriveOp, nth: Int, fault: DriveFault) = apply { byOp[op to nth] = fault }

    /** The next [times] requests (of [op], or of any kind when null) get [fault]. */
    fun next(fault: DriveFault, op: DriveOp? = null, times: Int = 1) = apply { queued += Triple(op, fault, intArrayOf(times)) }

    /** Every request (of [op], or any) gets [fault] until [clear]. */
    fun always(fault: DriveFault, op: DriveOp? = null) = apply { standing += op to fault }

    /** Every request after the [n]-th overall gets [fault] (by default no network): docs/15 §7's "stop after N". */
    fun stopAfter(n: Int, fault: DriveFault = DriveFault.Offline) = apply {
        stopAt = n
        stopFault = fault
    }

    fun clear() = apply {
        byCall.clear()
        byOp.clear()
        queued.clear()
        standing.clear()
        stopAt = null
    }

    /** The fault for request number [call] overall, number [opCall] of [op]; null for a normal answer. */
    internal fun take(op: DriveOp, call: Int, opCall: Int): DriveFault? {
        byCall.remove(call)?.let { return it }
        byOp.remove(op to opCall)?.let { return it }
        queued.firstOrNull { it.first == null || it.first == op }?.let { entry ->
            if (--entry.third[0] <= 0) queued.remove(entry)
            return entry.second
        }
        standing.firstOrNull { it.first == null || it.first == op }?.let { return it.second }
        stopAt?.let { if (call > it) return stopFault }
        return null
    }
}

/** A clock for the fake: [nowMs] moves only by [advance] and [sleep], which records what [DriveRetry] waited. */
class FakeClock(var nowMs: Long = 1_790_000_000_000) {
    /** Every wait the fake's [DriveRetry] made, in order. */
    val slept = mutableListOf<Long>()

    fun now(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }

    fun sleep(ms: Long) {
        slept += ms
        advance(ms)
    }
}

/** Tokens `token-1`, `token-2`, …: [accessToken] gives the current one, [onRejected] moves to the next. */
class FakeTokenProvider : TokenProvider {
    private var n = 1
    val rejected = mutableListOf<String>()

    /** When set, [accessToken] throws it (not connected). */
    var failWith: Throwable? = null

    override suspend fun accessToken(): String {
        failWith?.let { throw it }
        return "token-$n"
    }

    override suspend fun onRejected(token: String) {
        rejected += token
        if (token == "token-$n") n++
    }
}
