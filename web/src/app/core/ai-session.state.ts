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
