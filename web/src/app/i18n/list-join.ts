/**
 * "a", "a and b", "a, b and c", "a, b, c and d", … with each language's own list pattern — the same rule as
 * Android's `ImportWorker.joinList`: two items use `two`; three or more use `three`, with everything before the
 * last two folded into its first slot by `middle` ("a, b"). Any number of items: a list that exists to say what is
 * left out must never drop the fourth one.
 */
export function joinList(
  items: readonly string[],
  two: (a: string, b: string) => string,
  three: (a: string, b: string, c: string) => string,
  middle: (a: string, b: string) => string,
): string {
  if (items.length === 0) return '';
  if (items.length === 1) return items[0];
  if (items.length === 2) return two(items[0], items[1]);
  const head = items.slice(0, items.length - 2).reduce(middle);
  return three(head, items[items.length - 2], items[items.length - 1]);
}
