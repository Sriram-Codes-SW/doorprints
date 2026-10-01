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

import { describe, expect, it, vi } from 'vitest';
import swSource from '../../../public/sw.js' with { loader: 'text' };

/**
 * The service worker's `notificationclick` (slice 3b-2, S4b-BL-93e; tested since S4b-BL-103): a tap on a viewing
 * reminder, which the open app shows through `registration.showNotification(…, { data: { url: 'viewings' } })`,
 * closes the notification and brings the app to the Viewings page: an open window of this app is focused and moved
 * there, otherwise a new one is opened. The address is taken relative to the worker's scope, so a deployment under a
 * sub-path works, and a window of another app on the same origin is never taken over.
 *
 * The real `public/sw.js` is run here against a fake worker scope: only the scope and the listeners are touched when
 * it loads (the caches and the network are used inside the other handlers only).
 */
interface FakeWindow {
  url: string;
  focus: ReturnType<typeof vi.fn>;
  navigate?: ReturnType<typeof vi.fn>;
}

const appWindow = (url: string, navigate: 'ok' | 'fails' | 'none' = 'ok'): FakeWindow => {
  const w: FakeWindow = { url, focus: vi.fn(async () => w) };
  if (navigate === 'ok') w.navigate = vi.fn(async () => w);
  if (navigate === 'fails') w.navigate = vi.fn(async () => Promise.reject(new TypeError('not controlled')));
  return w;
};

function worker(scope: string, windows: FakeWindow[]) {
  const listeners = new Map<string, (event: unknown) => void>();
  const clients = {
    matchAll: vi.fn(async () => windows),
    openWindow: vi.fn(async () => null),
  };
  const self = {
    registration: { scope },
    clients,
    addEventListener: (type: string, listener: (event: unknown) => void) => listeners.set(type, listener),
  };
  new Function('self', swSource)(self);

  /** Dispatches a tap on a notification carrying `data`; resolves when the worker's `waitUntil` work has settled. */
  async function tap(data: unknown = { url: 'viewings' }) {
    const close = vi.fn();
    const pending: Promise<unknown>[] = [];
    listeners.get('notificationclick')!({ notification: { close, data }, waitUntil: (p: Promise<unknown>) => pending.push(p) });
    expect(pending).toHaveLength(1);
    await pending[0];
    return { close };
  }
  return { tap, clients, listeners };
}

describe('service worker: a tap on a viewing reminder', () => {
  it('registers a notificationclick listener beside the offline ones', () => {
    const { listeners } = worker('https://doorprints.web.app/', []);
    expect([...listeners.keys()]).toEqual(expect.arrayContaining(['install', 'activate', 'fetch', 'notificationclick']));
  });

  it('closes the notification and, with no window of the app open, opens the Viewings page', async () => {
    const { tap, clients } = worker('https://doorprints.web.app/', []);
    const { close } = await tap();
    expect(close).toHaveBeenCalledTimes(1);
    expect(clients.matchAll).toHaveBeenCalledWith({ type: 'window', includeUncontrolled: true });
    expect(clients.openWindow).toHaveBeenCalledExactlyOnceWith('https://doorprints.web.app/viewings');
  });

  it('focuses an open window of the app and moves it to the Viewings page instead of opening another', async () => {
    const open = appWindow('https://doorprints.web.app/houses/h1');
    const { tap, clients } = worker('https://doorprints.web.app/', [open]);
    await tap();
    expect(open.focus).toHaveBeenCalledTimes(1);
    expect(open.navigate).toHaveBeenCalledExactlyOnceWith('https://doorprints.web.app/viewings');
    expect(clients.openWindow).not.toHaveBeenCalled();
  });

  it('only focuses a window that already shows the Viewings page', async () => {
    const open = appWindow('https://doorprints.web.app/viewings');
    const { tap, clients } = worker('https://doorprints.web.app/', [open]);
    await tap();
    expect(open.focus).toHaveBeenCalledTimes(1);
    expect(open.navigate).not.toHaveBeenCalled();
    expect(clients.openWindow).not.toHaveBeenCalled();
  });

  it('under a sub-path, opens the page there and leaves a window of another app on the same origin alone', async () => {
    const other = appWindow('https://example.org/other-app/');
    const { tap, clients } = worker('https://example.org/doorprints/', [other]);
    await tap();
    expect(other.focus).not.toHaveBeenCalled();
    expect(other.navigate).not.toHaveBeenCalled();
    expect(clients.openWindow).toHaveBeenCalledExactlyOnceWith('https://example.org/doorprints/viewings');
  });

  it('under a sub-path, moves the app window found there to the page under that path', async () => {
    const other = appWindow('https://example.org/other-app/');
    const mine = appWindow('https://example.org/doorprints/compare');
    const { tap } = worker('https://example.org/doorprints/', [other, mine]);
    await tap();
    expect(mine.focus).toHaveBeenCalledTimes(1);
    expect(mine.navigate).toHaveBeenCalledExactlyOnceWith('https://example.org/doorprints/viewings');
    expect(other.focus).not.toHaveBeenCalled();
  });

  it('still settles when the window cannot be moved (not controlled by the worker) or has no navigate', async () => {
    const refusing = appWindow('https://doorprints.web.app/plan', 'fails');
    await expect(worker('https://doorprints.web.app/', [refusing]).tap()).resolves.toBeDefined();
    expect(refusing.focus).toHaveBeenCalledTimes(1);
    expect(refusing.navigate).toHaveBeenCalledTimes(1);

    const old = appWindow('https://doorprints.web.app/plan', 'none');
    const { tap, clients } = worker('https://doorprints.web.app/', [old]);
    await tap();
    expect(old.focus).toHaveBeenCalledTimes(1);
    expect(clients.openWindow).not.toHaveBeenCalled();
  });

  it('opens the app at its start when the notification carries no address', async () => {
    const { tap, clients } = worker('https://doorprints.web.app/', []);
    await tap(null);
    await tap({});
    expect(clients.openWindow.mock.calls).toEqual([['https://doorprints.web.app/'], ['https://doorprints.web.app/']]);
  });
});
