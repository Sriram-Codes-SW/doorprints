import { Injectable } from '@angular/core';
import type { AskResponse, PlanResponse } from './ai.service';
import type { HouseStatus } from './models';

/** The Ask page as the user left it: the question, the filters and the answer. */
export interface AskSession {
  question: string;
  status: HouseStatus | '';
  maxPrice: number | null;
  minBedrooms: number | null;
  result: AskResponse | null;
}

/** The Plan page as the user left it: the request, the start point and the route. */
export interface PlanSession {
  question: string;
  maxStops: number;
  start: { lat: number; lon: number };
  startSet: boolean;
  plan: PlanResponse | null;
}

/**
 * Keeps the last Ask answer and Plan route for the rest of this visit (UX audit 2026-09-23).
 *
 * Both pages exist to send the user on to a house — a citation, a stop — and Back used to rebuild them empty, so
 * getting the answer again cost another paid or rate-limited AI call. Memory only, deliberately: a question about
 * one's houses and an answer quoting their notes are not written to storage, and a reload starts clean. Cleared on
 * Disconnect and on "Remove all data".
 */
@Injectable({ providedIn: 'root' })
export class AiSessionState {
  ask: AskSession | null = null;
  plan: PlanSession | null = null;

  clear(): void {
    this.ask = null;
    this.plan = null;
  }
}
