"""Finds the SHARED stretches of build_in_boundaries.py: the parts of India's claim outline along which the OpenFreeMap
tiles draw a country line of their own from zoom 5, and where our outline should hand over to it. Run it after each
OpenFreeMap planet or style update (docs/10 S4b-BL-9) and paste its output into SHARED.

A tile line counts when OpenFreeMap Liberty's boundary_2 draws it after our rule 2 (admin level 2, not maritime, not
disputed, no claimed_by, at least one adm0 side, not Pakistan-China) and it is India's: one side is India or missing
(the tiles leave India's side empty), or it is the Pakistan-Afghanistan line of the Wakhan, which in India's view is
Gilgit-Baltistan's border; and the sample must lie beside it, not past one of its ends. The tiles often cut such a line into undisputed pieces (drawn) and disputed pieces (hidden),
so the outline is checked every SAMPLE_KM: a sample is shared when, at every zoom in ZOOMS, such a line is within
MAX_KM and runs within MAX_ANGLE degrees of the outline's own direction (a line meeting ours at a tri-junction does
not count). Shared runs shorter than MIN_RUN_KM stay ours. A piece of ours under BRIDGE_RUN_KM between two shared runs
is shared too when, at every zoom, the tile line stays within BRIDGE_KM along all of it (Natural Earth drifts up to
about 9 km from the tile line in Bhutan's south-east corner).

Each stretch is printed as (start on our outline, start on the tile line, end on our outline, end on the tile line):
the build cuts our outline at the two outline points and ends the neighbouring pieces of ours with a short connector
to the tile line points, so from zoom 5 the border is one line with no gap and no second line beside it.

Needs: pip install mapbox-vector-tile shapely. Network: tiles.openfreemap.org.
Usage: build_in_boundaries.py <natural-earth-vector> whole.geojson --no-shared
       find_shared_stretches.py whole.geojson [tile cache dir]"""
import json, math, os, sys, urllib.request
import mapbox_vector_tile
from shapely.geometry import LineString, Point, box
from shapely.strtree import STRtree

PLANET = 'https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf'
ZOOMS = (5, 7, 9)
SAMPLE_KM, MAX_KM, MAX_ANGLE, MIN_RUN_KM = 0.25, 7.0, 60.0, 10.0
BRIDGE_KM, BRIDGE_RUN_KM = 12.0, 30.0
END_DEG = 0.002  # about 200 m
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = sys.argv[1]
CACHE = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, '.tilecache')
KX = lambda lat: 111.32 * math.cos(math.radians(lat)); KY = 110.57
R = lambda v: round(v, 5)

def tile_xy(lon, lat, z):
    n = 2 ** z; r = math.radians(lat)
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)

def to_lonlat(x, y, z, px, py, extent):  # mapbox_vector_tile.decode returns y pointing up by default
    n = 2 ** z; yy = y + 1 - py / extent
    return (x + px / extent) / n * 360 - 180, math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))

def tile(z, x, y):
    os.makedirs(CACHE, exist_ok=True); path = os.path.join(CACHE, f'{z}_{x}_{y}.pbf')
    if not os.path.exists(path):
        req = urllib.request.Request(PLANET.format(z=z, x=x, y=y), headers={'User-Agent': 'doorprints-find-shared'})
        open(path, 'wb').write(urllib.request.urlopen(req, timeout=60).read())
    return mapbox_vector_tile.decode(open(path, 'rb').read())

def drawn_and_indias(p):
    if p.get('admin_level') != 2 or p.get('maritime') == 1 or p.get('disputed') == 1 or 'claimed_by' in p: return False
    sides = {p.get('adm0_l'), p.get('adm0_r')}
    if sides == {None} or sides <= {'PAK', 'CHN'}: return False
    return None in sides or 'IND' in sides or sides == {'PAK', 'AFG'}

data = json.load(open(DATA))
claim = next(f for f in data['features'] if f['properties']['kind'] == 'claim')['geometry']['coordinates']
near_claim = [LineString(l).buffer(0.2) for l in claim]

def lines_at(z):
    tiles = set()
    for l in claim:
        for lon, lat in l: tiles.add(tile_xy(lon, lat, z))
    tiles = {(x + dx, y + dy) for x, y in tiles for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
    out = []
    for x, y in sorted(tiles):
        n = 2 ** z; west, east = x / n * 360 - 180, (x + 1) / n * 360 - 180
        south = to_lonlat(x, y, z, 0, 0, 1)[1]; north = to_lonlat(x, y, z, 0, 1, 1)[1]
        if not any(b.intersects(box(west, south, east, north)) for b in near_claim): continue
        layer = tile(z, x, y).get('boundary')
        if not layer: continue
        for f in layer['features']:
            if not drawn_and_indias(f['properties']): continue
            g = f['geometry']; parts = [g['coordinates']] if g['type'] == 'LineString' else g['coordinates']
            for part in parts:
                if len(part) > 1: out.append(LineString([to_lonlat(x, y, z, px, py, layer['extent']) for px, py in part]))
    return out, STRtree(out)

TILES = {z: lines_at(z) for z in ZOOMS}

def bearing(ax, ay, bx, by): return math.degrees(math.atan2((by - ay) * KY, (bx - ax) * KX(ay)))

def nearest(z, lon, lat, direction, max_angle, buffer_deg=0.12):
    """(km, point on the tile line) of the nearest India's tile line at zoom z that runs within max_angle degrees."""
    lines, tree = TILES[z]; pt = Point(lon, lat); best = (1e9, None)
    for i in tree.query(pt.buffer(buffer_deg)):
        ln = lines[i]; s = ln.project(pt)
        # Beside the line, not past one of its ends: a disputed (hidden) piece of the tile line leaves such an end, and
        # a tile's clip edge does too, but the neighbouring tile's piece (tiles overlap at their edges) runs on there.
        if s <= END_DEG or s >= ln.length - END_DEG: continue
        q = ln.interpolate(s)
        km = math.hypot((q.x - lon) * KX(lat), (q.y - lat) * KY)
        u = ln.interpolate(max(0, s - 0.004)); v = ln.interpolate(min(ln.length, s + 0.004))
        if abs(((bearing(u.x, u.y, v.x, v.y) - direction) + 90) % 180 - 90) <= max_angle and km < best[0]: best = (km, q)
    return best

print(f'# find_shared_stretches.py: planet {PLANET.split("/")[4]}, zooms {"/".join(map(str, ZOOMS))}, samples every '
      f'{SAMPLE_KM:g} km, shared within {MAX_KM:g} km and {MAX_ANGLE:g} degrees, runs of {MIN_RUN_KM:g} km or more.')
for line in claim:
    samples = []  # (lon, lat, direction, km along)
    along = 0.0
    for (x0, y0), (x1, y1) in zip(line, line[1:]):
        d = bearing(x0, y0, x1, y1); km = math.hypot((x1 - x0) * KX(y0), (y1 - y0) * KY); n = max(1, round(km / SAMPLE_KM))
        for k in range(n): samples.append((x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, d, along + km * k / n))
        along += km
    samples.append((line[-1][0], line[-1][1], samples[-1][2], along))
    flag = [all(nearest(z, s[0], s[1], s[2], MAX_ANGLE)[0] <= MAX_KM for z in ZOOMS) for s in samples]
    def runs_of(flag):
        out = []
        for i, f in enumerate(flag):
            if out and out[-1][0] == f: out[-1][2] = i
            else: out.append([f, i, i])
        return out
    runs = runs_of(flag)
    for k in range(1, len(runs) - 1):  # bridge short pieces of ours where the tile line runs on a little further off
        f, a, b = runs[k]
        if f or samples[b][3] - samples[a][3] >= BRIDGE_RUN_KM or not (runs[k - 1][0] and runs[k + 1][0]): continue
        if all(nearest(z, s[0], s[1], 0, 90, 0.15)[0] <= BRIDGE_KM for z in ZOOMS for s in samples[a:b + 1]):
            for i in range(a, b + 1): flag[i] = True
    for f, a, b in runs_of(flag):
        if f and samples[b][3] - samples[a][3] < MIN_RUN_KM:
            for i in range(a, b + 1): flag[i] = False
    for f, a, b in runs_of(flag):
        if not f: continue
        s0, s1 = samples[a], samples[b]
        t0 = nearest(max(ZOOMS), s0[0], s0[1], s0[2], MAX_ANGLE)[1]; t1 = nearest(max(ZOOMS), s1[0], s1[1], s1[2], MAX_ANGLE)[1]
        print(f"    (({R(s0[0])}, {R(s0[1])}), ({R(t0.x)}, {R(t0.y)}), ({R(s1[0])}, {R(s1[1])}), ({R(t1.x)}, {R(t1.y)})),"
              f"  # {s1[3] - s0[3]:.1f} km")
