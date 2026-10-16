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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { GEOLOCATION, TraceRecorderService } from '../../core/trace-recorder.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import type { PlaceStatus } from '../../shared/trace-place-check';
import { placeCheckSummary } from '../../shared/trace-place-text';
import { PlaceCheckPanel } from './place-check-panel';
import { PlaceCheckState } from './place-check-state';
import type { CheckAnswer } from './place-check-state';

const DAY = 86_400_000;
const T = Date.UTC(2026, 9, 6, 12, 0, 0);

/** An answer as `PlaceCheckState` would make it, with the rows given as `[daysAgo, distanceM, walked, saved]`. */
function answerOf(status: PlaceStatus, kind: CheckAnswer['kind'] = 'house', rows: [number, number, boolean, boolean][] = [], over: { hasSaved?: boolean; fixAccuracyM?: number | null; fuzzy?: boolean } = {}): CheckAnswer {
  const result = {
    status,
    fuzzy: over.fuzzy ?? false,
    nearestM: rows.length ? Math.min(...rows.map((r) => r[1])) : null,
    rows: rows.map(([days, distanceM, walked, saved], i) => ({ walkIndex: i, distanceM, atMs: T - days * DAY, walked, saved, segment: 0, t: 0 })),
  };
  return {
    kind,
    place: { lat: 13, lon: 80 },
    summary: placeCheckSummary(result, { hasSaved: over.hasSaved ?? false, fixAccuracyM: over.fixAccuracyM ?? null }),
    stretches: [],
    bounds: [[80, 13], [80, 13]],
  };
}

describe('the answer panel (Have I been here?)', () => {
  let state: PlaceCheckState;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(T);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  async function render(opts: { lang?: Lang; mapUsable?: boolean } = {}) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: TraceRecorderService, useValue: new FakeRecorder() },
        { provide: GEOLOCATION, useValue: null },
      ],
    });
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    state = TestBed.inject(PlaceCheckState);
    const fixture = TestBed.createComponent(PlaceCheckPanel);
    fixture.componentRef.setInput('mapUsable', opts.mapUsable ?? true);
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const $ = <T extends HTMLElement>(s: string) => host.querySelector<T>(s);
    const show = async (answer: CheckAnswer) => {
      state.present(answer);
      await settle(fixture);
    };
    return { fixture, host, $, show };
  }

  it('shows nothing until there is an answer', async () => {
    const r = await render();
    expect(r.$('section')).toBeNull();
  });

  it('is a section named by its title, which takes focus on arrival (tabindex -1)', async () => {
    const r = await render();
    await r.show(answerOf('WALKED', 'house', [[1, 5, true, false]]));
    expect(r.$('section')!.getAttribute('aria-labelledby')).toBe('check-title');
    const title = r.$('#check-title')!;
    expect(title.textContent).toBe('Did I walk past this house?');
    expect(title.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(title);
  });

  it.each([
    ['here', 'Have I been here?'],
    ['house', 'Did I walk past this house?'],
    ['spot', 'Did I walk here?'],
  ] as const)('is titled for the place: %s', async (kind, title) => {
    const r = await render();
    await r.show(answerOf('EMPTY', kind));
    expect(r.$('#check-title')!.textContent).toBe(title);
  });

  it('says WALKED with the days newest first and the largest distance, and lists the rows', async () => {
    const r = await render();
    await r.show(answerOf('WALKED', 'house', [[1, 5, true, false], [3, 20, true, true]]));
    expect(r.$('#check-headline')!.textContent).toMatch(/^You walked within 20 m of this house on .+ and .+\.$/);
    const rows = [...r.host.querySelectorAll('#check-rows li')].map((x) => x.textContent);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('5 m away');
    expect(rows[1]).toContain('20 m away, saved walk');
  });

  it('shows at most five rows and then says how many more walks', async () => {
    const r = await render();
    await r.show(answerOf('WALKED', 'house', Array.from({ length: 7 }, (_, i): [number, number, boolean, boolean] => [i, 5, true, false])));
    expect(r.host.querySelectorAll('#check-rows li')).toHaveLength(5);
    expect(r.$('#check-rows-more')!.textContent).toBe('and 2 more walks');
  });

  it('adds what the answer covers to a negative answer, and the website line', async () => {
    const r = await render();
    await r.show(answerOf('NONE', 'spot'));
    const text = r.host.textContent!;
    expect(text).toContain('No walk of yours passed within 25 m of this spot in the last 30 days.');
    expect(text).toContain('This covers only the walks Doorprints recorded.');
    expect(text).toContain('On the website, only walks recorded while this page was open are included.');
    expect(r.$('#check-rows')).toBeNull();
  });

  it('says the loose-fix line for an accepted but loose fix', async () => {
    const r = await render();
    await r.show(answerOf('NONE', 'here', [], { fuzzy: true, fixAccuracyM: 38 }));
    expect(r.host.textContent).toContain('Your location is only accurate to about 38 m, so this answer may be off.');
  });

  it.each<[PlaceStatus, string]>([
    ['EMPTY', 'There are no walks to compare yet. Turn on Trace my path on the Map page and start a walk.'],
    ['IMPRECISE', 'Location not precise enough. Try again outdoors.'],
    ['INVALID_PLACE', 'This spot has no valid location.'],
  ])('says %s in its own words', async (status, words) => {
    const r = await render();
    await r.show(answerOf(status, 'here'));
    expect(r.$('#check-headline')!.textContent).toBe(words);
  });

  it('says the privacy sentence: shown only here, nothing saved or sent', async () => {
    const r = await render();
    await r.show(answerOf('NONE'));
    expect(r.host.textContent).toContain('Shown only here. Nothing is saved or sent.');
  });

  it('has no live region of its own for the answer (the shell\'s polite region says it once)', async () => {
    const r = await render();
    await r.show(answerOf('WALKED', 'house', [[1, 5, true, false]]));
    expect(r.host.querySelector('[aria-live]')).toBeNull();
    expect(r.host.querySelector('[role="status"]')).toBeNull();
    expect(r.$('#check-headline')!.closest('[role="alert"]')).toBeNull();
  });

  describe('the buttons', () => {
    it('Show on map frames the answer; it is not offered while there is no map', async () => {
      const r = await render();
      await r.show(answerOf('WALKED', 'house', [[1, 5, true, false]]));
      const show = vi.spyOn(state, 'show');
      r.$<HTMLButtonElement>('#check-show')!.click();
      expect(show).toHaveBeenCalledTimes(1);
      const noMap = await render({ mapUsable: false });
      await noMap.show(answerOf('WALKED', 'house'));
      expect(noMap.$('#check-show')).toBeNull();
    });

    it('Check again is for here only, and starts the watch in the click', async () => {
      const r = await render();
      await r.show(answerOf('NONE', 'house'));
      expect(r.$('#check-again')).toBeNull();
      await r.show(answerOf('NONE', 'here'));
      const locate = vi.spyOn(state, 'locateHere').mockImplementation(() => undefined);
      r.$<HTMLButtonElement>('#check-again')!.click();
      expect(locate).toHaveBeenCalledTimes(1);
    });

    it('while finding the location it says so with Cancel instead of the other buttons, and a refusal is read out as an alert', async () => {
      const r = await render();
      await r.show(answerOf('NONE', 'here'));
      state.locating.set(true);
      await settle(r.fixture);
      expect(r.$('#check-locating')!.textContent).toBe('Finding your location...');
      expect(r.$('#check-again')).toBeNull();
      const cancel = vi.spyOn(state, 'cancelLocating');
      r.$<HTMLButtonElement>('#check-cancel')!.click();
      expect(cancel).toHaveBeenCalledTimes(1);
      state.locating.set(false);
      state.locateProblem.set('denied');
      await settle(r.fixture);
      expect(r.$('#check-problem')!.textContent).toContain('Location is blocked for this site.');
      expect(r.$('#check-problem')!.closest('[role="alert"]')).not.toBeNull();
    });

    it('Close withdraws the answer and the announcement, and returns focus to Have I been here?', async () => {
      const r = await render();
      const opener = document.createElement('button');
      opener.id = 'place-check-open';
      document.body.appendChild(opener);
      const cancel = vi.spyOn(TestBed.inject(Announcer), 'cancel');
      await r.show(answerOf('WALKED', 'house', [[1, 5, true, false]]));
      r.$<HTMLButtonElement>('#check-close')!.click();
      await settle(r.fixture);
      expect(state.answer()).toBeNull();
      expect(r.$('section')).toBeNull();
      expect(cancel).toHaveBeenCalledWith({ key: 'trace.here.announce' });
      expect(document.activeElement).toBe(opener);
      opener.remove();
    });
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it.each<[PlaceStatus]>([['WALKED'], ['CLOSE'], ['NONE'], ['EMPTY'], ['IMPRECISE'], ['INVALID_PLACE']])('says %s with no raw key and passes the accessibility rules', async (status) => {
      const r = await render({ lang });
      const rows: [number, number, boolean, boolean][] = status === 'WALKED' ? [[1, 5, true, false], [2, 9, true, true]] : status === 'CLOSE' ? [[1, 31, false, false]] : [];
      await r.show(answerOf(status, 'here', rows, { fuzzy: true, fixAccuracyM: 33 }));
      expect(r.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(r.host.textContent).not.toContain('{');
      expect(audit(r.host)).toEqual([]);
    });
  });
});
