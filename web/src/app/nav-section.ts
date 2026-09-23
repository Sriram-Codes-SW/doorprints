/**
 * Which navigation item stands for the page on screen (the app shell's header and bottom bar). Pure, so it is unit
 * tested without the shell.
 */

/** The map and the pages under it: a house and the new-house form (Back goes to the map; Android nests them too). */
export function inMapSection(path: string): boolean {
  return path === '/' || path === '/houses' || path.startsWith('/houses/');
}

/**
 * A page under the map (a house, the new-house form) rather than the map itself: phones hide the bottom bar there and
 * give the form the whole screen, with the toolbar's Back to the map, as Android shows its NavigationBar only on tab
 * destinations (Root.kt). UX lead audit, round 3.
 */
export function hidesBottomBar(path: string): boolean {
  return inMapSection(path) && path !== '/';
}

/** Your data, and on phones also Connect, which has no item of its own in the bottom bar and lives in Your data. */
export function inDataSection(path: string, phone: boolean): boolean {
  return path.startsWith('/data') || (phone && path.startsWith('/connect'));
}

/**
 * `aria-current` of an item that stands for a section: "page" on the section's own page, "true" on a page inside it
 * (a house under the map, Connect inside Your data on phones), and none elsewhere.
 */
export function sectionCurrent(onOwnPage: boolean, inSection: boolean): 'page' | 'true' | null {
  if (onOwnPage) return 'page';
  return inSection ? 'true' : null;
}
