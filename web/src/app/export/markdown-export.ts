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

import type { Dict } from '../i18n/en';
import {
  escapeMarkdown,
  formatDateTime,
  formatDecimal,
  formatPrice,
  markdownCell,
  tr,
} from './deterministic';
import type { ExportBundle, ExportHouse } from './export-model';
import { brokerEntries } from './html-export';
import { checklistEntries, checklistLabel, costFacts, costHeading, detailFacts, labelOf, rankingShows, sectionShown, statusText, voiced } from './copy-facts';
import { movingInView, photoNote, answerCells, answerDisplayColumns, areaNoteCells, areaNoteDisplayColumns, distanceCells, distanceDisplayColumns, criteriaTable, customLabels, display, ratingShareLine, roomCells, roomDisplayColumns, stringsOf, viewingCells, viewingDisplayColumns } from './export-rows';
import { copyBlockOrder, type CopyBlock } from '../core/kind-capabilities';
import { optionSummaryKeys } from './option-summary';
import { photoFileName } from './photo-names';

/**
 * The Markdown copy (docs/11 §5.2): headings per house, tables for scores and visits, photos listed by file name
 * (never embedded — that is what the HTML copy is for). Lines end with `\n`, the file ends with one newline, and
 * every piece of user text is escaped, so a house called `**deal**` reads as `**deal**`.
 */
export function buildMarkdown(bundle: ExportBundle, dict: Dict): string {
  const out: string[] = [];
  const { counts, options } = bundle;

  out.push(`# ${escapeMarkdown(tr(dict, voiced(bundle, 'exp.title')))}`, '');
  out.push(escapeMarkdown(tr(dict, 'exp.subtitle')), '');
  out.push(escapeMarkdown(tr(dict, 'exp.exportedAt', { date: formatDateTime(bundle.exportedAt) })), '');
  out.push(
    escapeMarkdown(tr(dict, voiced(bundle, 'exp.counts'), { houses: counts.houses, visits: counts.visits, photos: counts.photos })),
    '',
  );
  if (ratingShareLine(bundle)) out.push(escapeMarkdown(ratingShareLine(bundle)), '');
  out.push(`## ${escapeMarkdown(tr(dict, 'exp.optionsHeading'))}`, '');
  for (const key of optionSummaryKeys(options)) out.push(`- ${escapeMarkdown(tr(dict, voiced(bundle, key)))}`);
  out.push('');
  out.push(`> ${escapeMarkdown(tr(dict, options.includeContacts ? 'exp.privacyContacts' : 'exp.privacyNoContacts'))}`, '');

  if (bundle.houses.length === 0) {
    out.push(escapeMarkdown(tr(dict, voiced(bundle, 'exp.noHouses'))), '');
    out.push(...brokersSection(bundle, dict));
    out.push(...criteriaSection(bundle));
    out.push('---', '', escapeMarkdown(tr(dict, 'exp.footer')), '');
    return out.join('\n');
  }

  out.push(`## ${escapeMarkdown(tr(dict, 'exp.ranking'))}`, '');
  // The Must-haves column is there only when some house of the copy misses one: an empty column says nothing.
  const show = rankingShows(bundle);
  const anyMissed = show.score && bundle.ranking.some((entry) => entry.result.failedMustHave.length > 0);
  out.push(
    row([
      tr(dict, 'exp.colRank'),
      tr(dict, voiced(bundle, 'exp.colHouse')),
      ...(show.score ? [tr(dict, 'common.score')] : []),
      ...(show.price ? [tr(dict, 'compare.price')] : []),
      tr(dict, 'house.status'),
      ...(anyMissed ? [tr(dict, 'exp.colMustHave')] : []),
    ]),
  );
  out.push(separator(3 + (show.score ? 1 : 0) + (show.price ? 1 : 0) + (anyMissed ? 1 : 0)));
  bundle.ranking.forEach((entry, index) => {
    out.push(
      row([
        String(index + 1),
        labelOf(entry.house.label, dict),
        ...(show.score ? [entry.score === null ? tr(dict, 'house.notScored') : formatDecimal(entry.score, 1)] : []),
        ...(show.price ? [formatPrice(dict, entry.house.price, entry.house.priceType)] : []),
        statusText(entry.house.status, dict),
        ...(anyMissed ? [entry.result.failedMustHave.length > 0 ? `✕ ${tr(dict, 'exp.mustHaveMissed')}` : ''] : []),
      ]),
    );
  });
  out.push('');

  bundle.houses.forEach((entry, index) => out.push(...houseSection(entry, index + 1, bundle, dict)));
  out.push(...brokersSection(bundle, dict));
  out.push(...criteriaSection(bundle));

  out.push('---', '', escapeMarkdown(tr(dict, 'exp.footer')), '');
  return out.join('\n');
}

function houseSection(entry: ExportHouse, position: number, bundle: ExportBundle, dict: Dict): string[] {
  const { house, visits, photos } = entry;
  const label = labelOf(house.label, dict);
  const labels = customLabels(bundle);
  const shows = (ref: string) => sectionShown(bundle, ref, entry);
  const out: string[] = [];
  out.push(`## ${position}. ${escapeMarkdown(label)}`, '');

  const facts = detailFacts(entry, bundle, dict);

  if (facts.length) {
    out.push(row([tr(dict, 'exp.colField'), tr(dict, 'exp.colValue')]));
    out.push(separator(2));
    for (const [name, value] of facts) out.push(row([name, value]));
    out.push('');
  }

  // The blocks after the details, each its own lines; the kind's `copyOrder` says which comes first (docs/03 §18.27), the house's fixed order is the default.
  const blocks: Record<CopyBlock, string[]> = { cost: [], rooms: [], questions: [], viewings: [], areaNotes: [], distances: [], moveIn: [], checklist: [], visits: [], notes: [], photos: [] };

  const cost = costFacts(entry, bundle, dict);
  if (cost.length) {
    const b = blocks.cost;
    b.push(`### ${escapeMarkdown(costHeading(entry, bundle, dict))}`, '');
    b.push(row([tr(dict, 'exp.colField'), tr(dict, 'exp.colValue')]));
    b.push(separator(2));
    for (const [name, value] of cost) b.push(row([name, value]));
    b.push('');
  }

  // The rooms (slice 1c).
  const rooms = shows('section.rooms') ? roomCells(entry, bundle.lengthUnit, stringsOf(bundle)) : [];
  if (rooms.length) {
    const b = blocks.rooms;
    b.push(`### ${escapeMarkdown(stringsOf(bundle).get('table.rooms'))}`, '');
    b.push(row(roomDisplayColumns(bundle)));
    b.push(separator(7));
    for (const r of rooms) b.push(row(r.map(escapeMarkdown)));
    b.push('');
  }

  // The questions (slice 3a); open ones first.
  const questions = shows('section.questions') ? answerCells(entry, stringsOf(bundle)) : [];
  if (questions.length) {
    const b = blocks.questions;
    b.push(`### ${escapeMarkdown(stringsOf(bundle).get('section.questions'))}`, '');
    b.push(row(answerDisplayColumns(bundle)));
    b.push(separator(3));
    for (const r of questions) b.push(row(r.map(escapeMarkdown)));
    b.push('');
  }

  // The viewings (slice 3b-1); upcoming first, then newest first.
  const viewings = shows('section.viewings') ? viewingCells(entry, stringsOf(bundle), bundle.options.includeContacts) : [];
  if (viewings.length) {
    const b = blocks.viewings;
    b.push(`### ${escapeMarkdown(stringsOf(bundle).get('section.viewings'))}`, '');
    const columns = viewingDisplayColumns(bundle);
    b.push(row(columns));
    b.push(separator(columns.length));
    for (const r of viewings) b.push(row(r.map(escapeMarkdown)));
    b.push('');
  }

  // Area notes and distances (slice 4a).
  for (const [block, heading, columns, list] of [
    ['areaNotes', stringsOf(bundle).get('section.areaNotes'), areaNoteDisplayColumns(bundle), shows('section.areaNotes') ? areaNoteCells(entry) : []],
    ['distances', stringsOf(bundle).get('section.distances'), distanceDisplayColumns(bundle), shows('section.distances') ? distanceCells(entry) : []],
  ] as const) {
    if (!list.length) continue;
    const b = blocks[block];
    b.push(`### ${escapeMarkdown(heading)}`, '');
    b.push(row([...columns]));
    b.push(separator(columns.length));
    for (const r of list) b.push(row(r.map(escapeMarkdown)));
    b.push('');
  }

  // Moving in (slice 5): date, notes, then the items with a tick.
  const movingIn = shows('section.moveIn') ? movingInView(entry, stringsOf(bundle)) : { facts: [], items: [] };
  if (movingIn.facts.length || movingIn.items.length) {
    const b = blocks.moveIn;
    b.push(`### ${escapeMarkdown(stringsOf(bundle).get('section.movingIn'))}`, '');
    if (movingIn.facts.length) {
      b.push(row([tr(dict, 'exp.colField'), tr(dict, 'exp.colValue')]));
      b.push(separator(2));
      for (const [name, value] of movingIn.facts) b.push(row([name, value]));
      b.push('');
    }
    for (const item of movingIn.items) b.push(`- ${item.done ? '✓' : '○'} ${escapeMarkdown(item.text)}`);
    if (movingIn.items.length) b.push('');
  }

  const checklist = shows('section.checklist') ? checklistEntries(house.checklist) : [];
  if (checklist.length) {
    const b = blocks.checklist;
    b.push(`### ${escapeMarkdown(tr(dict, 'house.checklist'))}`, '');
    b.push(row([tr(dict, 'compare.criterion'), tr(dict, 'common.score')]));
    b.push(separator(2));
    for (const [key, value] of checklist) b.push(row([checklistLabel(key, dict, labels), tr(dict, 'exp.scoreOf5', { n: value })]));
    b.push('');
  }

  if (visits.length) {
    const b = blocks.visits;
    b.push(`### ${escapeMarkdown(tr(dict, 'house.visits'))}`, '');
    b.push(row([tr(dict, 'exp.colArrived'), tr(dict, 'exp.colLeft'), tr(dict, 'exp.colStreet')]));
    b.push(separator(3));
    for (const visit of visits) {
      b.push(
        row([formatDateTime(visit.arrivedAt), visit.leftAt ? formatDateTime(visit.leftAt) : '–', visit.street ?? '']),
      );
    }
    b.push('');
  }

  if (house.notes) {
    const b = blocks.notes;
    b.push(`### ${escapeMarkdown(tr(dict, 'house.notes'))}`, '');
    for (const line of house.notes.split(/\r?\n/)) b.push(escapeMarkdown(line));
    b.push('');
  }

  if (photos.length) {
    const b = blocks.photos;
    b.push(`### ${escapeMarkdown(tr(dict, 'house.photos'))}`, '');
    for (const photo of photos) {
      const note = photoNote(entry, photo, stringsOf(bundle));
      b.push(`- \`${photoFileName(photo.id)}\`${note ? ` — ${escapeMarkdown(note)}` : ''}`);
    }
    b.push('');
  }

  for (const block of copyBlockOrder((entry.kind ?? bundle.voice)?.kind)) out.push(...blocks[block]);
  return out;
}

/** The **Brokers** section after the houses (slice 1b): a table per broker of the copy. */
function brokersSection(bundle: ExportBundle, dict: Dict): string[] {
  if (bundle.brokers.length === 0 || !sectionShown(bundle, 'field.brokerId')) return [];
  const out: string[] = [`## ${escapeMarkdown(tr(dict, 'brokers.title'))}`, ''];
  for (const b of bundle.brokers) {
    out.push(`### ${escapeMarkdown(b.broker.name)}`, '');
    const rows = brokerEntries(b, dict);
    if (rows.length === 0) continue;
    out.push(row([tr(dict, 'exp.colField'), tr(dict, 'exp.colValue')]));
    out.push(separator(2));
    for (const [name, value] of rows) out.push(row([name, value]));
    out.push('');
  }
  return out;
}

/** The **Criteria** section after the brokers (slice 2): name, weight, must-have, minimum score and archived. */
function criteriaSection(bundle: ExportBundle): string[] {
  if (bundle.criteria.length === 0 || !sectionShown(bundle, 'section.checklist')) return [];
  const table = criteriaTable(bundle);
  const strings = stringsOf(bundle);
  const shown = [1, 2, 3, 4, 5];
  const out: string[] = [`## ${escapeMarkdown(table.title)}`, ''];
  out.push(row(shown.map((i) => table.columns[i])));
  out.push(separator(shown.length));
  for (const r of table.rows) out.push(row(shown.map((i) => display(r[i], strings))));
  out.push('');
  return out;
}

function row(cells: readonly string[]): string {
  return `| ${cells.map(markdownCell).join(' | ')} |`;
}

function separator(columns: number): string {
  return `|${' --- |'.repeat(columns)}`;
}
