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

package app.doorprints.deviceauth

/** Where the code runs: the phones have a screen lock to check; the website has a passkey or nothing (docs/15 10.4). */
enum class AuthPlatform { PHONE, WEBSITE }

/**
 * Every action that crosses a level in docs/15 10.1, plus the base level of each. [needsNetwork] is true where the
 * action changes Drive (it is then refused offline, never queued, 3.3); [alwaysAllowed] marks the two actions that
 * only STOP Doorprints from using Drive on this device, so they work with no screen lock and no PRF.
 */
enum class DeletionAction(val base: DeleteLevel, val needsNetwork: Boolean, val alwaysAllowed: Boolean = false) {
    DELETE_ONE_BACKUP(DeleteLevel.L1, true),
    REMOVE_SHARED_HUNT(DeleteLevel.L1, false),
    DISCONNECT_THIS_DEVICE(DeleteLevel.L1, false, alwaysAllowed = true),
    TURN_AUTO_BACKUP_OFF(DeleteLevel.L1, false, alwaysAllowed = true),
    DELETE_ALL_BACKUPS(DeleteLevel.L2, true),
    STOP_SHARING(DeleteLevel.L2, true),
    REVOKE_DEVICE(DeleteLevel.L2, true),
    APPROVE_DEVICE(DeleteLevel.L2, true),
    DISCONNECT_ALL_DEVICES(DeleteLevel.L2, true),
    DELETE_EVERYTHING(DeleteLevel.L3, true),
    WEAKEN_PROTECTION(DeleteLevel.L3, true),
}

/** What the caller knows when it asks. [backupsLeft] null (unknown) is treated as the last backup: fail closed. */
data class DeletionContext(
    val platform: AuthPlatform,
    /** Phones only: [DeviceAuth.isDeviceLockEnabled]. Ignored on the website. */
    val deviceLock: Boolean,
    /** Website only: a passkey whose PRF extension seals the website's key is enrolled and usable. */
    val webPrf: Boolean,
    val online: Boolean,
    val backupsLeft: Int?,
)

enum class Factor { NONE, DEVICE_AUTH, PASSKEY }

/** Approving a new device also needs this (docs/15 10.1): the phone scans the QR code or compares the code. */
enum class Pairing { NONE, QR_OR_CODE, CODE }

/** What one action requires before it may start. */
data class Requirements(
    val level: DeleteLevel,
    val factor: Factor,
    val tickBox: Boolean,
    val delaySeconds: Int,
    val pairing: Pairing,
    /** How long a granted factor stays good, in ms; 0 when no factor is asked. */
    val authValidMs: Long,
)

enum class RefusalReason {
    /** A phone without a screen lock (docs/15 10.3). */
    NO_DEVICE_LOCK,

    /** The website without a PRF passkey: "To do this, use Doorprints on your phone". */
    USE_PHONE,

    /** "You are offline. Deleting from Google Drive needs a connection; nothing was deleted." */
    OFFLINE,
}

sealed interface DeletionDecision {
    data class Allowed(val requirements: Requirements) : DeletionDecision
    data class Refused(val reason: RefusalReason) : DeletionDecision
}

enum class GrantCheck { VALID, EXPIRED, NOT_YET }

/** Docs/15 3 and 10.1 as pure functions: no I/O, no clock, so Kotlin and the TypeScript twin run the same vectors. */
object DeletionPolicy {
    const val AUTH_VALID_MS = 60_000L
    const val DELAY_SECONDS_L3 = 5

    /** The level of an action in this situation. Deleting a backup that is (or may be) the last one is L2. */
    fun levelOf(action: DeletionAction, backupsLeft: Int?): DeleteLevel =
        if (action == DeletionAction.DELETE_ONE_BACKUP && (backupsLeft == null || backupsLeft <= 1)) DeleteLevel.L2 else action.base

    /** Order of refusals: no lock, then website without PRF, then offline. */
    fun decide(action: DeletionAction, ctx: DeletionContext): DeletionDecision {
        val level = levelOf(action, ctx.backupsLeft)
        if (!action.alwaysAllowed) {
            if (ctx.platform == AuthPlatform.PHONE && !ctx.deviceLock) return DeletionDecision.Refused(RefusalReason.NO_DEVICE_LOCK)
            if (ctx.platform == AuthPlatform.WEBSITE && level != DeleteLevel.L1 && !ctx.webPrf) {
                return DeletionDecision.Refused(RefusalReason.USE_PHONE)
            }
        }
        if (action.needsNetwork && !ctx.online) return DeletionDecision.Refused(RefusalReason.OFFLINE)
        val factor = when {
            level == DeleteLevel.L1 -> Factor.NONE
            ctx.platform == AuthPlatform.PHONE -> Factor.DEVICE_AUTH
            else -> Factor.PASSKEY
        }
        val pairing = when {
            action != DeletionAction.APPROVE_DEVICE -> Pairing.NONE
            ctx.platform == AuthPlatform.PHONE -> Pairing.QR_OR_CODE
            else -> Pairing.CODE
        }
        val l3 = level == DeleteLevel.L3
        return DeletionDecision.Allowed(
            Requirements(level, factor, tickBox = l3 || action == DeletionAction.DELETE_ALL_BACKUPS, delaySeconds = if (l3) DELAY_SECONDS_L3 else 0, pairing = pairing,
                authValidMs = if (factor == Factor.NONE) 0 else AUTH_VALID_MS),
        )
    }

    /** Whether *Delete for good* is enabled: the box ticked if asked, and the delay passed since the dialog opened. */
    fun confirmEnabled(r: Requirements, ticked: Boolean, elapsedMs: Long): Boolean =
        (!r.tickBox || ticked) && elapsedMs >= r.delaySeconds * 1000L

    /** A factor granted at [grantedAtMs] may start an operation at [startedAtMs] only within [Requirements.authValidMs] (an action with no factor has nothing to expire). */
    fun grantCheck(r: Requirements, grantedAtMs: Long, startedAtMs: Long): GrantCheck = when {
        r.factor == Factor.NONE -> GrantCheck.VALID
        startedAtMs < grantedAtMs -> GrantCheck.NOT_YET
        startedAtMs - grantedAtMs > r.authValidMs -> GrantCheck.EXPIRED
        else -> GrantCheck.VALID
    }
}
