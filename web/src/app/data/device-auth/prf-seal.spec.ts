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

import { describe, expect, it } from "vitest";
import { utf8 } from "../crypto/bytes";
import { WebCryptoProvider } from "../crypto/crypto-provider";
import type { DeletionContext } from "./delete-policy";
import { FakePrfAuthenticator, openWithPrf, sealWithPrf } from "./prf-seal";
import type { SealedBlob } from "./prf-seal";
import { WebAuthorizer } from "./web-authorizer";

const p = new WebCryptoProvider();
const cred = utf8("credential-1");
const key = utf8("the website device key bytes....");
const web: DeletionContext = {
  platform: "WEBSITE",
  deviceLock: false,
  webPrf: true,
  online: true,
  backupsLeft: 5,
};

async function sealed(prf: FakePrfAuthenticator): Promise<SealedBlob> {
  const b = await sealWithPrf(p, prf, cred, key);
  if (!("v" in b)) throw new Error("seal failed");
  return b;
}

describe("PRF-sealed website key", () => {
  it("opens with the same passkey and the plaintext round-trips", async () => {
    const prf = new FakePrfAuthenticator(p);
    const blob = await sealed(prf);
    expect(blob.ciphertext.length).toBe(key.length + 16);
    const r = await openWithPrf(p, prf, blob);
    expect(r.ok).toBe(true);
    if (r.ok) expect(Array.from(r.plaintext)).toEqual(Array.from(key));
  });

  it("a different passkey (other PRF secret) opens nothing: WRONG_KEY", async () => {
    const blob = await sealed(new FakePrfAuthenticator(p));
    const r = await openWithPrf(
      p,
      new FakePrfAuthenticator(p, utf8("another secret")),
      blob,
    );
    expect(r).toEqual({ ok: false, reason: "WRONG_KEY" });
  });

  it("a changed byte, salt or credential id opens nothing", async () => {
    const prf = new FakePrfAuthenticator(p);
    const blob = await sealed(prf);
    const flipped = {
      ...blob,
      ciphertext: blob.ciphertext.map((b, i) => (i === 0 ? b ^ 1 : b)),
    };
    expect((await openWithPrf(p, prf, flipped)).ok).toBe(false);
    expect(
      (await openWithPrf(p, prf, { ...blob, salt: new Uint8Array(32) })).ok,
    ).toBe(false);
    expect(
      (
        await openWithPrf(p, prf, {
          ...blob,
          credentialId: utf8("credential-2"),
        })
      ).ok,
    ).toBe(false);
  });

  it("cancelled, unsupported and failed passkeys give typed failures and no plaintext", async () => {
    const prf = new FakePrfAuthenticator(p);
    const blob = await sealed(prf);
    for (const kind of ["CANCELLED", "NOT_SUPPORTED", "FAILED"] as const) {
      prf.next = kind;
      expect(await openWithPrf(p, prf, blob)).toEqual({
        ok: false,
        reason: kind,
      });
    }
    prf.next = "CANCELLED";
    expect(await sealWithPrf(p, prf, cred, key)).toEqual({
      ok: false,
      reason: "CANCELLED",
    });
  });

  it("an unknown blob version fails closed", async () => {
    const prf = new FakePrfAuthenticator(p);
    const blob = await sealed(prf);
    expect(
      (await openWithPrf(p, prf, { ...blob, v: 2 as unknown as 1 })).ok,
    ).toBe(false);
  });
});

describe("WebAuthorizer", () => {
  let now = 1000;
  function make() {
    const prf = new FakePrfAuthenticator(p);
    const holder: { blob: SealedBlob | null } = { blob: null };
    const auth = new WebAuthorizer(
      p,
      prf,
      () => holder.blob,
      () => now,
    );
    return { prf, holder, auth };
  }

  it("L1 asks nothing; without PRF L2 is refused before any ask", async () => {
    const { prf, auth } = make();
    expect((await auth.authorize("DELETE_ONE_BACKUP", web)).kind).toBe(
      "GRANTED",
    );
    expect(
      await auth.authorize("DELETE_ALL_BACKUPS", { ...web, webPrf: false }),
    ).toEqual({ kind: "REFUSED", reason: "USE_PHONE" });
    expect(prf.asks).toBe(0);
  });

  it("L2 opens the sealed key once per grant; a grant is for one action, one use, 60 s", async () => {
    const { prf, holder, auth } = make();
    holder.blob = await sealed(prf);
    prf.asks = 0;
    const a = await auth.authorize("DELETE_ALL_BACKUPS", web);
    expect(prf.asks).toBe(1);
    if (a.kind !== "GRANTED") throw new Error("not granted");
    expect(auth.redeem(a.grant, "DELETE_EVERYTHING")).toBe("WRONG_ACTION");
    expect(auth.redeem(a.grant, "DELETE_ALL_BACKUPS")).toBe("VALID");
    expect(auth.redeem(a.grant, "DELETE_ALL_BACKUPS")).toBe("ALREADY_USED");
    const b = await auth.authorize("DELETE_EVERYTHING", web);
    if (b.kind !== "GRANTED") throw new Error("not granted");
    now += 60_001;
    expect(auth.redeem(b.grant, "DELETE_EVERYTHING")).toBe("EXPIRED");
  });

  it("cancelled, wrong key and a missing sealed key deny", async () => {
    const { prf, holder, auth } = make();
    expect(await auth.authorize("DELETE_ALL_BACKUPS", web)).toEqual({
      kind: "DENIED",
      reason: "NOT_SUPPORTED",
    });
    holder.blob = await sealed(prf);
    prf.next = "CANCELLED";
    expect(await auth.authorize("DELETE_ALL_BACKUPS", web)).toEqual({
      kind: "DENIED",
      reason: "CANCELLED",
    });
    prf.next = null;
    holder.blob = await sealed(new FakePrfAuthenticator(p, utf8("other")));
    expect(await auth.authorize("DELETE_ALL_BACKUPS", web)).toEqual({
      kind: "DENIED",
      reason: "WRONG_KEY",
    });
  });

  it("offline is refused, not queued", async () => {
    const { auth } = make();
    expect(
      await auth.authorize("DELETE_ALL_BACKUPS", { ...web, online: false }),
    ).toEqual({ kind: "REFUSED", reason: "OFFLINE" });
  });
});
