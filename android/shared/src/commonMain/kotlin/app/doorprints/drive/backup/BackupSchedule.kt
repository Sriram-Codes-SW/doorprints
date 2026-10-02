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

/**
 * When the automatic Drive backup is due (S4b-BL-116; docs/15 §1.3, §1.4 item 3), as one pure function with the clock
 * injected (web: `backup-schedule.ts`; vectors: docs/schemas/backup-vectors.json `schedule`). The platform's worker
 * (Android's WorkManager daily work, the iPhone's background task, the website's timer while open) asks it at each
 * trigger and runs [DriveBackupService.backUp] when [Decision.backup] is true; the wiring is the connect ticket's
 * (S4b-BL-117/-118).
 */
object BackupSchedule {
    /** The daily backup: 24 hours after the last one that finished. */
    const val DAILY_MS = 24L * 60 * 60 * 1000

    /** Once a week the newest backup is downloaded and opened, to prove it reads (docs/15 §1.4 item 3). */
    const val VERIFY_MS = 7 * DAILY_MS

    /** After a failure that can pass (offline, rate limit, 5xx), the next automatic try waits this long. */
    const val RETRY_MS = 30L * 60 * 1000

    /** A full Drive is tried again once a day (one notice, docs/15 §5.2). */
    const val QUOTA_RETRY_MS = DAILY_MS

    /** A last time more than this ahead of the clock means the clock moved back: do not wait for it. */
    const val CLOCK_SKEW_MS = 5L * 60 * 1000

    /** Why the last automatic or manual run failed, as the schedule needs it. */
    enum class Failure {
        /** Offline, rate limited, Drive's 5xx, or anything else that can pass by waiting. */
        RETRYABLE,

        /** Drive is full. */
        QUOTA,

        /** The grant was refused: nothing runs until the person connects again (that clears the failure). */
        UNAUTHORIZED,

        /** A problem that waiting will not fix (keys refused, a broken folder): only a manual run tries again. */
        BLOCKED,
    }

    data class Input(
        val now: Long,
        /** *Automatic backup and sync* is on. */
        val enabled: Boolean,
        /** The folder is open on this device ([DriveConnection.Ready]). */
        val ready: Boolean,
        /** The person tapped *Back up to Google Drive now*. */
        val manual: Boolean = false,
        /** When this device's last backup finished (its authenticated `createdAt`). */
        val lastSuccessAt: Long? = null,
        /** When the last run started, whatever its outcome. */
        val lastAttemptAt: Long? = null,
        /** Why the last run failed; null when it succeeded (or none ran). */
        val lastFailure: Failure? = null,
        /** When the newest backup was last downloaded and opened. */
        val lastVerifyAt: Long? = null,
    )

    enum class Reason {
        NOT_READY, MANUAL, DISABLED, NEEDS_CONNECT, BLOCKED, WAIT_QUOTA, WAIT_RETRY, FIRST, CLOCK_CHANGED, DAILY, NOT_DUE,
    }

    /**
     * [backup]: run a backup now. [nextAt]: when to ask again (null: only after something changes). [verify]: download
     * and open the newest backup in this run (or on its own when no backup is due).
     */
    data class Decision(val backup: Boolean, val reason: Reason, val nextAt: Long?, val verify: Boolean)

    fun decide(input: Input): Decision {
        val now = input.now
        val verify = input.ready && input.lastSuccessAt != null && input.lastFailure == null &&
            (input.lastVerifyAt == null || now - input.lastVerifyAt >= VERIFY_MS || input.lastVerifyAt > now + CLOCK_SKEW_MS)
        fun no(reason: Reason, nextAt: Long?) = Decision(false, reason, nextAt, verify)
        if (!input.ready) return Decision(false, Reason.NOT_READY, null, false)
        if (input.manual) return Decision(true, Reason.MANUAL, null, verify)
        if (!input.enabled) return no(Reason.DISABLED, null)
        val attempt = input.lastAttemptAt?.takeIf { it <= now + CLOCK_SKEW_MS }
        when (input.lastFailure) {
            Failure.UNAUTHORIZED -> return no(Reason.NEEDS_CONNECT, null)
            Failure.BLOCKED -> return no(Reason.BLOCKED, null)
            Failure.QUOTA -> if (attempt != null && now - attempt < QUOTA_RETRY_MS) return no(Reason.WAIT_QUOTA, attempt + QUOTA_RETRY_MS)
            Failure.RETRYABLE -> if (attempt != null && now - attempt < RETRY_MS) return no(Reason.WAIT_RETRY, attempt + RETRY_MS)
            null -> Unit
        }
        val last = input.lastSuccessAt ?: return Decision(true, Reason.FIRST, null, verify)
        if (last > now + CLOCK_SKEW_MS) return Decision(true, Reason.CLOCK_CHANGED, null, verify)
        if (now - last >= DAILY_MS) return Decision(true, Reason.DAILY, null, verify)
        return no(Reason.NOT_DUE, last + DAILY_MS)
    }
}
