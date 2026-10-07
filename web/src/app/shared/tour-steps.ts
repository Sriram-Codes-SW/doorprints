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

import type { TKey } from '../i18n/en';

/** One stop of the guided tour: where it is, what it points at, and what the person is asked to do there. */
export interface TourStep {
  readonly id: string;
  /** The route the step is on; the tour navigates there first. */
  readonly route: string;
  /** A CSS selector for the one thing to highlight (several, separated by ` || `: the first one on the page); absent, or not on the page, the step is a plain centred card. */
  readonly target?: string;
  readonly title: TKey;
  /** What the feature is for. */
  readonly body: TKey;
  /** The action to try, one sentence starting with a verb. */
  readonly action: TKey;
}

/**
 * The tour, in the order a house hunt goes: place a house, open it, find it again, walk, compare, rank, plan viewings,
 * keep the data, get help. Targets are ids already on the pages; a target that is missing (an empty list, a hidden
 * card) never stops the tour, the card is shown without the highlight.
 */
export const TOUR_STEPS: readonly TourStep[] = [
  { id: 'welcome', route: '/', title: 'tour.welcome.title', body: 'tour.welcome.body', action: 'tour.welcome.action' },
  { id: 'add', route: '/', target: '#add-toggle', title: 'tour.add.title', body: 'tour.add.body', action: 'tour.add.action' },
  { id: 'house', route: '/', title: 'tour.house.title', body: 'tour.house.body', action: 'tour.house.action' },
  { id: 'find', route: '/', target: '#house-search', title: 'tour.find.title', body: 'tour.find.body', action: 'tour.find.action' },
  { id: 'trace', route: '/', target: '#trace-on', title: 'tour.trace.title', body: 'tour.trace.body', action: 'tour.trace.action' },
  { id: 'compare', route: '/compare', target: '#picker-search || main h1', title: 'tour.compare.title', body: 'tour.compare.body', action: 'tour.compare.action' },
  { id: 'criteria', route: '/criteria', target: '#criteria-new', title: 'tour.criteria.title', body: 'tour.criteria.body', action: 'tour.criteria.action' },
  { id: 'viewings', route: '/viewings', target: 'main h1', title: 'tour.viewings.title', body: 'tour.viewings.body', action: 'tour.viewings.action' },
  { id: 'save', route: '/data', target: '#export-heading', title: 'tour.save.title', body: 'tour.save.body', action: 'tour.save.action' },
  { id: 'sync', route: '/data', target: '#sync-heading', title: 'tour.sync.title', body: 'tour.sync.body', action: 'tour.sync.action' },
  { id: 'help', route: '/data', target: '#help-heading', title: 'tour.help.title', body: 'tour.help.body', action: 'tour.help.action' },
  { id: 'done', route: '/', title: 'tour.done.title', body: 'tour.done.body', action: 'tour.done.action' },
];
