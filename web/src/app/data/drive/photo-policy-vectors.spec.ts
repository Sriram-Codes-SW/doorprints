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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../../docs/schemas/photo-policy-vectors.json';
import {
  decidePhotoNetwork, fromAndroid, fromApple, ONE_OFF_TTL_MS, OFFLINE, photoNetworkStatus, PhotoUploadGate, webNetworkConditions,
} from './photo-network-policy';
import type { Metering, NetworkConditions, PhotoAllowReason, PhotoNetworkStatus } from './photo-network-policy';

/** `docs/schemas/photo-policy-vectors.json` (read by Kotlin's `PhotoPolicyTest` too) and the gate (S4b-BL-128). */
interface Case {
  name: string;
  conditions: { online: boolean; metering: Metering; roaming: boolean; dataSaver: boolean };
  settings: { uploadOnMobileData: boolean };
  grantedAt: number | null;
  now: number;
  pending: number;
  expect: { allowed: boolean; reason: PhotoAllowReason; status: PhotoNetworkStatus };
}
const vectors = vectorsJson as unknown as { constants: { oneOffTtlMs: number }; cases: Case[] };

describe('photo-policy-vectors.json', () => {
  it('has the ttl the code uses', () => {
    expect(vectors.constants.oneOffTtlMs).toBe(ONE_OFF_TTL_MS);
  });

  it('every case', () => {
    expect(vectors.cases.length).toBe(26);
    for (const c of vectors.cases) {
      const d = decidePhotoNetwork(c.conditions, c.settings, c.grantedAt === null ? null : { grantedAt: c.grantedAt }, c.now);
      expect(d.allowed, c.name).toBe(c.expect.allowed);
      expect(d.reason, c.name).toBe(c.expect.reason);
      expect(photoNetworkStatus(d, c.pending), c.name).toBe(c.expect.status);
    }
  });
});

describe('PhotoUploadGate and the platform mappings', () => {
  it('the grant expires and the gate follows the network', () => {
    let t = 1_000_000;
    let net: NetworkConditions = { online: true, metering: 'METERED', roaming: false, dataSaver: false };
    const gate = new PhotoUploadGate(() => net, () => ({ uploadOnMobileData: false }), () => t);
    expect(gate.photosAllowed()).toBe(false);
    expect(gate.status(2)).toBe('WAITING_FOR_WIFI');
    gate.grantOneOff();
    expect(gate.photosAllowed()).toBe(true);
    expect(gate.status(2)).toBe('UPLOADING');
    t += ONE_OFF_TTL_MS - 1;
    expect(gate.photosAllowed()).toBe(true);
    t += 1;
    expect(gate.photosAllowed()).toBe(false);
    expect(gate.grant).toBeNull();
    net = { ...net, metering: 'UNMETERED' };
    expect(gate.photosAllowed()).toBe(true);
    net = OFFLINE;
    expect(gate.status(1)).toBe('PAUSED_OFFLINE');
    expect(gate.status(0)).toBe('DONE');
  });

  it('a cleared grant is gone', () => {
    const gate = new PhotoUploadGate(() => ({ online: true, metering: 'METERED', roaming: false, dataSaver: false }), () => ({ uploadOnMobileData: false }), () => 5);
    gate.grantOneOff();
    gate.clearGrant();
    expect(gate.photosAllowed()).toBe(false);
  });

  it('the setting moves photos on mobile data but not while roaming or saving', () => {
    let settings = { uploadOnMobileData: true };
    let cond: NetworkConditions = { online: true, metering: 'METERED', roaming: false, dataSaver: false };
    const gate = new PhotoUploadGate(() => cond, () => settings, () => 0);
    expect(gate.photosAllowed()).toBe(true);
    cond = { ...cond, roaming: true };
    expect(gate.photosAllowed()).toBe(false);
    cond = { ...cond, roaming: false, dataSaver: true };
    expect(gate.photosAllowed()).toBe(false);
    settings = { uploadOnMobileData: false };
    cond = { ...cond, dataSaver: false };
    expect(gate.photosAllowed()).toBe(false);
  });

  it('maps the platforms', () => {
    expect(fromAndroid(true, true, true, false)).toEqual({ online: true, metering: 'UNMETERED', roaming: false, dataSaver: false });
    expect(fromAndroid(true, false, false, true)).toEqual({ online: true, metering: 'METERED', roaming: true, dataSaver: true });
    expect(fromApple(true, true, true)).toEqual({ online: true, metering: 'METERED', roaming: false, dataSaver: true });
    expect(fromApple(false, false, false)).toEqual({ online: false, metering: 'UNMETERED', roaming: false, dataSaver: false });
  });

  it('the website treats an unknown network as metered, and reads navigator.connection when it is there', () => {
    expect(webNetworkConditions(undefined)).toEqual(OFFLINE);
    const unknown = webNetworkConditions({ onLine: true });
    expect(unknown.metering).toBe('UNKNOWN');
    expect(decidePhotoNetwork(unknown, { uploadOnMobileData: false }, null, 0).allowed).toBe(false);
    expect(webNetworkConditions({ onLine: true, connection: { type: 'wifi' } }).metering).toBe('UNMETERED');
    expect(webNetworkConditions({ onLine: true, connection: { type: 'ethernet' } }).metering).toBe('UNMETERED');
    expect(webNetworkConditions({ onLine: true, connection: { type: 'cellular', saveData: true } })).toEqual({ online: true, metering: 'METERED', roaming: false, dataSaver: true });
    expect(webNetworkConditions({ onLine: false, connection: { type: 'wifi' } }).online).toBe(false);
  });
});
