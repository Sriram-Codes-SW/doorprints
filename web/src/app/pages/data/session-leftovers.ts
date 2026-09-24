import { API_CONFIG_KEY, LANG_KEY, LEGACY_PREFIX, STORAGE_PREFIX } from '../../core/storage-keys';

/** The part of `Storage` this needs (a fake in the unit test). */
export type LeftoverStorage = Pick<Storage, 'length' | 'key' | 'removeItem'>;

/**
 * "Remove all data" also removes what this tab keeps in sessionStorage: every key with one of this app's prefixes
 * ({@link LOCAL_KEY_PREFIXES}) except the ones in {@link KEPT_KEYS}. Today that is unsaved house drafts
 * (`doorprints.houseDraft:*`, from `DRAFT_PREFIX`, which can hold a contact's name and phone number) and the text
 * handed to the share page (`doorprints.shareText`, `SHARE_TEXT_KEY`). A future `doorprints.*` session key is swept
 * too, without a change here.
 *
 * They are normally removed when the user leaves those pages inside the app, but one survives a reload of the house
 * page followed by leaving it through the address bar, or a tab the browser discarded. On the shared computer of
 * docs/11 §5.10, "removed" must then still be true for this tab. `doorprints.api-config` (the server address and
 * key, when "Remember on this device" is off) is not swept here: `ConfigService.clear()` removes it just before.
 * Returns how many entries were removed.
 */
export function clearSessionLeftovers(storage: LeftoverStorage | null): number {
  return removeAppKeys(storage);
}

/** This tab's sessionStorage, or null where it is missing or blocked (private mode, a policy). */
export function tabSessionStorage(): LeftoverStorage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Prefixes of this app's own localStorage and sessionStorage keys. "Remove all data" removes every key with one of
 * them from both, except {@link KEPT_KEYS} (docs/07 Appendix A.1, S10; {@link clearSessionLeftovers} for the
 * session keys). In localStorage, today `doorprints.mapView` (the last place looked at; `clearMapView` removes it
 * too), `doorprints.installDismissedAt` and `doorprints.storageRiskDismissedAt` (when the install offer and the
 * storage-risk notice were last put off). The two dates hold no house data, but on the shared computer of docs/11
 * §5.10 they still tell the next person that someone used Doorprints here. The pre-rename prefix `hh.` is swept too,
 * in case a key could not be moved to its new name at start (core/storage-keys.ts).
 */
export const LOCAL_KEY_PREFIXES: readonly string[] = [STORAGE_PREFIX, LEGACY_PREFIX];

/**
 * Keys with one of {@link LOCAL_KEY_PREFIXES} that "Remove all data" does not sweep: `doorprints.api-config` (the
 * server address and key) is removed by `ConfigService.clear()` just before, and `doorprints.lang` (the chosen
 * language) stays on purpose, as a preference rather than a record of anything (`LocalStore.clearEverything`).
 * Explicit, because both share the `doorprints.` prefix with the keys that are swept.
 */
export const KEPT_KEYS: readonly string[] = [LANG_KEY, API_CONFIG_KEY];

/** Removes every localStorage key of this app ({@link LOCAL_KEY_PREFIXES}, not {@link KEPT_KEYS}). Returns how many. */
export function clearLocalLeftovers(storage: LeftoverStorage | null): number {
  return removeAppKeys(storage);
}

/** Removes every key with one of {@link LOCAL_KEY_PREFIXES} but not in {@link KEPT_KEYS}, and nothing else. */
function removeAppKeys(storage: LeftoverStorage | null): number {
  if (!storage) return 0;
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key === null || KEPT_KEYS.includes(key)) continue;
    if (LOCAL_KEY_PREFIXES.some((prefix) => key.startsWith(prefix))) keys.push(key);
  }
  // Collected first: removing while walking by index would skip the entry after each removed one.
  for (const key of keys) storage.removeItem(key);
  return keys.length;
}

/** This origin's localStorage, or null where it is missing or blocked (private mode, a policy). */
export function originLocalStorage(): LeftoverStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
