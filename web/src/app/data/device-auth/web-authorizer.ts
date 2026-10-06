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

import { decide, grantCheck } from "./delete-policy";
import type {
  DeletionAction,
  DeletionContext,
  GrantCheck,
  RefusalReason,
  Requirements,
} from "./delete-policy";
import { checkProof, importProofKey, signProof } from "./deletion-proof";
import { openWithPrf } from "./prf-seal";
import type { PrfAuthenticator, SealedBlob } from "./prf-seal";
import { recoveryProofKey } from "./recovery-factor";
import type { RecoveryKey } from "../crypto/recovery-key";
import type { CryptoProvider } from "../crypto/crypto-provider";

export interface WebGrant {
  readonly id: number;
  readonly action: DeletionAction;
  readonly requirements: Requirements;
  readonly grantedAtMs: number;
}

export type WebAuthorization =
  | { kind: "GRANTED"; grant: WebGrant }
  | { kind: "REFUSED"; reason: RefusalReason }
  | {
      kind: "DENIED";
      reason: "CANCELLED" | "NOT_SUPPORTED" | "FAILED" | "WRONG_KEY";
    };

export type WebRedeemed = GrantCheck | "WRONG_ACTION" | "ALREADY_USED" | "NOT_ISSUED";

/**
 * The website's twin of Kotlin's `DriveGate.authorize`/`redeem` for L2 and L3: the passkey factor is opening the
 * sealed device key through the PRF seam, so a pass means the person was verified AND the key opened. There is no
 * page-only factor: without PRF the policy refuses before this asks anything.
 */
export class WebAuthorizer {
  private nextId = 1;
  private readonly spent = new Set<number>();
  /** Grants this authorizer issued after a policy pass (and a PRF open for L2/L3). Looked up by id on redeem. */
  private readonly issued = new Map<number, WebGrant>();
  /** Non-extractable HMAC key from the latest PRF open. Gone when the page is. L1 does not use it. */
  private proofKey: CryptoKey | null = null;
  /** The source of the current proofKey: 'prf' or 'recovery', or null if no key is loaded. */
  private proofSource: 'prf' | 'recovery' | null = null;

  constructor(
    private readonly p: CryptoProvider,
    private readonly prf: PrfAuthenticator,
    /** The passkey's sealed blob, read when asked (it may sit in storage, and the person may have just made it). */
    private readonly sealed: () => SealedBlob | null | Promise<SealedBlob | null>,
    private readonly clock: () => number,
    /** Optional recovery key verification seam for authorizeWithRecoveryKey. */
    private readonly recovery?: { verify(key: RecoveryKey): Promise<boolean> },
  ) {}

  async authorize(
    action: DeletionAction,
    ctx: DeletionContext,
  ): Promise<WebAuthorization> {
    const d = decide(action, ctx);
    if (d.outcome === "REFUSED") return { kind: "REFUSED", reason: d.reason };
    const req = d.requirements;
    if (req.factor === "NONE")
      return { kind: "GRANTED", grant: this.issue(action, req) };
    if (req.factor !== "PASSKEY")
      return { kind: "DENIED", reason: "NOT_SUPPORTED" };
    const blob = await this.sealed();
    if (!blob) return { kind: "DENIED", reason: "NOT_SUPPORTED" };
    const r = await openWithPrf(this.p, this.prf, blob, async (output) => {
      this.proofKey = await importProofKey(output);
      this.proofSource = 'prf';
    }).catch(() => ({
      ok: false as const,
      reason: "FAILED" as const,
    }));
    if (!r.ok) return { kind: "DENIED", reason: r.reason };
    r.plaintext.fill(0);
    return { kind: "GRANTED", grant: this.issue(action, req) };
  }

  async authorizeWithRecoveryKey(
    action: DeletionAction,
    ctx: DeletionContext,
    key: RecoveryKey,
  ): Promise<WebAuthorization> {
    const d = decide(action, ctx);
    if (d.outcome === "REFUSED") return { kind: "REFUSED", reason: d.reason };
    const req = d.requirements;
    if (req.factor === "NONE")
      return { kind: "GRANTED", grant: this.issue(action, req) };
    if (req.factor !== "PASSKEY")
      return { kind: "DENIED", reason: "NOT_SUPPORTED" };
    if (!this.recovery)
      return { kind: "DENIED", reason: "NOT_SUPPORTED" };
    let ok = false;
    try {
      ok = await this.recovery.verify(key);
    } catch {
      return { kind: "DENIED", reason: "FAILED" };
    }
    if (!ok) return { kind: "DENIED", reason: "WRONG_KEY" };
    this.proofKey = await recoveryProofKey(key);
    this.proofSource = 'recovery';
    return { kind: "GRANTED", grant: this.issue(action, req) };
  }

  /** Forgets the recovery proof key, leaving PRF keys intact. */
  forgetProof(): void {
    if (this.proofSource === 'recovery') {
      this.proofKey = null;
      this.proofSource = null;
    }
  }

  /** Hex HMAC of this operation, or null when this page has not opened the PRF. */
  proofFor(operationId: string, issuedAtMs: number): Promise<string | null> {
    return this.proofKey ? signProof(this.proofKey, operationId, issuedAtMs) : Promise.resolve(null);
  }

  /** True only when `proof` is the HMAC of this operation under the PRF-derived key. */
  verifyProof(operationId: string, issuedAtMs: number, proof: string): Promise<boolean> {
    return this.proofKey ? checkProof(this.proofKey, operationId, issuedAtMs, proof) : Promise.resolve(false);
  }

  redeem(grant: WebGrant, action: DeletionAction): WebRedeemed {
    const issued = this.issued.get(grant.id);
    if (!issued) return "NOT_ISSUED";
    if (issued.action !== action) return "WRONG_ACTION";
    if (this.spent.has(issued.id)) return "ALREADY_USED";
    const c = grantCheck(issued.requirements, issued.grantedAtMs, this.clock());
    if (c === "VALID") this.spent.add(issued.id);
    return c;
  }

  private issue(action: DeletionAction, requirements: Requirements): WebGrant {
    const grant: WebGrant = {
      id: this.nextId++,
      action,
      requirements,
      grantedAtMs: this.clock(),
    };
    this.issued.set(grant.id, grant);
    return grant;
  }
}
