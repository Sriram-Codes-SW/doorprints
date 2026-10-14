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
import { CHECKLIST, COST_FIELDS, STATUS_ICON } from '../core/models';
import type { HouseCost, HouseStatus } from '../core/models';
import { brokerLine } from '../shared/broker';
import { costSummary } from '../shared/house-cost';
import { floorWords } from '../shared/house-floor';
import { formatCoord, formatDate, formatDecimal, formatPrice, rupees, tr } from './deterministic';
import type { ExportBundle, ExportHouse } from './export-model';
import { customLabels } from './export-rows';
import { copyField, copyShows, copyWord } from './kind-copy';

/**
 * The rows of a readable copy (HTML and Markdown) that both formats write the same: the Details block of a candidate, its
 * Cost block, the ranking table's columns and the dictionary words the copy's voice changes (kinds PR 16, S4b-BL-266;
 * docs/03 §18.9). A copy without a voice (houses) reads the hand-written house keys word for word, as `house-pins.spec.ts`
 * holds it; a copy in another kind's voice reads that kind's labels through `KindLabels`, leaves out the rows and columns
 * its kind hides, and writes each extra under the heading of the kind section it belongs to.
 */

/** The label key of each cost field, for the Cost block and the form. */
export const COST_FIELD_KEY: Readonly<Record<keyof HouseCost, TKey>> = {
  deposit: 'cost.deposit',
  depositMonths: 'cost.depositMonths',
  maintenance: 'cost.maintenance',
  maintenanceIncluded: 'cost.maintenanceIncluded',
  brokerage: 'cost.brokerage',
  brokerageMonths: 'cost.brokerageMonths',
  lockInMonths: 'cost.lockInMonths',
  noticeMonths: 'cost.noticeMonths',
  availableFrom: 'cost.availableFrom',
  myOffer: 'cost.myOffer',
  agreedPrice: 'cost.agreedPrice',
};

/**
 * The **Cost** block of a readable copy (HTML, PDF, Markdown): a row per set cost field in the shared order, then
 * the monthly cost, the money to move in and the cost per sq ft when they compute (`costSummary`). Rupees are
 * written whole with Indian grouping; months as "{n} months".
 */
export function costEntries(house: ExportHouse['house'], dict: Dict): [string, string][] {
  const out: [string, string][] = [];
  const cost = house.cost ?? {};
  for (const field of COST_FIELDS) {
    const value = cost[field];
    if (value === null || value === undefined) continue;
    const label = tr(dict, COST_FIELD_KEY[field]);
    if (typeof value === 'boolean') out.push([label, tr(dict, value ? 'common.yes' : 'common.no')]);
    else if (typeof value === 'string') out.push([label, value]);
    else if (field.endsWith('Months')) out.push([label, tr(dict, 'common.months', { n: value })]);
    else out.push([label, rupees(value)]);
  }
  const summary = costSummary(house);
  if (summary.monthlyCost !== null) out.push([tr(dict, 'cost.monthlyCost'), rupees(summary.monthlyCost)]);
  if (summary.moveIn !== null) out.push([tr(dict, 'cost.moveIn'), rupees(summary.moveIn)]);
  if (summary.perSqFt !== null) out.push([tr(dict, 'cost.perSqFt'), rupees(summary.perSqFt)]);
  return out;
}

/** Checklist items in the app's fixed order first, then any unknown keys alphabetically. */
export function checklistEntries(checklist: Record<string, number>): [string, number][] {
  const known = CHECKLIST.filter((item) => typeof checklist[item.key] === 'number').map(
    (item) => [item.key, checklist[item.key]] as [string, number],
  );
  const extra = Object.keys(checklist)
    .filter((key) => !CHECKLIST.some((item) => item.key === key))
    .sort()
    .map((key) => [key, checklist[key]] as [string, number]);
  return [...known, ...extra];
}

/** A criterion's name: a built-in's translation, a custom criterion's own label (`labels`, slice 2), else the key. */
export function checklistLabel(key: string, dict: Dict, labels: ReadonlyMap<string, string> = new Map()): string {
  const item = CHECKLIST.find((entry) => entry.key === key);
  return item ? tr(dict, item.labelKey) : (labels.get(key) ?? key);
}

/** A house status as the readable copy writes it: an icon plus the translated word, so colour is never the only signal. */
export function statusText(status: HouseStatus, dict: Dict): string {
  // The icon repeats the status in a second channel, so the file never relies on colour (WCAG 1.4.1).
  return `${STATUS_ICON[status]} ${tr(dict, `status.${status}`)}`;
}

/** A house's label, or the translated "untitled" when it has none. */
export function labelOf(label: string, dict: Dict): string {
  return label.trim() === '' ? tr(dict, 'common.untitled') : label;
}

/**
 * The Details block of a candidate, label and value (unescaped), in the order both copies write it: status, score and
 * coverage, price, rooms and area, rating, the address, the contacts, then the kind's extras of the Details section and when
 * it was saved. A value that is empty or `–` is left out, and so is every row its kind hides.
 */
export function detailFacts(entry: ExportHouse, bundle: ExportBundle, dict: Dict): [string, string][] {
  const { house, score } = entry;
  const voice = entry.kind ?? bundle.voice;
  const labels = customLabels(bundle);
  const facts: [string, string][] = [];
  /** `ref` is the place in the kind; `legacy` the house's hand-written key, which the house's copies keep word for word. */
  const add = (ref: string, key: TKey, value: string) => {
    if (copyShows(voice, ref) && value && value !== '–') facts.push([tr(dict, key), value]);
  };
  const field = (ref: string, legacy: TKey, value: string) => add(ref, copyField(voice, ref, legacy), value);
  const checklist = 'section.checklist';
  field('field.status', 'house.status', statusText(house.status, dict));
  add(checklist, 'compare.overall', score === null ? tr(dict, 'house.notScored') : formatDecimal(score, 1));
  if (entry.result.scored > 0) {
    add(checklist, 'exp.coverageLabel', tr(dict, 'exp.coverage', { n: entry.result.scored, total: entry.result.active }));
  }
  if (entry.result.failedMustHave.length > 0) {
    add(checklist, 'exp.mustHaveMissed', entry.result.failedMustHave.map((key) => checklistLabel(key, dict, labels)).join(', '));
  }
  field('field.money', 'compare.price', formatPrice(dict, house.price, house.priceType));
  const bedrooms = house.bedrooms ?? null;
  field('field.bedrooms', 'compare.bhk', bedrooms === null ? '' : tr(dict, 'common.bhk', { n: bedrooms }));
  field('field.areaSqft', 'compare.area', house.areaSqft == null ? '' : tr(dict, 'common.sqft', { n: house.areaSqft }));
  // S4b-BL-87: the floor in words (0 the ground floor), after the area.
  field('field.floor', 'house.floor', house.floor == null ? '' : floorWords((k, p) => tr(dict, k, p), house.floor));
  const rating = house.rating ?? null;
  field('field.rating', 'compare.rating', rating === null ? '' : tr(dict, 'common.stars', { n: rating }));
  field('field.address', 'house.address', house.address ?? '');
  field('field.street', 'house.street', house.street ?? '');
  field('field.locality', 'house.locality', house.locality ?? '');
  field('field.location', 'house.location', `${formatCoord(house.lat)}, ${formatCoord(house.lon)}`);
  // FR-068: the reader learns the pin is only the locality, as the hollow marker says on the map.
  if (house.locationSource === 'APPROX') field('field.locationSource', 'house.approx', tr(dict, 'common.yes'));
  field('field.listingUrl', 'house.listingUrl', house.listingUrl ?? '');
  if (bundle.options.includeContacts) {
    field('field.contactName', 'house.contactName', house.contactName ?? '');
    field('field.contactPhone', 'house.contactPhone', house.contactPhone ?? '');
    const broker = bundle.brokers.find((b) => b.id === house.brokerId);
    field('field.brokerId', 'house.broker', broker ? brokerLine(broker.broker) : '');
  }
  // Kinds PR 6 and 16 (S4b-BL-210, S4b-BL-266): the kind's typed extras of the Details section; a house has none.
  for (const e of entry.extras ?? []) if (e.section !== 'cost') facts.push([e.label, e.text]);
  add('field.label', 'exp.fieldSaved', formatDate(house.createdAt));
  return facts;
}

/**
 * The Cost block (a school's *Fees*): the house's cost rows where the kind has money, then the extras of the kind's cost
 * section, so each extra is written under the heading of its own section (S4b-BL-266).
 */
export function costFacts(entry: ExportHouse, bundle: ExportBundle, dict: Dict): [string, string][] {
  const own = copyShows(entry.kind ?? bundle.voice, 'field.money') ? costEntries(entry.house, dict) : [];
  return [...own, ...(entry.extras ?? []).filter((e) => e.section === 'cost').map((e): [string, string] => [e.label, e.text])];
}

/** The heading of a candidate's Cost block: the house's *Cost*, a school's *Fees* (its kind's section heading). */
export function costHeading(entry: ExportHouse, bundle: ExportBundle, dict: Dict): string {
  return tr(dict, copyField(entry.kind ?? bundle.voice, 'section.cost', 'house.cost'));
}

/** What the ranking table shows (S4b-BL-266): a kind with no checklist has no score column and one with no money no price column. */
export function rankingShows(bundle: ExportBundle): { score: boolean; price: boolean } {
  return { score: copyShows(bundle.voice, 'section.checklist'), price: copyShows(bundle.voice, 'field.money') };
}

/** A section of a candidate its kind hides is not written, whatever a stray row holds (`entry`: the candidate; none: the copy). */
export function sectionShown(bundle: ExportBundle, ref: string, entry?: ExportHouse): boolean {
  return copyShows(entry?.kind ?? bundle.voice, ref);
}

/** A dictionary word in the copy's voice (`exp.title`, `exp.counts`, `exp.colHouse`): the house's key for a house. */
export function voiced(bundle: ExportBundle, key: TKey): TKey {
  return copyWord(bundle.voice, key);
}
