import { afterEach, describe, expect, it } from 'vitest';
import { focusIfLost } from './focus';

/** Ask, Plan and Connect bring focus back to their main button only when the focused control was removed. */
describe('focusIfLost', () => {
  afterEach(() => document.body.replaceChildren());

  function buttons(): [HTMLButtonElement, HTMLButtonElement] {
    const target = document.createElement('button');
    target.id = 'target';
    const other = document.createElement('button');
    document.body.append(target, other);
    return [target, other];
  }

  it('focuses the element when focus is on <body>', () => {
    const [target] = buttons();
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.activeElement).toBe(document.body);
    focusIfLost('target');
    expect(document.activeElement).toBe(target);
  });

  it('leaves focus the user moved elsewhere alone', () => {
    const [, other] = buttons();
    other.focus();
    focusIfLost('target');
    expect(document.activeElement).toBe(other);
  });

  it('does nothing when the element is not there', () => {
    (document.activeElement as HTMLElement | null)?.blur();
    expect(() => focusIfLost('missing')).not.toThrow();
    expect(document.activeElement).toBe(document.body);
  });
});
