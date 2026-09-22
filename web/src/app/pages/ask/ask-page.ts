import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AI_MAX_QUESTION_CHARS,
  AiService,
  AskFilters,
  AskResponse,
  aiErrorMsg,
  splitCitations,
} from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { HouseStatus, STATUSES, STATUS_KEY } from '../../core/models';
import { Msg } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';

/** "Ask about my houses": RAG answers over the user's own houses, with every cited house linked (docs/ai 5.2). */
@Component({
  selector: 'app-ask-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './ask-page.html',
  styleUrl: './ask-page.css',
})
export class AskPage {
  protected readonly ai = inject(AiService);
  private readonly announcer = inject(Announcer);

  protected readonly maxChars = AI_MAX_QUESTION_CHARS;
  protected readonly statuses = STATUSES;
  protected readonly statusKey = STATUS_KEY;

  protected question = '';
  protected status: HouseStatus | '' = '';
  protected maxPrice: number | null = null;
  protected minBedrooms: number | null = null;

  protected readonly busy = signal(false);
  protected readonly error = signal<Msg | null>(null);
  protected readonly result = signal<AskResponse | null>(null);

  /** Citation number per house id, in order of first appearance, e.g. "[1]". */
  private readonly citeNumbers = computed(() => {
    const map = new Map<string, number>();
    for (const c of this.result()?.citations ?? []) if (!map.has(c.houseId)) map.set(c.houseId, map.size + 1);
    return map;
  });

  /** The answer split into text and citation markers; markers render as numbered links to the house. */
  protected readonly parts = computed(() => splitCitations(this.result()?.answer ?? ''));

  protected labelOf(houseId: string): string {
    return this.result()?.citations.find((c) => c.houseId === houseId)?.label ?? '';
  }

  protected numberOf(houseId: string): number {
    return this.citeNumbers().get(houseId) ?? 0;
  }

  protected submit(): void {
    const q = this.question.trim();
    if (!q || this.busy()) return;
    const filters: AskFilters = {};
    if (this.status) filters.status = this.status;
    if (this.maxPrice != null && this.maxPrice > 0) filters.maxPrice = Math.round(this.maxPrice);
    if (this.minBedrooms != null && this.minBedrooms > 0) filters.minBedrooms = Math.round(this.minBedrooms);
    this.busy.set(true);
    this.error.set(null);
    this.ai.ask(q, Object.keys(filters).length ? filters : undefined).subscribe({
      next: (r) => {
        this.result.set(r);
        this.busy.set(false);
        this.announcer.announce({ key: 'ask.answered' });
      },
      error: (err: unknown) => {
        this.error.set(aiErrorMsg(err));
        this.busy.set(false);
      },
    });
  }
}
