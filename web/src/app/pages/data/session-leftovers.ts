/** The part of `Storage` this needs (a fake in the unit test). */
export type LeftoverStorage = Pick<Storage, 'length' | 'key' | 'removeItem'>;

/**
 * "Remove all data" also removes what this tab keeps in sessionStorage: every key with one of this app's prefixes
 * ({@link LOCAL_KEY_PREFIXES}). Today that is unsaved house drafts (`hh.houseDraft:*`, from `DRAFT_PREFIX`, which
 * can hold a contact's name and phone number) and the text handed to the share page (`hh.shareText`,
 * `SHARE_TEXT_KEY`). A future `hh.*` or `doorprints.*` session key is swept too, without a change here.
 *
 * They are normally removed when the user leaves those pages inside the app, but one survives a reload of the house
 * page followed by leaving it through the address bar, or a tab the browser discarded. On the shared computer of
 * docs/11 §5.10, "removed" must then still be true for this tab. `house-hunt.api-config` (the server address and
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
 * them from both (docs/07 Appendix A.1, S10; {@link clearSessionLeftovers} for the session keys). In localStorage,
 * today `hh.mapView` (the last place looked at; `clearMapView` removes it too),
 * `hh.installDismissedAt` and `hh.storageRiskDismissedAt` (when the install offer and the storage-risk notice were
 * last put off). The two dates hold no house data, but on the shared computer of docs/11 §5.10 they still tell the
 * next person that someone used Doorprints here. `doorprints.` has no key yet; it is swept so a future one is too.
 *
 * The two `house-hunt.` keys are not swept here: `house-hunt.api-config` (the server address and key) is removed by
 * `ConfigService.clear()` just before, and `house-hunt.lang` (the chosen language) stays on purpose, as a
 * preference rather than a record of anything (`LocalStore.clearEverything`).
 */
export const LOCAL_KEY_PREFIXES: readonly string[] = ['hh.', 'doorprints.'];

/** Removes every localStorage key of this app ({@link LOCAL_KEY_PREFIXES}) and nothing else. Returns how many. */
export function clearLocalLeftovers(storage: LeftoverStorage | null): number {
  return removeAppKeys(storage);
}

/** Removes every key with one of {@link LOCAL_KEY_PREFIXES} from `storage`, and nothing else. Returns how many. */
function removeAppKeys(storage: LeftoverStorage | null): number {
  if (!storage) return 0;
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key !== null && LOCAL_KEY_PREFIXES.some((prefix) => key.startsWith(prefix))) keys.push(key);
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
