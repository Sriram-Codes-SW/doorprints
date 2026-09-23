/** How many houses Compare shows side by side. */
export const MAX_SELECTED = 4;
export const MIN_SELECTED = 2;

/**
 * The house ids a `?ids=a,b,c` query parameter asks for, in order and without duplicates, kept only when they are
 * houses that can be compared (`allowed`); at most {@link MAX_SELECTED}. Null when the parameter is absent, so the
 * page knows to pick a default instead.
 */
export function idsFromQuery(raw: string | null, allowed: ReadonlySet<string>): string[] | null {
  if (raw === null) return null;
  const ids: string[] = [];
  for (const id of raw.split(',').map((x) => x.trim())) {
    if (id && allowed.has(id) && !ids.includes(id)) ids.push(id);
    if (ids.length === MAX_SELECTED) break;
  }
  return ids;
}
