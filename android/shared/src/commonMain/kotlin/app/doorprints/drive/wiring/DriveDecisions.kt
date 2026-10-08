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

package app.doorprints.drive.wiring

import app.doorprints.deviceauth.RunDecision
import app.doorprints.drive.connect.ConnectState

/*
 * The small decisions of the Drive wiring, as pure functions so the JVM tests pin each one (S4b-BL-117/-118/-127):
 * whether Drive is "in use" on this phone, which sync target the repository uses, and whether a background run may go.
 */

/** What the phone remembers about Drive being in use: [engaged] is persisted, [seenConnected] lives for the process only. */
data class Engagement(val engaged: Boolean = false, val seenConnected: Boolean = false)

/** Remembers whether Drive is in use on this phone, from the controller's state changes. */
object DriveEngagement {
    /**
     * [Engagement] after the controller moved to [state] with [folderGone] as it stands. A folder that is open
     * ([ConnectState.READY], or the recovery key on screen) engages Drive. Going back to disconnected or unavailable
     * disengages it **only for the person's own *Disconnect*** (and only after this process saw the folder open, or the
     * folder gone: a fresh process starts at [ConnectState.DISCONNECTED] before it has reconnected, which must not forget
     * that the person had Drive on). A folder found deleted elsewhere ([folderGone], docs/15 §3.4) is **not** a
     * disconnect: Drive stays in use and the server stays off for this phone until the person answers *Start again* or
     * *Disconnect*. Every other state (connecting, an error, a join form) changes nothing.
     */
    fun next(now: Engagement, state: ConnectState, folderGone: Boolean = false): Engagement = when (state) {
        ConnectState.READY, ConnectState.FIRST_CONNECT_SHOW_RECOVERY_KEY -> Engagement(engaged = true, seenConnected = true)
        ConnectState.DISCONNECTED, ConnectState.UNAVAILABLE -> when {
            // The person has Drive on and the folder is gone: any later disconnect without the flag is theirs.
            folderGone -> now.copy(seenConnected = now.seenConnected || now.engaged)
            now.seenConnected -> Engagement()
            else -> now
        }
        else -> now
    }
}

/** Which backend the repository's sync uses (S4b-BL-70's seam). */
object DriveSyncChoice {
    /**
     * Drive replaces the server while it is [engaged]: both would share the rows' clean marks and the pull cursors, and
     * a row sent to one would never reach the other. Engaged but not reconnected yet is [drive] = null, so nothing syncs
     * (the repository says "not configured") until the folder is open again; it never falls back to the server then.
     * Not engaged: the server's backend, exactly as before Drive existed.
     */
    fun <T : Any> choose(engaged: Boolean, drive: T?, server: () -> T?): T? = if (engaged) drive else server()

    /** The repository's `syncBackendFor`: [choose] with the backend of the Drive pass now running ([DriveSyncRoute.current]). */
    fun backendFor(
        engaged: () -> Boolean,
        route: DriveSyncRoute,
        server: (app.doorprints.data.AppSettings) -> app.doorprints.data.SyncBackend?,
    ): suspend (app.doorprints.data.AppSettings) -> app.doorprints.data.SyncBackend? =
        { settings -> choose(engaged(), route.current()) { server(settings) } }
}

/** Why a background Drive run did not go. */
enum class SkipReason { NOT_CONNECTED, AUTO_OFF, LOCK_REMOVED, LOCK_UNKNOWN, KEY_LOST }

/** Whether a background Drive run may go, or why not. */
sealed interface WorkDecision {
    data object Run : WorkDecision
    data class Skip(val reason: SkipReason) : WorkDecision
}

/** The guard of every background Drive run. */
object DriveWorkRules {
    /**
     * May a background Drive run go (docs/15 §1.3, §10.3)? Only when Drive is connected **and** automatic backup is on
     * **and** the phone still has its screen lock. [lock] is asked last and only then (asking can drop the device key
     * when the lock is gone, so a person who never used Drive is never touched), and every run asks again.
     */
    fun decide(engaged: Boolean, autoBackupOn: Boolean, lock: () -> RunDecision): WorkDecision {
        if (!engaged) return WorkDecision.Skip(SkipReason.NOT_CONNECTED)
        if (!autoBackupOn) return WorkDecision.Skip(SkipReason.AUTO_OFF)
        return when (lock()) {
            RunDecision.Run -> WorkDecision.Run
            RunDecision.PausedNoLock -> WorkDecision.Skip(SkipReason.LOCK_REMOVED)
            RunDecision.PausedUnknown -> WorkDecision.Skip(SkipReason.LOCK_UNKNOWN)
        }
    }

    /** True for the skips that mean "paused for want of a screen lock": the documented notice is shown for them. */
    fun isLockPause(reason: SkipReason): Boolean =
        reason == SkipReason.LOCK_REMOVED || reason == SkipReason.LOCK_UNKNOWN || reason == SkipReason.KEY_LOST
}

/** What Settings says about the screen lock (docs/15 §10.3). */
enum class LockNotice {
    /** The lock is there: nothing to say. */
    NONE,

    /** No lock and Drive is not in use: Drive cannot be switched on; the card is replaced by the words and *Open settings*. */
    NEEDS_LOCK,

    /** No lock and Drive is in use: it is paused; "Your houses are safe on this phone". The card stays so the person can disconnect. */
    PAUSED,

    /**
     * The lock is there but the phone's key store lost the device key (a vendor Keystore error, not a removed lock): Drive is
     * paused and the person connects again. The card stays so they can.
     */
    KEY_LOST,
}

/** What the screen lock means for the Drive card, the background work and the notices (docs/15 §10.3). */
object DriveLockRules {
    /** The notice Settings shows for the state of the lock, whether Drive is in use and a key store fault. */
    fun notice(engaged: Boolean, lockPresent: Boolean, keyStoreFault: Boolean = false): LockNotice = when {
        lockPresent && engaged && keyStoreFault -> LockNotice.KEY_LOST
        lockPresent -> LockNotice.NONE
        engaged -> LockNotice.PAUSED
        else -> LockNotice.NEEDS_LOCK
    }

    /** What a paused run's notification says: the key store's own words when it lost the key, else the removed lock's. */
    fun pausedNotice(keyStoreFault: Boolean): LockNotice = if (keyStoreFault) LockNotice.KEY_LOST else LockNotice.PAUSED

    /**
     * Why a run that is already paused stays out without building anything, or null when it must look again. A pause is the
     * person's to lift (they connect again), but it is dropped by the live check once the lock is back, so a pause with the
     * lock still gone, or with the key store still at fault, is left alone: no graph, no key store call, no second notice.
     */
    fun standingPause(paused: Boolean, lockPresent: Boolean, keyStoreFault: Boolean): SkipReason? = when {
        !paused -> null
        !lockPresent -> SkipReason.LOCK_REMOVED
        keyStoreFault -> SkipReason.KEY_LOST
        else -> null
    }

    /** True when the Drive card may be shown and used: with a lock, or to disconnect while paused. */
    fun showsCard(notice: LockNotice): Boolean = notice != LockNotice.NEEDS_LOCK

    /** The background work exists only while Drive is in use **and** automatic backup is on (docs/15 §1.3, "one switch"). */
    fun shouldSchedule(engaged: Boolean, autoBackupOn: Boolean): Boolean = engaged && autoBackupOn
}
