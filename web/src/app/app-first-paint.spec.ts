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

import { ApplicationRef, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';

/**
 * S4b-BL-249: a page the router creates is updated before the browser can paint it. Zoneless, the first update waits for a
 * timer raced with the next frame, and a frame painted before it showed Connect, Share and Your data with empty headings,
 * then moved everything down (0.3-0.8 of layout shift on a slow runner in CI). The shell updates the application in a
 * microtask after the outlet activates a page.
 */
@Component({ template: '<h1>Page</h1>' })
class Page {}

afterEach(() => TestBed.resetTestingModule());

describe('the first paint of a page (S4b-BL-249)', () => {
  it('runs the first update in a microtask after the outlet creates the page, before any frame', async () => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([{ path: 'page', component: Page }])] });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    const tick = vi.spyOn(TestBed.inject(ApplicationRef), 'tick');

    await TestBed.inject(Router).navigateByUrl('/page');
    await Promise.resolve();

    expect(tick).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toBe('Page');
  });
});
