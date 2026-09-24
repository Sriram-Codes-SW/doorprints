import { describe, expect, it } from 'vitest';
import { foldAttribution } from './map-style';

/** A map container with MapLibre's credits control in the given state, `width` px wide. */
function mapRoot(width: number, classes: string): HTMLElement {
  const root = document.createElement('div');
  Object.defineProperty(root, 'offsetWidth', { value: width });
  const credits = document.createElement('details');
  credits.className = `maplibregl-ctrl maplibregl-ctrl-attrib ${classes}`;
  root.append(credits);
  return root;
}

const creditsOf = (root: HTMLElement) => root.querySelector('.maplibregl-ctrl-attrib')!;

describe('foldAttribution (owner report 2026-09-24: credits open over a phone map for good)', () => {
  it('folds open compact credits on a phone-width map into the (i) button', () => {
    const root = mapRoot(384, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(root)).toBe(true);
    expect(creditsOf(root).classList).toContain('maplibregl-compact');
    expect(creditsOf(root).classList).not.toContain('maplibregl-compact-show');
  });

  it('folds at MapLibre own compact width, 640px, and leaves a wider (desktop) map as it is', () => {
    expect(foldAttribution(mapRoot(640, 'maplibregl-compact maplibregl-compact-show'))).toBe(true);
    const wide = mapRoot(641, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(wide)).toBe(false);
    expect(creditsOf(wide).classList).toContain('maplibregl-compact-show');
  });

  it('does nothing when the credits are already folded, or not compact', () => {
    expect(foldAttribution(mapRoot(360, 'maplibregl-compact'))).toBe(false);
    const full = mapRoot(360, '');
    expect(foldAttribution(full)).toBe(false);
    expect(creditsOf(full).className).toBe('maplibregl-ctrl maplibregl-ctrl-attrib ');
  });

  it('does nothing on a map with no credits control (map unavailable)', () => {
    expect(foldAttribution(document.createElement('div'))).toBe(false);
  });
});
