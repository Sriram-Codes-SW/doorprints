import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import type { Msg } from '../i18n/translation.service';
import { ConfigService } from './config.service';
import { errorMsg } from './format';
import type { HouseStatus, PriceType } from './models';

// Types follow docs/ai/ai-design.md section 13 and the backend records in com.househunt.ai.*
// (AiStatusController.AiStatus, extract.HouseDraft, rag.AskModels, agent.PlanModels).

export interface AiStatus {
  enabled: boolean;
  mcpEnabled: boolean;
  chatModel: string | null;
  embeddingModel: string | null;
}

/** A suggestion only; nothing is saved until the user saves the form. */
export interface HouseDraft {
  label: string | null;
  address: string | null;
  street: string | null;
  locality: string | null;
  price: number | null;
  priceType: PriceType | null;
  bedrooms: number | null;
  contactName: string | null;
  contactPhone: string | null;
  listingUrl: string | null;
  notes: string | null;
  amenities: string[];
  warnings: string[];
}

export interface AskFilters {
  status?: HouseStatus;
  priceType?: PriceType;
  maxPrice?: number;
  minBedrooms?: number;
  minRating?: number;
}

export interface Citation {
  houseId: string;
  label: string | null;
  snippet: string | null;
}

export interface AskResponse {
  answer: string;
  citations: Citation[];
  grounded: boolean;
  retrieved: number;
}

export interface PlanRequest {
  question: string;
  startLat: number;
  startLon: number;
  maxStops?: number;
}

export interface PlannedStop {
  order: number;
  houseId: string;
  label: string | null;
  lat: number;
  lon: number;
  reason: string | null;
  legMeters: number;
  walkMinutes: number;
}

export interface PlanResponse {
  summary: string | null;
  stops: PlannedStop[];
  totalMeters: number;
  totalWalkMinutes: number;
  toolCalls: string[];
  fallback: boolean;
}

/** Same limits as the server defaults (app.ai.max-input-chars / max-question-chars). */
export const AI_MAX_LISTING_CHARS = 8000;
export const AI_MAX_QUESTION_CHARS = 1000;

/**
 * Optional AI features. Everything AI in the UI is hidden unless GET /api/ai/status says `enabled`
 * (it is off by default on the server, and an unreachable server counts as off).
 */
@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ConfigService);
  private readonly status = signal<AiStatus | null>(null);
  readonly enabled = computed(() => this.status()?.enabled === true);

  constructor() {
    this.refresh();
  }

  /** Re-reads the status (after connecting or disconnecting). */
  refresh(): void {
    if (!this.config.configured()) {
      this.status.set(null);
      return;
    }
    this.http.get<AiStatus>('/api/ai/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set(null),
    });
  }

  extractListing(text: string): Observable<HouseDraft> {
    return this.http.post<HouseDraft>('/api/ai/extract-listing', { text });
  }

  ask(question: string, filters?: AskFilters): Observable<AskResponse> {
    return this.http.post<AskResponse>('/api/ai/ask', filters ? { question, filters } : { question });
  }

  planVisits(request: PlanRequest): Observable<PlanResponse> {
    return this.http.post<PlanResponse>('/api/ai/plan-visits', request);
  }
}

/** Translated message for AI failures: 429 with Retry-After, 503 provider down/quota, else the generic mapping. */
export function aiErrorMsg(err: unknown): Msg {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 429) {
      const seconds = Number.parseInt(err.headers.get('Retry-After') ?? '', 10);
      return { key: 'ai.rateLimited', params: { s: Number.isFinite(seconds) ? seconds : 60 } };
    }
    if (err.status === 503) return { key: 'ai.providerDown' };
    if (err.status === 404) return { key: 'ai.disabled' };
  }
  return errorMsg(err);
}

/** Splits an answer into text and [house:<id>] citation markers, so markers can become links. */
export type AnswerPart = { kind: 'text'; text: string } | { kind: 'cite'; houseId: string };

export function splitCitations(answer: string): AnswerPart[] {
  const parts: AnswerPart[] = [];
  const re = /\[house:([0-9a-fA-F-]{36})\]/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(answer)) !== null) {
    if (m.index > last) parts.push({ kind: 'text', text: answer.slice(last, m.index) });
    parts.push({ kind: 'cite', houseId: m[1] });
    last = m.index + m[0].length;
  }
  if (last < answer.length) parts.push({ kind: 'text', text: answer.slice(last) });
  return parts;
}
