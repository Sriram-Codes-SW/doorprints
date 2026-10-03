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

import { b64, concat, constantTimeEquals, equalBytes, unb64, utf8 } from './bytes';
import { CanonicalJson, exactObj, int, isObj, MAX_SAFE, parseJson, str } from './canonical-json';
import { CryptoError, sha256Of } from './crypto-provider';
import type { AesKey, CryptoProvider, P256PrivateKey } from './crypto-provider';
import { aesFromBase, chainWrapKey, deriveFolderBits, FOLDER_KEY_SIZE, hmacFromBase, HPKE_INFO, importFolderBase, kidOf, WrapAad } from './folder-key';
import type { FolderKeys } from './folder-key';
import { Hpke } from './hpke';
import type { RecoveryKey } from './recovery-key';

export type KeysErrorKind =
  | 'MALFORMED'
  | 'NOT_CANONICAL'
  | 'UNSUPPORTED_FORMAT'
  | 'INVALID_ENTRY'
  | 'NOT_ENROLLED'
  | 'REVOKED'
  | 'UNWRAP_FAILED'
  | 'MAC_INVALID'
  | 'NOT_PINNED'
  | 'ROLLED_BACK'
  | 'PIN_MISMATCH'
  | 'FORK_DETECTED'
  | 'CONCURRENT_UPDATE'
  | 'NO_RECOVERY'
  | 'RECOVERY_MISMATCH'
  | 'RECOVERY_ANCHOR_INVALID'
  | 'CHAIN_BROKEN'
  | 'ALREADY_ENROLLED'
  | 'NOT_LISTED'
  | 'LAST_RECIPIENT'
  | 'REVISION_LIMIT'
  | 'REVISION_JUMP'
  | 'NEW_RECOVERY_REQUIRED'
  | 'TOO_LARGE';

/**
 * The kinds of Kotlin's `KeysException`. Every kind found before the MAC and the pin (MALFORMED, NOT_CANONICAL,
 * UNSUPPORTED_FORMAT, INVALID_ENTRY, NOT_ENROLLED, REVOKED, UNWRAP_FAILED, NO_RECOVERY, RECOVERY_MISMATCH) comes from
 * bytes anyone in the Google account can write: report it, and never act destructively on it. The same holds for
 * every kind: anyone with write access to the Drive folder can cause any of them, so NO error kind from `keys.json`
 * may trigger a revoke, a re-key, a wipe of local keys or data, or re-creating the folder (docs/15 §9.9);
 * `ROLLED_BACK` is not benign either (docs/02 RR-29).
 */
export class KeysError extends Error {
  constructor(
    readonly kind: KeysErrorKind,
    message: string,
  ) {
    super(`keys ${kind}: ${message}`);
    this.name = 'KeysError';
  }
}

export type DevicePlatform = 'android' | 'ios' | 'web';
const PLATFORMS: readonly DevicePlatform[] = ['android', 'ios', 'web'];

export interface HpkeWrap {
  enc: Uint8Array;
  ct: Uint8Array;
}
export interface DeviceEntry {
  kid: Uint8Array;
  name: string;
  platform: DevicePlatform;
  publicKey: Uint8Array;
  enrolledAt: number;
  enrolledBy: Uint8Array | null;
  wrap: HpkeWrap;
}
/** `anchor`: the folder key of `anchorEpoch` under a key only the recovery key's holder can derive. */
export interface RecoveryEntry {
  kid: Uint8Array;
  publicKey: Uint8Array;
  anchorEpoch: number;
  anchor: { nonce: Uint8Array; ct: Uint8Array };
  wrap: HpkeWrap;
}
/** A revoked kid: a device's, or a replaced recovery key's (`isRecovery`; never a writer of files). */
export interface RevokedEntry {
  kid: Uint8Array;
  isRecovery: boolean;
  revokedAt: number;
  revokedAtEpoch: number;
}
export interface ChainLink {
  epoch: number;
  nonce: Uint8Array;
  ct: Uint8Array;
}
export interface KeysBody {
  revision: number;
  epoch: number;
  chain: ChainLink[];
  devices: DeviceEntry[];
  recovery: RecoveryEntry | null;
  revoked: RevokedEntry[];
}

export const KEYS_FORMAT = 'doorprints-keys/1';
const MAX_FILE = 256 * 1024;
const MAX_DEVICES = 64;
const MAX_REVOKED = 1024;
const MAX_EPOCH = 2147483647;

function wrapJson(j: CanonicalJson, w: HpkeWrap): void {
  j.raw('{"enc":').string(b64(w.enc)).raw(',"ct":').string(b64(w.ct)).raw('}');
}

export function bodyJson(b: KeysBody): Uint8Array {
  const j = new CanonicalJson();
  j.raw('{"revision":').number(b.revision).raw(',"epoch":').number(b.epoch).raw(',"chain":[');
  b.chain.forEach((c, i) => {
    if (i > 0) j.raw(',');
    j.raw('{"epoch":').number(c.epoch).raw(',"nonce":').string(b64(c.nonce)).raw(',"ct":').string(b64(c.ct)).raw('}');
  });
  j.raw('],"devices":[');
  b.devices.forEach((d, i) => {
    if (i > 0) j.raw(',');
    j.raw('{"kid":')
      .string(b64(d.kid))
      .raw(',"name":')
      .string(d.name)
      .raw(',"platform":')
      .string(d.platform)
      .raw(',"publicKey":')
      .string(b64(d.publicKey))
      .raw(',"enrolledAt":')
      .number(d.enrolledAt)
      .raw(',"enrolledBy":');
    if (d.enrolledBy === null) j.raw('null');
    else j.string(b64(d.enrolledBy));
    j.raw(',"wrap":');
    wrapJson(j, d.wrap);
    j.raw('}');
  });
  j.raw('],"recovery":');
  if (b.recovery === null) j.raw('null');
  else {
    j.raw('{"kid":')
      .string(b64(b.recovery.kid))
      .raw(',"publicKey":')
      .string(b64(b.recovery.publicKey))
      .raw(',"anchorEpoch":')
      .number(b.recovery.anchorEpoch)
      .raw(',"anchor":{"nonce":')
      .string(b64(b.recovery.anchor.nonce))
      .raw(',"ct":')
      .string(b64(b.recovery.anchor.ct))
      .raw('},"wrap":');
    wrapJson(j, b.recovery.wrap);
    j.raw('}');
  }
  j.raw(',"revoked":[');
  b.revoked.forEach((r, i) => {
    if (i > 0) j.raw(',');
    j.raw('{"kid":').string(b64(r.kid)).raw(',"kind":').string(r.isRecovery ? 'recovery' : 'device').raw(',"revokedAt":').number(r.revokedAt).raw(',"revokedAtEpoch":').number(r.revokedAtEpoch).raw('}');
  });
  j.raw(']}');
  return j.bytes();
}

/** 1..64 UTF-16 units, no control characters, no unpaired surrogate (Kotlin `validNameOrNull`). */
export function validName(name: string): boolean {
  if (name.length === 0 || name.length > 64) return false;
  for (let i = 0; i < name.length; i++) {
    const c = name.charCodeAt(i);
    if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
    if (c >= 0xd800 && c <= 0xdbff) {
      const n = i + 1 < name.length ? name.charCodeAt(i + 1) : 0;
      if (!(n >= 0xdc00 && n <= 0xdfff)) return false;
      i++;
      continue;
    }
    if (c >= 0xdc00 && c <= 0xdfff) return false;
  }
  return true;
}

/** Module-private: only this file can build an `OpenedKeys` or move a pin. */
const TOKEN: unique symbol = Symbol('keys-file');

/**
 * An opened `keys.json` (Kotlin `OpenedKeys`); only `KeysFile` makes one.
 * Each folder key is a non-extractable HKDF base (S4b-BL-132). A list this object just wrote still holds the raw
 * key for the next wrap (add a device, a new epoch). A list opened from a wrap does not: `rawFolderKey` opens that
 * wrap again, and the bytes are the caller's to overwrite.
 */
export class OpenedKeys implements FolderKeys {
  private readonly held = new Map<number, CryptoKey>();
  /** Set only for a list this object just wrote. An opened list leaves this null. */
  private raw: Uint8Array | null;
  private readonly reveal: (() => Promise<Uint8Array>) | null;
  private readonly p: CryptoProvider;
  readonly body: KeysBody;

  constructor(
    token: typeof TOKEN,
    p: CryptoProvider,
    body: KeysBody,
    base: CryptoKey,
    raw: Uint8Array | null,
    reveal: (() => Promise<Uint8Array>) | null,
  ) {
    if (token !== TOKEN) throw new KeysError('INVALID_ENTRY', 'OpenedKeys is made by KeysFile only');
    this.p = p;
    this.body = body;
    this.held.set(body.epoch, base);
    this.raw = raw;
    this.reveal = reveal;
  }

  get epoch(): number {
    return this.body.epoch;
  }
  get revision(): number {
    return this.body.revision;
  }

  /** Whether this object still holds the raw folder key (a list it just wrote). An opened list does not. */
  retainsRaw(): boolean {
    return this.raw !== null;
  }

  /**
   * The raw key of a list this object just wrote. An opened list has none: use `rawFolderKey`, which opens the wrap
   * again.
   */
  currentFolderKey(): Uint8Array {
    if (!this.raw) throw new KeysError('INVALID_ENTRY', 'this folder key is held only as a non-extractable key');
    return this.raw.slice();
  }

  /** A fresh copy of the current folder key. The caller overwrites it. Not retained on an opened list. */
  async rawFolderKey(): Promise<Uint8Array> {
    if (this.raw) return this.raw.slice();
    if (!this.reveal) throw new KeysError('INVALID_ENTRY', 'no folder key');
    return this.reveal();
  }

  async keyIdAt(epoch: number = this.body.epoch): Promise<Uint8Array> {
    const base = await this.baseAt(epoch);
    if (!base) throw new KeysError('CHAIN_BROKEN', `epoch ${epoch}`);
    return deriveFolderBits(base, 'doorprints/dpx1/key-id');
  }

  async contentWrapAes(epoch: number = this.body.epoch): Promise<AesKey | null> {
    const base = await this.baseAt(epoch);
    return base ? aesFromBase(this.p, base, 'doorprints/dpx1/content-wrap') : null;
  }

  async hmacUnder(epoch: number, info: string, data: Uint8Array): Promise<Uint8Array> {
    const base = await this.baseAt(epoch);
    if (!base) throw new KeysError('CHAIN_BROKEN', `epoch ${epoch}`);
    return hmacFromBase(base, info, data);
  }

  async folderKey(epoch: number): Promise<Uint8Array | null> {
    if (!Number.isInteger(epoch) || epoch < 1 || epoch > this.body.epoch) return null;
    if (epoch === this.body.epoch) return this.rawFolderKey();
    await this.ensure(epoch);
    return this.openLink(epoch + 1);
  }

  /** Best-effort: drops the raw copy. The HKDF bases are not exportable, so there is nothing further to overwrite. */
  wipe(): void {
    this.raw?.fill(0);
    this.raw = null;
    this.held.clear();
  }

  private async baseAt(epoch: number): Promise<CryptoKey | null> {
    if (!Number.isInteger(epoch) || epoch < 1 || epoch > this.body.epoch) return null;
    await this.ensure(epoch);
    return this.held.get(epoch) ?? null;
  }

  private async ensure(epoch: number): Promise<void> {
    for (let e = this.body.epoch; e > epoch; e--) {
      if (this.held.has(e - 1)) continue;
      const prev = await this.openLink(e);
      if (prev.length !== FOLDER_KEY_SIZE) throw new KeysError('CHAIN_BROKEN', `link of epoch ${e}`);
      try {
        this.held.set(e - 1, await importFolderBase(prev));
      } finally {
        prev.fill(0);
      }
    }
  }

  private async openLink(epoch: number): Promise<Uint8Array> {
    const parent = this.held.get(epoch);
    const link = this.body.chain.find((c) => c.epoch === epoch);
    if (!parent || !link) throw new KeysError('CHAIN_BROKEN', `no link for epoch ${epoch}`);
    let prev: Uint8Array;
    try {
      prev = await this.p.aesGcmOpen(await aesFromBase(this.p, parent, 'doorprints/dpx1/chain-wrap'), link.nonce, WrapAad.chain(epoch), link.ct);
    } catch {
      throw new KeysError('CHAIN_BROKEN', `link of epoch ${epoch}`);
    }
    if (prev.length !== FOLDER_KEY_SIZE) throw new KeysError('CHAIN_BROKEN', `link of epoch ${epoch}`);
    return prev;
  }
}

export interface NewDevice {
  publicKey: Uint8Array;
  name: string;
  platform: DevicePlatform;
}

export interface WrittenKeys {
  bytes: Uint8Array;
  opened: OpenedKeys;
}

/**
 * `keys.json` (docs/15 §9.3..§9.5, §9.9), byte for byte the twin of Kotlin's `KeysFile` (the layout is in its
 * comment). No I/O. A list is trusted by the device's pin (`KeysGuard`), not by its MAC: HPKE base mode does not say
 * who wrapped a key. Writing: re-read the head before the change and after the upload, then `KeysGuard.acceptWritten`
 * (two writers at once are a FORK_DETECTED everywhere). Never write data under a folder key not yet confirmed by that
 * read-back.
 */
export class KeysFile {
  private readonly hpke: Hpke;

  constructor(private readonly p: CryptoProvider) {
    this.hpke = new Hpke(p);
  }

  /** The first connect to an empty folder; after the upload: `KeysGuard.pinCreated`. */
  async createFirstDevice(device: NewDevice, recovery: RecoveryKey | null, now: number): Promise<WrittenKeys> {
    checkTime(now);
    const folderKey = this.p.randomBytes(FOLDER_KEY_SIZE);
    try {
      const pub = this.validPublic(device.publicKey);
      const kid = kidOf(this.p, pub);
      if (!validName(device.name)) throw new KeysError('INVALID_ENTRY', 'device name');
      const entry: DeviceEntry = { kid, name: device.name, platform: device.platform, publicKey: pub, enrolledAt: now, enrolledBy: null, wrap: await this.wrap(pub, kid, 1, folderKey) };
      const rec = recovery ? await this.newRecoveryEntry(recovery, 1, folderKey) : null;
      if (rec && equalBytes(rec.kid, kid)) throw new KeysError('INVALID_ENTRY', 'recovery key equals the device key');
      return await this.write({ revision: 1, epoch: 1, chain: [], devices: [entry], recovery: rec, revoked: [] }, folderKey);
    } finally {
      folderKey.fill(0);
    }
  }

  /** Opens with this device's key: MAC, then the pin; no pin is NOT_PINNED. */
  open(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard): Promise<OpenedKeys> {
    return this.openDevice(file, device, guard, null);
  }

  /**
   * This device uploaded the list and was reloaded before `pinCreated`. The device key opens its own wrap and,
   * when there is no pin yet, this makes that pin (`CREATED`). A list this device is not in throws `NOT_ENROLLED`.
   */
  finishOwnCreate(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard): Promise<OpenedKeys> {
    return this.openDevice(file, device, guard, null, 'CREATED');
  }

  /**
   * The first pin (S4b-BL-126): opens only if the folder key equals `trustedFolderKey`, received over an authenticated
   * channel (the QR code's HPKE PSK wrap), then pins it. Never call it with a key read from Drive.
   */
  openFirstPin(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard, trustedFolderKey: Uint8Array): Promise<OpenedKeys> {
    return this.openDevice(file, device, guard, trustedFolderKey, 'FIRST_PIN');
  }

  /**
   * Repin (S4b-BL-126): a device on the losing side of a fork replaces its pin, whatever its order, only on a stronger
   * proof: the folder key received again over the QR/PSK enrolment. Only on the person's action, never on an error.
   */
  repinFirstPin(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard, trustedFolderKey: Uint8Array): Promise<OpenedKeys> {
    return this.openDevice(file, device, guard, trustedFolderKey, 'REPIN');
  }

  private async openDevice(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard, trusted: Uint8Array | null, trust: KeysTrust = 'PINNED'): Promise<OpenedKeys> {
    const { body, mac } = this.parse(file);
    const pub = device.publicKey;
    const kid = kidOf(this.p, pub);
    if (body.revoked.some((r) => equalBytes(r.kid, kid))) throw new KeysError('REVOKED', 'this device is in the revoked list');
    const entry = body.devices.find((d) => equalBytes(d.kid, kid) && equalBytes(d.publicKey, pub));
    if (!entry) throw new KeysError('NOT_ENROLLED', 'this device is not in the list');
    const folderKey = await this.unwrap(entry.wrap, device, body.epoch, kid);
    try {
      const base = await importFolderBase(folderKey);
      await this.checkMacBase(body, mac, base);
      if (trusted && !constantTimeEquals(folderKey, trusted)) throw new KeysError('PIN_MISMATCH', 'not the folder key received at enrolment');
      const opened = new OpenedKeys(TOKEN, this.p, body, base, null, () => this.unwrap(entry.wrap, device, body.epoch, kid));
      await guard.accept(TOKEN, opened, trust);
      return opened;
    } finally {
      folderKey.fill(0);
    }
  }

  /** The wrap, the MAC, then the anchor: the chain down to the anchor's epoch must end at the key it holds. */
  openWithRecovery(file: Uint8Array, recovery: RecoveryKey, guard: KeysGuard): Promise<OpenedKeys> {
    return this.recoveryOpen(file, recovery, guard, 'RECOVERY_ANCHOR');
  }

  /** Repin with the current recovery key's anchor (an old key, shown before a revoke, is RR-29). */
  repinWithRecovery(file: Uint8Array, recovery: RecoveryKey, guard: KeysGuard): Promise<OpenedKeys> {
    return this.recoveryOpen(file, recovery, guard, 'REPIN');
  }

  private async recoveryOpen(file: Uint8Array, recovery: RecoveryKey, guard: KeysGuard, trust: KeysTrust): Promise<OpenedKeys> {
    const { body, mac } = this.parse(file);
    const listed = body.recovery;
    if (!listed) throw new KeysError('NO_RECOVERY', 'no recovery key in the list');
    const pair = await recovery.keyPair(this.p);
    if (!equalBytes(listed.publicKey, pair.publicKey)) throw new KeysError('RECOVERY_MISMATCH', 'another recovery key');
    const folderKey = await this.unwrap(listed.wrap, pair, body.epoch, listed.kid);
    try {
      const base = await importFolderBase(folderKey);
      await this.checkMacBase(body, mac, base);
      const opened = new OpenedKeys(TOKEN, this.p, body, base, null, () => this.unwrap(listed.wrap, pair, body.epoch, listed.kid));
      let bottom: Uint8Array | null = null;
      try {
        bottom = await opened.folderKey(listed.anchorEpoch);
      } catch (e) {
        if (!(e instanceof KeysError)) throw e;
      }
      if (!bottom) throw new KeysError('RECOVERY_ANCHOR_INVALID', 'the chain does not reach the anchor');
      let anchored: Uint8Array;
      try {
        anchored = await this.p.aesGcmOpen(await recovery.anchorKey(this.p), listed.anchor.nonce, WrapAad.recoveryAnchor(listed.anchorEpoch, listed.kid), listed.anchor.ct);
      } catch (e) {
        if (e instanceof CryptoError) throw new KeysError('RECOVERY_ANCHOR_INVALID', 'the anchor does not open');
        throw e;
      }
      const same = constantTimeEquals(anchored, bottom);
      anchored.fill(0);
      bottom.fill(0);
      if (!same) throw new KeysError('RECOVERY_ANCHOR_INVALID', 'the chain does not end at the anchored key');
      await guard.accept(TOKEN, opened, trust);
      return opened;
    } finally {
      folderKey.fill(0);
    }
  }

  async addDevice(opened: OpenedKeys, approverKid: Uint8Array, device: NewDevice, now: number): Promise<WrittenKeys> {
    checkTime(now);
    const body = opened.body;
    const isRecovery = (k: Uint8Array) => body.recovery !== null && equalBytes(body.recovery.kid, k);
    if (!body.devices.some((d) => equalBytes(d.kid, approverKid)) && !isRecovery(approverKid)) throw new KeysError('NOT_LISTED', 'the approver is not in the list');
    const pub = this.validPublic(device.publicKey);
    const kid = kidOf(this.p, pub);
    if (body.revoked.some((r) => equalBytes(r.kid, kid))) throw new KeysError('REVOKED', 'a revoked key cannot be listed again');
    if (body.devices.some((d) => equalBytes(d.kid, kid)) || isRecovery(kid)) throw new KeysError('ALREADY_ENROLLED', 'already listed');
    if (!validName(device.name)) throw new KeysError('INVALID_ENTRY', 'device name');
    const revision = nextRevision(body);
    const folderKey = await opened.rawFolderKey();
    try {
      const entry: DeviceEntry = {
        kid,
        name: device.name,
        platform: device.platform,
        publicKey: pub,
        enrolledAt: now,
        enrolledBy: approverKid.slice(),
        wrap: await this.wrap(pub, kid, body.epoch, folderKey),
      };
      return await this.write({ ...body, revision, devices: [...body.devices, entry] }, folderKey);
    } finally {
      folderKey.fill(0);
    }
  }

  /**
   * A new epoch: revoke a device and/or replace the recovery key (`newRecovery`: a new anchor at the new epoch, the old
   * recovery kid joins the revoked list). A revoke needs a new recovery key when the folder has one
   * (NEW_RECOVERY_REQUIRED; the revoked device could forge lists the old key accepts); the screen shows it once. A new
   * epoch starts at revision 1, so a poisoned revision never blocks a revoke.
   */
  async newEpoch(opened: OpenedKeys, now: number, opts: { revokeKid?: Uint8Array; newRecovery?: RecoveryKey } = {}): Promise<WrittenKeys> {
    checkTime(now);
    const body = opened.body;
    const { revokeKid, newRecovery } = opts;
    if (body.epoch === MAX_EPOCH) throw new KeysError('REVISION_LIMIT', 'epoch limit');
    if (revokeKid && !body.devices.some((d) => equalBytes(d.kid, revokeKid))) throw new KeysError('NOT_LISTED', 'no such device');
    const old = body.recovery;
    if (revokeKid && old && !newRecovery) throw new KeysError('NEW_RECOVERY_REQUIRED', 'a revoke issues a new recovery key');
    if (newRecovery && old && equalBytes(kidOf(this.p, (await newRecovery.keyPair(this.p)).publicKey), old.kid)) {
      throw new KeysError('NEW_RECOVERY_REQUIRED', 'the same recovery key');
    }
    const revision = 1;
    const remaining = body.devices.filter((d) => !revokeKid || !equalBytes(d.kid, revokeKid));
    const epoch = body.epoch + 1;
    const oldKey = await opened.rawFolderKey();
    const newKey = this.p.randomBytes(FOLDER_KEY_SIZE);
    try {
      const chainNonce = this.p.randomBytes(12);
      const link: ChainLink = { epoch, nonce: chainNonce, ct: await this.p.aesGcmSeal(await chainWrapKey(this.p, newKey), chainNonce, WrapAad.chain(epoch), oldKey) };
      const devices: DeviceEntry[] = [];
      for (const d of remaining) devices.push({ ...d, wrap: await this.wrap(d.publicKey, d.kid, epoch, newKey) });
      let recovery: RecoveryEntry | null = null;
      if (newRecovery) recovery = await this.newRecoveryEntry(newRecovery, epoch, newKey);
      else if (old) recovery = { ...old, wrap: await this.wrap(old.publicKey, old.kid, epoch, newKey) };
      const rec = recovery;
      if (rec && (devices.some((d) => equalBytes(d.kid, rec.kid)) || body.revoked.some((r) => equalBytes(r.kid, rec.kid)))) {
        throw new KeysError('INVALID_ENTRY', 'recovery key equals a device key or a revoked one');
      }
      if (devices.length === 0 && !rec) throw new KeysError('LAST_RECIPIENT', 'nobody could open the folder');
      let revoked = body.revoked;
      if (revokeKid) revoked = [...revoked, { kid: revokeKid.slice(), isRecovery: false, revokedAt: now, revokedAtEpoch: epoch }];
      if (newRecovery && old) revoked = [...revoked, { kid: old.kid, isRecovery: true, revokedAt: now, revokedAtEpoch: epoch }];
      return await this.write({ revision, epoch, chain: [...body.chain, link], devices, recovery: rec, revoked }, newKey);
    } finally {
      oldKey.fill(0);
      newKey.fill(0);
    }
  }

  /** The anchor first, then the wrap (the order of random draws, as Kotlin). */
  private async newRecoveryEntry(recovery: RecoveryKey, epoch: number, folderKey: Uint8Array): Promise<RecoveryEntry> {
    const pub = this.validPublic((await recovery.keyPair(this.p)).publicKey);
    const kid = kidOf(this.p, pub);
    const nonce = this.p.randomBytes(12);
    const ct = await this.p.aesGcmSeal(await recovery.anchorKey(this.p), nonce, WrapAad.recoveryAnchor(epoch, kid), folderKey);
    return { kid, publicKey: pub, anchorEpoch: epoch, anchor: { nonce, ct }, wrap: await this.wrap(pub, kid, epoch, folderKey) };
  }

  private async wrap(pub: Uint8Array, kid: Uint8Array, epoch: number, folderKey: Uint8Array): Promise<HpkeWrap> {
    const s = await this.hpke.seal(pub, HPKE_INFO, WrapAad.folderKey(epoch, kid), folderKey);
    return { enc: s.enc, ct: s.ciphertext };
  }

  private async unwrap(w: HpkeWrap, key: P256PrivateKey, epoch: number, kid: Uint8Array): Promise<Uint8Array> {
    let folderKey: Uint8Array;
    try {
      folderKey = await this.hpke.open(w.enc, key, HPKE_INFO, WrapAad.folderKey(epoch, kid), w.ct);
    } catch (e) {
      if (e instanceof CryptoError) throw new KeysError('UNWRAP_FAILED', 'wrap did not open');
      throw e;
    }
    if (folderKey.length !== FOLDER_KEY_SIZE) throw new KeysError('UNWRAP_FAILED', 'wrap length');
    return folderKey;
  }

  private async checkMacBase(body: KeysBody, mac: Uint8Array, base: CryptoKey): Promise<void> {
    if (!constantTimeEquals(await this.macBase(base, body), mac)) throw new KeysError('MAC_INVALID', 'MAC');
  }

  private macBase(base: CryptoKey, body: KeysBody): Promise<Uint8Array> {
    return hmacFromBase(base, 'doorprints/dpx1/dir', concat(utf8(KEYS_FORMAT), new Uint8Array([0]), bodyJson(body)));
  }

  private async write(body: KeysBody, folderKey: Uint8Array): Promise<WrittenKeys> {
    this.checkRules(body);
    const base = await importFolderBase(folderKey);
    const bytes = encode(body, await this.macBase(base, body));
    if (bytes.length > MAX_FILE) throw new KeysError('TOO_LARGE', `the list would be ${bytes.length} bytes`);
    return { bytes, opened: new OpenedKeys(TOKEN, this.p, body, base, folderKey.slice(), null) };
  }

  /** @internal Parses and checks the structure and every entry; the MAC is checked once the key is known. */
  parse(file: Uint8Array): { body: KeysBody; mac: Uint8Array } {
    if (file.length > MAX_FILE) throw new KeysError('MALFORMED', 'too large');
    const root = parseJson(file);
    if (!isObj(root)) throw new KeysError('MALFORMED', 'not a JSON object');
    const format = str(root['format']) ?? malformed('format');
    if (format !== KEYS_FORMAT) throw new KeysError('UNSUPPORTED_FORMAT', 'format');
    exactObj(root, 'format', 'body', 'mac') ?? malformed('fields');
    const mac = b64of(root['mac'], 32, 'mac');
    const body = parseBody(root['body']);
    if (!equalBytes(encode(body, mac), file)) throw new KeysError('NOT_CANONICAL', 'not canonical');
    this.checkRules(body);
    return { body, mac };
  }

  private checkRules(b: KeysBody): void {
    const bad = (why: string): never => {
      throw new KeysError('INVALID_ENTRY', why);
    };
    if (b.chain.length !== b.epoch - 1) bad('chain length');
    b.chain.forEach((c, i) => {
      if (c.epoch !== i + 2) bad('chain order');
    });
    if (b.devices.length === 0 && !b.recovery) bad('no recipient');
    if (b.devices.length > MAX_DEVICES || b.revoked.length > MAX_REVOKED) bad('too many entries');
    const kids: Uint8Array[] = [];
    for (const d of b.devices) {
      if (!equalBytes(d.kid, kidOf(this.p, this.validPublic(d.publicKey)))) bad('device kid');
      if (!validName(d.name)) bad('device name');
      if (d.enrolledBy && equalBytes(d.enrolledBy, d.kid)) bad('enrolled by itself');
      kids.push(d.kid);
    }
    if (b.recovery) {
      if (!equalBytes(b.recovery.kid, kidOf(this.p, this.validPublic(b.recovery.publicKey)))) bad('recovery kid');
      if (b.recovery.anchorEpoch > b.epoch) bad('recovery anchor in a future epoch');
      kids.push(b.recovery.kid);
    }
    for (const r of b.revoked) {
      if (r.revokedAtEpoch > b.epoch) bad('revoked in a future epoch');
      kids.push(r.kid);
    }
    for (let i = 0; i < kids.length; i++) for (let j = i + 1; j < kids.length; j++) if (equalBytes(kids[i], kids[j])) bad('duplicate kid');
    // enrolledBy names a listed device, the recovery key or a revoked kid.
    for (const d of b.devices) if (d.enrolledBy && !kids.some((k) => equalBytes(k, d.enrolledBy!))) bad('enrolled by an unknown kid');
  }

  private validPublic(pub: Uint8Array): Uint8Array {
    try {
      return this.p.p256ValidatePublic(pub);
    } catch {
      throw new KeysError('INVALID_ENTRY', 'public key');
    }
  }
}

function malformed(what: string): never {
  throw new KeysError('MALFORMED', what);
}

function b64of(v: unknown, size: number, what: string): Uint8Array {
  const s = str(v);
  return (s === null ? null : unb64(s, size)) ?? malformed(what);
}

function encode(body: KeysBody, mac: Uint8Array): Uint8Array {
  return concat(
    new CanonicalJson().raw('{"format":').string(KEYS_FORMAT).raw(',"body":').bytes(),
    bodyJson(body),
    new CanonicalJson().raw(',"mac":').string(b64(mac)).raw('}').bytes(),
  );
}

function hpkeWrap(v: unknown): HpkeWrap {
  const o = exactObj(v, 'enc', 'ct') ?? malformed('wrap');
  return { enc: b64of(o['enc'], 65, 'wrap.enc'), ct: b64of(o['ct'], 48, 'wrap.ct') };
}

function array(v: unknown, what: string): unknown[] {
  return Array.isArray(v) ? v : malformed(what);
}

function parseBody(v: unknown): KeysBody {
  const o = exactObj(v, 'revision', 'epoch', 'chain', 'devices', 'recovery', 'revoked') ?? malformed('body');
  const revision = int(o['revision'], 1, MAX_SAFE) ?? malformed('revision');
  const epoch = int(o['epoch'], 1, MAX_EPOCH) ?? malformed('epoch');
  const chain = array(o['chain'], 'chain').map((x) => {
    const c = exactObj(x, 'epoch', 'nonce', 'ct') ?? malformed('chain entry');
    return { epoch: int(c['epoch'], 2, MAX_EPOCH) ?? malformed('chain.epoch'), nonce: b64of(c['nonce'], 12, 'chain.nonce'), ct: b64of(c['ct'], 48, 'chain.ct') };
  });
  const devices = array(o['devices'], 'devices').map((x): DeviceEntry => {
    const d = exactObj(x, 'kid', 'name', 'platform', 'publicKey', 'enrolledAt', 'enrolledBy', 'wrap') ?? malformed('device');
    const platform = PLATFORMS.find((p) => p === d['platform']) ?? malformed('platform');
    return {
      kid: b64of(d['kid'], 16, 'kid'),
      name: str(d['name']) ?? malformed('name'),
      platform,
      publicKey: b64of(d['publicKey'], 65, 'publicKey'),
      enrolledAt: int(d['enrolledAt'], 0, MAX_SAFE) ?? malformed('enrolledAt'),
      enrolledBy: d['enrolledBy'] === null ? null : b64of(d['enrolledBy'], 16, 'enrolledBy'),
      wrap: hpkeWrap(d['wrap']),
    };
  });
  let recovery: RecoveryEntry | null = null;
  if (o['recovery'] !== null) {
    const r = exactObj(o['recovery'], 'kid', 'publicKey', 'anchorEpoch', 'anchor', 'wrap') ?? malformed('recovery');
    const a = exactObj(r['anchor'], 'nonce', 'ct') ?? malformed('recovery.anchor');
    recovery = {
      kid: b64of(r['kid'], 16, 'recovery.kid'),
      publicKey: b64of(r['publicKey'], 65, 'recovery.publicKey'),
      anchorEpoch: int(r['anchorEpoch'], 1, MAX_EPOCH) ?? malformed('recovery.anchorEpoch'),
      anchor: { nonce: b64of(a['nonce'], 12, 'recovery.anchor.nonce'), ct: b64of(a['ct'], 48, 'recovery.anchor.ct') },
      wrap: hpkeWrap(r['wrap']),
    };
  }
  const revoked = array(o['revoked'], 'revoked').map((x) => {
    const r = exactObj(x, 'kid', 'kind', 'revokedAt', 'revokedAtEpoch') ?? malformed('revoked entry');
    const kind = r['kind'] === 'device' || r['kind'] === 'recovery' ? r['kind'] : malformed('revoked.kind');
    return {
      kid: b64of(r['kid'], 16, 'revoked.kid'),
      isRecovery: kind === 'recovery',
      revokedAt: int(r['revokedAt'], 0, MAX_SAFE) ?? malformed('revokedAt'),
      revokedAtEpoch: int(r['revokedAtEpoch'], 2, MAX_EPOCH) ?? malformed('revokedAtEpoch'),
    };
  });
  return { revision, epoch, chain, devices, recovery, revoked };
}

function nextRevision(body: KeysBody): number {
  if (body.revision >= MAX_SAFE) throw new KeysError('REVISION_LIMIT', 'revision limit');
  return body.revision + 1;
}

function checkTime(now: number): void {
  if (!Number.isSafeInteger(now) || now < 0) throw new KeysError('INVALID_ENTRY', 'time');
}

/**
 * What a device has accepted of one folder's `keys.json` (Kotlin `KeysWatermark`): the highest (epoch, revision),
 * the pin of that epoch's folder key (`keyId`, HKDF "doorprints/dpx1/key-id") and the SHA-256 of that revision's body.
 */
export interface KeysWatermark {
  epoch: number;
  revision: number;
  keyId: Uint8Array;
  bodyHash: Uint8Array;
}

/** Where a device keeps its watermark (on the website: IndexedDB, with S4b-BL-126). `compareAndSet` must be atomic. */
export interface KeysWatermarkStore {
  load(): Promise<KeysWatermark | null>;
  compareAndSet(expected: KeysWatermark | null, next: KeysWatermark): Promise<boolean>;
}

export function sameWatermark(a: KeysWatermark | null, b: KeysWatermark | null): boolean {
  if (!a || !b) return a === b;
  return a.epoch === b.epoch && a.revision === b.revision && equalBytes(a.keyId, b.keyId) && equalBytes(a.bodyHash, b.bodyHash);
}

/** How a list was proven: by the pin, one of the three named first-pin paths, or a repin. */
type KeysTrust = 'PINNED' | 'FIRST_PIN' | 'RECOVERY_ANCHOR' | 'CREATED' | 'REPIN';
export type KeysOrder = 'LOWER' | 'SAME_EPOCH' | 'HIGHER_EPOCH';

/** (epoch, revision) ordered lexicographically: a higher epoch always wins, a lower one never does. */
export function keysOrder(seenEpoch: number, seenRevision: number, epoch: number, revision: number): KeysOrder {
  if (epoch < seenEpoch) return 'LOWER';
  if (epoch > seenEpoch) return 'HIGHER_EPOCH';
  return revision < seenRevision ? 'LOWER' : 'SAME_EPOCH';
}

function higher(next: KeysWatermark, seen: KeysWatermark): boolean {
  return next.epoch > seen.epoch || (next.epoch === seen.epoch && next.revision > seen.revision);
}

const MAX_TRIES = 4;
/** The largest revision step within one epoch a device accepts (Kotlin `KeysGuard.MAX_REVISION_STEP`). */
export const MAX_REVISION_STEP = 1024;

/**
 * The trust anchor of a device in one folder, the twin of Kotlin's `KeysGuard` (docs/15 §9.9): a list is accepted
 * only if it leads to the pinned folder key (same epoch: same key id, and the same revision means the same body;
 * higher epoch: the whole chain down to the pinned epoch ends at the pinned key; lower epoch: rolled back). No pin:
 * only `KeysFile.openFirstPin`, `KeysFile.openWithRecovery` and `pinCreated` may make one.
 */
export class KeysGuard {
  constructor(
    private readonly p: CryptoProvider,
    private readonly store: KeysWatermarkStore,
  ) {}

  watermark(): Promise<KeysWatermark | null> {
    return this.store.load();
  }

  /** After this device's own write was confirmed by Drive. */
  acceptWritten(written: WrittenKeys): Promise<void> {
    return this.accept(TOKEN, written.opened, 'PINNED');
  }

  /** After `createFirstDevice`'s list was confirmed by Drive: the first pin, only if there is none. */
  pinCreated(written: WrittenKeys): Promise<void> {
    return this.accept(TOKEN, written.opened, 'CREATED');
  }

  /** Only this module may call it (`token`), so app code cannot pin arbitrary state. */
  async accept(token: typeof TOKEN, opened: OpenedKeys, trust: KeysTrust): Promise<void> {
    if (token !== TOKEN) throw new KeysError('INVALID_ENTRY', 'not callable from outside keys-file.ts');
    const next = await watermarkOf(this.p, opened);
    for (let i = 0; i < MAX_TRIES; i++) {
      const seen = await this.store.load();
      if (trust === 'REPIN') {
        if (sameWatermark(seen, next)) return;
        if (await this.store.compareAndSet(seen, next)) return;
        continue;
      }
      if (!seen) {
        if (trust === 'PINNED') throw new KeysError('NOT_PINNED', 'no pin for this folder yet');
      } else {
        await this.verify(seen, opened, next);
        if (!higher(next, seen)) return;
      }
      if (await this.store.compareAndSet(seen, next)) return;
    }
    throw new KeysError('CONCURRENT_UPDATE', 'the watermark kept changing');
  }

  private async verify(seen: KeysWatermark, opened: OpenedKeys, next: KeysWatermark): Promise<void> {
    const order = keysOrder(seen.epoch, seen.revision, next.epoch, next.revision);
    if (order === 'LOWER') {
      throw new KeysError('ROLLED_BACK', `epoch ${next.epoch} revision ${next.revision} is older than epoch ${seen.epoch} revision ${seen.revision}`);
    }
    if (order === 'SAME_EPOCH') {
      if (!constantTimeEquals(next.keyId, seen.keyId)) throw new KeysError('FORK_DETECTED', `another folder key for epoch ${next.epoch}`);
      if (next.revision - seen.revision > MAX_REVISION_STEP) throw new KeysError('REVISION_JUMP', `revision ${next.revision} is far above ${seen.revision}`);
      if (next.revision === seen.revision && !equalBytes(next.bodyHash, seen.bodyHash)) throw new KeysError('FORK_DETECTED', `another list at revision ${next.revision}`);
      return;
    }
    let id: Uint8Array;
    try {
      id = await opened.keyIdAt(seen.epoch);
    } catch (e) {
      if (e instanceof KeysError) throw new KeysError('PIN_MISMATCH', `the chain does not reach epoch ${seen.epoch}`);
      throw e;
    }
    if (!constantTimeEquals(id, seen.keyId)) throw new KeysError('PIN_MISMATCH', 'the chain does not end at the pinned key');
  }
}

async function watermarkOf(p: CryptoProvider, opened: OpenedKeys): Promise<KeysWatermark> {
  const id = await opened.keyIdAt();
  return { epoch: opened.epoch, revision: opened.revision, keyId: id, bodyHash: sha256Of(p, bodyJson(opened.body)) };
}

