import { Component, Injector, OnDestroy, afterNextRender, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  AI_MAX_QUESTION_CHARS,
  AiService,
  AskFilters,
  AskResponse,
  aiErrorMsg,
  splitCitations,
} from '../../core/ai.service';
import { AiSessionState } from '../../core/ai-session.state';
import { Announcer } from '../../core/announcer.service';
import { ConfigService } from '../../core/config.service';
import { HouseStatus, STATUSES, STATUS_KEY } from '../../core/models';
import { Msg } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import { RunResult, runResult } from '../../shared/run-result';
import { focusIfLost } from '../../shared/focus';

/**
 * "Ask about my houses": RAG answers over the user's own houses, with every cited house linked (docs/ai 5.2).
 *
 * Following a citation and pressing Back comes back to the same question, filters and answer (AiSessionState), so
 * seeing it again costs no second AI call. The answer is not a live region: the announcer says "Answer ready" and
 * focus moves to the answer's heading, where a screen reader can read it at its own pace.
 *
 * Asking again after an error keeps the error card in place, drawn as being updated with "Thinking…" as its state,
 * until the new run ends (Android 1.33's rule, `RefreshableResultCard`), so the answer below does not jump up by the
 * card's height and back. The card is keyed on its run ({@link RunResult}), so a new failure is read even when its
 * words are the same.
 */
@Component({
  selector: 'app-ask-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './ask-page.html',
  styleUrl: './ask-page.css',
})
export class AskPage implements OnDestroy {
  protected readonly ai = inject(AiService);
  protected readonly config = inject(ConfigService);
  private readonly announcer = inject(Announcer);
  private readonly session = inject(AiSessionState);
  private readonly injector = inject(Injector);

  protected readonly maxChars = AI_MAX_QUESTION_CHARS;
  protected readonly statuses = STATUSES;
  protected readonly statusKey = STATUS_KEY;

  protected question = '';
  protected status: HouseStatus | '' = '';
  protected maxPrice: number | null = null;
  protected minBedrooms: number | null = null;

  protected readonly busy = signal(false);
  /** "Ask" was pressed with no question: said under the question box, which gets the focus. */
  protected readonly questionMissing = signal(false);
  /** The last run's failure; it stays on screen while the next run is busy, and that run's end replaces it. */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly result = signal<AskResponse | null>(null);
  private request: Subscription | null = null;

  /** Citation number per house id, in order of first appearance, e.g. "[1]". */
  private readonly citeNumbers = computed(() => {
    const map = new Map<string, number>();
    for (const c of this.result()?.citations ?? []) if (!map.has(c.houseId)) map.set(c.houseId, map.size + 1);
    return map;
  });

  /** The answer split into text and citation markers; markers render as numbered links to the house. */
  protected readonly parts = computed(() => splitCitations(this.result()?.answer ?? ''));

  constructor() {
    const saved = this.session.ask;
    if (saved) {
      this.question = saved.question;
      this.status = saved.status;
      this.maxPrice = saved.maxPrice;
      this.minBedrooms = saved.minBedrooms;
      this.result.set(saved.result);
    }
  }

  ngOnDestroy(): void {
    this.request?.unsubscribe();
    this.session.ask = {
      question: this.question,
      status: this.status,
      maxPrice: this.maxPrice,
      minBedrooms: this.minBedrooms,
      result: this.result(),
    };
  }

  protected labelOf(houseId: string): string {
    return this.result()?.citations.find((c) => c.houseId === houseId)?.label ?? '';
  }

  protected numberOf(houseId: string): number {
    return this.citeNumbers().get(houseId) ?? 0;
  }

  /** Typing a question clears "Type your question first". */
  protected onQuestion(value: string): void {
    if (this.questionMissing() && value.trim()) this.questionMissing.set(false);
  }

  protected submit(): void {
    if (this.busy()) return;
    const q = this.question.trim();
    if (!q) {
      // The button is aria-disabled, not disabled, so it can be pressed: say why nothing happened, where it is fixed.
      this.questionMissing.set(true);
      afterNextRender(() => document.getElementById('question')?.focus(), { injector: this.injector });
      return;
    }
    this.questionMissing.set(false);
    const filters: AskFilters = {};
    if (this.status) filters.status = this.status;
    if (this.maxPrice != null && this.maxPrice > 0) filters.maxPrice = Math.round(this.maxPrice);
    if (this.minBedrooms != null && this.minBedrooms > 0) filters.minBedrooms = Math.round(this.minBedrooms);
    // An earlier error stays in place while this run is busy (drawn as being updated); the run's end replaces it.
    this.busy.set(true);
    this.request = this.ai.ask(q, Object.keys(filters).length ? filters : undefined).subscribe({
      next: (r) => {
        this.request = null;
        this.error.set(null);
        this.result.set(r);
        this.busy.set(false);
        this.announcer.announce({ key: 'ask.answered' });
        afterNextRender(() => document.getElementById('answer-heading')?.focus(), { injector: this.injector });
      },
      error: (err: unknown) => {
        this.request = null;
        this.error.set(runResult(aiErrorMsg(err)));
        this.busy.set(false);
        // Cancel went away with the request: if it had focus, focus goes back to the button that sends it again.
        afterNextRender(() => focusIfLost('ask-submit'), { injector: this.injector });
      },
    });
  }

  /** "Cancel" while the answer is being prepared: drops the request, and focus goes back to "Ask". */
  protected cancel(): void {
    if (!this.request) return;
    this.request.unsubscribe();
    this.request = null;
    this.busy.set(false);
    this.announcer.announce({ key: 'ai.cancelled' });
    afterNextRender(() => document.getElementById('ask-submit')?.focus(), { injector: this.injector });
  }
}
