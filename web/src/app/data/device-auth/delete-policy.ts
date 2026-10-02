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

export type AuthPlatform = "PHONE" | "WEBSITE";
export type DeleteLevel = "L1" | "L2" | "L3";
export type Factor = "NONE" | "DEVICE_AUTH" | "PASSKEY";
export type Pairing = "NONE" | "QR_OR_CODE" | "CODE";
export type RefusalReason = "NO_DEVICE_LOCK" | "USE_PHONE" | "OFFLINE";
export type GrantCheck = "VALID" | "EXPIRED" | "NOT_YET";
export type LockState = "PRESENT" | "REMOVED" | "UNKNOWN";

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

export interface DeletionContext {
  platform: AuthPlatform;
  deviceLock: boolean;
  webPrf: boolean;
  online: boolean;
  /** null: unknown, treated as the last backup (fail closed). */
  backupsLeft: number | null;
}

export interface Requirements {
  level: DeleteLevel;
  factor: Factor;
  tickBox: boolean;
  delaySeconds: number;
  pairing: Pairing;
  authValidMs: number;
}

export type DeletionDecision =
  | { outcome: "ALLOWED"; requirements: Requirements }
  | { outcome: "REFUSED"; reason: RefusalReason };

export const AUTH_VALID_MS = 60_000;
export const DELAY_SECONDS_L3 = 5;

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
      tickBox: l3,
      delaySeconds: l3 ? DELAY_SECONDS_L3 : 0,
      pairing,
      authValidMs: factor === "NONE" ? 0 : AUTH_VALID_MS,
    },
  };
}

export function confirmEnabled(
  r: Pick<Requirements, "tickBox" | "delaySeconds">,
  ticked: boolean,
  elapsedMs: number,
): boolean {
  return (!r.tickBox || ticked) && elapsedMs >= r.delaySeconds * 1000;
}

export function grantCheck(
  r: Pick<Requirements, "factor" | "authValidMs">,
  grantedAtMs: number,
  startedAtMs: number,
): GrantCheck {
  if (r.factor === "NONE") return "VALID";
  if (startedAtMs < grantedAtMs) return "NOT_YET";
  return startedAtMs - grantedAtMs > r.authValidMs ? "EXPIRED" : "VALID";
}

export type ConnectDecision = "ALLOWED" | "NEEDS_SCREEN_LOCK";
export type RunDecision = "RUN" | "PAUSED_NO_LOCK" | "PAUSED_UNKNOWN";

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
