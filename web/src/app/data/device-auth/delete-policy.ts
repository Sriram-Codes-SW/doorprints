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

/** The twin of Kotlin's `DeletionPolicy` and `GateRules` (docs/15 3 and 10, S4b-BL-127), checked by delete-policy-vectors.json. */

/** Which app is asking; the phone can use its screen lock, the website needs a passkey (PRF). */
export type AuthPlatform = "PHONE" | "WEBSITE";
/**
 * How strong a confirmation an action needs: L1 none, L2 a verification, L3 a verification, a tick box and a short delay.
 */
export type DeleteLevel = "L1" | "L2" | "L3";
/** The proof of presence an action asks for: none, the phone's device authentication, or the website's passkey. */
export type Factor = "NONE" | "DEVICE_AUTH" | "PASSKEY";
/**
 * How a new device is paired when approving it: not at all, by QR code or typed code (phone), or by typed code (website).
 */
export type Pairing = "NONE" | "QR_OR_CODE" | "CODE";
/**
 * Why an action is refused before anything is asked: no screen lock, the website lacks PRF so use the phone, or offline.
 */
export type RefusalReason = "NO_DEVICE_LOCK" | "USE_PHONE" | "OFFLINE";
/** Whether an authorisation is still usable when the action starts. */
export type GrantCheck = "VALID" | "EXPIRED" | "NOT_YET";
/** What the phone reports about its screen lock; UNKNOWN is not treated as removed. */
export type LockState = "PRESENT" | "REMOVED" | "UNKNOWN";

/**
 * Every destructive or protection-weakening action in the Drive features; each has a base level in `ACTIONS` that decides how strongly the person must prove it is them.
 */
export type DeletionAction =
  | "DELETE_ONE_BACKUP"
  | "REMOVE_SHARED_HUNT"
  | "DISCONNECT_THIS_DEVICE"
  | "TURN_AUTO_BACKUP_OFF"
  | "DELETE_ALL_BACKUPS"
  | "STOP_SHARING"
  | "REVOKE_DEVICE"
  | "APPROVE_DEVICE"
  | "DISCONNECT_ALL_DEVICES"
  | "DELETE_EVERYTHING"
  | "WEAKEN_PROTECTION";

interface ActionInfo {
  base: DeleteLevel;
  needsNetwork: boolean;
  alwaysAllowed: boolean;
}

/**
 * Per action: its base level, whether it needs the network, and whether it is always allowed (leaving or pausing never needs a lock).
 */
export const ACTIONS: Readonly<Record<DeletionAction, ActionInfo>> = {
  DELETE_ONE_BACKUP: { base: "L1", needsNetwork: true, alwaysAllowed: false },
  REMOVE_SHARED_HUNT: { base: "L1", needsNetwork: false, alwaysAllowed: false },
  DISCONNECT_THIS_DEVICE: {
    base: "L1",
    needsNetwork: false,
    alwaysAllowed: true,
  },
  TURN_AUTO_BACKUP_OFF: {
    base: "L1",
    needsNetwork: false,
    alwaysAllowed: true,
  },
  DELETE_ALL_BACKUPS: { base: "L2", needsNetwork: true, alwaysAllowed: false },
  STOP_SHARING: { base: "L2", needsNetwork: true, alwaysAllowed: false },
  REVOKE_DEVICE: { base: "L2", needsNetwork: true, alwaysAllowed: false },
  APPROVE_DEVICE: { base: "L2", needsNetwork: true, alwaysAllowed: false },
  DISCONNECT_ALL_DEVICES: {
    base: "L2",
    needsNetwork: true,
    alwaysAllowed: false,
  },
  DELETE_EVERYTHING: { base: "L3", needsNetwork: true, alwaysAllowed: false },
  WEAKEN_PROTECTION: { base: "L3", needsNetwork: true, alwaysAllowed: false },
};

/** The facts `decide` weighs: platform, screen lock, website PRF support, connectivity and how many backups remain. */
export interface DeletionContext {
  platform: AuthPlatform;
  deviceLock: boolean;
  webPrf: boolean;
  online: boolean;
  /** null: unknown, treated as the last backup (fail closed). */
  backupsLeft: number | null;
}

/** What the person must do for an allowed action; the screen shows exactly this and the gate enforces it. */
export interface Requirements {
  level: DeleteLevel;
  factor: Factor;
  tickBox: boolean;
  delaySeconds: number;
  pairing: Pairing;
  authValidMs: number;
}

/** The policy's answer for one action: allowed with its requirements, or refused with the reason. */
export type DeletionDecision =
  | { outcome: "ALLOWED"; requirements: Requirements }
  | { outcome: "REFUSED"; reason: RefusalReason };

/** How long a verification stays valid for the action that follows it. */
export const AUTH_VALID_MS = 60_000;
/** The wait before an L3 confirmation button turns on. */
export const DELAY_SECONDS_L3 = 5;

/**
 * The level of an action: deleting a backup is raised from L1 to L2 when it is the last one or the count is unknown (fail closed).
 */
export function levelOf(
  action: DeletionAction,
  backupsLeft: number | null,
): DeleteLevel {
  return action === "DELETE_ONE_BACKUP" &&
    (backupsLeft === null || backupsLeft <= 1)
    ? "L2"
    : ACTIONS[action].base;
}

/** Order of refusals: no lock, then website without PRF, then offline. */
export function decide(
  action: DeletionAction,
  ctx: DeletionContext,
): DeletionDecision {
  const info = ACTIONS[action];
  const level = levelOf(action, ctx.backupsLeft);
  if (!info.alwaysAllowed) {
    if (ctx.platform === "PHONE" && !ctx.deviceLock)
      return { outcome: "REFUSED", reason: "NO_DEVICE_LOCK" };
    if (ctx.platform === "WEBSITE" && level !== "L1" && !ctx.webPrf)
      return { outcome: "REFUSED", reason: "USE_PHONE" };
  }
  if (info.needsNetwork && !ctx.online)
    return { outcome: "REFUSED", reason: "OFFLINE" };
  const factor: Factor =
    level === "L1"
      ? "NONE"
      : ctx.platform === "PHONE"
        ? "DEVICE_AUTH"
        : "PASSKEY";
  const pairing: Pairing =
    action !== "APPROVE_DEVICE"
      ? "NONE"
      : ctx.platform === "PHONE"
        ? "QR_OR_CODE"
        : "CODE";
  const l3 = level === "L3";
  return {
    outcome: "ALLOWED",
    requirements: {
      level,
      factor,
      tickBox: l3 || action === 'DELETE_ALL_BACKUPS',
      delaySeconds: l3 ? DELAY_SECONDS_L3 : 0,
      pairing,
      authValidMs: factor === "NONE" ? 0 : AUTH_VALID_MS,
    },
  };
}

/** Whether the confirm button may be pressed: the tick box (if required) is ticked and the delay has passed. */
export function confirmEnabled(
  r: Pick<Requirements, "tickBox" | "delaySeconds">,
  ticked: boolean,
  elapsedMs: number,
): boolean {
  return (!r.tickBox || ticked) && elapsedMs >= r.delaySeconds * 1000;
}

/**
 * Whether a verification granted at [grantedAtMs] covers an action started at [startedAtMs]: not from the future, and within `authValidMs`. Actions that need no factor are always VALID.
 */
export function grantCheck(
  r: Pick<Requirements, "factor" | "authValidMs">,
  grantedAtMs: number,
  startedAtMs: number,
): GrantCheck {
  if (r.factor === "NONE") return "VALID";
  if (startedAtMs < grantedAtMs) return "NOT_YET";
  return startedAtMs - grantedAtMs > r.authValidMs ? "EXPIRED" : "VALID";
}

/** Whether connecting Drive may go ahead on this device. */
export type ConnectDecision = "ALLOWED" | "NEEDS_SCREEN_LOCK";
/** Whether background Drive work may run, or is paused because the screen lock is gone or unknown. */
export type RunDecision = "RUN" | "PAUSED_NO_LOCK" | "PAUSED_UNKNOWN";

/** The screen-lock rules for connecting, running and dropping local keys; only the phone is held to a screen lock. */
export const GateRules = {
  connect(platform: AuthPlatform, lockEnabled: boolean): ConnectDecision {
    return platform === "WEBSITE" || lockEnabled
      ? "ALLOWED"
      : "NEEDS_SCREEN_LOCK";
  },
  run(platform: AuthPlatform, lock: LockState): RunDecision {
    if (platform === "WEBSITE" || lock === "PRESENT") return "RUN";
    return lock === "REMOVED" ? "PAUSED_NO_LOCK" : "PAUSED_UNKNOWN";
  },
  /** Only a positive "removed" on a phone drops the local keys. */
  dropsKeys(platform: AuthPlatform, lock: LockState): boolean {
    return platform === "PHONE" && lock === "REMOVED";
  },
} as const;
