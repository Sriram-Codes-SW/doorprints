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

import type { Dict, TKey } from '../i18n/en';
import {
  escapeHtml,
  formatDateTime,
  formatDecimal,
  formatInt,
  formatPrice,
  tr,
} from './deterministic';
import { checklistEntries, checklistLabel, costFacts, costHeading, detailFacts, labelOf, rankingShows, sectionShown, statusText, voiced } from './copy-facts';
import type { ExportBroker, ExportBundle, ExportHouse } from './export-model';
import { movingInView, photoNote, answerCells, answerDisplayColumns, areaNoteCells, areaNoteDisplayColumns, distanceCells, distanceDisplayColumns, criteriaTable, customLabels, display, ratingShareLine, roomCells, roomDisplayColumns, stringsOf, viewingCells, viewingDisplayColumns } from './export-rows';
import { copyBlockOrder, type CopyBlock } from '../core/kind-capabilities';
import { optionSummaryKeys } from './option-summary';
import { photoFileName } from './photo-names';

/**
 * The readable copy (docs/11 §5.2): one self-contained HTML file with inline CSS, **no JavaScript** and photos as
 * `data:` URIs. It opens in any browser for ever, and its print stylesheet is what the PDF export prints.
 *
 * A restrictive CSP `<meta>` is embedded so that even a file that somehow contained a script tag could not run it
 * or phone home: `default-src 'none'; img-src data:; style-src 'unsafe-inline'`.
 *
 * Every piece of user text goes through `escapeHtml`, including notes, labels and contact names.
 */

/** Photo bytes, already turned into `data:` URIs by the caller (the builder itself stays synchronous and pure). */
export type PhotoDataUris = ReadonlyMap<string, string>;

/** How the HTML copy is built. */
export interface HtmlOptions {
  /**
   * List photos by file name instead of embedding them.
   *
   * Used for the copy that goes **inside a backup ZIP**: the bytes are already in that ZIP's `photos/` folder, so
   * embedding them again as base64 would put every photo in the file twice (and ~1.33x bigger the second time,
   * which is what made a large backup an out-of-memory tab kill). The standalone HTML and PDF exports still embed,
   * because those are single files that have to work on their own.
   */
  readonly photosAsFileNames?: boolean;
}

/**
 * Builds the self-contained readable copy as one HTML string: cover, ranking, a section per house, brokers and criteria.
 * Pure and synchronous: the caller supplies the dictionary and the photos already as `data:` URIs. All text is escaped, and the embedded content policy forbids scripts and network.
 */
export function buildHtml(
  bundle: ExportBundle,
  dict: Dict,
  photos: PhotoDataUris,
  options: HtmlOptions = {},
): string {
  const lang = bundle.options.lang;
  const title = tr(dict, voiced(bundle, 'exp.title'));
  return [
    '<!doctype html>',
    `<html lang="${escapeHtml(lang)}">`,
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; style-src \'unsafe-inline\'">',
    `<title>${escapeHtml(title)}</title>`,
    `<style>${STYLE}</style>`,
    '</head>',
    '<body>',
    cover(bundle, dict),
    ranking(bundle, dict),
    bundle.houses.length === 0
      ? `<p class="empty">${escapeHtml(tr(dict, voiced(bundle, 'exp.noHouses')))}</p>`
      : bundle.houses.map((h, index) => houseSection(h, index + 1, bundle, dict, photos, options)).join('\n'),
    brokersSection(bundle, dict),
    criteriaSection(bundle),
    `<footer><p>${escapeHtml(tr(dict, 'exp.footer'))}</p></footer>`,
    '</body>',
    '</html>',
    '',
  ].join('\n');
}

function cover(bundle: ExportBundle, dict: Dict): string {
  const { counts, options } = bundle;
  const items = optionSummaryKeys(options).map((key) => `<li>${escapeHtml(tr(dict, voiced(bundle, key)))}</li>`);
  return [
    '<header class="cover">',
    `<h1>${escapeHtml(tr(dict, voiced(bundle, 'exp.title')))}</h1>`,
    `<p class="lead">${escapeHtml(tr(dict, 'exp.subtitle'))}</p>`,
    `<p class="meta">${escapeHtml(tr(dict, 'exp.exportedAt', { date: formatDateTime(bundle.exportedAt) }))}</p>`,
    `<p class="meta">${escapeHtml(
      tr(dict, voiced(bundle, 'exp.counts'), { houses: counts.houses, visits: counts.visits, photos: counts.photos }),
    )}</p>`,
    ratingShareLine(bundle) ? `<p class="meta">${escapeHtml(ratingShareLine(bundle))}</p>` : '',
    `<h2>${escapeHtml(tr(dict, 'exp.optionsHeading'))}</h2>`,
    `<ul class="options">${items.join('')}</ul>`,
    `<p class="privacy">${escapeHtml(
      tr(dict, options.includeContacts ? 'exp.privacyContacts' : 'exp.privacyNoContacts'),
    )}</p>`,
    '</header>',
  ]
    .filter((line) => line !== '')
    .join('\n');
}

function ranking(bundle: ExportBundle, dict: Dict): string {
  if (bundle.ranking.length === 0) return '';
  // The Must-haves column is there only when some house of the copy misses one: an empty column says nothing.
  const show = rankingShows(bundle);
  const anyMissed = show.score && bundle.ranking.some((entry) => entry.result.failedMustHave.length > 0);
  const head: TKey[] = [
    'exp.colRank',
    voiced(bundle, 'exp.colHouse'),
    ...(show.score ? (['common.score'] as const) : []),
    ...(show.price ? (['compare.price'] as const) : []),
    'house.status',
    ...(anyMissed ? (['exp.colMustHave'] as const) : []),
  ];
  const rows = bundle.ranking.map((entry, index) => {
    const { house, score } = entry;
    return [
      '<tr>',
      `<td>${formatInt(index + 1)}</td>`,
      `<th scope="row">${escapeHtml(labelOf(house.label, dict))}</th>`,
      ...(show.score ? [`<td>${escapeHtml(score === null ? tr(dict, 'house.notScored') : formatDecimal(score, 1))}</td>`] : []),
      ...(show.price ? [`<td>${escapeHtml(formatPrice(dict, house.price, house.priceType))}</td>`] : []),
      `<td>${escapeHtml(statusText(house.status, dict))}</td>`,
      // The mark is words, not colour: "Must-have missed" when a must-have is scored below its minimum.
      ...(anyMissed
        ? [`<td>${entry.result.failedMustHave.length > 0 ? escapeHtml(`✕ ${tr(dict, 'exp.mustHaveMissed')}`) : ''}</td>`]
        : []),
      '</tr>',
    ].join('');
  });
  return [
    '<section class="ranking">',
    `<h2>${escapeHtml(tr(dict, 'exp.ranking'))}</h2>`,
    '<table>',
    `<thead><tr>${head.map((k) => `<th scope="col">${escapeHtml(tr(dict, k))}</th>`).join('')}</tr></thead>`,
    `<tbody>${rows.join('')}</tbody>`,
    '</table>',
    '</section>',
  ].join('\n');
}

function houseSection(
  entry: ExportHouse,
  position: number,
  bundle: ExportBundle,
  dict: Dict,
  photos: PhotoDataUris,
  options: HtmlOptions,
): string {
  const { house, visits } = entry;
  const label = labelOf(house.label, dict);
  const labels = customLabels(bundle);
  const shows = (ref: string) => sectionShown(bundle, ref, entry);
  const factRow = (name: string, value: string) => `<tr><th scope="row">${escapeHtml(name)}</th><td>${escapeHtml(value)}</td></tr>`;
  const rows = detailFacts(entry, bundle, dict).map(([name, value]) => factRow(name, value));
  const costRows = costFacts(entry, bundle, dict).map(([name, value]) => factRow(name, value));

  // The rooms (slice 1c), after the cost and before the checklist; the total of their areas as a last row.
  const roomList = shows('section.rooms') ? roomCells(entry, bundle.lengthUnit, stringsOf(bundle)) : [];
  const roomsHtml = roomList.length
    ? [
        `<h3>${escapeHtml(stringsOf(bundle).get('table.rooms'))}</h3>`,
        '<table>',
        `<thead><tr>${roomDisplayColumns(bundle).map((c) => `<th scope="col">${escapeHtml(c)}</th>`).join('')}</tr></thead>`,
        `<tbody>${roomList
          .map((row) => `<tr>${row.map((c, i) => (i === 0 ? `<th scope="row">${escapeHtml(c)}</th>` : `<td>${escapeHtml(c)}</td>`)).join('')}</tr>`)
          .join('')}</tbody>`,
        '</table>',
      ].join('')
    : '';

  // The questions (slice 3a), after the rooms and before the checklist: question, answer, status; open ones first.
  const questionList = shows('section.questions') ? answerCells(entry, stringsOf(bundle)) : [];
  const questionsHtml = questionList.length
    ? [
        `<h3>${escapeHtml(stringsOf(bundle).get('section.questions'))}</h3>`,
        '<table>',
        `<thead><tr>${answerDisplayColumns(bundle).map((c) => `<th scope="col">${escapeHtml(c)}</th>`).join('')}</tr></thead>`,
        `<tbody>${questionList
          .map((row) => `<tr>${row.map((c, i) => (i === 0 ? `<th scope="row">${escapeHtml(c)}</th>` : `<td>${escapeHtml(c)}</td>`)).join('')}</tr>`)
          .join('')}</tbody>`,
        '</table>',
      ].join('')
    : '';

  // The viewings (slice 3b-1), after the questions and before the checklist; upcoming first, then newest first.
  const viewingList = shows('section.viewings') ? viewingCells(entry, stringsOf(bundle), bundle.options.includeContacts) : [];
  const viewingsHtml = viewingList.length
    ? [
        `<h3>${escapeHtml(stringsOf(bundle).get('section.viewings'))}</h3>`,
        '<table>',
        `<thead><tr>${viewingDisplayColumns(bundle).map((c) => `<th scope="col">${escapeHtml(c)}</th>`).join('')}</tr></thead>`,
        `<tbody>${viewingList
          .map((row) => `<tr>${row.map((c, i) => (i === 0 ? `<th scope="row">${escapeHtml(c)}</th>` : `<td>${escapeHtml(c)}</td>`)).join('')}</tr>`)
          .join('')}</tbody>`,
        '</table>',
      ].join('')
    : '';

  // Area notes and distances (slice 4a), after the viewings and before the checklist.
  const simpleTable = (heading: string, columns: string[], list: string[][]): string =>
    list.length
      ? [
          `<h3>${escapeHtml(heading)}</h3>`,
          '<table>',
          `<thead><tr>${columns.map((c) => `<th scope="col">${escapeHtml(c)}</th>`).join('')}</tr></thead>`,
          `<tbody>${list.map((row) => `<tr>${row.map((c, i) => (i === 0 ? `<th scope="row">${escapeHtml(c)}</th>` : `<td>${escapeHtml(c)}</td>`)).join('')}</tr>`).join('')}</tbody>`,
          '</table>',
        ].join('')
      : '';
  const areaNotesHtml = simpleTable(stringsOf(bundle).get('section.areaNotes'), areaNoteDisplayColumns(bundle), shows('section.areaNotes') ? areaNoteCells(entry) : []);
  const distancesHtml = simpleTable(stringsOf(bundle).get('section.distances'), distanceDisplayColumns(bundle), shows('section.distances') ? distanceCells(entry) : []);

  // Moving in (slice 5), after the distances and before the checklist: date and notes, then the items with a tick.
  const movingIn = shows('section.moveIn') ? movingInView(entry, stringsOf(bundle)) : { facts: [], items: [] };
  const movingInHtml =
    movingIn.facts.length || movingIn.items.length
      ? [
          `<h3>${escapeHtml(stringsOf(bundle).get('section.movingIn'))}</h3>`,
          movingIn.facts.length
            ? `<table class="fields">${movingIn.facts
                .map(([name, value]) => `<tr><th scope="row">${escapeHtml(name)}</th><td>${escapeHtml(value).replace(/\r?\n/g, '<br>')}</td></tr>`)
                .join('')}</table>`
            : '',
          movingIn.items.length
            ? `<ul class="move-in">${movingIn.items
                .map((item) => `<li>${item.done ? '✓' : '○'} ${escapeHtml(item.text)}</li>`)
                .join('')}</ul>`
            : '',
        ].join('')
      : '';

  const checklistRows = (shows('section.checklist') ? checklistEntries(house.checklist) : []).map(
    ([key, value]) =>
      `<tr><th scope="row">${escapeHtml(checklistLabel(key, dict, labels))}</th><td>${escapeHtml(
        tr(dict, 'exp.scoreOf5', { n: value }),
      )}</td></tr>`,
  );

  const visitRows = visits.map(
    (visit) =>
      `<tr><td>${escapeHtml(formatDateTime(visit.arrivedAt))}</td><td>${escapeHtml(
        visit.leftAt ? formatDateTime(visit.leftAt) : '–',
      )}</td><td>${escapeHtml(visit.street ?? '')}</td></tr>`,
  );

  const photoTags = options.photosAsFileNames
    ? entry.photos.map((photo) => {
        const note = photoNote(entry, photo, stringsOf(bundle));
        return `<li><code>${escapeHtml(photoFileName(photo.id))}</code>${note ? ` — ${escapeHtml(note)}` : ''}</li>`;
      })
    : entry.photos
        .map((photo, index) => {
          const src = photos.get(photo.id);
          if (!src) return '';
          const alt = tr(dict, 'exp.photoAlt', { n: index + 1, house: label });
          const note = photoNote(entry, photo, stringsOf(bundle));
          return `<figure><img src="${escapeHtml(src)}" alt="${escapeHtml(alt)}">${note ? `<figcaption>${escapeHtml(note)}</figcaption>` : ''}</figure>`;
        })
        .filter((tag) => tag !== '');

  // The blocks after the details, each as its own string; the kind's `copyOrder` says which comes first (docs/03 §18.28), the house's fixed order is the default.
  const blocks: Record<CopyBlock, string> = {
    cost: costRows.length
      ? `<h3>${escapeHtml(costHeading(entry, bundle, dict))}</h3><table class="fields">${costRows.join('')}</table>`
      : '',
    rooms: roomsHtml,
    questions: questionsHtml,
    viewings: viewingsHtml,
    areaNotes: areaNotesHtml,
    distances: distancesHtml,
    moveIn: movingInHtml,
    checklist: checklistRows.length
      ? `<h3>${escapeHtml(tr(dict, 'house.checklist'))}</h3><table class="fields">${checklistRows.join('')}</table>`
      : '',
    visits: visitRows.length
      ? [
          `<h3>${escapeHtml(tr(dict, 'house.visits'))}</h3>`,
          '<table>',
          `<thead><tr><th scope="col">${escapeHtml(tr(dict, 'exp.colArrived'))}</th><th scope="col">${escapeHtml(
            tr(dict, 'exp.colLeft'),
          )}</th><th scope="col">${escapeHtml(tr(dict, 'exp.colStreet'))}</th></tr></thead>`,
          `<tbody>${visitRows.join('')}</tbody>`,
          '</table>',
        ].join('')
      : '',
    notes: house.notes
      ? `<h3>${escapeHtml(tr(dict, 'house.notes'))}</h3><p class="notes">${escapeHtml(house.notes).replace(/\r?\n/g, '<br>')}</p>`
      : '',
    photos: photoTags.length
      ? options.photosAsFileNames
        ? `<h3>${escapeHtml(tr(dict, 'house.photos'))}</h3><ul class="photo-files">${photoTags.join(
            '',
          )}</ul><p class="meta">${escapeHtml(tr(dict, 'exp.photosInBackup'))}</p>`
        : `<h3>${escapeHtml(tr(dict, 'house.photos'))}</h3><div class="photos">${photoTags.join('')}</div>`
      : '',
  };

  return [
    '<section class="house">',
    `<h2>${escapeHtml(`${position}. ${label}`)}</h2>`,
    `<table class="fields">${rows.join('')}</table>`,
    ...copyBlockOrder((entry.kind ?? bundle.voice)?.kind).map((block) => blocks[block]),
    '</section>',
  ]
    .filter((part) => part !== '')
    .join('\n');
}

/**
 * The **Brokers** section after the houses (slice 1b): each broker of the copy with its fields and the houses of the
 * copy that use it. Empty when the copy has no brokers (or no contact details).
 */
function brokersSection(bundle: ExportBundle, dict: Dict): string {
  if (bundle.brokers.length === 0 || !sectionShown(bundle, 'field.brokerId')) return '';
  const items = bundle.brokers.map((b) => {
    const rows = brokerEntries(b, dict).map(
      ([label, value]) => `<tr><th scope="row">${escapeHtml(label)}</th><td>${escapeHtml(value)}</td></tr>`,
    );
    return `<h3>${escapeHtml(b.broker.name)}</h3><table class="fields">${rows.join('')}</table>`;
  });
  return [
    '<section class="brokers">',
    `<h2>${escapeHtml(tr(dict, 'brokers.title'))}</h2>`,
    items.join('\n'),
    '</section>',
  ].join('\n');
}

/**
 * The **Criteria** section after the brokers (slice 2): each criterion with its weight, must-have, minimum score and
 * whether it is archived, in the app's order. Only a copy with criterion records has it.
 */
function criteriaSection(bundle: ExportBundle): string {
  if (bundle.criteria.length === 0 || !sectionShown(bundle, 'section.checklist')) return '';
  const table = criteriaTable(bundle);
  const strings = stringsOf(bundle);
  // The key and the order are for the spreadsheet; a reader has the name, the weight, the must-have and the rest.
  const shown = [1, 2, 3, 4, 5];
  return [
    '<section class="criteria">',
    `<h2>${escapeHtml(table.title)}</h2>`,
    '<table>',
    `<thead><tr>${shown.map((i) => `<th scope="col">${escapeHtml(table.columns[i])}</th>`).join('')}</tr></thead>`,
    `<tbody>${table.rows
      .map((row) => `<tr>${shown.map((i) => (i === 1 ? `<th scope="row">${escapeHtml(display(row[i], strings))}</th>` : `<td>${escapeHtml(display(row[i], strings))}</td>`)).join('')}</tr>`)
      .join('')}</tbody>`,
    '</table>',
    '</section>',
  ].join('\n');
}

/** A broker's rows in a readable copy: the fields that are set, then the houses of the copy that use it. */
export function brokerEntries(b: ExportBroker, dict: Dict): [string, string][] {
  const out: [string, string][] = [];
  if (b.broker.phone) out.push([tr(dict, 'brokers.phone'), b.broker.phone]);
  if (b.broker.agency) out.push([tr(dict, 'brokers.agency'), b.broker.agency]);
  if (b.broker.feeTerms) out.push([tr(dict, 'brokers.feeTerms'), b.broker.feeTerms]);
  if (b.broker.rating) out.push([tr(dict, 'compare.rating'), tr(dict, 'common.stars', { n: b.broker.rating })]);
  if (b.broker.notes) out.push([tr(dict, 'brokers.notes'), b.broker.notes]);
  if (b.houses.length > 0) {
    out.push([tr(dict, 'brokers.housesHeading'), b.houses.map((h) => labelOf(h.label, dict)).join(', ')]);
  }
  return out;
}

/**
 * Inline stylesheet. System fonts only (the file must work with no network), generous line height for Indic
 * scripts, and a print rule that starts every house on its own page — that is what "PDF via a print view" means.
 */
const STYLE = `
:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0 auto; padding: 24px 16px 48px; max-width: 46rem; background: #ffffff; color: #1c2421;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Noto Sans Devanagari',
  'Noto Sans Tamil', 'Noto Sans Telugu', sans-serif; line-height: 1.6; }
h1 { font-size: 1.75rem; margin: 0 0 8px; }
h2 { font-size: 1.375rem; margin: 32px 0 8px; }
h3 { font-size: 1rem; margin: 20px 0 6px; }
p { margin: 0 0 12px; }
.cover { border-bottom: 3px solid #1f6f5c; padding-bottom: 16px; }
.lead { font-size: 1.125rem; }
.meta, .options { color: #4a5551; }
.options { margin: 0 0 12px; padding-left: 1.25rem; }
.privacy { background: #e3f0ec; border-left: 4px solid #1f6f5c; padding: 8px 12px; }
table { border-collapse: collapse; width: 100%; margin: 0 0 16px; }
th, td { border: 1px solid #d9e0dd; padding: 6px 10px; text-align: start; vertical-align: top; }
thead th { background: #eef2f0; }
.fields th { width: 38%; background: #f7faf9; font-weight: 600; }
.notes { white-space: normal; }
.photos { display: flex; flex-wrap: wrap; gap: 8px; }
.photos figure { margin: 0; width: calc(50% - 4px); }
.photos img { width: 100%; height: auto; border: 1px solid #d9e0dd; border-radius: 6px; }
.photo-files { margin: 0 0 8px; padding-left: 1.25rem; }
.move-in { margin: 0 0 8px; padding-left: 0; list-style: none; }
.photos figcaption { font-size: 0.85rem; padding-top: 2px; }
.house { border-top: 1px solid #d9e0dd; padding-top: 8px; }
.empty { font-style: italic; }
footer { margin-top: 32px; border-top: 1px solid #d9e0dd; padding-top: 12px; color: #4a5551; font-size: 0.875rem; }
@page { size: A4; margin: 14mm; }
@media print {
  body { max-width: none; padding: 0; }
  .house { break-before: page; page-break-before: always; border-top: 0; }
  .photos figure { width: calc(33% - 6px); }
  a { text-decoration: none; color: inherit; }
}
`.trim();
