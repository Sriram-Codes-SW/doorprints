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
import { browserDeviceName, parseConnectLink } from './pairing.service';

const INVITE = 'Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo';

describe('parseConnectLink', () => {
  it('accepts an https origin and an invite, and keeps the origin only', () => {
    expect(parseConnectLink('https://home.example.ts.net', INVITE)).toEqual({
      server: 'https://home.example.ts.net',
      invite: INVITE,
    });
    expect(parseConnectLink('https://home.example.ts.net/', INVITE)?.server).toBe('https://home.example.ts.net');
    expect(parseConnectLink('https://home.example.ts.net:8443', INVITE)?.server).toBe('https://home.example.ts.net:8443');
  });

  it('allows http only on this computer (development)', () => {
    expect(parseConnectLink('http://localhost:8080', INVITE)?.server).toBe('http://localhost:8080');
    expect(parseConnectLink('http://127.0.0.1:8080', INVITE)?.server).toBe('http://127.0.0.1:8080');
    expect(parseConnectLink('http://home.example.org', INVITE)).toBeNull();
  });

  it('refuses anything that is not a plain server address or not an invite', () => {
    for (const server of [
      null,
      '',
      'not a url',
      'javascript:alert(1)',
      'ftp://home.example.org',
      'https://user:pass@home.example.org',
      'https://home.example.org/api',
      'https://home.example.org/?x=1',
      'https://home.example.org/#x',
    ]) {
      expect(parseConnectLink(server, INVITE), String(server)).toBeNull();
    }
    for (const invite of [null, '', 'short', 'has spaces in it but is long enough', `${INVITE}!`, 'x'.repeat(101)]) {
      expect(parseConnectLink('https://home.example.org', invite), String(invite)).toBeNull();
    }
  });
});

describe('browserDeviceName', () => {
  it('names the browser and system the way the owner page does, marked as the website', () => {
    expect(
      browserDeviceName(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36',
      ),
    ).toBe('Chrome on Windows (website)');
    expect(
      browserDeviceName(
        'Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36',
      ),
    ).toBe('Chrome on Android (website)');
    expect(
      browserDeviceName(
        'Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/19.0 Mobile/15E148 Safari/604.1',
      ),
    ).toBe('Safari on iOS (website)');
    expect(browserDeviceName('Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0')).toBe(
      'Firefox on Linux (website)',
    );
    expect(
      browserDeviceName(
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36 Edg/140.0',
      ),
    ).toBe('Edge on macOS (website)');
    expect(browserDeviceName('')).toBe('Browser (website)');
  });
});
