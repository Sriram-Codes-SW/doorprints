import { describe, expect, it } from 'vitest';
import { keepLocal, keepLocalRecord } from './sync-rules';

/**
 * The same cases as `android/shared/.../sync/SyncRulesTest.kt`. If one side ever changes, two devices stop
 * converging, so these expectations must stay identical in both apps.
 */
describe('keepLocal', () => {
  it('keeps a local edit only when it is dirty and strictly newer', () => {
    expect(keepLocal(true, 200, 100)).toBe(true);
    expect(keepLocal(true, 100, 200)).toBe(false);
  });

  it('lets the server win on a tie, so every device converges', () => {
    expect(keepLocal(true, 100, 100)).toBe(false);
  });

  it('never keeps a row that has already been pushed', () => {
    expect(keepLocal(false, 500, 100)).toBe(false);
  });
});

describe('keepLocalRecord', () => {
  const incoming = { updatedAt: '2026-09-10T10:00:00.000Z' };

  it('treats a row this browser does not have as new', () => {
    expect(keepLocalRecord(undefined, incoming)).toBe(false);
    expect(keepLocalRecord(null, incoming)).toBe(false);
  });

  it('compares ISO timestamps as instants', () => {
    expect(keepLocalRecord({ updatedAt: '2026-09-10T15:30:00+05:30', dirty: true }, incoming)).toBe(false);
    expect(keepLocalRecord({ updatedAt: '2026-09-10T16:00:00+05:30', dirty: true }, incoming)).toBe(true);
  });

  it('never keeps a clean local row', () => {
    expect(keepLocalRecord({ updatedAt: '2030-01-01T00:00:00.000Z', dirty: false }, incoming)).toBe(false);
  });

  it('treats a missing local timestamp as the epoch', () => {
    expect(keepLocalRecord({ updatedAt: null, dirty: true }, incoming)).toBe(false);
  });
});
