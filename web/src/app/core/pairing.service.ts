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

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Types follow the backend records in app.doorprints.server.device (PairingService.Started, PairingController).

/** A code to type on the owner page, and the token this browser polls with (docs/03 §12.1). */
export interface PairingStarted {
  /** Shown as `K7MQ-4XRD`. */
  userCode: string;
  pollToken: string;
  /** Seconds until the code expires. */
  expiresIn: number;
  /** Seconds between polls. */
  interval: number;
}

/** The answer to a poll. `deviceKey` comes once, with `approved`; `denied` and `expired` end the pairing. */
export interface PairingPolled {
  status: 'pending' | 'approved' | 'denied' | 'expired';
  /** Only with `approved`, and only once. */
  deviceKey: string | null;
}

/** A connect link's two values, checked (see {@link parseConnectLink}). */
export interface ConnectLink {
  server: string;
  invite: string;
}

/**
 * Connecting without a pasted key (ADR-25): by a code the owner types on their owner page, or by an invite from the
 * owner page's QR code or link. The calls need no key (the server rate-limits them per address), so they go to the
 * address given, not through the API interceptor.
 */
@Injectable({ providedIn: 'root' })
export class PairingService {
  private readonly http = inject(HttpClient);

  /**
   * Asks the server for a pairing code to type on the owner page; the device name is what the owner sees in the list of
   * devices.
   */
  start(baseUrl: string, deviceName: string): Observable<PairingStarted> {
    return this.http.post<PairingStarted>(`${baseUrl}/api/pair/start`, { deviceName });
  }

  /**
   * Asks whether the owner has approved the code yet, with the token from {@link start}; call it every `interval`
   * seconds.
   */
  poll(baseUrl: string, pollToken: string): Observable<PairingPolled> {
    return this.http.post<PairingPolled>(`${baseUrl}/api/pair/poll`, { pollToken });
  }

  /** 410 when the invite was already used or has expired. */
  redeem(baseUrl: string, invite: string, deviceName: string): Observable<{ deviceKey: string }> {
    return this.http.post<{ deviceKey: string }>(`${baseUrl}/api/pair/redeem`, { invite, deviceName });
  }
}

/** Invites are 32 random bytes in base64url (43 characters); anything else is not one. */
const INVITE = /^[A-Za-z0-9_-]{20,100}$/;

/**
 * The `server` and `invite` of a connect link (`/connect?server=…&invite=…`), or null when either is missing or not
 * usable: the server must be an `https://` origin (or `http://` on this computer, for development), with no path,
 * query or credentials; the invite must look like one.
 */
export function parseConnectLink(server: string | null, invite: string | null): ConnectLink | null {
  if (!server || !invite || !INVITE.test(invite)) return null;
  let url: URL;
  try {
    url = new URL(server);
  } catch {
    return null;
  }
  const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) return null;
  if (url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    return null;
  }
  return { server: url.origin, invite };
}

/**
 * The name the owner page shows for this browser, such as "Chrome on Windows (website)": the same browser and system
 * words the owner page uses for its own sign-ins (OwnerAuth.label), so the owner recognises it.
 */
export function browserDeviceName(userAgent: string): string {
  const ua = userAgent || '';
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /OPR\//.test(ua)
      ? 'Opera'
      : /Firefox\//.test(ua)
        ? 'Firefox'
        : /Chrome\//.test(ua)
          ? 'Chrome'
          : /Safari\//.test(ua)
            ? 'Safari'
            : 'Browser';
  const system = /Android/.test(ua)
    ? 'Android'
    : /iPhone|iPad|iPod/.test(ua)
      ? 'iOS'
      : /Windows/.test(ua)
        ? 'Windows'
        : /Mac OS X|Macintosh/.test(ua)
          ? 'macOS'
          : /CrOS/.test(ua)
            ? 'ChromeOS'
            : /Linux/.test(ua)
              ? 'Linux'
              : null;
  return `${system ? `${browser} on ${system}` : browser} (website)`;
}
