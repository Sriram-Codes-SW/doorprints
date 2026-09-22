import { Component, ElementRef, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { HouseApiService } from '../../core/house-api.service';
import { GeocodeService } from '../../core/geocode.service';
import {
  CHECKLIST,
  HouseDto,
  HouseStatus,
  STATUSES,
  STATUS_COLOR,
  STATUS_ICON,
  STATUS_KEY,
  VisitDto,
  houseScore,
  newHouse,
  uuid,
} from '../../core/models';
import { errorMsg, telHref } from '../../core/format';
import { Announcer } from '../../core/announcer.service';
import { resizeImage } from '../../core/image-resize';
import { LatLon, LocationMap, round6 } from '../../shared/location-map';
import { AuthImage } from '../../shared/auth-image';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { ConfirmService } from '../../core/confirm.service';
import { AI_MAX_LISTING_CHARS, AiService, HouseDraft, aiErrorMsg } from '../../core/ai.service';
import { TPipe } from '../../i18n/t.pipe';

interface OpenPhoto {
  src: string;
  alt: string;
}

@Component({
  selector: 'app-house-detail-page',
  imports: [FormsModule, RouterLink, LocationMap, AuthImage, TPipe],
  templateUrl: './house-detail-page.html',
  styleUrl: './house-detail-page.css',
})
export class HouseDetailPage implements OnInit {
  private readonly api = inject(HouseApiService);
  private readonly geocode = inject(GeocodeService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  protected readonly ai = inject(AiService);
  protected readonly i18n = inject(TranslationService);

  // "Import from listing text" (AI, new houses only; hidden unless the server has AI enabled).
  protected listingText = '';
  protected readonly listingMax = AI_MAX_LISTING_CHARS;
  protected readonly importing = signal(false);
  protected readonly importWarnings = signal<string[]>([]);

  protected readonly draft = signal<HouseDto | null>(null);
  protected readonly isNew = signal(false);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly justSaved = signal(false);
  protected readonly nameError = signal(false);
  protected readonly error = signal<Msg | null>(null);
  protected readonly geocoding = signal(false);

  protected readonly visits = signal<VisitDto[]>([]);
  protected readonly sortedVisits = computed(() =>
    this.visits()
      .filter((v) => !v.deleted)
      .sort((a, b) => Date.parse(b.arrivedAt) - Date.parse(a.arrivedAt)),
  );
  protected readonly markingVisit = signal(false);

  protected readonly photoIds = signal<string[]>([]);
  protected readonly uploading = signal(0);
  protected readonly lightbox = signal<OpenPhoto | null>(null);
  private readonly viewer = viewChild<ElementRef<HTMLDialogElement>>('viewer');

  protected readonly checklist = CHECKLIST;
  protected readonly statuses = STATUSES;
  protected readonly statusKey = STATUS_KEY;
  protected readonly statusIcon = STATUS_ICON;
  protected readonly statusColor = STATUS_COLOR;
  protected readonly checkValues: readonly number[] = [0, 1, 2, 3, 4, 5];
  protected readonly starValues: readonly number[] = [1, 2, 3, 4, 5];
  protected readonly telHref = telHref;

  constructor() {
    // Native <dialog>.showModal() gives focus trapping, Esc to close and focus return for free.
    effect(() => {
      const open = this.lightbox() !== null;
      const dialog = this.viewer()?.nativeElement;
      if (!dialog) return;
      if (open && !dialog.open) dialog.showModal();
      if (!open && dialog.open) dialog.close();
    });
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      const q = this.route.snapshot.queryParamMap;
      const lat = Number.parseFloat(q.get('lat') ?? '');
      const lon = Number.parseFloat(q.get('lon') ?? '');
      this.isNew.set(true);
      this.draft.set(newHouse(Number.isFinite(lat) ? lat : 0, Number.isFinite(lon) ? lon : 0));
      this.loading.set(false);
      return;
    }
    this.api.house(id).subscribe({
      next: (h) => {
        this.draft.set(h);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(errorMsg(err));
        this.loading.set(false);
      },
    });
    this.api.visits(id).subscribe({
      next: (v) => this.visits.set(v),
      error: () => this.visits.set([]),
    });
    this.api.photoIds(id).subscribe({
      next: (ids) => this.photoIds.set(ids),
      error: () => this.photoIds.set([]),
    });
  }

  /** Route guard hook: warn before leaving with unsaved edits (in-app dialog, app language). */
  canLeave(): boolean | Promise<boolean> {
    if (!this.dirty()) return true;
    return this.confirm.ask({ key: 'confirm.leaveUnsaved' }, { confirmKey: 'confirm.leave', danger: true });
  }

  /**
   * Sends pasted listing text to POST /api/ai/extract-listing and pre-fills the empty form fields. Nothing is saved:
   * the user reviews, picks the map location and presses Create (docs/ai AI-004 human confirmation).
   */
  protected importListing(): void {
    const text = this.listingText.trim();
    if (!text || this.importing()) return;
    this.importing.set(true);
    this.error.set(null);
    this.importWarnings.set([]);
    this.ai.extractListing(text).subscribe({
      next: (draft) => {
        this.applyDraft(draft);
        this.importWarnings.set(draft.warnings ?? []);
        this.importing.set(false);
        this.announcer.announce({ key: 'import.done' });
        document.getElementById('house-name')?.focus();
      },
      error: (err: unknown) => {
        this.error.set({ key: 'import.failed', params: { reason: aiErrorMsg(err) } });
        this.importing.set(false);
      },
    });
  }

  private applyDraft(a: HouseDraft): void {
    const d = this.draft();
    if (!d) return;
    const pick = <T>(incoming: T | null | undefined, current: T | null | undefined): T | null =>
      incoming !== null && incoming !== undefined && incoming !== '' ? incoming : (current ?? null);
    const extra = [a.notes, a.amenities?.length ? a.amenities.join(', ') : null].filter((x): x is string => !!x);
    const notes = [d.notes, ...extra].filter((x): x is string => !!x && !!x.trim()).join('\n');
    this.patch({
      label: pick(a.label, d.label) ?? '',
      address: pick(a.address, d.address),
      street: pick(a.street, d.street),
      locality: pick(a.locality, d.locality),
      price: pick(a.price, d.price),
      priceType: pick(a.priceType, d.priceType),
      bedrooms: pick(a.bedrooms, d.bedrooms),
      contactName: pick(a.contactName, d.contactName),
      contactPhone: pick(a.contactPhone, d.contactPhone),
      listingUrl: pick(a.listingUrl, d.listingUrl),
      notes: notes || null,
    });
  }

  protected score(h: HouseDto): number | null {
    return houseScore(h);
  }

  protected markDirty(): void {
    this.dirty.set(true);
    this.justSaved.set(false);
    if (this.nameError() && this.draft()?.label.trim()) {
      this.nameError.set(false);
      if (this.error()?.key === 'house.nameRequired') this.error.set(null);
    }
  }

  private patch(changes: Partial<HouseDto>): void {
    const d = this.draft();
    if (!d) return;
    this.draft.set({ ...d, ...changes });
    this.markDirty();
  }

  protected setStatus(status: HouseStatus): void {
    this.patch({ status });
  }

  protected setRating(n: number | null): void {
    this.patch({ rating: n });
  }

  protected checkValue(h: HouseDto, key: string): number | null {
    const v = h.checklist[key];
    return typeof v === 'number' ? v : null;
  }

  protected setCheck(key: string, value: number | null): void {
    const d = this.draft();
    if (!d) return;
    const checklist: Record<string, number> = { ...d.checklist };
    if (value === null) {
      delete checklist[key];
    } else {
      checklist[key] = value;
    }
    this.patch({ checklist });
  }

  protected onMoved(p: LatLon): void {
    this.patch({ lat: p.lat, lon: p.lon });
  }

  /** Typed coordinates: the keyboard alternative to dragging the pin. */
  protected onCoord(axis: 'lat' | 'lon', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = Number.parseFloat(input.value);
    const limit = axis === 'lat' ? 90 : 180;
    const d = this.draft();
    if (!d) return;
    if (!Number.isFinite(value) || Math.abs(value) > limit) {
      input.value = String(d[axis]);
      this.error.set({ key: 'house.coordsInvalid' });
      return;
    }
    this.error.set(null);
    this.patch(axis === 'lat' ? { lat: round6(value) } : { lon: round6(value) });
  }

  protected fillAddress(): void {
    const d = this.draft();
    if (!d || this.geocoding()) return;
    this.geocoding.set(true);
    this.error.set(null);
    this.geocode.reverse(d.lat, d.lon).subscribe({
      next: (r) => {
        const cur = this.draft();
        if (cur) {
          this.patch({
            address: r.address ?? cur.address,
            street: r.street ?? cur.street,
            locality: r.locality ?? cur.locality,
            label: cur.label.trim() ? cur.label : (r.street ?? cur.label),
          });
          this.announcer.announce({ key: 'house.addressFilled' });
        }
        this.geocoding.set(false);
      },
      error: (err: unknown) => {
        this.error.set({ key: 'house.lookupFailed', params: { reason: errorMsg(err) } });
        this.geocoding.set(false);
      },
    });
  }

  protected save(): void {
    const d = this.draft();
    if (!d || this.saving()) return;
    const label = d.label.trim();
    if (!label) {
      this.nameError.set(true);
      this.error.set({ key: 'house.nameRequired' });
      document.getElementById('house-name')?.focus();
      return;
    }
    this.nameError.set(false);
    const body: HouseDto = {
      ...d,
      label,
      address: blankToNull(d.address),
      street: blankToNull(d.street),
      locality: blankToNull(d.locality),
      contactName: blankToNull(d.contactName),
      contactPhone: blankToNull(d.contactPhone),
      listingUrl: blankToNull(d.listingUrl),
      notes: blankToNull(d.notes),
      price: toWholeNumber(d.price),
      bedrooms: toWholeNumber(d.bedrooms),
    };
    this.saving.set(true);
    this.error.set(null);
    this.api.saveHouse(body).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dirty.set(false);
        this.announcer.announce({ key: 'house.saved' });
        if (this.isNew()) {
          void this.router.navigate(['/houses', saved.id], { replaceUrl: true });
        } else {
          this.draft.set(saved);
          this.justSaved.set(true);
        }
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set({ key: 'house.saveFailed', params: { reason: errorMsg(err) } });
      },
    });
  }

  protected async deleteHouse(): Promise<void> {
    const d = this.draft();
    if (!d) return;
    if (this.isNew()) {
      this.dirty.set(false);
      void this.router.navigate(['/']);
      return;
    }
    const name = d.label || this.i18n.t('house.thisHouse');
    const ok = await this.confirm.ask({ key: 'confirm.deleteHouse', params: { name } }, {
      confirmKey: 'house.delete',
      danger: true,
    });
    if (!ok) return;
    this.api.deleteHouse(d.id).subscribe({
      next: () => {
        this.dirty.set(false);
        this.announcer.announce({ key: 'house.deleted' });
        void this.router.navigate(['/']);
      },
      error: (err: unknown) => this.error.set({ key: 'house.deleteFailed', params: { reason: errorMsg(err) } }),
    });
  }

  protected markVisited(): void {
    const d = this.draft();
    if (!d || this.isNew() || this.markingVisit()) return;
    const now = new Date().toISOString();
    const visit: VisitDto = {
      id: uuid(),
      houseId: d.id,
      lat: d.lat,
      lon: d.lon,
      street: d.street ?? null,
      arrivedAt: now,
      leftAt: null,
      source: 'MANUAL',
      deleted: false,
      syncVersion: 0,
    };
    this.markingVisit.set(true);
    this.api.saveVisit(visit).subscribe({
      next: (saved) => {
        this.visits.update((list) => [saved ?? visit, ...list.filter((v) => v.id !== visit.id)]);
        this.markingVisit.set(false);
        this.announcer.announce({ key: 'house.visitRecorded' });
      },
      error: (err: unknown) => {
        this.error.set({ key: 'house.visitFailed', params: { reason: errorMsg(err) } });
        this.markingVisit.set(false);
      },
    });
  }

  protected async deleteVisit(v: VisitDto): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.removeVisit' }, { confirmKey: 'confirm.remove', danger: true });
    if (!ok) return;
    this.api.deleteVisit(v.id).subscribe({
      next: () => {
        this.visits.update((list) => list.filter((x) => x.id !== v.id));
        this.announcer.announce({ key: 'house.visitRemoved' });
        document.getElementById('visits-heading')?.focus();
      },
      error: (err: unknown) => this.error.set({ key: 'house.removeVisitFailed', params: { reason: errorMsg(err) } }),
    });
  }

  protected async onFiles(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files: File[] = input.files ? Array.from(input.files) : [];
    input.value = '';
    const d = this.draft();
    if (!d || this.isNew()) return;
    for (const file of files) {
      this.uploading.update((n) => n + 1);
      try {
        const blob = await resizeImage(file, 1600, 0.8);
        const res = await firstValueFrom(this.api.uploadPhoto(d.id, blob));
        this.photoIds.update((ids) => (ids.includes(res.id) ? ids : [...ids, res.id]));
        this.announcer.announce({ key: 'house.photoUploaded' });
      } catch (err: unknown) {
        this.error.set({ key: 'house.uploadFailed', params: { file: file.name, reason: errorMsg(err) } });
      } finally {
        this.uploading.update((n) => n - 1);
      }
    }
  }

  protected async deletePhoto(id: string): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.deletePhoto' }, { confirmKey: 'common.delete', danger: true });
    if (!ok) return;
    this.api.deletePhoto(id).subscribe({
      next: () => {
        this.photoIds.update((ids) => ids.filter((x) => x !== id));
        this.lightbox.set(null);
        this.announcer.announce({ key: 'house.photoDeleted' });
        document.getElementById('photos-heading')?.focus();
      },
      error: (err: unknown) => this.error.set({ key: 'house.deletePhotoFailed', params: { reason: errorMsg(err) } }),
    });
  }

  protected openPhoto(src: string, n: number): void {
    const name = this.draft()?.label || this.i18n.t('common.untitled');
    this.lightbox.set({ src, alt: this.i18n.t('house.photoAlt', { n, name }) });
  }

  protected closePhoto(): void {
    this.lightbox.set(null);
  }

  /** A click on the backdrop (the dialog element itself, outside its content) closes the viewer. */
  protected onViewerClick(event: MouseEvent): void {
    const dialog = this.viewer()?.nativeElement;
    if (dialog && event.target === dialog) dialog.close();
  }

  protected listingHref(url: string | null | undefined): string {
    const u = (url ?? '').trim();
    return /^[a-z][a-z0-9+.-]*:/i.test(u) ? u : `https://${u}`;
  }
}

function blankToNull(s: string | null | undefined): string | null {
  const t = (s ?? '').trim();
  return t ? t : null;
}

function toWholeNumber(n: number | null | undefined): number | null {
  if (n === null || n === undefined || typeof n !== 'number' || !Number.isFinite(n)) return null;
  return Math.max(0, Math.round(n));
}
