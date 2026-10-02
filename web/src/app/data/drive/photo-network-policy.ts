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

/*
 * When photos may use the network (S4b-BL-128, docs/15 §11): pure logic, no I/O. Kotlin twin: `PhotoNetworkPolicy.kt`,
 * the same names; `docs/schemas/photo-policy-vectors.json` pins the table on both stacks. Text and the backups are not
 * photos: they go on any network. Battery and charging are NOT conditions (owner, 2026-10-02).
 * Web treats UNKNOWN as ALLOWED (no type on desktop); Android/iOS report real state (keep UNKNOWN as METERED).
 */

/** What the network is, as far as the platform can tell. UNKNOWN on web is allowed (no type on desktop), on phones it's metered. */
export type Metering = 'UNMETERED' | 'METERED' | 'UNKNOWN';

/** `roaming`: Android only; `dataSaver`: Android Data Saver, iPhone Low Data Mode, the website's `saveData`. */
export interface NetworkConditions {
  readonly online: boolean;
  readonly metering: Metering;
  readonly roaming: boolean;
  readonly dataSaver: boolean;
}

export const OFFLINE: NetworkConditions = { online: false, metering: 'UNKNOWN', roaming: false, dataSaver: false };

/** Where the policy gets the network from: here the website's `navigator.connection` ({@link webNetworkState}). */
export type NetworkState = () => NetworkConditions;

/** The setting *Upload photos on mobile data*, off by default. */
export interface PhotoSettings {
  readonly uploadOnMobileData: boolean;
}

export const DEFAULT_PHOTO_SETTINGS: PhotoSettings = { uploadOnMobileData: false };

/** How long a one-off grant lasts (30 minutes). */
export const ONE_OFF_TTL_MS = 30 * 60_000;

/** The one-off *Upload photos now over mobile data*: valid for {@link ONE_OFF_TTL_MS} from `grantedAt`, then gone. */
export interface OneOffGrant {
  readonly grantedAt: number;
}

/** A clock that moved back before `grantedAt` makes it inactive (fail closed). */
export function grantActive(grant: OneOffGrant, now: number): boolean {
  return now >= grant.grantedAt && now < grant.grantedAt + ONE_OFF_TTL_MS;
}

export type PhotoAllowReason =
  | 'OFFLINE' | 'UNMETERED' | 'MOBILE_DATA_SETTING' | 'ONE_OFF' | 'WAITING_METERED' | 'WAITING_ROAMING' | 'WAITING_DATA_SAVER';

/** What the status line says about photos: *Waiting for Wi-Fi*, *Uploading*, *Paused (offline)*, *Done*. */
export type PhotoNetworkStatus = 'WAITING_FOR_WIFI' | 'UPLOADING' | 'PAUSED_OFFLINE' | 'DONE';

export interface PhotoNetworkDecision {
  readonly allowed: boolean;
  readonly reason: PhotoAllowReason;
}

/**
 * May photo bytes move now? In order: no network, no; an active one-off grant, yes (it also overrides roaming and Data
 * Saver); an unmetered network, yes; on web (webUnknownAllowed=true), UNKNOWN is also yes; a metered network only with
 * the setting on and neither roaming nor Data Saver/Low Data Mode.
 */
export function decidePhotoNetwork(
  c: NetworkConditions,
  s: PhotoSettings,
  grant: OneOffGrant | null,
  now: number,
  webUnknownAllowed: boolean = false,
): PhotoNetworkDecision {
  if (!c.online) return { allowed: false, reason: 'OFFLINE' };
  if (grant && grantActive(grant, now)) return { allowed: true, reason: 'ONE_OFF' };
  if (c.metering === 'UNMETERED') return { allowed: true, reason: 'UNMETERED' };
  if (c.metering === 'UNKNOWN' && webUnknownAllowed) return { allowed: true, reason: 'UNMETERED' };
  if (!s.uploadOnMobileData) return { allowed: false, reason: 'WAITING_METERED' };
  if (c.roaming) return { allowed: false, reason: 'WAITING_ROAMING' };
  if (c.dataSaver) return { allowed: false, reason: 'WAITING_DATA_SAVER' };
  return { allowed: true, reason: 'MOBILE_DATA_SETTING' };
}

/** The status for `pending` photos waiting to move: nothing pending is *Done* whatever the network. */
export function photoNetworkStatus(d: PhotoNetworkDecision, pending: number): PhotoNetworkStatus {
  if (pending <= 0) return 'DONE';
  if (d.reason === 'OFFLINE') return 'PAUSED_OFFLINE';
  return d.allowed ? 'UPLOADING' : 'WAITING_FOR_WIFI';
}

/** Android: INTERNET, NOT_METERED, NOT_ROAMING capabilities and Data Saver. */
export function fromAndroid(hasInternet: boolean, notMetered: boolean, notRoaming: boolean, dataSaverEnabled: boolean): NetworkConditions {
  return { online: hasInternet, metering: notMetered ? 'UNMETERED' : 'METERED', roaming: !notRoaming, dataSaver: dataSaverEnabled };
}

/** iPhone: `NWPath` satisfied, `isExpensive`, `isConstrained`. */
export function fromApple(satisfied: boolean, expensive: boolean, constrained: boolean): NetworkConditions {
  return { online: satisfied, metering: expensive ? 'METERED' : 'UNMETERED', roaming: false, dataSaver: constrained };
}

interface ConnectionLike {
  readonly type?: string;
  readonly saveData?: boolean;
}

/**
 * The website: `navigator.connection` exists only in Chromium and often has no `type` on a computer. `wifi` and
 * `ethernet` are unmetered; `cellular` is metered; anything else (no API, no type) is UNKNOWN and so treated as
 * metered by the policy. `saveData` is the Data Saver flag. `navigator.onLine === false` is offline.
 */
export function webNetworkConditions(nav: { readonly onLine?: boolean; readonly connection?: ConnectionLike } | undefined): NetworkConditions {
  if (!nav) return OFFLINE;
  const online = nav.onLine !== false;
  const c = nav.connection;
  const type = c?.type;
  const metering: Metering = type === 'wifi' || type === 'ethernet' ? 'UNMETERED' : type === 'cellular' ? 'METERED' : 'UNKNOWN';
  return { online, metering, roaming: false, dataSaver: c?.saveData === true };
}

export const webNetworkState: NetworkState = () =>
  webNetworkConditions(typeof navigator === 'undefined' ? undefined : (navigator as unknown as { onLine?: boolean; connection?: ConnectionLike }));

/**
 * The gate the sync loop's "photos allowed" comes from: the network, the setting and the one-off grant, read at the
 * moment of the call. A grant lives in memory (a one-off: it is gone with the page).
 * webUnknownAllowed: on web, UNKNOWN network (no type on desktop) is treated as allowed (S4b-BL-131).
 */
export class PhotoUploadGate {
  grant: OneOffGrant | null = null;

  constructor(
    private readonly network: NetworkState,
    private readonly settings: () => PhotoSettings,
    private readonly clock: () => number,
    private readonly webUnknownAllowed: boolean = false,
  ) {}

  grantOneOff(): OneOffGrant {
    this.grant = { grantedAt: this.clock() };
    return this.grant;
  }

  clearGrant(): void {
    this.grant = null;
  }

  decision(): PhotoNetworkDecision {
    const now = this.clock();
    if (this.grant && !grantActive(this.grant, now)) this.grant = null;
    return decidePhotoNetwork(this.network(), this.settings(), this.grant, now, this.webUnknownAllowed);
  }

  photosAllowed(): boolean {
    return this.decision().allowed;
  }

  status(pending: number): PhotoNetworkStatus {
    return photoNetworkStatus(this.decision(), pending);
  }
}
