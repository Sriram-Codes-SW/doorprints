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

// The conformance suite's helper (kinds PR 32, docs/03 §18.26): `forKind(id)` gives the same flows a house, a school and the synthetic kind
// of `docs/schemas/kinds/test-all.json` (every capability on). The synthetic kind is built from its file by `builtInKindFromFile` and registered
// through the development-only seams of the kind registries (`registerTestKind`, `registerTestCapabilities`, `registerTestAiKind`,
// `registerQuestionBank`), so a feature that works only for the two built-in kinds fails against it.
import houseJson from '../../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../../docs/schemas/kinds/school.json';
import testAllJson from '../../../../../docs/schemas/kinds/test-all.json';
import { houseOf, type CandidateDto, type ExtraValue } from '../../core/candidate';
import { type BuiltInKind, registerTestKind } from '../../core/dev-kinds';
import { type AiKind, registerTestAiKind } from '../../core/ai/kind-ai';
import { KindFields, type KindExtra } from '../../core/kind-fields';
import { KindKeys, KindLabels } from '../../core/kind-labels';
import { capabilitiesOf, parseCapabilities, registerTestCapabilities, type KindCapabilities } from '../../core/kind-capabilities';
import { kindById } from '../../core/kind-setting';
import type { HouseDto } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { registerQuestionBank, type DefaultQuestion } from '../question';

type Label = { web: string | null; android: string | null };
interface FileExtra {
  id: string; type: KindExtra['type']; section: string; order: number; label: Label; integer?: boolean; min?: number; max?: number; unit?: string;
  better?: 'lower' | 'higher'; cadences?: string[]; cadenceFrom?: 'money'; forCadences?: string[]; maxLength?: number; was?: string;
  options?: { id: string; label: Label }[];
}
/** A kind file as the suite reads it (the parts it needs). */
export interface KindFileJson {
  id: string;
  labels: { new: Label; many: Label; delete: Label };
  statuses: { id: string; label: Label }[];
  sections: { id: string; label: Label; visible?: boolean }[];
  core: { id: string; section: string; order: number; label: Label; visible?: boolean; cadences?: { id: string; label: Label }[] }[];
  extras: FileExtra[];
  checklist: { key: string; label: Label }[];
  questions: { seed: string; scopes: { id: string; cadences: string[] }[] };
  search: string[];
  ai: { extract: string[]; ask: string[]; plan: string[]; never: string[]; words?: { noun: string; records: string; extras?: Record<string, { words: string; options?: Record<string, string> }> } };
  capabilities?: unknown;
}

/** The ids the suite runs its flows for: the house as today, the school, and the synthetic kind with every capability. */
export const FLOW_KINDS = ['house', 'school', 'zz_all'] as const;
export type FlowKindId = (typeof FLOW_KINDS)[number];

const FILES: Readonly<Record<FlowKindId, KindFileJson>> = {
  house: houseJson as unknown as KindFileJson,
  school: schoolJson as unknown as KindFileJson,
  zz_all: testAllJson as unknown as KindFileJson,
};

const web = (l: Label): TKey | null => l.web as TKey | null;

/** The kind of a kind file: its labels, its keys and its typed extras, as the screens read a built-in kind. */
export function builtInKindFromFile(file: KindFileJson): BuiltInKind {
  const keys: Record<string, TKey> = {};
  const put = (ref: string, l: Label | undefined) => { const k = l ? web(l) : null; if (k) keys[ref] = k; };
  put('kind.new', file.labels.new); put('kind.many', file.labels.many); put('kind.delete', file.labels.delete);
  for (const s of file.statuses) put(`status.${s.id}`, s.label);
  for (const s of file.sections) put(`section.${s.id}`, s.label);
  for (const c of file.core) {
    put(`field.${c.id}`, c.label);
    for (const cad of c.cadences ?? []) put(`cadence.${cad.id}`, cad.label);
  }
  for (const e of file.extras) put(`field.${e.id}`, e.label);
  for (const c of file.checklist) put(`check.${c.key}`, c.label);
  const sections: Record<string, string> = {};
  for (const c of file.core) sections[c.id] = c.section;
  for (const e of file.extras) sections[e.id] = e.section;
  const hidden = [
    ...file.sections.filter((s) => s.visible === false).map((s) => `section.${s.id}`),
    ...file.core.filter((c) => c.visible === false).map((c) => `field.${c.id}`),
  ];
  const caps = parseCapabilities(file.capabilities) ?? capabilitiesOf(file.id);
  const labels = new KindLabels({ kind: file.id, capabilities: caps, keys, byCadence: {}, sections, hidden });
  const extras: KindExtra[] = file.extras.map((e) => ({
    id: e.id, type: e.type, section: e.section, order: e.order,
    ...(e.integer !== undefined ? { integer: e.integer } : {}), ...(e.min !== undefined ? { min: e.min } : {}), ...(e.max !== undefined ? { max: e.max } : {}),
    ...(e.unit ? { unit: e.unit } : {}), ...(e.better ? { better: e.better } : {}),
    ...(e.cadences ? { cadences: e.cadences as KindExtra['cadences'] } : {}), ...(e.cadenceFrom ? { cadenceFrom: e.cadenceFrom } : {}),
    ...(e.forCadences ? { forCadences: e.forCadences as KindExtra['forCadences'] } : {}), ...(e.maxLength ? { maxLength: e.maxLength } : {}),
    ...(e.options ? { options: e.options.map((o) => ({ id: o.id, label: web(o.label) as TKey })) } : {}),
    ...(e.was ? { was: e.was } : {}),
  }));
  return { id: file.id, labels, keys: new KindKeys(labels), fields: new KindFields(labels, extras, file.search), extracts: file.ai.extract.length > 0 };
}

/** The AI reading of a kind file's `ai` block (its words, its slots and its extras), with plain prompts: the suite asserts the documents. */
export function aiKindFromFile(file: KindFileJson): AiKind {
  const words = file.ai.words!;
  const extras = Object.entries(words.extras ?? {}).map(([id, w]) => {
    const e = file.extras.find((x) => x.id === id)!;
    return {
      id, type: e.type as AiKind['extras'][number]['type'], words: w.words, ...(w.options ? { options: w.options } : {}),
      ...(e.min !== undefined ? { min: e.min } : {}), ...(e.max !== undefined ? { max: e.max } : {}),
      ...(e.maxLength ? { maxLength: e.maxLength } : {}), ...(e.cadences ? { cadence: e.cadences[0] } : {}),
    };
  });
  const refusal = `I don't know based on the ${words.records} you have saved.`;
  return {
    id: file.id, noun: words.noun, extract: file.ai.extract, ask: file.ai.ask, plan: file.ai.plan, never: file.ai.never, extras,
    extractSystem: (tag) => `You extract ${words.noun} details from <${tag}>.`, records: words.records, refusal,
    askSystem: (tag) => `You answer questions about the saved ${words.records} between <${tag}>. If they do not contain the answer, reply exactly: "${refusal}"`,
    planSystem: (tag, lat, lon, max) => `Plan at most ${max} stops from ${lat}, ${lon} over the ${words.records} in <${tag}>.`,
  };
}

/** Two questions in the kind's own bank (`qd_<kind>_`), so its Questions page, its seed and its scopes have something to show. */
function questionBankOf(id: string): DefaultQuestion[] {
  const q = (n: number, en: string): DefaultQuestion => ({
    id: `qd_${id}_${n}`, category: 'OTHER', appliesTo: 'BOTH', defaultOn: true, sort: n, text: { en, hi: en, ta: en, te: en },
  });
  return [q(1, 'Is the place open on Sundays?'), q(2, 'Who is the person to ask for?')];
}

/** Registers a kind file as a kind of this run (kind, capabilities, AI reading and question bank); returns the call that takes it all away. */
export function registerKindFile(file: KindFileJson): () => void {
  const caps = parseCapabilities(file.capabilities);
  if (!caps) throw new Error(`${file.id}: capabilities do not parse`);
  const undo = [
    registerTestCapabilities(file.id, caps),
    registerTestKind(builtInKindFromFile(file)),
    registerTestAiKind(aiKindFromFile(file)),
    registerQuestionBank(file.id, questionBankOf(file.id), file.questions.scopes.map((s) => s.id) as never),
  ];
  return () => undo.reverse().forEach((u) => u());
}

/** A value of an extra, `n` giving a second, different one (Compare marks a best, a filter keeps one). */
function extraValueOf(e: FileExtra, n: number, moneyCadence: string): ExtraValue {
  switch (e.type) {
    case 'number': return Math.min(e.max ?? 1000, Math.max(e.min ?? 0, 2) + n);
    case 'money': return { amount: 12000 - n * 3000, cadence: (e.cadences?.[0] ?? (e.cadenceFrom ? moneyCadence : 'month')) as never };
    case 'text': return n === 0 ? 'Alpha Wing' : 'Beta Wing';
    case 'enum': return e.options![Math.min(n, e.options!.length - 1)].id;
    case 'date': return n === 0 ? '2026-10-01' : '2026-11-15';
    default: return n === 0;
  }
}

/** One kind of the suite: its file, its capabilities and a candidate of it (`n` is 0 or 1: two different ones). */
export interface FlowKind {
  readonly id: FlowKindId;
  readonly file: KindFileJson;
  readonly caps: KindCapabilities;
  readonly baseline: boolean;
  /** The built-in kind as the screens read it (registered first, for the synthetic one). */
  kind(): BuiltInKind;
  candidate(n?: number): CandidateDto;
  house(n?: number): HouseDto;
}

/**
 * The same flows for every kind: `forKind('zz_all')` registers the synthetic kind (call `release()` after, `afterEach`), the other two need nothing.
 * A candidate has a label, a place, a rating, a status, a note, a phone, a score for three checklist items and one value for every extra the file has.
 */
export function forKind(id: FlowKindId): FlowKind & { release(): void } {
  const file = FILES[id];
  const release = id === 'zz_all' ? registerKindFile(file) : () => undefined;
  const money = file.core.some((c) => c.id === 'money');
  const caps = capabilitiesOf(id);
  const candidate = (n = 0): CandidateDto => {
    const cadence = money ? (n === 0 ? 'month' : 'once') : null;
    const extras: Record<string, ExtraValue> = {};
    for (const e of file.extras) extras[e.id] = extraValueOf(e, n, cadence ?? 'month');
    return {
      id: `55555555-5555-4555-8555-00000000${(id.length * 100 + n).toString(16).padStart(4, '0')}`,
      kind: id,
      label: `${n === 0 ? 'Alpha' : 'Beta'} ${id.replace(/_/g, '-')} place`, address: `${n + 4} Lake Road, Adyar`, street: 'Lake Road', locality: 'Adyar',
      lat: 13.0 + n * 0.01, lon: 80.25, status: n === 0 ? 'SHORTLISTED' : 'NEW', rating: 4 - n, notes: `Note ${n} ${id}`,
      contactName: 'Mrs Rao', contactPhone: '+91 98765 43210',
      ...(money ? { money: { amount: n === 0 ? 28000 : 5_000_000, cadence: cadence as 'month' | 'once' } } : {}),
      checklist: Object.fromEntries(file.checklist.slice(0, 3).map((c, i) => [c.key, 5 - i - n])),
      extras, deleted: false, syncVersion: 0, createdAt: '2026-10-01T10:00:00.000Z', updatedAt: '2026-10-01T10:00:00.000Z',
    } as unknown as CandidateDto;
  };
  return {
    id, file, caps, baseline: caps.baseline, release,
    kind: () => kindById(id),
    candidate,
    house: (n = 0) => houseOf(candidate(n)),
  };
}
