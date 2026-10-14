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

import { TestBed } from '@angular/core/testing';
import { NavigationError, ROUTER_CONFIGURATION, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { appConfig } from './app.config';
import { ChunkRecovery } from './core/chunk-recovery';

describe('appConfig', () => {
  /*
   * TC-NAV (UX lead review 2026-09-22): edit a house, press its Back, choose "Keep editing", Save, press Back again —
   * this must land on the list. With the router's default ('replace') the cancelled Back wrote the house URL over the
   * list's history entry, and the second Back left the app (or did nothing in an installed app).
   */
  it('restores history with historyGo() when a guard cancels a Back, instead of overwriting the previous entry', () => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    expect(TestBed.inject(ROUTER_CONFIGURATION).canceledNavigationResolution).toBe('computed');
  });

  it('gives a navigation error to the chunk recovery, and still rejects the navigation as before', async () => {
    const handle = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [...appConfig.providers, { provide: ChunkRecovery, useValue: { handle } }] });
    const router = TestBed.inject(Router);
    const failure = new TypeError('Failed to fetch dynamically imported module: https://doorprints.web.app/chunk-X.js');
    router.resetConfig([{ path: 'x', loadComponent: () => Promise.reject(failure) }]);
    await expect(router.navigateByUrl('/x')).rejects.toBe(failure);
    expect(handle).toHaveBeenCalledTimes(1);
    expect(handle.mock.calls[0][0]).toBeInstanceOf(NavigationError);
    expect(handle.mock.calls[0][0].error).toBe(failure);
  });
});
