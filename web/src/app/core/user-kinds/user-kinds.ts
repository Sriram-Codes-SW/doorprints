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

import type { TKey } from '../../i18n/en';
import { USER_TEXT_PREFIX, setUserTexts } from '../../i18n/user-text';
import type { BuiltInKind } from '../dev-kinds';
import type { KindCapabilities } from '../kind-capabilities';
import { KindFields, type KindExtra } from '../kind-fields';
import { KindKeys, KindLabels } from '../kind-labels';
import { STORAGE_PREFIX } from '../storage-keys';
import { USER_KIND_LIMITS, readUserKind, type UserKindFile, type UserKindRead } from './user-kind-file';
import { replaceUserKindFacts, setUserKindLoader, type UserKindFacts } from './user-kind-ids';

/**
 * The kinds a person added by importing a kind file (docs/03 ADR-38): stored in this browser, checked again every time they
 * are read, and drawn as any other kind (the conformance suite runs its flows for one, `kind-conformance.spec.ts`).
 *
 * - **Stored** in localStorage under {@link USER_KINDS_KEY}, as the list of checked files, because the kinds must be known
 *   before the router is built (`app.config.ts` reads the spaces as the app starts, synchronously), as the rest of the
 *   kinds setting is. At most {@link USER_KIND_LIMITS}.maxKinds kinds of at most 64 KiB each. "Remove all data" sweeps the
 *   key with every other `doorprints.` key. A stored file this version cannot read is kept (a newer version may) but not drawn.
 * - **In the backup**: `search.userKinds` of a full backup (`backup-family.ts`); an import adds the ones this browser does not
 *   have, each checked as an imported file is. Not in the Drive sync file or on the server: their entries are (the `kind` id
 *   is kept by every reader), and a device without the kind shows them in its default space until the kind is added there.
 * - **Words**: the person's own, shown as written in every language (`i18n/user-text.ts`); the words of a space that name a
 *   kind are the neutral ones (`ukind.*`), except the heading and the add button, which are the kind's own labels.
 * - **No AI** reads a kind a person adds (its `ai` slots are empty by the profile): its space has no assistant and no listing fill.
 */

/** The stored kind files (localStorage, a JSON list). */
export const USER_KINDS_KEY = `${STORAGE_PREFIX}kinds.user`;

type Read = Pick<Storage, 'getItem'> | null;
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null;

interface Entry {
  readonly file: UserKindFile;
  readonly kind: BuiltInKind;
  readonly capabilities: KindCapabilities;
}

const KINDS = new Map<string, Entry>();

/** The key of a person's word for `ref` of kind `id`. */
export function userTextKey(id: string, ref: string): TKey {
  return `${USER_TEXT_PREFIX}${id}.${ref}` as TKey;
}

/** The words of a space that are the kind's own labels (the rest are the neutral `ukind.*` words). */
const OWN_WORDS: Readonly<Record<string, string>> = {
  heading: 'kind.many', add: 'kind.new', create: 'kind.new', exportScope: 'kind.many', titleNew: 'w.titleNew', titleMap: 'w.titleMap',
};

/** A checked kind file as the screens read a kind: its labels and keys, its typed extras, its texts and its registry facts. */
export function buildUserKind(file: UserKindFile, capabilities: KindCapabilities): { kind: BuiltInKind; texts: Record<string, string>; facts: UserKindFacts } {
  const id = file.id;
  const texts: Record<string, string> = {};
  const keys: Record<string, TKey> = {};
  const put = (ref: string, text: string): TKey => {
    const key = userTextKey(id, ref);
    texts[key] = text;
    return key;
  };
  const label = (ref: string, text: string) => {
    keys[ref] = put(ref, text);
  };
  label('kind.new', file.labels.new.text);
  label('kind.many', file.labels.many.text);
  label('kind.delete', file.labels.delete.text);
  put('w.titleNew', `${file.labels.new.text} · Doorprints`);
  put('w.titleMap', `${file.labels.many.text} · Doorprints`);
  for (const s of file.statuses) label(`status.${s.id}`, s.label.text);
  for (const s of file.sections) label(`section.${s.id}`, s.label.text);
  for (const c of file.core) {
    label(`field.${c.id}`, c.label.text);
    for (const cad of c.cadences ?? []) label(`cadence.${cad.id}`, cad.label.text);
  }
  for (const e of file.extras) label(`field.${e.id}`, e.label.text);
  for (const c of file.checklist) label(`check.${c.key}`, c.label.text);
  const sections: Record<string, string> = {};
  for (const c of file.core) sections[c.id] = c.section;
  for (const e of file.extras) sections[e.id] = e.section;
  const hidden = [
    ...file.sections.filter((s) => s.visible === false).map((s) => `section.${s.id}`),
    ...file.core.filter((c) => c.visible === false).map((c) => `field.${c.id}`),
    ...file.extras.filter((e) => e.visible === false).map((e) => `field.${e.id}`),
  ];
  const labels = new KindLabels({ kind: id, capabilities, keys, byCadence: {}, sections, hidden });
  const extras: KindExtra[] = file.extras.map((e) => ({
    id: e.id, type: e.type, section: e.section, order: e.order,
    ...(e.integer !== undefined ? { integer: e.integer } : {}), ...(e.min !== undefined ? { min: e.min } : {}), ...(e.max !== undefined ? { max: e.max } : {}),
    ...(e.unit ? { unit: e.unit } : {}), ...(e.better ? { better: e.better } : {}),
    ...(e.cadences ? { cadences: e.cadences as KindExtra['cadences'] } : {}), ...(e.cadenceFrom ? { cadenceFrom: e.cadenceFrom } : {}),
    ...(e.forCadences ? { forCadences: e.forCadences as KindExtra['forCadences'] } : {}), ...(e.maxLength ? { maxLength: e.maxLength } : {}),
    ...(e.options ? { options: e.options.map((o) => ({ id: o.id, label: put(`option.${e.id}.${o.id}`, o.label.text) })) } : {}),
  }));
  const fields = new KindFields(labels, extras, searchOf(file));
  const kind: BuiltInKind = { id, labels, keys: new KindKeys(labels), fields, extracts: false };
  const words: Record<string, string> = {};
  for (const [word, ref] of Object.entries(OWN_WORDS)) words[word] = userTextKey(id, ref);
  const facts: UserKindFacts = { id, capabilities, scopes: file.questions.scopes.map((s) => s.id), words };
  return { kind, texts, facts };
}

/**
 * What the list search reads for a kind a person adds: its file's `search`, then every typed extra it does not name, so a
 * field the person defined is always found (CLAUDE.md, *search grows with the values*). The core fields are searched for
 * every kind (`searchText`).
 */
export function searchOf(file: UserKindFile): string[] {
  return [...file.search, ...file.extras.map((e) => e.id).filter((x) => !file.search.includes(x))];
}

function localStore(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** The stored list as it is (each item unchecked), or an empty list. */
function stored(storage: Read): unknown[] {
  try {
    const v: unknown = JSON.parse(storage?.getItem(USER_KINDS_KEY) ?? '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

/** Checks a stored or a backup's kind file again, from its own JSON text, as an imported file is. */
function recheck(raw: unknown): UserKindRead {
  let text: string;
  try {
    text = JSON.stringify(raw) ?? '';
  } catch {
    return { ok: false, fault: { code: 'notJson', path: '$' } };
  }
  return readUserKind(text);
}

/** Reads the stored kinds again and rebuilds every registry (the first time anything asks, and after every change). */
export function reloadUserKinds(storage: Read = localStore()): void {
  ready = true;
  KINDS.clear();
  const texts: Record<string, string> = {};
  const facts: UserKindFacts[] = [];
  for (const raw of stored(storage)) {
    if (KINDS.size >= USER_KIND_LIMITS.maxKinds) break;
    const read = recheck(raw);
    if (!read.ok || KINDS.has(read.file.id)) continue;
    const built = buildUserKind(read.file, read.capabilities);
    KINDS.set(read.file.id, { file: read.file, kind: built.kind, capabilities: read.capabilities });
    Object.assign(texts, built.texts);
    facts.push(built.facts);
  }
  setUserTexts(texts);
  replaceUserKindFacts(facts);
}

let ready = false;
function userKindsReady(): void {
  if (!ready) {
    ready = true;
    reloadUserKinds();
  }
}
setUserKindLoader(userKindsReady);

/** The kinds this browser has added, drawn as kinds (in the order they were added). */
export function userBuiltInKinds(): readonly BuiltInKind[] {
  userKindsReady();
  return [...KINDS.values()].map((e) => e.kind);
}

/** The checked file of an added kind, or undefined. */
export function userKindFile(id: string): UserKindFile | undefined {
  userKindsReady();
  return KINDS.get(id)?.file;
}

/** Every added kind's checked file, for the backup (`search.userKinds`). */
export function userKindFiles(): readonly UserKindFile[] {
  userKindsReady();
  return [...KINDS.values()].map((e) => e.file);
}

/** What adding a kind did. */
export type AddResult = 'added' | 'replaced' | 'full' | 'storage';

/**
 * Stores a checked file: a new kind is added at the end (refused when this browser has {@link USER_KIND_LIMITS}.maxKinds), one
 * with the id of an added kind replaces it in place (the person confirmed it). Its entries are never touched.
 */
export function saveUserKind(file: UserKindFile, storage: Store = localStore()): AddResult {
  const list = stored(storage);
  const at = list.findIndex((raw) => typeof raw === 'object' && raw !== null && (raw as { id?: unknown }).id === file.id);
  if (at < 0 && userKindIdsStored(list).length >= USER_KIND_LIMITS.maxKinds) return 'full';
  const next = [...list];
  if (at >= 0) next[at] = file;
  else next.push(file);
  if (!write(storage, next)) return 'storage';
  ready = true;
  reloadUserKinds(storage);
  return at >= 0 ? 'replaced' : 'added';
}

/** Takes an added kind away: its file only. Its entries stay, in the default space, until the kind is added again. */
export function removeUserKind(id: string, storage: Store = localStore()): boolean {
  const list = stored(storage);
  const next = list.filter((raw) => !(typeof raw === 'object' && raw !== null && (raw as { id?: unknown }).id === id));
  if (next.length === list.length) return false;
  if (!write(storage, next)) return false;
  ready = true;
  reloadUserKinds(storage);
  return true;
}

/**
 * The kinds of a backup (`search.userKinds`) this browser does not have yet, added after each is checked as an imported file
 * is; one already here is kept as it is (never replaced by a file), one that does not pass is left out. Returns the ids added.
 */
export function adoptUserKinds(raws: readonly unknown[] | null | undefined, storage: Store = localStore()): string[] {
  if (!Array.isArray(raws)) return [];
  const added: string[] = [];
  for (const raw of raws.slice(0, USER_KIND_LIMITS.maxKinds)) {
    const read = recheck(raw);
    if (!read.ok || userKindIdsStored(stored(storage)).includes(read.file.id)) continue;
    if (saveUserKind(read.file, storage) === 'added') added.push(read.file.id);
  }
  return added;
}

function userKindIdsStored(list: readonly unknown[]): string[] {
  return list.map((raw) => (typeof raw === 'object' && raw !== null ? (raw as { id?: unknown }).id : null)).filter((x): x is string => typeof x === 'string');
}

function write(storage: Store, list: readonly unknown[]): boolean {
  try {
    if (!storage) return false;
    if (list.length) storage.setItem(USER_KINDS_KEY, JSON.stringify(list));
    else storage.removeItem(USER_KINDS_KEY);
    return true;
  } catch {
    return false;
  }
}
