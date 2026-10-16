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
import { isBaselineKind } from '../core/kind-capabilities';
import { toolWord } from '../core/space-tool-words';
import { spaceWords, type SpaceWords } from '../core/space-words';

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
  /**
   * What a space of another kind shows instead of the house's text (kinds PR 31, docs/03 section 18.25; owner: a generic sentence, not a text per kind).
   * `word`: a word of the space (the Add button's name, the search's); `tool`: the house key read in the space's own words
   * (`toolWord`); `generic`: the one generic sentence of the tour, which names the kind's plural. A part left out stays the house's.
   */
  readonly kind?: { readonly title?: KindText; readonly body?: KindText; readonly action?: KindText };
  /** The step opens a tool of the space on screen (Compare), on the address that kind has for it. */
  readonly tool?: 'compare';
}

export type KindText = 'generic' | { readonly word: keyof SpaceWords } | { readonly tool: TKey };

/** What a tour step says in a space: the keys to show, and the parameters of the generic sentences. */
export interface TourText {
  readonly title: TKey;
  readonly body: TKey;
  readonly action: TKey;
  /** The kind's plural, as a key: the generic sentences name it as `{many}` (the caller translates it). */
  readonly many: TKey;
}

/**
 * The tour, in the order a house hunt goes: place a house, open it, find it again, walk, compare, rank, plan viewings,
 * keep the data, back it up, AI, get help. Targets are ids already on the pages; a target that is missing (an empty list, a hidden
 * card) never stops the tour, the card is shown without the highlight.
 */
export const TOUR_STEPS: readonly TourStep[] = [
  { id: 'welcome', route: '/', title: 'tour.welcome.title', body: 'tour.welcome.body', action: 'tour.welcome.action', kind: { body: 'generic' } },
  { id: 'language', route: '/', target: '#lang-select', title: 'tour.language.title', body: 'tour.language.body', action: 'tour.language.action' },
  { id: 'add', route: '/', target: '#add-toggle', title: 'tour.add.title', body: 'tour.add.body', action: 'tour.add.action', kind: { title: { word: 'add' }, body: { word: 'addHint' }, action: { word: 'addHintShort' } } },
  { id: 'house', route: '/', title: 'tour.house.title', body: 'tour.house.body', action: 'tour.house.action', kind: { title: { tool: 'title.house' }, body: 'generic', action: 'generic' } },
  { id: 'find', route: '/', target: '#house-search', title: 'tour.find.title', body: 'tour.find.body', action: 'tour.find.action', kind: { title: { word: 'search' }, body: 'generic', action: 'generic' } },
  { id: 'offline', route: '/', target: 'app-offline-save', title: 'tour.offline.title', body: 'tour.offline.body', action: 'tour.offline.action' },
  { id: 'trace', route: '/', target: 'app-trace-card summary', title: 'tour.trace.title', body: 'tour.trace.body', action: 'tour.trace.action', kind: { body: 'generic' } },
  { id: 'check', route: '/', target: '#place-check-open', title: 'tour.check.title', body: 'tour.check.body', action: 'tour.check.action', kind: { body: 'generic' } },
  { id: 'compare', route: '/compare', target: '#picker-search || main h1', title: 'tour.compare.title', body: 'tour.compare.body', action: 'tour.compare.action', tool: 'compare', kind: { title: { tool: 'compare.title' }, body: 'generic', action: 'generic' } },
  { id: 'criteria', route: '/criteria', target: '#criteria-new', title: 'tour.criteria.title', body: 'tour.criteria.body', action: 'tour.criteria.action', kind: { body: 'generic' } },
  { id: 'viewings', route: '/viewings', target: 'main h1', title: 'tour.viewings.title', body: 'tour.viewings.body', action: 'tour.viewings.action', kind: { title: { tool: 'viewings.title' }, body: 'generic', action: 'generic' } },
  { id: 'brokers', route: '/data', target: '#brokers-heading', title: 'tour.brokers.title', body: 'tour.brokers.body', action: 'tour.brokers.action', kind: { body: 'generic', action: 'generic' } },
  { id: 'save', route: '/data', target: '#export-heading', title: 'tour.save.title', body: 'tour.save.body', action: 'tour.save.action' },
  { id: 'drive', route: '/data', target: '#drive-heading', title: 'tour.drive.title', body: 'tour.drive.body', action: 'tour.drive.action' },
  { id: 'sync', route: '/data', target: '#sync-heading', title: 'tour.sync.title', body: 'tour.sync.body', action: 'tour.sync.action', kind: { body: 'generic' } },
  { id: 'ai', route: '/connect', target: '#ai-heading', title: 'tour.ai.title', body: 'tour.ai.body', action: 'tour.ai.action', kind: { body: 'generic' } },
  { id: 'install', route: '/data', target: '#install-heading', title: 'tour.install.title', body: 'tour.install.body', action: 'tour.install.action' },
  { id: 'help', route: '/data', target: '#help-heading', title: 'tour.help.title', body: 'tour.help.body', action: 'tour.help.action' },
  { id: 'done', route: '/', title: 'tour.done.title', body: 'tour.done.body', action: 'tour.done.action', kind: { body: 'generic', action: 'generic' } },
];

/** The generic sentences, by part and step id (`*`: every other step). */
const GENERIC: Readonly<Record<'body' | 'action', Readonly<Record<string, TKey>>>> = {
  body: { '*': 'tour.kind.body', welcome: 'tour.kind.welcome', done: 'tour.kind.doneBody' },
  action: { '*': 'tour.kind.action', done: 'tour.kind.doneAction' },
};

/**
 * The text of a tour step in the space of `kind`. The baseline kind (the house) gets the step's own keys, so the house tour is word for
 * word what it was; any other kind gets its own words where the step names a thing the kind words (the Add button, the search, the
 * details, Compare, the viewings) and one generic sentence where the house's text says "house", with the kind's plural filled in.
 */
export function tourText(step: TourStep, kind: string): TourText {
  const base: TourText = { title: step.title, body: step.body, action: step.action, many: 'map.stats.houses' };
  if (isBaselineKind(kind) || !step.kind) return base;
  const words = spaceWords(kind);
  const pick = (part: 'title' | 'body' | 'action', own: TKey): TKey => {
    const k = step.kind?.[part];
    if (!k) return own;
    if (k === 'generic') return part === 'title' ? own : (GENERIC[part][step.id] ?? GENERIC[part]['*']);
    return 'word' in k ? (words[k.word] as TKey) : toolWord(kind, k.tool);
  };
  return {
    title: pick('title', step.title),
    body: pick('body', step.body),
    action: pick('action', step.action),
    many: words.many,
  };
}
