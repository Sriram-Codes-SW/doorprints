import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HouseApiService } from '../../core/house-api.service';
import { CHECKLIST, HouseDto, STATUS_ICON, STATUS_KEY, houseScore } from '../../core/models';
import { errorMsg, telHref } from '../../core/format';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';

const MAX_SELECTED = 4;
const MIN_SELECTED = 2;

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
export class ComparePage implements OnInit {
  private readonly api = inject(HouseApiService);
  protected readonly i18n = inject(TranslationService);

  protected readonly loading = signal(true);
  protected readonly error = signal<Msg | null>(null);
  private readonly houses = signal<HouseDto[]>([]);
  protected readonly selectedIds = signal<string[]>([]);
  /** Visit count per house id; missing = not loaded yet. */
  private readonly visitCounts = signal<Record<string, number>>({});
  private readonly requestedVisits = new Set<string>();

  protected readonly statusKey = STATUS_KEY;
  protected readonly statusIcon = STATUS_ICON;
  protected readonly telHref = telHref;
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
    // Fetch visit counts for newly selected houses.
    effect(() => {
      for (const id of this.selectedIds()) {
        if (this.requestedVisits.has(id)) continue;
        this.requestedVisits.add(id);
        this.api.visits(id).subscribe({
          next: (list) =>
            this.visitCounts.update((c) => ({ ...c, [id]: list.filter((v) => !v.deleted).length })),
          error: () => this.requestedVisits.delete(id),
        });
      }
    });
  }

  ngOnInit(): void {
    this.api.houses().subscribe({
      next: (list) => {
        this.houses.set(list);
        this.loading.set(false);
        const cands = this.candidates();
        const shortlisted = cands.filter((c) => c.house.status === 'SHORTLISTED');
        const initial = (shortlisted.length >= MIN_SELECTED ? shortlisted : cands).slice(0, 3);
        this.selectedIds.set(initial.map((c) => c.house.id));
      },
      error: (err: unknown) => {
        this.error.set(errorMsg(err));
        this.loading.set(false);
      },
    });
  }

  protected isSelected(id: string): boolean {
    return this.selectedIds().includes(id);
  }

  protected toggle(id: string): void {
    const ids = this.selectedIds();
    if (ids.includes(id)) {
      this.selectedIds.set(ids.filter((x) => x !== id));
    } else if (ids.length < MAX_SELECTED) {
      this.selectedIds.set([...ids, id]);
    }
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
