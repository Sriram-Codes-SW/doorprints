/**
 * Focuses the element with this id when focus has fallen to <body>, which is where it goes when the focused
 * control is removed (Cancel on Ask and Plan when the request ends, "Save anyway" on Connect when a new check
 * succeeds). Focus the user has already moved elsewhere is left alone. Call it after the render that removed the
 * control, e.g. `afterNextRender(() => focusIfLost('ask-submit'), { injector })` (WCAG 2.4.3).
 */
export function focusIfLost(id: string, doc: Document = document): void {
  const active = doc.activeElement;
  if (active === null || active === doc.body) doc.getElementById(id)?.focus();
}
