import { describe, expect, it } from 'vitest';
import { nextRunResult, runResult } from './run-result';

/**
 * A message rendered `@for (c of [card]; track c.run)` is a new node, and so is read again, exactly when its run
 * changes (web UX gate R9): every run the user asked for, even with the same words; never a background repeat.
 */
describe('runResult', () => {
  it('numbers every run differently, even with the same value', () => {
    const a = runResult({ key: 'error.network' });
    const b = runResult({ key: 'error.network' });
    expect(b.value).toEqual(a.value);
    expect(b.run).not.toBe(a.run);
  });

  it('a run the user asked for (Retry) is a new run', () => {
    const shown = runResult({ key: 'error.network' });
    expect(nextRunResult(shown, { key: 'error.network' }, true).run).not.toBe(shown.run);
  });

  it('a background re-read keeps the run on screen, with the new value', () => {
    const shown = runResult({ key: 'error.network' });
    const next = nextRunResult(shown, { key: 'error.storageFull' }, false);
    expect(next.run).toBe(shown.run);
    expect(next.value).toEqual({ key: 'error.storageFull' });
  });

  it('a first failure is a new run, whoever started it', () => {
    const first = nextRunResult(null, { key: 'error.network' }, false);
    expect(first.value).toEqual({ key: 'error.network' });
    expect(typeof first.run).toBe('number');
  });
});
