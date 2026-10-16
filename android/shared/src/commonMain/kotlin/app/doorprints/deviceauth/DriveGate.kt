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

/** What the local detector says about the screen lock. [UNKNOWN]: it could not tell (an error); never a reason to drop keys. */
enum class LockState { PRESENT, REMOVED, UNKNOWN }

/**
 * Reports whether the screen lock this phone's Drive keys were made under is still there (docs/15 10.3). Android:
 * `KeyguardManager.isDeviceSecure` and Keystore keys that became invalid; iOS: `canEvaluatePolicy` and Keychain items
 * gone. Local state only; no Drive content is ever an input.
 */
fun interface LockLostDetector {
    fun lockState(): LockState
}

/**
 * What to do locally when the lock is gone. It holds NO Drive client by design: the reaction cannot delete, revoke,
 * re-key or re-create anything remote (docs/15 10.3: "its local houses were never touched"). Both calls are idempotent.
 */
interface LockLossActions {
    /** Forget this device's Drive keys and tokens (the platform has already invalidated them). The houses stay. */
    fun dropLocalKeys()

    /** This phone must connect again and re-enrol (approval or recovery key) once a lock is set. */
    fun requireReenrolment()
}

sealed interface ConnectDecision {
    data object Allowed : ConnectDecision

    /** "Google Drive backup needs a screen lock on this phone ..." with *Open settings*. */
    data object NeedsScreenLock : ConnectDecision
}

/** Whether a Drive run may go ahead; see [GateRules.run]. */
sealed interface RunDecision {
    data object Run : RunDecision

    /** "Google Drive backup is paused because this phone no longer has a screen lock ...". No upload, download or delete. */
    data object PausedNoLock : RunDecision

    /** The lock state could not be read: paused, keys kept. */
    data object PausedUnknown : RunDecision
}

/** A pure twin of the gate's rules (shared vectors). */
object GateRules {
    fun connect(platform: AuthPlatform, lockEnabled: Boolean): ConnectDecision =
        if (platform == AuthPlatform.WEBSITE || lockEnabled) ConnectDecision.Allowed else ConnectDecision.NeedsScreenLock

    /**
     * Whether Drive work may run: the website always; a phone only while its screen lock is present. A lock that
     * was removed or cannot be read pauses it.
     */
    fun run(platform: AuthPlatform, lock: LockState): RunDecision = when {
        platform == AuthPlatform.WEBSITE -> RunDecision.Run
        lock == LockState.PRESENT -> RunDecision.Run
        lock == LockState.REMOVED -> RunDecision.PausedNoLock
        else -> RunDecision.PausedUnknown
    }

    /** Only a positive "removed" on a phone drops the local keys. */
    fun dropsKeys(platform: AuthPlatform, lock: LockState): Boolean = platform == AuthPlatform.PHONE && lock == LockState.REMOVED
}

/** A factor passed for one action (docs/15 10.2): good for one operation, within the policy's time, never cached. */
class AuthGrant internal constructor(
    internal val id: Long,
    val action: DeletionAction,
    val requirements: Requirements,
    val grantedAtMs: Long,
)

/** The outcome of [DriveGate.authorize]. */
sealed interface Authorization {
    data class Granted(val grant: AuthGrant) : Authorization
    data class Refused(val reason: RefusalReason) : Authorization

    /** The factor did not pass: "Nothing was deleted." */
    data class Denied(val result: AuthResult) : Authorization

    /** The lock went away while asking; the run is paused. */
    data class Paused(val decision: RunDecision) : Authorization
}

/** The answer to [DriveGate.redeem]: the grant may be used ([OK]) or why it may not. */
enum class Redeemed { OK, EXPIRED, NOT_YET, WRONG_ACTION, ALREADY_USED, PAUSED }

/**
 * The guard in front of every Drive action (docs/15 10.3). Connecting needs the lock; every run first asks
 * [beforeRun]; an L2 or L3 action asks [authorize] and then [redeem]s the grant when it starts. [clock] is epoch
 * milliseconds; a session on a monotonic clock may pass one.
 */
class DriveGate(
    private val platform: AuthPlatform,
    private val auth: DeviceAuth,
    private val detector: LockLostDetector,
    private val actions: LockLossActions,
    private val clock: () -> Long,
) {
    private var nextId = 1L
    private val spent = HashSet<Long>()

    /** Whether Drive may be connected now: a phone needs a screen lock first. */
    fun canConnect(): ConnectDecision = GateRules.connect(platform, auth.isDeviceLockEnabled())

    /** Before sync, backup, delete or share. A removed lock drops the LOCAL keys only and asks for re-enrolment. */
    fun beforeRun(): RunDecision {
        val lock = try {
            detector.lockState()
        } catch (_: Exception) {
            LockState.UNKNOWN
        }
        if (GateRules.dropsKeys(platform, lock)) {
            actions.dropLocalKeys()
            actions.requireReenrolment()
        }
        return GateRules.run(platform, lock)
    }

    /**
     * Decides whether a deletion-type [action] may start and, if it needs a factor, asks the phone's own lock
     * screen ([reason] is the text it shows). Returns a one-use [AuthGrant] to [redeem], or why not. A failed or
     * throwing check is a denial, never a pass.
     */
    suspend fun authorize(action: DeletionAction, ctx: DeletionContext, reason: String): Authorization {
        val req = when (val d = DeletionPolicy.decide(action, ctx)) {
            is DeletionDecision.Refused -> return Authorization.Refused(d.reason)
            is DeletionDecision.Allowed -> d.requirements
        }
        if (platform == AuthPlatform.PHONE) {
            val run = beforeRun()
            if (run != RunDecision.Run && !action.alwaysAllowed) return Authorization.Paused(run)
        }
        if (req.factor == Factor.NONE) return Authorization.Granted(issue(action, req))
        // The website's passkey is asked by the web twin (PRF seam); the phone's check is this one. A website call
        // here has no factor to ask: refuse rather than pass.
        if (req.factor != Factor.DEVICE_AUTH) return Authorization.Denied(AuthResult.NOT_AVAILABLE)
        val result = try {
            auth.authenticate(reason, req.level)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            AuthResult.FAILED
        }
        return if (result == AuthResult.SUCCESS) Authorization.Granted(issue(action, req)) else Authorization.Denied(result)
    }

    /** Called when the operation starts. A grant works once, for its own action, within the time, with the lock still there. */
    fun redeem(grant: AuthGrant, action: DeletionAction): Redeemed {
        if (grant.action != action) return Redeemed.WRONG_ACTION
        if (grant.id in spent) return Redeemed.ALREADY_USED
        if (platform == AuthPlatform.PHONE && !action.alwaysAllowed && beforeRun() != RunDecision.Run) return Redeemed.PAUSED
        return when (DeletionPolicy.grantCheck(grant.requirements, grant.grantedAtMs, clock())) {
            GrantCheck.VALID -> {
                spent += grant.id
                Redeemed.OK
            }
            GrantCheck.EXPIRED -> Redeemed.EXPIRED
            GrantCheck.NOT_YET -> Redeemed.NOT_YET
        }
    }

    /** Mints a grant with a fresh id stamped with the current time. */
    private fun issue(action: DeletionAction, req: Requirements) = AuthGrant(nextId++, action, req, clock())
}
