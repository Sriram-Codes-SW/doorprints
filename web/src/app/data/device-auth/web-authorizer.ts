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
import { openWithPrf } from "./prf-seal";
import type { PrfAuthenticator, SealedBlob } from "./prf-seal";
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

export type WebRedeemed = GrantCheck | "WRONG_ACTION" | "ALREADY_USED";

/**
 * The website's twin of Kotlin's `DriveGate.authorize`/`redeem` for L2 and L3: the passkey factor is opening the
 * sealed device key through the PRF seam, so a pass means the person was verified AND the key opened. There is no
 * page-only factor: without PRF the policy refuses before this asks anything.
 */
export class WebAuthorizer {
  private nextId = 1;
  private readonly spent = new Set<number>();

  constructor(
    private readonly p: CryptoProvider,
    private readonly prf: PrfAuthenticator,
    private readonly sealed: () => SealedBlob | null,
    private readonly clock: () => number,
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
    const blob = this.sealed();
    if (!blob) return { kind: "DENIED", reason: "NOT_SUPPORTED" };
    const r = await openWithPrf(this.p, this.prf, blob).catch(() => ({
      ok: false as const,
      reason: "FAILED" as const,
    }));
    if (!r.ok) return { kind: "DENIED", reason: r.reason };
    r.plaintext.fill(0);
    return { kind: "GRANTED", grant: this.issue(action, req) };
  }

  redeem(grant: WebGrant, action: DeletionAction): WebRedeemed {
    if (grant.action !== action) return "WRONG_ACTION";
    if (this.spent.has(grant.id)) return "ALREADY_USED";
    const c = grantCheck(grant.requirements, grant.grantedAtMs, this.clock());
    if (c === "VALID") this.spent.add(grant.id);
    return c;
  }

  private issue(action: DeletionAction, requirements: Requirements): WebGrant {
    return {
      id: this.nextId++,
      action,
      requirements,
      grantedAtMs: this.clock(),
    };
  }
}
