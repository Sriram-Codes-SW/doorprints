"""Builds in-held-areas.geojson (S4b-BL-12): one polygon P around the parts of India's claim outline that Pakistan and
China administer (Gilgit-Baltistan and PoK, Shaksgam, Aksai Chin). Both apps add ["!", ["within", P]] to the filter of
Liberty's `boundary_3` (admin levels 3-6), so the Pakistani district and tehsil lines and the Chinese county lines that
the OpenFreeMap tiles draw there from tile zoom 9, marked undisputed and with no country code, are not drawn
(web: src/app/shared/india-boundaries.ts; Android: IndiaViewRules.kt / IndiaViewOps.kt).

How `within` treats a line (maplibre-gl style spec 26.4.4 within.ts + util/geometry_util.ts; maplibre-native
android-v13.6.1 src/mln/style/expression/within.cpp + src/mln/util/geometry_util.cpp, the same algorithm): per TILE
feature, in the tile's own coordinates (Mercator; the feature as clipped to its tile plus the tile buffer), true only
when the feature's bbox is strictly inside P's bbox and every part of it has all its vertices strictly inside P (a
vertex on an edge is outside) and no segment crossing an edge of P. A feature that crosses P's edge is drawn whole. The
tiles merge the lines of one admin level into one multi-line feature per tile, so a feature can carry a Pakistani line
and an Indian one at once: such a feature must stay drawn.

P, step by step:
 1. The held areas by the tiles' own de facto lines: India's outline (Natural Earth IND view, as build_in_boundaries.py)
    cut by the disputed admin-2 lines (LoC, LAC, AGPL) of the zoom 13 tiles, taken along the zoom 10 ones; each face
    is held when Natural Earth's default view puts most of it in Pakistan or China; every other face is
    Indian-administered.
 2. Grown GROW_DEG outward (so a tile piece that runs a little past the held area is still inside), plus, for each
    boundary_3 feature of the zoom 9-11 tiles that reaches more than DEEP_DEG into a held area and nowhere as deep
    into the Indian-administered part, its convex hull grown HULL_DEG (at those zooms a tile piece runs up to a tile
    width past the held area); simplified by OUTER_TOL_DEG.
 3. Minus the Indian-administered part shrunk by INDIAN_REACH_DEG (and simplified by INNER_TOL_DEG): P reaches only
    that far across the tiles' LoC/LAC, so an Indian line is never inside P, and a Pakistani or Chinese line that ends
    on the LoC/LAC is.
Side effect: Pakistani, Chinese and Afghan admin 3-6 lines within about GROW_DEG of the held areas (more near the
hulls) are not drawn either; nothing of India's own lines is affected but the last few hundred metres of a line that
ends at the LoC/LAC, at tile zoom 10 and above (docs/10 S4b-BL-12).

Output: a FeatureCollection with one Feature (properties {"kind": "held"}) whose geometry is one Polygon, coordinates
rounded to 5 decimals, compact JSON and a newline, like in-boundaries.geojson. The app copy
(android/app/src/main/assets/geo/in-held-areas.geojson) must be the same bytes (IndiaBoundaryDataTest pins the sha256;
the web spec pins it too). Rebuild it after each OpenFreeMap planet update (S4b-BL-9), with the new planet in PLANET.

Needs: pip install mapbox-vector-tile shapely. Network: tiles.openfreemap.org (about 1 400 tiles; cached).
Usage: build_in_held_areas.py <natural-earth-vector checkout, commit ca96624> <output file> [tile cache dir] [--check]
(--check also prints, per tile zoom 9-11, how many boundary_3 features P hides and keeps, by the renderers' rule)"""
import json, math, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
import mapbox_vector_tile
from shapely.geometry import LineString, MultiLineString, Polygon, box, shape
from shapely.ops import unary_union
from shapely.prepared import prep

PLANET = 'https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf'
BBOX = (72.3, 31.8, 80.6, 37.2)  # Jammu and Kashmir and Ladakh with the held areas, and a margin
GROW_DEG = 0.2           # about 20 km
DEEP_DEG = 0.01          # about 1 km
HULL_DEG = 0.03
OUTER_TOL_DEG = 0.03
INDIAN_REACH_DEG = 0.007  # about 700 m
INNER_TOL_DEG = 0.0035
SPLIT_GAP_DEG = 0.001    # the LoC/LAC lines are widened this much to cut the outline into faces
EXTENT = 8192            # the renderers' tile extent (EXTENT in both), for --check

NE = sys.argv[1]; OUT = sys.argv[2]
args = [a for a in sys.argv[3:] if not a.startswith('--')]
CACHE = args[0] if args else os.path.join(os.path.dirname(os.path.abspath(__file__)), '.tilecache')


def tile_xy(lon, lat, z):
    n = 2 ** z; r = math.radians(lat)
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)


def to_lonlat(x, y, z, px, py, extent):  # mapbox_vector_tile.decode returns y pointing up by default
    n = 2 ** z; yy = y + 1 - py / extent
    return (x + px / extent) / n * 360 - 180, math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))


def tile_box(z, x, y):
    return box(*to_lonlat(x, y, z, 0, 0, 1), *to_lonlat(x, y, z, 1, 1, 1))


def tiles_over(geom, z):
    w, s, e, n = geom.bounds
    (x0, y0), (x1, y1) = tile_xy(w, n, z), tile_xy(e, s, z)
    g = prep(geom)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) if g.intersects(tile_box(z, x, y))]


def raw(z, x, y):
    os.makedirs(CACHE, exist_ok=True); path = os.path.join(CACHE, f'{z}_{x}_{y}.pbf')
    if not os.path.exists(path):
        req = urllib.request.Request(PLANET.format(z=z, x=x, y=y), headers={'User-Agent': 'doorprints-geo-build'})
        for attempt in range(4):
            try:
                body = urllib.request.urlopen(req, timeout=60).read(); break
            except OSError:
                if attempt == 3: raise
                time.sleep(2 ** attempt)
        open(path + '.part', 'wb').write(body); os.replace(path + '.part', path)
    return open(path, 'rb').read()


def boundary_features(z, xys):
    """(tile, properties, [parts as lists of (lon, lat)], [parts in world-pixel coordinates at zoom z]) per feature."""
    with ThreadPoolExecutor(12) as ex: list(ex.map(lambda t: raw(z, *t), xys))
    out = []
    for x, y in xys:
        lay = mapbox_vector_tile.decode(raw(z, x, y)).get('boundary')
        if not lay: continue
        ext = lay['extent']
        for f in lay['features']:
            g = f['geometry']
            parts = [g['coordinates']] if g['type'] == 'LineString' else g['coordinates'] if g['type'] == 'MultiLineString' else []
            parts = [p for p in parts if len(p) > 1]
            if not parts: continue
            ll = [[to_lonlat(x, y, z, px, py, ext) for px, py in p] for p in parts]
            world = [[(x * EXTENT + px * EXTENT / ext, y * EXTENT + (ext - py) * EXTENT / ext) for px, py in p] for p in parts]
            out.append(((z, x, y), f['properties'], ll, world))
    return out


def liberty_b3(p):  # Liberty's boundary_3 filter: admin 3-6, not maritime, not disputed, no claim
    a = p.get('admin_level')
    return a is not None and 3 <= a <= 6 and p.get('maritime') != 1 and p.get('disputed') != 1 and 'claimed_by' not in p


# 1. The held areas, cut out of India's outline by the tiles' own LoC/LAC.
india = next(shape(f['geometry']) for f in json.load(open(f'{NE}/geojson/ne_10m_admin_0_countries_ind.geojson'))['features']
             if f['properties']['ADM0_A3'] == 'IND').buffer(0)
de_facto = unary_union([shape(f['geometry']).buffer(0) for f in json.load(open(f'{NE}/geojson/ne_10m_admin_0_countries.geojson'))['features']
                        if f['properties']['ADM0_A3'] in ('PAK', 'CHN')])
area = box(*BBOX)
disputed = lambda z, xys: [LineString(p) for t, pr, ll, w in boundary_features(z, xys)
                           if pr.get('admin_level') == 2 and pr.get('disputed') == 1 for p in ll]
loc10 = unary_union(disputed(10, tiles_over(area, 10)))
loc = unary_union(disputed(13, tiles_over(loc10.buffer(0.02).intersection(area), 13)))
faces = india.intersection(area).difference(loc.buffer(SPLIT_GAP_DEG))
faces = list(getattr(faces, 'geoms', [faces]))
is_held = lambda f: f.intersection(de_facto).area > 0.5 * f.area
held = unary_union([f for f in faces if is_held(f)]).buffer(SPLIT_GAP_DEG)
indian = india.difference(held)
print('held areas', [round(f.area, 3) for f in faces if is_held(f)], 'deg2; faces', len(faces), file=sys.stderr)

# 2. Grown outward, with the hulls of the zoom 9-11 tile features that lie in a held area and not in the Indian part.
held_deep, indian_deep = prep(held.buffer(-DEEP_DEG)), prep(indian.buffer(-DEEP_DEG))
near = held.buffer(0.35)
low = {z: [f for f in boundary_features(z, tiles_over(near, z)) if liberty_b3(f[1])] for z in (9, 10, 11)}
hulls = []
for z, feats in low.items():
    for t, p, ll, w in feats:
        ml = MultiLineString(ll)
        if held_deep.intersects(ml) and not indian_deep.intersects(ml):
            hulls.append(ml.convex_hull.buffer(HULL_DEG))
outer = unary_union([held.buffer(GROW_DEG), *hulls]).simplify(OUTER_TOL_DEG)

# 3. Minus the Indian-administered part, shrunk: P reaches INDIAN_REACH_DEG across the LoC/LAC.
P = outer.difference(indian.buffer(-INDIAN_REACH_DEG).simplify(INNER_TOL_DEG))
if P.geom_type == 'MultiPolygon':
    P = max(P.geoms, key=lambda g: g.area)
r5 = lambda ring: [[round(x, 5), round(y, 5)] for x, y in ring.coords]
P = Polygon(r5(P.exterior), [r5(r) for r in P.interiors])
assert P.is_valid, 'P is not a valid polygon'
vertices = len(P.exterior.coords) + sum(len(r.coords) for r in P.interiors)
print('P: 1 polygon,', len(P.interiors), 'holes,', vertices, 'vertices, bounds', [round(v, 3) for v in P.bounds], file=sys.stderr)
geometry = {'type': 'Polygon', 'coordinates': [r5(P.exterior)] + [r5(r) for r in P.interiors]}
out = {'type': 'FeatureCollection', 'features': [{'type': 'Feature', 'properties': {'kind': 'held'}, 'geometry': geometry}]}
open(OUT, 'w').write(json.dumps(out, separators=(',', ':')) + '\n')

if '--check' in sys.argv:
    # The renderers' `within` for a line feature (see the docstring), on the zoom 9-11 features.
    def world(lon, lat, z):
        size = EXTENT * 2 ** z
        return ((lon + 180) * size / 360, (180 - math.degrees(math.log(math.tan(lat * math.pi / 360 + math.pi / 4)))) * size / 360)

    def inside(p, rings):
        on, odd = False, False
        for r in rings:
            for (x1, y1), (x2, y2) in zip(r, r[1:]):
                a1, b1, a2, b2 = p[0] - x1, p[1] - y1, p[0] - x2, p[1] - y2
                if a1 * b2 - a2 * b1 == 0 and a1 * a2 <= 0 and b1 * b2 <= 0: on = True
                if (y1 > p[1]) != (y2 > p[1]) and p[0] < (x2 - x1) * (p[1] - y1) / (y2 - y1) + x1: odd = not odd
        return odd and not on

    def crosses(a, b, rings):
        side = lambda p, q, r: (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
        for r in rings:
            for c, d in zip(r, r[1:]):
                if (d[0] - c[0]) * (b[1] - a[1]) - (d[1] - c[1]) * (b[0] - a[0]) == 0: continue
                s1, s2 = side(a, c, d), side(b, c, d); s3, s4 = side(c, a, b), side(d, a, b)
                if ((s1 > 0 > s2) or (s1 < 0 < s2)) and ((s3 > 0 > s4) or (s3 < 0 < s4)): return True
        return False

    for z, feats in low.items():
        rings = [[world(x, y, z) for x, y in r.coords] for r in [P.exterior, *P.interiors]]
        xs = [p[0] for p in rings[0]]; ys = [p[1] for p in rings[0]]
        counts = {}
        for t, p, ll, w in feats:
            pts = [q for part in w for q in part]
            ok = (min(q[0] for q in pts) > min(xs) and max(q[0] for q in pts) < max(xs) and
                  min(q[1] for q in pts) > min(ys) and max(q[1] for q in pts) < max(ys))
            ok = ok and all(all(inside(q, rings) for q in part) and not any(crosses(a, b, rings) for a, b in zip(part, part[1:])) for part in w)
            ml = MultiLineString(ll)
            where = ('held+Indian' if indian_deep.intersects(ml) else 'held') if held_deep.intersects(ml) else 'Indian' if indian_deep.intersects(ml) else 'other'
            k = (where, 'hidden' if ok else 'drawn'); counts[k] = counts.get(k, 0) + 1
        print(f'z{z}:', dict(sorted(counts.items())), file=sys.stderr)
