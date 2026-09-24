/**
 * This app's localStorage and sessionStorage key names, and the one-time move from the names used before the rename
 * to Doorprints.
 *
 * Every key starts with {@link STORAGE_PREFIX}. Until 2026-09-24 the language and the server settings were
 * `house-hunt.lang` and `house-hunt.api-config`, and every other key started with `hh.` (`hh.mapView`,
 * `hh.houseDraft:<id>`, …). {@link migrateLegacyStorage} runs in `main.ts` before anything reads storage and moves
 * them to their new names, so nobody loses a saved language, server, map position or unsaved draft.
 */

/** The prefix of every key this app writes. */
export const STORAGE_PREFIX = 'doorprints.';

/** The chosen language (localStorage). A preference that "Remove all data" keeps. */
export const LANG_KEY = `${STORAGE_PREFIX}lang`;

/** The server address and API key (localStorage when remembered, else sessionStorage). See ConfigService. */
export const API_CONFIG_KEY = `${STORAGE_PREFIX}api-config`;

/**
 * Stored names from before the rename, kept only so {@link migrateLegacyStorage} can find them: a key in this
 * list moves to the name beside it, and a key starting with {@link LEGACY_PREFIX} moves to the same suffix after
 * {@link STORAGE_PREFIX}.
 */
export const LEGACY_KEYS: ReadonlyMap<string, string> = new Map([
  ['house-hunt.lang', LANG_KEY],
  ['house-hunt.api-config', API_CONFIG_KEY],
]);

/** The prefix of the other pre-rename keys (`hh.mapView` → `doorprints.mapView`). */
export const LEGACY_PREFIX = 'hh.';

/** The part of `Storage` the move needs (a fake in the unit test). */
export type MigratableStorage = Pick<Storage, 'length' | 'key' | 'getItem' | 'setItem' | 'removeItem'>;

/** The current name of a pre-rename key, or null when `key` is not one. */
export function currentKeyFor(key: string): string | null {
  const renamed = LEGACY_KEYS.get(key);
  if (renamed !== undefined) return renamed;
  if (key.startsWith(LEGACY_PREFIX)) return STORAGE_PREFIX + key.slice(LEGACY_PREFIX.length);
  return null;
}

/**
 * Moves every pre-rename key in `storage` to its current name. A value already saved under the current name wins
 * (it is newer), and then the old key is simply removed. The old key is removed only once its value is safe under
 * the new name: if writing fails (storage full or blocked), it stays and the move is tried again at the next start.
 * Returns how many keys were moved or dropped as superseded.
 */
export function migrateLegacyKeys(storage: MigratableStorage | null): number {
  if (!storage) return 0;
  const legacy: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key !== null && currentKeyFor(key) !== null) legacy.push(key);
  }
  // Collected first: changing storage while walking it by index would skip or repeat entries.
  let done = 0;
  for (const key of legacy) {
    const target = currentKeyFor(key)!;
    try {
      if (storage.getItem(target) === null) {
        const value = storage.getItem(key);
        if (value === null) continue;
        storage.setItem(target, value);
      }
      storage.removeItem(key);
      done++;
    } catch {
      // Keep the old key; nothing is lost, and the next start tries again.
    }
  }
  return done;
}

/** Runs {@link migrateLegacyKeys} on this origin's localStorage and this tab's sessionStorage. Never throws. */
export function migrateLegacyStorage(): void {
  for (const pick of [() => localStorage, () => sessionStorage]) {
    try {
      migrateLegacyKeys(pick());
    } catch {
      // Storage missing (not a browser) or blocked (private mode, a policy): nothing to move.
    }
  }
}
