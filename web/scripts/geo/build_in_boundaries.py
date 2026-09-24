"""Builds in-boundaries.geojson: land boundaries as the Government of India depicts them, from Natural Earth
(public domain). kind=world: lines used below zoom 5 only, in place of the tiles' country lines: the 1:50m admin-0
land boundary lines whose India point-of-view class is an international boundary (FCLASS_IN when set, else
FCLASS_ISO), outside the claim boxes, and the SHARED stretches of India's own outline (below). kind=claim: the rest of
the land-boundary parts of India's own polygon from the 1:10m India point-of-view countries file, inside the claim
boxes; used at every zoom. kind=state: the Assam-Arunachal Pradesh state line from the 1:10m admin-1 lines; used from
zoom 5, where the tiles mark it disputed and no layer draws it (S4b-BL-15).

SHARED: the stretches of India's outline along which the base map's tiles draw a country line of their own from zoom
5 (an undisputed line with India's side, or in the Wakhan the Gilgit-Baltistan-Afghanistan line). From zoom 5 that
tile line is the more precise one, and drawing ours as well showed two close lines (S4b-BL-11, S4b-BL-16). Each
stretch is cut out of kind=claim at two points on our outline and moved to kind=world; the claim pieces on either side
end with a short connector (at most MAX_KM of find_shared_stretches.py) to the matching point on the tile line, so from
zoom 5 the border has no gap. Where the finder moved a hand-over (S4b-BL-17), the stretch ends where our outline
crosses a neighbour's tile line (Sikkim's north-west tri-junction: both points are that crossing, no connector) or the
connector runs to where India's tile line stops (Jomotsangkha, Longwa). The list is the output of find_shared_stretches.py for the OpenFreeMap planet named
there, run on a build with SHARED = []; re-run it after each planet or style update (S4b-BL-9) and paste its output.

Usage: build_in_boundaries.py <natural-earth-vector checkout, commit ca96624> <output file> [--no-shared]
(--no-shared: the outline whole, as find_shared_stretches.py needs it)"""
import json, math, sys
NE = sys.argv[1]; OUT = sys.argv[2]
BOXES = {  # lon_min, lon_max, lat_min, lat_max
  'west': (72.4, 81.2, 32.35, 37.2),     # Jammu, Kashmir, Ladakh incl. PoK, Gilgit-Baltistan, Shaksgam, Aksai Chin
  'middle': (78.3, 81.2, 29.9, 32.35),   # Himachal Pradesh and Uttarakhand with Tibet, Kalapani
  'sikkim': (88.0, 89.3, 27.0, 28.2),    # Sikkim with Tibet, Doklam tri-junction
  'east': (91.5, 97.5, 26.5, 29.6),      # Arunachal Pradesh
}
# find_shared_stretches.py: planet 20260913_164504_pt, zooms 7/9/11, samples every 0.25 km, shared within 7 km and 60 degrees, runs of 2 km or more.
# Each: (start on our outline, start on the tile line, end on our outline, end on the tile line).
SHARED = [
    ((80.92395, 30.27488), (80.90718, 30.21954), (80.54953, 29.89368), (80.56858, 29.88757)),  # 77.5 km
    ((88.07688, 26.99179), (88.0916, 27.00515), (87.9913, 27.0815), (87.99512, 27.10364)),  # 13.5 km
    ((87.98934, 27.21839), (88.01203, 27.2162), (88.11262, 27.8688), (88.11262, 27.8688)),  # 75.2 km before moving the hand-overs (end: crossing)
    ((88.87192, 27.2776), (88.86834, 27.26367), (88.84561, 26.99494), (88.87021, 26.99536)),  # 44.6 km
    ((91.48447, 26.85273), (91.45899, 26.80662), (92.0758, 26.90129), (92.11504, 26.89452)),  # 79.3 km before moving the hand-overs (end: run-on 6.0 km)
    ((95.24693, 26.6489), (95.23396, 26.68247), (95.05438, 26.49492), (95.0768, 26.47664)),  # 31.3 km before moving the hand-overs (start: run-on 1.7 km)
    ((73.77475, 36.83811), (73.79993, 36.8906), (74.56178, 37.02968), (74.56167, 37.02996)),  # 105.9 km
]
if '--no-shared' in sys.argv: SHARED = []
def inbox(pt):
    for n,(a,b,c,d) in BOXES.items():
        if a <= pt[0] <= b and c <= pt[1] <= d: return n
    return None
R = lambda v: round(v, 5)
def key(p): return (round(p[0], 6), round(p[1], 6))
# claim segments from India's polygon
pov = json.load(open(f'{NE}/geojson/ne_10m_admin_0_countries_ind.geojson'))
ind = None; neigh = set()
NEIGH = {'PAK','CHN','NPL','BTN','BGD','MMR','AFG'}
for f in pov['features']:
    a3 = f['properties']['ADM0_A3']; g = f['geometry']
    polys = g['coordinates'] if g['type'] == 'MultiPolygon' else [g['coordinates']]
    if a3 == 'IND': ind = polys
    elif a3 in NEIGH:
        for poly in polys:
            for ring in poly:
                for p in ring: neigh.add(key(p))
claims = []
for poly in ind:
    ring = poly[0]; cur = []
    for i in range(len(ring) - 1):
        p, q = ring[i], ring[i + 1]
        ok = key(p) in neigh and key(q) in neigh and (inbox(p) or inbox(q))
        if ok:
            if not cur: cur = [p]
            cur.append(q)
        elif cur:
            claims.append(cur); cur = []
    if cur: claims.append(cur)
# Split the claim at the SHARED stretches: those go to world (below zoom 5 only), the rest stays claim, and each piece of
# claim next to a shared stretch ends with a short connector to the tile line, so from zoom 5 the border is one line.
def locate(line, pt):
    """(segment index + fraction along it) of the point of `line` nearest `pt`, which must lie on the line."""
    best = (1e9, None)
    for i, ((x0, y0), (x1, y1)) in enumerate(zip(line, line[1:])):
        dx, dy = x1 - x0, y1 - y0; L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((pt[0] - x0) * dx + (pt[1] - y0) * dy) / L2))
        d = (x0 + t * dx - pt[0]) ** 2 + (y0 + t * dy - pt[1]) ** 2
        if d < best[0]: best = (d, i + t)
    return best
def point_at(line, pos):
    i = min(int(pos), len(line) - 2); t = pos - i; (x0, y0), (x1, y1) = line[i], line[i + 1]
    return [x0 + t * (x1 - x0), y0 + t * (y1 - y0)]
def between(line, a, b):  # the part of `line` from position a to position b (a < b)
    return [point_at(line, a)] + [list(p) for p in line[int(a) + 1:int(math.ceil(b))] ] + [point_at(line, b)]
shared_lines, found = [], set()
pieces = []
for line in claims:
    cuts = []
    for k, (s0, t0, s1, t1) in enumerate(SHARED):
        d0, a = locate(line, s0); d1, b = locate(line, s1)
        if d0 < 1e-8 and d1 < 1e-8:
            if not a < b: raise SystemExit(f'SHARED stretch {k} runs against its claim line')
            # SHARED is rounded to 5 decimals: a cut within that of a line end is at the end (else a piece of ours
            # would be a connector alone, a spur).
            near = lambda pos, i: max(abs(u - v) for u, v in zip(point_at(line, pos), line[i])) < 1e-4
            if near(a, 0): a = 0.0
            if near(b, len(line) - 1): b = float(len(line) - 1)
            cuts.append((a, b, t0, t1)); found.add(k)
    cuts.sort()
    pos, lead = 0.0, None  # lead: the connector point the next piece starts from
    end = len(line) - 1
    for a, b, t0, t1 in cuts:
        if a < pos: raise SystemExit('SHARED stretches overlap')
        # A piece of ours before the stretch, ending with its connector. None when the stretch starts where this claim
        # line starts (a box edge): there is nothing of ours to join, and a connector alone would be a spur.
        if a > pos + 1e-9: pieces.append(([list(lead)] if lead else []) + between(line, pos, a) + [list(t0)])
        shared_lines.append(between(line, a, b)); pos, lead = b, t1
    # The piece after the last stretch, likewise none when the stretch runs to where this claim line ends.
    if pos < end - 1e-9: pieces.append(([list(lead)] if lead else []) + between(line, pos, end))
claims = [p for p in pieces if len(p) > 1]
missing = set(range(len(SHARED))) - found
if missing: raise SystemExit(f'SHARED stretches not found on the claim outline: {sorted(missing)}')
# world lines, 1:50m, India point of view
w = json.load(open(f'{NE}/geojson/ne_50m_admin_0_boundary_lines_land.geojson'))
world = []
for f in w['features']:
    p = f['properties']; cls = p.get('FCLASS_IN') or p.get('FEATURECLA') or ''
    if not cls.startswith('International boundary'): continue
    g = f['geometry']; lines = g['coordinates'] if g['type'] == 'MultiLineString' else [g['coordinates']]
    for line in lines:
        cur = []
        for pt in line:
            if inbox(pt):
                if len(cur) > 1: world.append(cur)
                cur = []
            else: cur.append(pt)
        if len(cur) > 1: world.append(cur)
world += shared_lines
# the Assam-Arunachal Pradesh state line, 1:10m admin-1 lines (two features, notes India_20 and India_200)
s1 = json.load(open(f'{NE}/geojson/ne_10m_admin_1_states_provinces_lines.geojson'))
states = []
for f in sorted(s1['features'], key=lambda f: f['properties']['NOTE'] or ''):
    if f['properties']['ADM0_A3'] != 'IND' or f['properties']['NAME'] != 'Assam - Arunachal Pradesh': continue
    g = f['geometry']; states += g['coordinates'] if g['type'] == 'MultiLineString' else [g['coordinates']]
if not states: raise SystemExit('Assam - Arunachal Pradesh line not found')
def rounded(line):  # rounded to 5 decimals, without repeated points (a cut that falls on a vertex)
    out = []
    for x, y in line:
        p = [R(x), R(y)]
        if not out or out[-1] != p: out.append(p)
    return out
feat = lambda kind, lines: {'type': 'Feature', 'properties': {'kind': kind},
    'geometry': {'type': 'MultiLineString', 'coordinates': [l for l in map(rounded, lines) if len(l) > 1]}}
out = {'type': 'FeatureCollection', 'features': [feat('world', world), feat('claim', claims), feat('state', states)]}
s = json.dumps(out, separators=(',', ':')) + '\n'
open(OUT, 'w').write(s)
print('claim segments', len(claims), 'points', sum(len(l) for l in claims), '| shared stretches', len(shared_lines),
      'points', sum(len(l) for l in shared_lines), '| world lines', len(world), '| state lines', len(states),
      'points', sum(len(l) for l in states), '| bytes', len(s))
