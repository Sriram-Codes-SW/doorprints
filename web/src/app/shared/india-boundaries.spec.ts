import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FilterSpecification, LayerSpecification, SourceSpecification, StyleSpecification } from 'maplibre-gl';
import {
  applyIndiaBoundaries,
  type BoundaryStyleTarget,
  FALLBACK_LINE_PAINT,
  HIDDEN_STATE_LOCAL_NAMES,
  HIDDEN_STATE_NAMES,
  inBoundariesUrl,
  indiaBoundaryStyle,
  isLegacyFilter,
  STATE_FALLBACK_LINE_PAINT,
  TILE_ZOOM_GUARD,
} from './india-boundaries';
import { libertyExcerpt } from './testing/liberty-style.fixture';

const URL_ = 'https://doorprints.web.app/geo/in-boundaries.geojson';

type Json = Record<string, unknown>;
const ids = (style: StyleSpecification) => style.layers.map((l) => l.id);
const layer = (style: StyleSpecification, id: string) => style.layers.find((l) => l.id === id) as unknown as Json;
const read = (l: LayerSpecification, key: string) => (l as unknown as Json)[key];
const without = (style: StyleSpecification, drop: string[]): StyleSpecification => ({
  ...style,
  layers: style.layers.filter((l) => !drop.includes(l.id)),
});

/**
 * The part of the MapLibre expression language these filters use, with MapLibre's semantics (`get` of a missing
 * property is null, `in` looks a value up in an array, `==`/`!=` compare strictly, `>=`/`<=` compare two numbers or
 * two strings). `zoom` is `tileZoom`, the zoom of the tile the feature comes from: maplibre-gl evaluates a layer filter
 * with the worker tile's zoom (`line_bucket.ts`, `new EvaluationParameters(this.zoom)`), not the map's. Anything else
 * throws, so a rule that starts using another operator, or `zoom` where no tile zoom is given, cannot pass by accident.
 */
function evaluate(expr: unknown, props: Json, tileZoom?: number): unknown {
  if (!Array.isArray(expr)) return expr;
  const [op, ...args] = expr as [string, ...unknown[]];
  const arg = (i: number) => evaluate(args[i], props, tileZoom);
  const ordered = (): [number, number] | [string, string] => {
    const a = arg(0);
    const b = arg(1);
    if ((typeof a === 'number' && typeof b === 'number') || (typeof a === 'string' && typeof b === 'string')) {
      return [a, b] as [number, number] | [string, string];
    }
    throw new Error(`test evaluator: "${op}" compares ${JSON.stringify(a)} with ${JSON.stringify(b)}`);
  };
  switch (op) {
    case 'zoom':
      if (tileZoom === undefined) throw new Error('test evaluator: "zoom" needs a tile zoom');
      return tileZoom;
    case 'literal':
      return args[0];
    case 'get':
      return props[args[0] as string] ?? null;
    case 'has':
      return (args[0] as string) in props;
    case 'coalesce':
      for (let i = 0; i < args.length; i++) {
        const v = arg(i);
        if (v !== null && v !== undefined) return v;
      }
      return null;
    case 'in': {
      const haystack = arg(1);
      return Array.isArray(haystack) ? haystack.includes(arg(0)) : String(haystack).includes(String(arg(0)));
    }
    case '==':
      return arg(0) === arg(1);
    case '!=':
      return arg(0) !== arg(1);
    case '>=': {
      const [a, b] = ordered();
      return a >= b;
    }
    case '<=': {
      const [a, b] = ordered();
      return a <= b;
    }
    case '!':
      return !arg(0);
    case 'all':
      return args.every((_, i) => arg(i) === true);
    case 'any':
      return args.some((_, i) => arg(i) === true);
    default:
      throw new Error(`test evaluator: no operator "${op}"`);
  }
}

/** A tile of this zoom has loaded: what the map draws once the tiles are in (street level). */
const LOADED_TILE_ZOOM = 14;

/** Whether layer `id` draws a feature with `props` from a tile of zoom `tileZoom`. */
const shows = (style: StyleSpecification, id: string, props: Json, tileZoom = LOADED_TILE_ZOOM) =>
  evaluate(layer(style, id)['filter'], props, tileZoom) === true;

/** Rule 2 on `boundary_2`, in expression syntax: an adm0 side, and not Pakistan-China on both sides. */
const COUNTRY_RULE = [
  'all',
  ['any', ['has', 'adm0_l'], ['has', 'adm0_r']],
  ['!', ['all', ['in', ['get', 'adm0_l'], ['literal', ['PAK', 'CHN']]], ['in', ['get', 'adm0_r'], ['literal', ['PAK', 'CHN']]]]],
];

/**
 * The first deprecated-syntax node found inside an expression filter, or null: a port of `findMixedLegacyFilter` in the
 * style spec 26.4.4 (`src/feature_filter/index.ts`), which MapLibre reports for a filter that mixes the two syntaxes.
 */
function mixedLegacy(filter: unknown): unknown {
  if (!Array.isArray(filter) || filter.length < 1) return null;
  const children =
    filter[0] === 'all' || filter[0] === 'any' || filter[0] === 'none' ? filter.slice(1) : filter[0] === '!' ? [filter[1]] : [];
  for (const child of children) {
    if (!Array.isArray(child)) continue;
    if (isLegacyFilter(child)) return child;
    const inner = mixedLegacy(child);
    if (inner) return inner;
  }
  return null;
}

/** A line layer on source-layer `boundary`, for the guarded-layer cases. */
const boundaryLine = (id: string, extra: Json = {}) =>
  ({ id, type: 'line', source: 'openmaptiles', 'source-layer': 'boundary', ...extra }) as unknown as LayerSpecification;

describe('indiaBoundaryStyle, on the Liberty style as both apps load it', () => {
  const liberty = libertyExcerpt();
  const { style, warnings } = indiaBoundaryStyle(liberty, URL_);

  it('applies every rule without a warning', () => {
    expect(warnings).toEqual([]);
  });

  it('rule 1: hides every disputed line (LoC, LAC, claim lines) and changes nothing else in that layer', () => {
    const before = layer(liberty, 'boundary_disputed');
    const after = layer(style, 'boundary_disputed');
    expect(after['layout']).toEqual({ visibility: 'none' });
    expect({ ...after, layout: undefined }).toEqual({ ...before, layout: undefined });
  });

  it('rule 2: country lines from the tiles start at zoom 5, keep their own filter and lose the Pakistan-China line', () => {
    const before = layer(liberty, 'boundary_2');
    const after = layer(style, 'boundary_2');
    expect(after['minzoom']).toBe(5);
    expect(after['filter']).toEqual(['all', ['all', before['filter'], COUNTRY_RULE], ['>=', ['zoom'], 5]]);
    expect(TILE_ZOOM_GUARD).toEqual(['>=', ['zoom'], 5]);
    expect(after['paint']).toEqual(before['paint']);
    expect(after['layout']).toEqual(before['layout']);

    const line = { admin_level: 2 };
    // Khunjerab, the only Pakistan-China admin-2 line in the tiles (z5), either way round.
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'PAK', adm0_r: 'CHN' })).toBe(false);
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'CHN', adm0_r: 'PAK' })).toBe(false);
    // Every other country line stays, including those where India's side has no code in the tiles.
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'PAK', adm0_r: 'AFG' })).toBe(true);
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'NPL', adm0_r: 'CHN' })).toBe(true);
    expect(shows(style, 'boundary_2', { ...line, adm0_r: 'PAK' })).toBe(true);
    expect(shows(style, 'boundary_2', { ...line, adm0_r: 'BTN' })).toBe(true);
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'MMR' })).toBe(true);
    // Liberty's own conditions still hold.
    expect(shows(style, 'boundary_2', { ...line, adm0_l: 'IND', adm0_r: 'NPL', maritime: 1 })).toBe(false);
    expect(shows(style, 'boundary_2', { ...line, disputed: 1 })).toBe(false);
    expect(shows(style, 'boundary_2', { admin_level: 4 })).toBe(false);
  });

  it('rule 2: a country line with no adm0 side (the Natural Earth lines of the zoom 0-4 tiles) is never drawn', () => {
    // The zoom 4 tiles carry only admin_level, disputed, maritime and disputed_name: their ISO-view line through
    // Jammu and Kashmir, Ladakh, Aksai Chin and Arunachal Pradesh has neither adm0_l nor adm0_r.
    expect(shows(style, 'boundary_2', {})).toBe(false);
    expect(shows(style, 'boundary_2', { admin_level: 2 })).toBe(false);
    expect(shows(style, 'boundary_2', { admin_level: 2, disputed: 0, maritime: 0 })).toBe(false);
    // Without the rules, Liberty drew it (the bug).
    expect(evaluate(layer(liberty, 'boundary_2')['filter'], { admin_level: 2, disputed: 0, maritime: 0 })).toBe(true);
  });

  it('rule 2: a country line from a zoom 0-4 tile is never drawn, even with an adm0 side; from zoom 5 it is', () => {
    // MapLibre shows a zoom 4 tile, overzoomed, while a zoom 5+ tile loads or is missing offline; the filter's zoom is
    // that tile's zoom, so the guard drops its lines at every map zoom.
    const zoomFourLine = { admin_level: 2, disputed: 0, adm0_r: 'BTN' };
    for (const tileZoom of [0, 4]) expect(shows(style, 'boundary_2', zoomFourLine, tileZoom), `z${tileZoom}`).toBe(false);
    for (const tileZoom of [5, 8, 14, 16]) expect(shows(style, 'boundary_2', zoomFourLine, tileZoom), `z${tileZoom}`).toBe(true);
  });

  it('rule 2: state lines (boundary_3) take only the features of a zoom 5+ tile, and keep their minzoom and paint', () => {
    const before = layer(liberty, 'boundary_3');
    const after = layer(style, 'boundary_3');
    expect(after['filter']).toEqual(['all', before['filter'], ['>=', ['zoom'], 5]]);
    expect({ ...after, filter: undefined }).toEqual({ ...before, filter: undefined });
    expect(after['minzoom']).toBe(5);
    // Tile 4/11/6 has an undisputed admin-4 line along the Line of Control north of the Kashmir valley and one across
    // Aksai Chin. Liberty's own filter lets it through from a zoom 4 tile (the bug); the guard drops it.
    const stateLine = { admin_level: 4, disputed: 0, maritime: 0 };
    expect(evaluate(before['filter'], stateLine, 4)).toBe(true);
    for (const tileZoom of [0, 4]) expect(shows(style, 'boundary_3', stateLine, tileZoom), `z${tileZoom}`).toBe(false);
    for (const tileZoom of [5, 8, 14, 16]) expect(shows(style, 'boundary_3', stateLine, tileZoom), `z${tileZoom}`).toBe(true);
    // Liberty's own conditions still hold at zoom 5+.
    expect(shows(style, 'boundary_3', { ...stateLine, admin_level: 2 })).toBe(false);
    expect(shows(style, 'boundary_3', { ...stateLine, disputed: 1 })).toBe(false);
    expect(shows(style, 'boundary_3', { ...stateLine, claimed_by: 'CN' })).toBe(false);
  });

  it('rule 2: every filter it writes is in one syntax (MapLibre warns about a mix and reads it wrongly)', () => {
    for (const id of ['boundary_2', 'boundary_3', 'label_state', 'label_other']) {
      const filter = layer(style, id)['filter'];
      expect(isLegacyFilter(filter), id).toBe(false);
      expect(mixedLegacy(filter), id).toBeNull();
    }
  });

  it('rule 3: adds the bundled Natural Earth source, same-origin, credited in the attribution', () => {
    expect(style.sources['in-boundaries']).toEqual({ type: 'geojson', data: URL_, attribution: 'Natural Earth' });
    expect(style.sources['openmaptiles']).toEqual(liberty.sources['openmaptiles']);
    expect(style.sources['ne2_shaded']).toEqual(liberty.sources['ne2_shaded']);
  });

  it('rule 3: puts the two overlay layers directly above boundary_2, and the state line directly above boundary_3', () => {
    expect(ids(style)).toEqual([
      ...ids(liberty).slice(0, ids(liberty).indexOf('boundary_3') + 1),
      'in-boundary-state',
      ...ids(liberty).slice(ids(liberty).indexOf('boundary_3') + 1, ids(liberty).indexOf('boundary_2') + 1),
      'in-boundary-world',
      'in-boundary-claim',
      ...ids(liberty).slice(ids(liberty).indexOf('boundary_2') + 1),
    ]);
  });

  it('rule 3: India\'s state line (Assam-Arunachal Pradesh) from zoom 5, dashed and drawn like boundary_3', () => {
    const paint = layer(liberty, 'boundary_3')['paint'] as Json;
    expect(layer(style, 'in-boundary-state')).toEqual({
      id: 'in-boundary-state',
      type: 'line',
      source: 'in-boundaries',
      filter: ['==', ['get', 'kind'], 'state'],
      layout: { 'line-join': 'round' },
      paint: { 'line-color': paint['line-color'], 'line-width': paint['line-width'], 'line-dasharray': paint['line-dasharray'] },
      minzoom: 5,
    });
    expect(shows(style, 'in-boundary-state', { kind: 'state' })).toBe(true);
    expect(shows(style, 'in-boundary-state', { kind: 'claim' })).toBe(false);
    expect(shows(style, 'in-boundary-world', { kind: 'state' })).toBe(false);
    expect(shows(style, 'in-boundary-claim', { kind: 'state' })).toBe(false);
    // A copy: changing the overlay's dashes later can never change boundary_3's.
    expect((layer(style, 'in-boundary-state')['paint'] as Json)['line-dasharray']).not.toBe(paint['line-dasharray']);
  });

  it('rule 3: world lines below zoom 5 only, India\'s own outline at every zoom, drawn like boundary_2', () => {
    const paint = layer(liberty, 'boundary_2')['paint'] as Json;
    const common = {
      type: 'line',
      source: 'in-boundaries',
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': paint['line-color'], 'line-width': paint['line-width'], 'line-opacity': paint['line-opacity'] },
    };
    expect(layer(style, 'in-boundary-world')).toEqual({
      id: 'in-boundary-world',
      ...common,
      filter: ['==', ['get', 'kind'], 'world'],
      maxzoom: 5,
    });
    expect(layer(style, 'in-boundary-claim')).toEqual({ id: 'in-boundary-claim', ...common, filter: ['==', ['get', 'kind'], 'claim'] });
    expect(shows(style, 'in-boundary-world', { kind: 'world' })).toBe(true);
    expect(shows(style, 'in-boundary-world', { kind: 'claim' })).toBe(false);
    expect(shows(style, 'in-boundary-claim', { kind: 'claim' })).toBe(true);
    expect(shows(style, 'in-boundary-claim', { kind: 'world' })).toBe(false);
    // A copy: changing the overlay's paint later can never change boundary_2's.
    expect((layer(style, 'in-boundary-claim')['paint'] as Json)['line-width']).not.toBe(paint['line-width']);
  });

  it('rule 4: hides the "Azad Kashmir" and "Gilgit-Baltistan" state labels by English or Urdu name', () => {
    const state = { class: 'state' };
    expect(shows(style, 'label_state', { ...state, name: 'آزاد کشمیر', 'name:en': 'Azad Kashmir' })).toBe(false);
    expect(shows(style, 'label_state', { ...state, name: 'گلگت بلتستان', 'name:en': 'Gilgit-Baltistan' })).toBe(false);
    expect(shows(style, 'label_state', { ...state, name: 'آزاد کشمیر' })).toBe(false);
    expect(shows(style, 'label_state', { ...state, name: 'Azad Jammu and Kashmir' })).toBe(false);
    expect(shows(style, 'label_state', { ...state, name: 'x', 'name:en': 'Gilgit-Baltistan' })).toBe(false);
    for (const name of ['Jammu and Kashmir', 'Ladakh', 'Arunachal Pradesh', 'Punjab', 'Sindh', 'Xinjiang']) {
      expect(shows(style, 'label_state', { ...state, name, 'name:en': name }), name).toBe(true);
      expect(shows(style, 'label_state', { ...state, name }), name).toBe(true);
    }
    expect(shows(style, 'label_state', { class: 'city', name: 'Punjab' })).toBe(false);
    expect(HIDDEN_STATE_NAMES).toEqual(['Azad Kashmir', 'Azad Jammu and Kashmir', 'Gilgit-Baltistan']);
    expect(HIDDEN_STATE_LOCAL_NAMES).toEqual(['آزاد کشمیر', 'گلگت بلتستان']);
  });

  it('rule 4: checks every place label layer, and adds the condition only where a state can show', () => {
    const clauses = [
      ['!', ['in', ['coalesce', ['get', 'name:en'], ['get', 'name']], ['literal', HIDDEN_STATE_NAMES]]],
      ['!', ['in', ['get', 'name'], ['literal', HIDDEN_STATE_LOCAL_NAMES]]],
    ];
    expect(layer(style, 'label_state')['filter']).toEqual(['all', layer(liberty, 'label_state')['filter'], ...clauses]);
    // label_other names "state" only to leave it out; the extra condition changes nothing there.
    expect(layer(style, 'label_other')['filter']).toEqual(['all', layer(liberty, 'label_other')['filter'], ...clauses]);
    for (const id of ['label_village', 'label_town', 'label_city', 'label_city_capital', 'label_country_3', 'label_country_2', 'label_country_1']) {
      expect(layer(style, id), id).toBe(layer(liberty, id));
    }
  });

  it('changes nothing else in the base map', () => {
    const touched = ['boundary_2', 'boundary_3', 'boundary_disputed', 'label_state', 'label_other'];
    for (const l of liberty.layers.filter((x) => !touched.includes(x.id))) {
      expect(layer(style, l.id), l.id).toBe(l as unknown as Json);
    }
    expect({ ...style, sources: undefined, layers: undefined }).toEqual({ ...liberty, sources: undefined, layers: undefined });
  });

  it('is pure: the style it was given is unchanged', () => {
    expect(liberty).toEqual(libertyExcerpt());
  });

  it('changes nothing the second time (the source is already there)', () => {
    const again = indiaBoundaryStyle(style, URL_);
    expect(again.style).toBe(style);
    expect(again.warnings).toEqual([]);
  });
});

describe('indiaBoundaryStyle when the base style has changed (rule 5: skip with a warning, never fail)', () => {
  it('without boundary_disputed: warns, and still applies the other rules and the overlay', () => {
    const { style, warnings } = indiaBoundaryStyle(without(libertyExcerpt(), ['boundary_disputed']), URL_);
    expect(warnings).toEqual(['layer "boundary_disputed" not found; nothing to hide']);
    expect(layer(style, 'boundary_2')['minzoom']).toBe(5);
    expect(ids(style).indexOf('in-boundary-world')).toBe(ids(style).indexOf('boundary_2') + 1);
    expect(style.sources['in-boundaries']).toBeDefined();
  });

  it('without boundary_2: warns, puts the overlay directly above the first boundary layer, with the fallback paint', () => {
    const { style, warnings } = indiaBoundaryStyle(without(libertyExcerpt(), ['boundary_2']), URL_);
    expect(warnings).toEqual(['layer "boundary_2" not found; the overlay is placed by the fallback rule']);
    expect(layer(style, 'boundary_3')['filter']).toEqual(['all', layer(libertyExcerpt(), 'boundary_3')['filter'], TILE_ZOOM_GUARD]);
    const at = ids(style).indexOf('boundary_3');
    expect(ids(style).slice(at, at + 5)).toEqual([
      'boundary_3',
      'in-boundary-state',
      'in-boundary-world',
      'in-boundary-claim',
      'boundary_disputed',
    ]);
    expect(layer(style, 'in-boundary-claim')['paint']).toEqual({ 'line-color': 'hsl(248,1%,41%)', 'line-width': 1.2, 'line-opacity': 1 });
    expect(layer(style, 'in-boundary-world')['paint']).toEqual(FALLBACK_LINE_PAINT);
    expect((layer(style, 'boundary_disputed')['layout'] as Json)['visibility']).toBe('none');
  });

  it('without boundary_3: still guards boundary_2 against zoom 0-4 tiles, without a warning', () => {
    const { style, warnings } = indiaBoundaryStyle(without(libertyExcerpt(), ['boundary_3']), URL_);
    expect(warnings).toEqual([]);
    expect(shows(style, 'boundary_2', { admin_level: 2, adm0_r: 'BTN' }, 4)).toBe(false);
    expect(shows(style, 'boundary_2', { admin_level: 2, adm0_r: 'BTN' }, 5)).toBe(true);
  });

  it('with no boundary layer at all: warns three times, puts the overlay below the first symbol layer', () => {
    const { style, warnings } = indiaBoundaryStyle(
      without(libertyExcerpt(), ['boundary_3', 'boundary_2', 'boundary_disputed']),
      URL_,
    );
    expect(warnings).toHaveLength(3);
    expect(warnings).toContain('no line layer on source-layer "boundary" starts at zoom 5; no tile-zoom guard added');
    const at = ids(style).indexOf('in-boundary-world');
    expect(ids(style).slice(at, at + 3)).toEqual(['in-boundary-world', 'in-boundary-claim', 'road_one_way_arrow']);
  });

  it('with no boundary and no symbol layer: puts the overlay on top', () => {
    const base = libertyExcerpt();
    const bare = { ...base, layers: base.layers.filter((l) => l.type !== 'symbol' && !l.id.startsWith('boundary')) };
    const { style } = indiaBoundaryStyle(bare, URL_);
    expect(ids(style).slice(-2)).toEqual(['in-boundary-world', 'in-boundary-claim']);
  });

  it('without a state label layer: warns, and still adds the overlay', () => {
    const { style, warnings } = indiaBoundaryStyle(without(libertyExcerpt(), ['label_state', 'label_other']), URL_);
    expect(warnings).toEqual(['no symbol layer on source-layer "place" shows states; no label hidden']);
    expect(ids(style)).toContain('in-boundary-claim');
  });

  it('with an overlay id already taken: warns and leaves that layer out, keeps the other', () => {
    const base = libertyExcerpt();
    const taken = { ...base, layers: [...base.layers, { id: 'in-boundary-world', type: 'background' } as LayerSpecification] };
    const { style, warnings } = indiaBoundaryStyle(taken, URL_);
    expect(warnings).toEqual(['layer id "in-boundary-world" is already taken; that overlay layer is not added']);
    expect(ids(style).filter((id) => id === 'in-boundary-world')).toHaveLength(1);
    expect(ids(style)).toContain('in-boundary-claim');
  });

  it('with an empty style: warns for each rule and adds the overlay and its source', () => {
    const { style, warnings } = indiaBoundaryStyle({ version: 8, sources: {}, layers: [] }, URL_);
    expect(warnings).toHaveLength(4);
    expect(ids(style)).toEqual(['in-boundary-state', 'in-boundary-world', 'in-boundary-claim']);
    expect(Object.keys(style.sources)).toEqual(['in-boundaries']);
  });

  it('without boundary_3: puts the state line directly below the other overlay layers, with the fallback paint', () => {
    const { style } = indiaBoundaryStyle(without(libertyExcerpt(), ['boundary_3']), URL_);
    const at = ids(style).indexOf('boundary_2');
    expect(ids(style).slice(at, at + 4)).toEqual(['boundary_2', 'in-boundary-state', 'in-boundary-world', 'in-boundary-claim']);
    expect(layer(style, 'in-boundary-state')['paint']).toEqual(STATE_FALLBACK_LINE_PAINT);
    expect((layer(style, 'in-boundary-state')['paint'] as Json)['line-dasharray']).not.toBe(STATE_FALLBACK_LINE_PAINT['line-dasharray']);
  });

  it('with the state-line id already taken: warns and leaves it out, keeps the other overlay layers', () => {
    const base = libertyExcerpt();
    base.layers.push({ id: 'in-boundary-state', type: 'background' } as LayerSpecification);
    const { style, warnings } = indiaBoundaryStyle(base, URL_);
    expect(warnings).toEqual(['layer id "in-boundary-state" is already taken; that overlay layer is not added']);
    expect(ids(style).filter((id) => id === 'in-boundary-state')).toHaveLength(1);
    expect(ids(style)).toContain('in-boundary-world');
    expect(ids(style)).toContain('in-boundary-claim');
  });

  it('takes each paint property boundary_2 does not set from the fallback', () => {
    const base = libertyExcerpt();
    const partial = {
      ...base,
      layers: base.layers.map((l) =>
        l.id === 'boundary_2' ? ({ ...l, paint: { 'line-color': '#123456' } } as LayerSpecification) : l,
      ),
    };
    const { style } = indiaBoundaryStyle(partial, URL_);
    expect(layer(style, 'in-boundary-claim')['paint']).toEqual({ 'line-color': '#123456', 'line-width': 1.2, 'line-opacity': 1 });
  });

  it('boundary_2 with no filter gets only the India rules and the tile-zoom guard; a state layer with none gets both', () => {
    const base = libertyExcerpt();
    const bare = {
      ...base,
      layers: base.layers.map((l) =>
        l.id === 'boundary_2' || l.id === 'label_state' ? ({ ...l, filter: undefined } as LayerSpecification) : l,
      ),
    };
    const { style } = indiaBoundaryStyle(bare, URL_);
    expect(layer(style, 'boundary_2')['filter']).toEqual(['all', COUNTRY_RULE, TILE_ZOOM_GUARD]);
    expect(shows(style, 'boundary_2', { adm0_l: 'PAK', adm0_r: 'CHN' })).toBe(false);
    expect(shows(style, 'boundary_2', { adm0_r: 'BTN' })).toBe(true);
    expect(shows(style, 'boundary_2', { adm0_r: 'BTN' }, 4)).toBe(false);
    expect(shows(style, 'boundary_2', {})).toBe(false);
    expect(shows(style, 'label_state', { name: 'Gilgit-Baltistan' })).toBe(false);
    expect(shows(style, 'label_state', { name: 'Ladakh' })).toBe(true);
  });

  it('keeps a filter in the deprecated syntax in that syntax (MapLibre refuses a mix of the two)', () => {
    const base = libertyExcerpt();
    const legacy = {
      ...base,
      layers: base.layers.map((l) => {
        if (l.id === 'boundary_2') return { ...l, filter: ['all', ['==', 'admin_level', 2], ['!=', 'maritime', 1]] } as LayerSpecification;
        if (l.id === 'label_state') return { ...l, filter: ['==', 'class', 'state'] } as LayerSpecification;
        return l;
      }),
    };
    const { style, warnings } = indiaBoundaryStyle(legacy, URL_);
    const country = layer(style, 'boundary_2')['filter'] as FilterSpecification;
    const state = layer(style, 'label_state')['filter'] as FilterSpecification;
    expect(isLegacyFilter(country)).toBe(true);
    expect(isLegacyFilter(state)).toBe(true);
    // The adm0 rule in the deprecated syntax, and no tile-zoom guard (that syntax has no zoom): a warning instead.
    expect(country).toEqual([
      'all',
      ['all', ['==', 'admin_level', 2], ['!=', 'maritime', 1]],
      [
        'all',
        ['any', ['has', 'adm0_l'], ['has', 'adm0_r']],
        ['none', ['all', ['in', 'adm0_l', 'PAK', 'CHN'], ['in', 'adm0_r', 'PAK', 'CHN']]],
      ],
    ]);
    expect(warnings).toEqual([
      'layer "boundary_2" has a filter in the deprecated syntax, which has no zoom; not guarded against zoom 0-4 tiles',
    ]);
    expect(layer(style, 'boundary_2')['minzoom']).toBe(5);
    // boundary_3 is still in expression syntax, so it is still guarded.
    expect(layer(style, 'boundary_3')['filter']).toEqual(['all', layer(base, 'boundary_3')['filter'], TILE_ZOOM_GUARD]);
    expect(state).toEqual([
      'all',
      ['==', 'class', 'state'],
      ['!in', 'name:en', ...HIDDEN_STATE_NAMES],
      ['!in', 'name', ...HIDDEN_STATE_NAMES, ...HIDDEN_STATE_LOCAL_NAMES],
    ]);
  });
});

describe('the tile-zoom guard (rule 2): which layers get it', () => {
  it('leaves a state-line layer in the deprecated syntax as it is, with a warning, and still guards boundary_2', () => {
    const base = libertyExcerpt();
    const legacyState = ['all', ['>=', 'admin_level', 3], ['<=', 'admin_level', 6], ['!=', 'maritime', 1]];
    const legacy = {
      ...base,
      layers: base.layers.map((l) => (l.id === 'boundary_3' ? ({ ...l, filter: legacyState } as LayerSpecification) : l)),
    };
    const { style, warnings } = indiaBoundaryStyle(legacy, URL_);
    expect(warnings).toEqual([
      'layer "boundary_3" has a filter in the deprecated syntax, which has no zoom; not guarded against zoom 0-4 tiles',
    ]);
    expect(layer(style, 'boundary_3')).toBe(layer(legacy, 'boundary_3'));
    expect(shows(style, 'boundary_2', { admin_level: 2, adm0_r: 'BTN' }, 4)).toBe(false);
  });

  it('guards boundary_2, boundary_3 and every other boundary line layer from zoom 5; never a zoom 0-4 or a symbol layer', () => {
    const base = libertyExcerpt();
    const own = ['==', ['get', 'admin_level'], 8];
    const extra = [
      boundaryLine('boundary_high', { minzoom: 6, filter: own }),
      boundaryLine('boundary_high_bare', { minzoom: 5 }),
      boundaryLine('boundary_low', { minzoom: 0, filter: own }),
      boundaryLine('boundary_any_zoom', { filter: own }),
      { ...boundaryLine('boundary_text', { minzoom: 5, filter: own }), type: 'symbol' } as unknown as LayerSpecification,
      { ...boundaryLine('road_high', { minzoom: 6, filter: own }), 'source-layer': 'transportation' } as unknown as LayerSpecification,
    ];
    const withExtra = {
      ...base,
      layers: [
        ...base.layers.map((l) => (l.id === 'boundary_disputed' ? ({ ...l, minzoom: 5 } as LayerSpecification) : l)),
        ...extra,
      ],
    };
    const { style, warnings } = indiaBoundaryStyle(withExtra, URL_);
    expect(warnings).toEqual([]);
    const guarded = style.layers
      .filter((l) => JSON.stringify(read(l, 'filter') ?? null).includes('["zoom"]'))
      .map((l) => l.id);
    expect(guarded).toEqual(['boundary_3', 'boundary_2', 'boundary_high', 'boundary_high_bare']);
    expect(layer(style, 'boundary_high')['filter']).toEqual(['all', own, TILE_ZOOM_GUARD]);
    // A layer with no filter gets the guard alone.
    expect(layer(style, 'boundary_high_bare')['filter']).toEqual(TILE_ZOOM_GUARD);
    for (const id of ['boundary_low', 'boundary_any_zoom', 'boundary_text', 'road_high']) {
      expect(layer(style, id), id).toBe(layer(withExtra, id));
    }
    // boundary_disputed is hidden, never filtered.
    expect(layer(style, 'boundary_disputed')['filter']).toEqual(layer(base, 'boundary_disputed')['filter']);
  });
});

describe('isLegacyFilter (port of the style spec 26.4.4 classifier)', () => {
  it('reads every Liberty filter the rules touch as an expression', () => {
    const liberty = libertyExcerpt();
    for (const id of ['boundary_2', 'label_state', 'label_other']) {
      expect(isLegacyFilter(layer(liberty, id)['filter']), id).toBe(false);
    }
  });

  it('tells the two syntaxes apart the way MapLibre does', () => {
    expect(isLegacyFilter(['==', 'class', 'state'])).toBe(true);
    expect(isLegacyFilter(['in', 'class', 'state', 'city'])).toBe(true);
    expect(isLegacyFilter(['!in', 'name', 'x'])).toBe(true);
    expect(isLegacyFilter(['all', ['has', 'x'], ['==', 'a', 1]])).toBe(true);
    expect(isLegacyFilter(['==', ['get', 'class'], 'state'])).toBe(false);
    expect(isLegacyFilter(['in', ['get', 'a'], ['literal', ['x']]])).toBe(false);
    expect(isLegacyFilter(['all', ['has', 'x'], ['==', ['get', 'a'], 1]])).toBe(false);
    expect(isLegacyFilter(['has', 'x'])).toBe(false);
    expect(isLegacyFilter(true)).toBe(false);
    // The adm0 clause is valid in both syntaxes, so it never decides which one a filter is in.
    expect(isLegacyFilter(['any', ['has', 'adm0_l'], ['has', 'adm0_r']])).toBe(false);
    expect(isLegacyFilter(['all', ['any', ['has', 'adm0_l'], ['has', 'adm0_r']], ['none', ['in', 'adm0_l', 'PAK']]])).toBe(true);
    expect(isLegacyFilter(TILE_ZOOM_GUARD)).toBe(false);
  });
});

describe('inBoundariesUrl (same origin, from the base href, from any deep link)', () => {
  it('resolves against the base href, never against the current route', () => {
    // <base href="/"> makes document.baseURI the site root on /houses/42 too.
    expect(inBoundariesUrl('https://doorprints.web.app/')).toBe('https://doorprints.web.app/geo/in-boundaries.geojson');
    expect(inBoundariesUrl('https://owner.github.io/doorprints/')).toBe('https://owner.github.io/doorprints/geo/in-boundaries.geojson');
    expect(inBoundariesUrl('http://localhost:4200/')).toBe('http://localhost:4200/geo/in-boundaries.geojson');
  });
});

/** A map that records what it is asked to do, for applyIndiaBoundaries. */
function fakeMap(style: StyleSpecification | undefined, failOn?: string) {
  const calls: unknown[][] = [];
  const record = (name: string, ...args: unknown[]) => {
    calls.push([name, ...args]);
    if (name === failOn) throw new Error(`${name} refused`);
  };
  const target: BoundaryStyleTarget = {
    getStyle: () => style,
    addSource: (id: string, source: SourceSpecification) => record('addSource', id, source),
    addLayer: (l: LayerSpecification, beforeId?: string) => record('addLayer', l.id, beforeId),
    setVisibility: (id, visibility) => record('setVisibility', id, visibility),
    setFilter: (id, filter) => record('setFilter', id, filter),
    setZoomRange: (id, min, max) => record('setZoomRange', id, min, max),
  };
  return { target, calls };
}

describe('applyIndiaBoundaries (on style.load of a live map)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('makes exactly the changes the rules call for, source first, overlay above boundary_2', () => {
    const liberty = libertyExcerpt();
    const expected = indiaBoundaryStyle(libertyExcerpt(), URL_).style;
    const { target, calls } = fakeMap(liberty);
    const warn = vi.fn();
    applyIndiaBoundaries(target, URL_, warn);
    expect(warn).not.toHaveBeenCalled();
    expect(calls).toEqual([
      ['addSource', 'in-boundaries', { type: 'geojson', data: URL_, attribution: 'Natural Earth' }],
      ['setFilter', 'boundary_3', layer(expected, 'boundary_3')['filter']],
      ['addLayer', 'in-boundary-state', 'boundary_2'],
      ['setFilter', 'boundary_2', layer(expected, 'boundary_2')['filter']],
      ['setZoomRange', 'boundary_2', 5, 24],
      ['addLayer', 'in-boundary-world', 'boundary_disputed'],
      ['addLayer', 'in-boundary-claim', 'boundary_disputed'],
      ['setVisibility', 'boundary_disputed', 'none'],
      ['setFilter', 'label_other', layer(expected, 'label_other')['filter']],
      ['setFilter', 'label_state', layer(expected, 'label_state')['filter']],
    ]);
    expect(liberty).toEqual(libertyExcerpt());
  });

  it('does nothing on a style that already has the overlay', () => {
    const { target, calls } = fakeMap(indiaBoundaryStyle(libertyExcerpt(), URL_).style);
    const warn = vi.fn();
    applyIndiaBoundaries(target, URL_, warn);
    expect(calls).toEqual([]);
    expect(warn).not.toHaveBeenCalled();
  });

  it('warns about a missing layer and still adds the overlay (rule 5)', () => {
    const { target, calls } = fakeMap(without(libertyExcerpt(), ['boundary_disputed']));
    const warn = vi.fn();
    applyIndiaBoundaries(target, URL_, warn);
    expect(warn).toHaveBeenCalledWith('layer "boundary_disputed" not found; nothing to hide');
    expect(calls).toContainEqual(['addLayer', 'in-boundary-claim', 'waterway_line_label']);
    expect(calls).toContainEqual(['addSource', 'in-boundaries', { type: 'geojson', data: URL_, attribution: 'Natural Earth' }]);
  });

  it('never throws: a refused map call is a warning, and the other changes are still made', () => {
    const { target, calls } = fakeMap(libertyExcerpt(), 'setFilter');
    const warn = vi.fn();
    expect(() => applyIndiaBoundaries(target, URL_, warn)).not.toThrow();
    expect(warn).toHaveBeenCalledWith('filtering layer "boundary_2" failed (setFilter refused)');
    expect(calls).toContainEqual(['addLayer', 'in-boundary-claim', 'boundary_disputed']);
    expect(calls).toContainEqual(['setVisibility', 'boundary_disputed', 'none']);
  });

  it('warns and does nothing before a style has loaded, or when the style cannot be read', () => {
    const warn = vi.fn();
    const empty = fakeMap(undefined);
    applyIndiaBoundaries(empty.target, URL_, warn);
    expect(empty.calls).toEqual([]);
    expect(warn).toHaveBeenCalledWith('no style loaded yet');

    const broken: BoundaryStyleTarget = {
      ...fakeMap(undefined).target,
      getStyle: () => {
        throw new Error('gone');
      },
    };
    expect(() => applyIndiaBoundaries(broken, URL_, warn)).not.toThrow();
    expect(warn).toHaveBeenCalledWith('could not read the style (gone)');
  });

  it('warns on the console by default (web counterpart of Android Log.w)', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { target } = fakeMap(without(libertyExcerpt(), ['boundary_disputed']));
    applyIndiaBoundaries(target, URL_);
    expect(spy).toHaveBeenCalledWith('India boundaries: layer "boundary_disputed" not found; nothing to hide');
  });
});
