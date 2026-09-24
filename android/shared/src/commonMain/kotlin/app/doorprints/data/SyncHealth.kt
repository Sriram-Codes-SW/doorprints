package app.doorprints.data

import app.doorprints.shared.sync.SyncOutcome

/**
 * When background sync has been failing long enough to say so outside Settings (UX review, whole-app audit). An API
 * key revoked on the server, or a server that has gone away, made every sync fail silently while the user believed
 * their partner could see the new houses. The house list shows a warning with *Open Settings* when [warningSince]
 * returns a time. Pure, so the rules are unit tested (SyncHealthTest).
 */
object SyncHealth {
    /** Failures in a row of a kind that will not fix itself (AUTH, SERVER) before the list warns. */
    const val FAILURES_BEFORE_WARNING = 3

    /** Any failure kind warns once no sync has worked for this long. */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /** An outcome that means sync is not failing: it worked, or there is no server to sync with. */
    fun isHealthy(kind: SyncOutcome.Kind): Boolean =
        kind == SyncOutcome.Kind.OK || kind == SyncOutcome.Kind.NOT_CONFIGURED

    /** The failure count after [kind]: back to 0 on a healthy outcome, one more otherwise. */
    fun nextFailures(kind: SyncOutcome.Kind, previous: Int): Int = if (isHealthy(kind)) 0 else previous + 1

    /** When the current run of failures started: kept while failing, set to [now] on the first, 0 when healthy. */
    fun nextFailingSince(kind: SyncOutcome.Kind, previousFailures: Int, previousSince: Long, now: Long): Long = when {
        isHealthy(kind) -> 0L
        previousFailures == 0 || previousSince <= 0L -> now
        else -> previousSince
    }

    /**
     * The time to show in "Sync has not worked since <time>", or null when there is nothing to warn about: the last
     * success, or the first failure when sync has never worked. Only while a server is configured and the last
     * outcome was a failure.
     */
    fun warningSince(
        serverConfigured: Boolean,
        last: SyncOutcome.Kind?,
        failures: Int,
        failingSince: Long,
        lastOkAt: Long,
        now: Long,
    ): Long? {
        if (!serverConfigured || last == null || isHealthy(last) || failures <= 0) return null
        val since = if (lastOkAt > 0L) lastOkAt else failingSince
        if (since <= 0L) return null
        val stuck = (last == SyncOutcome.Kind.AUTH || last == SyncOutcome.Kind.SERVER) &&
            failures >= FAILURES_BEFORE_WARNING
        val stale = now - since >= STALE_AFTER_MS
        return if (stuck || stale) since else null
    }
}
