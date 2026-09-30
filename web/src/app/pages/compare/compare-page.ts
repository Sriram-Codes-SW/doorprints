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

import { Component, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LocalDataService } from '../../core/local-data.service';
import { brokerLine } from '../../shared/broker';
import { Announcer } from '../../core/announcer.service';
import { CHECKLIST, HouseDto, STATUS_ICON, STATUS_KEY, houseScore } from '../../core/models';
import { errorMsg, telHref } from '../../core/format';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import { MAX_SELECTED, MIN_SELECTED, idsFromQuery } from './compare-selection';
import { GLYPHS } from '../../shared/glyphs';
import { RunResult, nextRunResult } from '../../shared/run-result';
import { costSummary } from '../../shared/house-cost';
import { LocalStore } from '../../data/local-store.service';
import { areaNumber, totalAreaSqCm } from '../../shared/room-sizes';
import type { LengthUnit } from '../../shared/room-sizes';

/** Above this many candidates the picker gets a search box: a wall of chips cannot be scanned. */
const SEARCH_ABOVE = 12;

interface Cell {
  /** What is shown. */
  text: string;
  /** Screen-reader wording when `text` is symbolic (stars, dashes). */
  srText?: string;
}

interface Row {
  id: string;
  label: string;
  cells: Cell[];
  /** Index of the columns holding the best value (highlighted), if this row is scored. */
  best: Set<number>;
}

@Component({
  selector: 'app-compare-page',
  imports: [RouterLink, TPipe],
  templateUrl: './compare-page.html',
  styleUrl: './compare-page.css',
})
export class ComparePage {
  private readonly api = inject(LocalDataService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);
  protected readonly i18n = inject(TranslationService);
  private readonly store = inject(LocalStore);
  /** The Length units preference (a local setting): the total area of the rooms is shown in it. */
  private readonly lengthUnit = signal<LengthUnit>('FT');

  protected readonly loading = signal(true);
  /** Why the houses could not be read; keyed on its run so that Retry failing the same way is read again. */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  private readonly houses = signal<HouseDto[]>([]);
  /** Broker name and agency by broker id (slice 1b): the Contact row shows them for a linked house. */
  private readonly brokerLines = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly selectedIds = signal<string[]>([]);
  /** Visit count per house id; missing = not loaded yet. */
  private readonly visitCounts = signal<Record<string, number>>({});
  /**
   * The selection is chosen once per page, not again on every sync: from `?ids=` when the URL has it (Back from a
   * house, a bookmark), else the default pick.
   */
  private selectionChosen = false;
  /** The picker's search text (only offered above {@link SEARCH_ABOVE} candidates). */
  protected readonly pickerQuery = signal('');

  protected readonly statusKey = STATUS_KEY;
  protected readonly statusIcon = STATUS_ICON;
  protected readonly telHref = telHref;
  protected readonly glyphs = GLYPHS;
  protected readonly maxSelected = MAX_SELECTED;
  protected readonly minSelected = MIN_SELECTED;

  /** Non-rejected houses, shortlisted first, then by score. */
  protected readonly candidates = computed(() =>
    this.houses()
      .filter((h) => h.status !== 'REJECTED' && !h.deleted)
      .map((h) => ({ house: h, score: houseScore(h) }))
      .sort((a, b) => {
        const s = Number(b.house.status === 'SHORTLISTED') - Number(a.house.status === 'SHORTLISTED');
        return s !== 0 ? s : (b.score ?? -1) - (a.score ?? -1);
      }),
  );

  protected readonly showPickerSearch = computed(() => this.candidates().length > SEARCH_ABOVE);

  /** The chips on show: those matching the search, and always the selected ones (so none can be lost from view). */
  protected readonly visibleCandidates = computed(() => {
    const q = this.pickerQuery().trim().toLowerCase();
    const all = this.candidates();
    if (!q || !this.showPickerSearch()) return all;
    const selected = new Set(this.selectedIds());
    return all.filter(
      (c) =>
        selected.has(c.house.id) ||
        [c.house.label, c.house.street, c.house.locality].some((x) => !!x && x.toLowerCase().includes(q)),
    );
  });

  protected readonly selected = computed<HouseDto[]>(() => {
    const byId = new Map(this.houses().map((h) => [h.id, h] as const));
    return this.selectedIds()
      .map((id) => byId.get(id))
      .filter((h): h is HouseDto => !!h);
  });

  /** Rebuilt when the selection, visit counts or the language change (i18n.t reads the lang signal). */
  protected readonly rows = computed<Row[]>(() => {
    const i18n = this.i18n;
    const houses = this.selected();
    const visits = this.visitCounts();
    const none: Cell = { text: '–', srText: i18n.t('compare.noValue') };
    const plain = (text: string | null): Cell => (text ? { text } : none);
    const rows: Row[] = [];
    const scores = houses.map((h) => houseScore(h));
    rows.push({
      id: 'score',
      label: i18n.t('compare.overall'),
      cells: scores.map((s) => (s === null ? none : { text: i18n.score(s) })),
      best: bestOf(scores),
    });
    rows.push({
      id: 'price',
      label: i18n.t('compare.price'),
      cells: houses.map((h) => plain(h.price != null ? i18n.price(h.price, h.priceType) : null)),
      best: new Set<number>(),
    });
    rows.push({
      id: 'bhk',
      label: i18n.t('compare.bhk'),
      cells: houses.map((h) => plain(h.bedrooms != null ? i18n.number(h.bedrooms) : null)),
      best: new Set<number>(),
    });
    // Slice 1a: the carpet area and what the house really costs (docs/11 5.21), the same arithmetic as the form.
    rows.push({
      id: 'area',
      label: i18n.t('compare.area'),
      cells: houses.map((h) => plain(h.areaSqft != null ? i18n.t('common.sqft', { n: i18n.number(h.areaSqft) }) : null)),
      best: new Set<number>(),
    });
    const summaries = houses.map((h) => costSummary(h));
    const money = (v: number | null) => plain(v !== null ? i18n.price(v, null) : null);
    rows.push({ id: 'monthlyCost', label: i18n.t('cost.monthlyCost'), cells: summaries.map((s) => money(s.monthlyCost)), best: new Set<number>() });
    rows.push({ id: 'moveIn', label: i18n.t('cost.moveIn'), cells: summaries.map((s) => money(s.moveIn)), best: new Set<number>() });
    rows.push({
      id: 'perSqFt',
      label: i18n.t('cost.perSqFt'),
      cells: summaries.map((s) => money(s.perSqFt !== null ? Math.round(s.perSqFt) : null)),
      best: new Set<number>(),
    });
    rows.push({
      id: 'availableFrom',
      label: i18n.t('cost.availableFrom'),
      cells: houses.map((h) => plain(h.cost?.availableFrom ?? null)),
      best: new Set<number>(),
    });
    rows.push({
      id: 'agreedPrice',
      label: i18n.t('cost.agreedPrice'),
      cells: houses.map((h) => money(h.cost?.agreedPrice ?? null)),
      best: new Set<number>(),
    });
    // Slice 1c: the number of rooms, and their total area when at least one has both sizes (the unit is the setting).
    const unit = this.lengthUnit();
    rows.push({
      id: 'rooms',
      label: i18n.t('compare.rooms'),
      cells: houses.map((h) => {
        const list = h.rooms ?? [];
        if (list.length === 0) return none;
        const { total, sized } = totalAreaSqCm(list);
        if (sized === 0) return { text: i18n.number(list.length) };
        const area =
          unit === 'M'
            ? i18n.t('rooms.sqm', { v: areaNumber(total, unit) })
            : i18n.t('rooms.sqft', { v: i18n.number(Number(areaNumber(total, unit))) });
        return { text: `${i18n.number(list.length)} · ${area}` };
      }),
      best: new Set<number>(),
    });
    rows.push({
      id: 'rating',
      label: i18n.t('compare.rating'),
      cells: houses.map((h) =>
        h.rating
          ? {
              text: '★'.repeat(h.rating) + '☆'.repeat(Math.max(0, 5 - h.rating)),
              srText: i18n.t('common.stars', { n: h.rating }),
            }
          : none,
      ),
      best: new Set<number>(),
    });
    rows.push({
      id: 'visits',
      label: i18n.t('compare.visits'),
      cells: houses.map((h) =>
        visits[h.id] === undefined ? { text: '…', srText: i18n.t('common.loading') } : { text: i18n.number(visits[h.id]) },
      ),
      best: new Set<number>(),
    });
    rows.push({
      id: 'street',
      label: i18n.t('compare.street'),
      cells: houses.map((h) => plain([h.street, h.locality].filter((x) => !!x).join(', ') || null)),
      best: new Set<number>(),
    });
    for (const item of CHECKLIST) {
      const values = houses.map((h) => {
        const v = h.checklist[item.key];
        return typeof v === 'number' ? v : null;
      });
      rows.push({
        id: 'check-' + item.key,
        label: i18n.t(item.labelKey),
        cells: values.map((v) => (v === null ? none : { text: i18n.number(v) })),
        best: bestOf(values),
      });
    }
    return rows;
  });

  constructor() {
    // Load now, and again after writes to this browser's store (coalesced: a sync pull writes row by row).
    effect(() => {
      this.api.settled();
      this.reload();
    });
    effect(() => {
      this.api.settled();
      this.store.lengthUnit().then((u) => this.lengthUnit.set(u), () => undefined);
    });
    effect(() => {
      this.api.settled();
      this.api.brokers().subscribe({
        next: (rows) => this.brokerLines.set(new Map(rows.map((r) => [r.id, brokerLine(r.broker)]))),
        error: () => this.brokerLines.set(new Map()),
      });
    });
    // Visit counts for the selected houses, again after any local write: a visit added on the house screen, or one
    // that arrived with a sync pull, changes these numbers. One read of the visits store for all of them.
    effect(() => {
      this.api.settled();
      const ids = this.selectedIds();
      if (ids.length === 0) return;
      this.api.visitCounts().subscribe((counts) =>
        this.visitCounts.update((c) => ({ ...c, ...Object.fromEntries(ids.map((id) => [id, counts.get(id) ?? 0])) })),
      );
    });
  }

  /**
   * Re-reads the houses after writes to this browser's store (see `LocalDataService.settled`), so a sync
   * pull or an edit made in another tab shows up here instead of waiting for a page reload. The **selection** is
   * only chosen on the first load: picking it again on every sync would move the columns under the user.
   * `userAsked` is Retry: its failure is a new run and is read again even with the same words (see nextRunResult).
   */
  /** Who to call for a house: the linked broker's name and agency, else the contact name the house holds. */
  protected contactOf(h: HouseDto): string | null {
    return (h.brokerId ? this.brokerLines().get(h.brokerId) : undefined) ?? h.contactName ?? null;
  }

  protected reload(userAsked = false): void {
    this.api.houses().subscribe({
      next: (list) => {
        this.houses.set(list);
        this.error.set(null);
        this.loading.set(false);
        if (this.selectionChosen) return;
        this.selectionChosen = true;
        const cands = this.candidates();
        const fromUrl = idsFromQuery(
          this.route.snapshot.queryParamMap.get('ids'),
          new Set(cands.map((c) => c.house.id)),
        );
        if (fromUrl !== null) {
          this.selectedIds.set(fromUrl);
          return;
        }
        const shortlisted = cands.filter((c) => c.house.status === 'SHORTLISTED');
        const initial = (shortlisted.length >= MIN_SELECTED ? shortlisted : cands).slice(0, 3);
        this.selectedIds.set(initial.map((c) => c.house.id));
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
  }

  protected isSelected(id: string): boolean {
    return this.selectedIds().includes(id);
  }

  /** True for a chip that cannot be chosen now: four are chosen and this is not one of them. */
  protected isBlocked(id: string): boolean {
    return !this.isSelected(id) && this.selectedIds().length >= MAX_SELECTED;
  }

  /**
   * A chip was pressed. A blocked chip stays focusable (`aria-disabled`, not `disabled`, which would take it out of
   * the tab order and leave a screen reader saying only "dimmed") and says why nothing happened. The selection goes
   * into the URL (`?ids=`), replacing the entry, so Back from a house comes back to the same comparison and a
   * comparison can be bookmarked.
   */
  protected toggle(id: string): void {
    const ids = this.selectedIds();
    let next: string[];
    if (ids.includes(id)) {
      next = ids.filter((x) => x !== id);
    } else if (ids.length < MAX_SELECTED) {
      next = [...ids, id];
    } else {
      this.announcer.announce({
        key: 'compare.choose',
        params: { min: MIN_SELECTED, max: MAX_SELECTED, n: ids.length },
      });
      return;
    }
    this.selectedIds.set(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { ids: next.join(',') },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected onPickerSearch(event: Event): void {
    this.pickerQuery.set((event.target as HTMLInputElement).value);
  }
}

/** Indices holding the highest value, when at least one value exists and not every value ties. */
function bestOf(values: (number | null)[]): Set<number> {
  const present = values.filter((v): v is number => v !== null);
  if (present.length === 0) return new Set<number>();
  const max = Math.max(...present);
  const best = new Set<number>();
  values.forEach((v, i) => {
    if (v === max) best.add(i);
  });
  return best.size === values.length && values.length > 1 ? new Set<number>() : best;
}
