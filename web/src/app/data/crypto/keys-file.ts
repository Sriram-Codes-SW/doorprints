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
import { CryptoError } from './crypto-provider';
import type { CryptoProvider, P256PrivateKey } from './crypto-provider';
import { chainWrapKey, FOLDER_KEY_SIZE, HPKE_INFO, kidOf, macKey, WrapAad } from './folder-key';
import type { FolderKeys } from './folder-key';
import { Hpke } from './hpke';
import type { KeysGuard } from './keys-guard';
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
  | 'ROLLED_BACK'
  | 'WATERMARK_CONFLICT'
  | 'NO_RECOVERY'
  | 'RECOVERY_MISMATCH'
  | 'CHAIN_BROKEN'
  | 'ALREADY_ENROLLED'
  | 'NOT_LISTED'
  | 'LAST_RECIPIENT';

/** The kinds of Kotlin's `KeysException`. */
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
export interface RecoveryEntry {
  kid: Uint8Array;
  publicKey: Uint8Array;
  wrap: HpkeWrap;
}
export interface RevokedEntry {
  kid: Uint8Array;
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
    j.raw('{"kid":').string(b64(b.recovery.kid)).raw(',"publicKey":').string(b64(b.recovery.publicKey)).raw(',"wrap":');
    wrapJson(j, b.recovery.wrap);
    j.raw('}');
  }
  j.raw(',"revoked":[');
  b.revoked.forEach((r, i) => {
    if (i > 0) j.raw(',');
    j.raw('{"kid":').string(b64(r.kid)).raw(',"revokedAt":').number(r.revokedAt).raw(',"revokedAtEpoch":').number(r.revokedAtEpoch).raw('}');
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

/** An opened `keys.json` and the folder keys it chains to (Kotlin `OpenedKeys`). */
export class OpenedKeys implements FolderKeys {
  private readonly keys = new Map<number, Uint8Array>();

  constructor(
    private readonly p: CryptoProvider,
    readonly body: KeysBody,
    currentKey: Uint8Array,
  ) {
    this.keys.set(body.epoch, currentKey.slice());
  }

  get epoch(): number {
    return this.body.epoch;
  }
  get revision(): number {
    return this.body.revision;
  }

  currentFolderKey(): Uint8Array {
    return this.keys.get(this.body.epoch)!.slice();
  }

  async folderKey(epoch: number): Promise<Uint8Array | null> {
    if (!Number.isInteger(epoch) || epoch < 1 || epoch > this.body.epoch) return null;
    for (let e = this.body.epoch; e > epoch; e--) {
      if (this.keys.has(e - 1)) continue;
      const link = this.body.chain.find((c) => c.epoch === e);
      if (!link) throw new KeysError('CHAIN_BROKEN', `no link for epoch ${e}`);
      let prev: Uint8Array;
      try {
        prev = await this.p.aesGcmOpen(await chainWrapKey(this.p, this.keys.get(e)!), link.nonce, WrapAad.chain(e), link.ct);
      } catch {
        throw new KeysError('CHAIN_BROKEN', `link of epoch ${e}`);
      }
      if (prev.length !== FOLDER_KEY_SIZE) throw new KeysError('CHAIN_BROKEN', `link of epoch ${e}`);
      this.keys.set(e - 1, prev);
    }
    return this.keys.get(epoch)!.slice();
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
 * `keys.json` (docs/15 §9.3..§9.5), byte for byte the twin of Kotlin's `KeysFile` (the layout is in its comment). No
 * I/O: after a confirmed upload of a written list, the caller calls `KeysGuard.accept` with its epoch and revision.
 */
export class KeysFile {
  private readonly hpke: Hpke;

  constructor(private readonly p: CryptoProvider) {
    this.hpke = new Hpke(p);
  }

  async createFirstDevice(device: NewDevice, recoveryPublicKey: Uint8Array | null, now: number): Promise<WrittenKeys> {
    checkTime(now);
    const folderKey = this.p.randomBytes(FOLDER_KEY_SIZE);
    const pub = this.validPublic(device.publicKey);
    const kid = kidOf(this.p, pub);
    if (!validName(device.name)) throw new KeysError('INVALID_ENTRY', 'device name');
    const entry: DeviceEntry = { kid, name: device.name, platform: device.platform, publicKey: pub, enrolledAt: now, enrolledBy: null, wrap: await this.wrap(pub, kid, 1, folderKey) };
    const recovery = recoveryPublicKey ? await this.recoveryEntry(recoveryPublicKey, 1, folderKey) : null;
    if (recovery && equalBytes(recovery.kid, kid)) throw new KeysError('INVALID_ENTRY', 'recovery key equals the device key');
    return this.write({ revision: 1, epoch: 1, chain: [], devices: [entry], recovery, revoked: [] }, folderKey);
  }

  async open(file: Uint8Array, device: P256PrivateKey, guard: KeysGuard): Promise<OpenedKeys> {
    const { body, mac } = this.parse(file);
    const pub = device.publicKey;
    const kid = kidOf(this.p, pub);
    if (body.revoked.some((r) => equalBytes(r.kid, kid))) throw new KeysError('REVOKED', 'this device was revoked');
    const entry = body.devices.find((d) => equalBytes(d.kid, kid) && equalBytes(d.publicKey, pub));
    if (!entry) throw new KeysError('NOT_ENROLLED', 'this device is not in the list');
    const folderKey = await this.unwrap(entry.wrap, device, body.epoch, kid);
    return this.finishOpen(body, mac, folderKey, guard);
  }

  async openWithRecovery(file: Uint8Array, recovery: RecoveryKey, guard: KeysGuard): Promise<OpenedKeys> {
    const { body, mac } = this.parse(file);
    const listed = body.recovery;
    if (!listed) throw new KeysError('NO_RECOVERY', 'no recovery key in the list');
    const pair = await recovery.keyPair(this.p);
    if (!equalBytes(listed.publicKey, pair.publicKey)) throw new KeysError('RECOVERY_MISMATCH', 'another recovery key');
    const folderKey = await this.unwrap(listed.wrap, pair, body.epoch, listed.kid);
    return this.finishOpen(body, mac, folderKey, guard);
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
    const folderKey = opened.currentFolderKey();
    const entry: DeviceEntry = {
      kid,
      name: device.name,
      platform: device.platform,
      publicKey: pub,
      enrolledAt: now,
      enrolledBy: approverKid.slice(),
      wrap: await this.wrap(pub, kid, body.epoch, folderKey),
    };
    return this.write({ ...body, revision: nextRevision(body), devices: [...body.devices, entry] }, folderKey);
  }

  async newEpoch(opened: OpenedKeys, now: number, opts: { revokeKid?: Uint8Array; newRecoveryPublicKey?: Uint8Array } = {}): Promise<WrittenKeys> {
    checkTime(now);
    const body = opened.body;
    const { revokeKid, newRecoveryPublicKey } = opts;
    if (body.epoch === MAX_EPOCH) throw new KeysError('INVALID_ENTRY', 'epoch limit');
    if (revokeKid && !body.devices.some((d) => equalBytes(d.kid, revokeKid))) throw new KeysError('NOT_LISTED', 'no such device');
    const remaining = body.devices.filter((d) => !revokeKid || !equalBytes(d.kid, revokeKid));
    const epoch = body.epoch + 1;
    const oldKey = opened.currentFolderKey();
    const newKey = this.p.randomBytes(FOLDER_KEY_SIZE);
    const chainNonce = this.p.randomBytes(12);
    const link: ChainLink = { epoch, nonce: chainNonce, ct: await this.p.aesGcmSeal(await chainWrapKey(this.p, newKey), chainNonce, WrapAad.chain(epoch), oldKey) };
    const devices: DeviceEntry[] = [];
    for (const d of remaining) devices.push({ ...d, wrap: await this.wrap(d.publicKey, d.kid, epoch, newKey) });
    const recoveryPub = newRecoveryPublicKey ?? body.recovery?.publicKey;
    const recovery = recoveryPub ? await this.recoveryEntry(recoveryPub, epoch, newKey) : null;
    if (recovery && (devices.some((d) => equalBytes(d.kid, recovery.kid)) || body.revoked.some((r) => equalBytes(r.kid, recovery.kid)))) {
      throw new KeysError('INVALID_ENTRY', 'recovery key equals a device key');
    }
    if (devices.length === 0 && !recovery) throw new KeysError('LAST_RECIPIENT', 'nobody could open the folder');
    const revoked = revokeKid ? [...body.revoked, { kid: revokeKid.slice(), revokedAt: now, revokedAtEpoch: epoch }] : body.revoked;
    return this.write({ revision: nextRevision(body), epoch, chain: [...body.chain, link], devices, recovery, revoked }, newKey);
  }

  private async recoveryEntry(publicKey: Uint8Array, epoch: number, folderKey: Uint8Array): Promise<RecoveryEntry> {
    const pub = this.validPublic(publicKey);
    const kid = kidOf(this.p, pub);
    return { kid, publicKey: pub, wrap: await this.wrap(pub, kid, epoch, folderKey) };
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

  private async finishOpen(body: KeysBody, mac: Uint8Array, folderKey: Uint8Array, guard: KeysGuard): Promise<OpenedKeys> {
    if (!constantTimeEquals(await this.mac(folderKey, body), mac)) throw new KeysError('MAC_INVALID', 'MAC');
    await guard.accept(body.epoch, body.revision);
    return new OpenedKeys(this.p, body, folderKey);
  }

  private async mac(folderKey: Uint8Array, body: KeysBody): Promise<Uint8Array> {
    return this.p.hmacSha256(await macKey(this.p, folderKey), concat(utf8(KEYS_FORMAT), new Uint8Array([0]), bodyJson(body)));
  }

  private async write(body: KeysBody, folderKey: Uint8Array): Promise<WrittenKeys> {
    return { bytes: encode(body, await this.mac(folderKey, body)), opened: new OpenedKeys(this.p, body, folderKey) };
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
      kids.push(b.recovery.kid);
    }
    for (const r of b.revoked) {
      if (r.revokedAtEpoch > b.epoch) bad('revoked in a future epoch');
      kids.push(r.kid);
    }
    for (let i = 0; i < kids.length; i++) for (let j = i + 1; j < kids.length; j++) if (equalBytes(kids[i], kids[j])) bad('duplicate kid');
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
    const r = exactObj(o['recovery'], 'kid', 'publicKey', 'wrap') ?? malformed('recovery');
    recovery = { kid: b64of(r['kid'], 16, 'recovery.kid'), publicKey: b64of(r['publicKey'], 65, 'recovery.publicKey'), wrap: hpkeWrap(r['wrap']) };
  }
  const revoked = array(o['revoked'], 'revoked').map((x) => {
    const r = exactObj(x, 'kid', 'revokedAt', 'revokedAtEpoch') ?? malformed('revoked entry');
    return {
      kid: b64of(r['kid'], 16, 'revoked.kid'),
      revokedAt: int(r['revokedAt'], 0, MAX_SAFE) ?? malformed('revokedAt'),
      revokedAtEpoch: int(r['revokedAtEpoch'], 2, MAX_EPOCH) ?? malformed('revokedAtEpoch'),
    };
  });
  return { revision, epoch, chain, devices, recovery, revoked };
}

function nextRevision(body: KeysBody): number {
  if (body.revision >= MAX_SAFE) throw new KeysError('INVALID_ENTRY', 'revision limit');
  return body.revision + 1;
}

function checkTime(now: number): void {
  if (!Number.isSafeInteger(now) || now < 0) throw new KeysError('INVALID_ENTRY', 'time');
}
