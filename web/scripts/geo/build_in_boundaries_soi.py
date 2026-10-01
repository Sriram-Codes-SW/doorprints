# Copyright 2026 Sriram (Sriram-Codes-SW)
#
# This file is part of Doorprints.
#
# Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
# Public License as published by the Free Software Foundation, version 3 of the License.
#
# Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
# warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
# the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Builds in-boundaries-soi.json: India's international land boundary and the Assam-Arunachal Pradesh state
line, taken without alteration from the Survey of India's Administrative Boundary Database, product OVSF/1M/7
("Entire country Upto Distt. level with HQ", 1:1M, shapefile), layer STATE_BOUNDARY (docs/03 ADR-22,
docs/ops/soi-review-pack.md).

What the build does to SoI's data, and nothing else (docs/ops/soi-review-pack.md, readings R1-R3):
  1. reads the STATE_BOUNDARY polygons (records, parts, rings) as stored (projected Lambert Conformal Conic on WGS 84,
     metres; the parameters are read from STATE_BOUNDARY.prj);
  2. SELECTS whole runs of consecutive, unchanged vertices of those rings: the edges of India's international land
     boundary (kind=claim) and the edges ARUNACHAL PRADESH shares with ASSAM (kind=state);
  3. inverse-projects each selected vertex exactly (ellipsoidal formulas, double precision) to longitude and latitude
     on WGS 84 and writes them rounded to 7 decimals (about 1 cm).
No vertex is moved, added, removed, simplified, smoothed or snapped; no vertex order changes inside a run; nothing is
clipped by a box and no connector is drawn. tools/soi-verify.py checks this independently.

Selection rule (a selection aid only: no Natural Earth coordinate is ever written):
  * an edge (two consecutive vertices of a ring) is INTERNAL when another ring holds the same two vertices, compared
    exactly as stored, as an edge in the opposite direction (a line two states share); the rest are EXTERNAL;
  * the external edges are linked into India's outer loops (the mainland outline and the islands);
  * a vertex is NEAR LAND when it lies within LAND_KM of a land-boundary line of the repository's Natural Earth outline
    as it was for the 2026-10-01 build (web/public/geo/in-boundaries.geojson of commit 52453f7, kinds world and claim:
    every international land boundary at 1:50m in India's point of view, and India's own outline at 1:10m in the four
    claim areas; `git show 52453f7:web/public/geo/in-boundaries.geojson > ne-reference.geojson`; the file shipped since
    S4b-BL-114 holds only the world lines the Survey of India data does not cover), unless the nearest point of those lines
    is a free end of one of them (where a land boundary meets the sea, as at Sir Creek and the Sundarbans) and the
    vertex is more than END_KM beyond it;
  * an external edge is LAND when both its vertices are near land, else COAST;
  * along each loop, a stretch of coast edges shorter than BRIDGE_KM with land on both sides is land (the Natural Earth
    lines are cut at the claim boxes, which leaves short gaps), and a land stretch shorter than MIN_RUN_KM with coast on
    both sides is coast (reported as ambiguous);
  * the shipped runs are the maximal runs of land edges within one source ring (a run that crosses the ring's first
    vertex is one run, "to" < "from").
Thresholds, as measured on OVSF/1M/7 (2026-10-01): the shipped land vertices lie a median 2.1 km from the Natural
Earth lines (p90 4.5 km, p99 6.9 km); the nearest coast more than 40 km from a sea end (Kori Creek, Kutch) lies
them (12.9 km), hence LAND_KM 12; the few land stretches farther than 12 km (the Karakoram and the Wakhan, about 15 km each) and
the gaps where the Natural Earth lines are cut (at most 15 km) are bridged; no external edge of a state without a
coast came out as coast. Where a land boundary meets the sea the rule stops where Natural Earth's line stops, which is
a question for the Survey of India (docs/ops/soi-review-pack.md).

Usage: build_in_boundaries_soi.py <OVSF/1M/7 ZIP or extracted folder> <out.geojson> --ne ne-reference.geojson
       [--report report.json] [--decimals 7] [--encoding geojson|polyline7]
       [--states "JAMMU AND KASHMIR,LADAKH,..."]
--encoding polyline7 writes each run as an encoded polyline (precision 1e-7 degree, lossless against the 7-decimal
GeoJSON; decoder in tools/soi-verify.py) in properties.polyline7 with a null geometry, the compact option of the size
budget. --decimals 6 is the other option (about 11 cm; fails the 2 cm check of tools/soi-verify.py by design).
--states keeps only the runs of the named states' rings (STATE as spelt in the .dbf), a narrower selection of whole
runs (the size option "claim areas only" of docs/ops/soi-review-pack.md).
Python 3 standard library only."""
import argparse, gzip, hashlib, io, json, math, os, re, struct, sys, zipfile
from collections import defaultdict

PRODUCT = 'OVSF/1M/7'
SOURCE = 'Survey of India, Administrative Boundary Database'
LAYER = 'STATE_BOUNDARY'
LAND_KM = 12.0     # a vertex this close to a Natural Earth land-boundary line is near land
END_KM = 1.0       # ... unless it lies more than this beyond a free end of that line (where the boundary meets the sea)
FREE_END_M = 100.0 # a Natural Earth line end with no other line's vertex within this distance is a free end
BRIDGE_KM = 30.0   # a coast stretch shorter than this between two land stretches of one loop is land
MIN_RUN_KM = 2.0   # a land stretch shorter than this between two coast stretches is coast (reported)
STATE_PAIR = ('ARUNACHAL PRADESH', 'ASSAM')  # kind=state: the edges of the first that the second holds reversed

# ---------------------------------------------------------------------------------------------------------- input


class Source:
    """The OVSF/1M/7 files, from the ZIP as downloaded or from its extracted folder (member names are matched without
    their folder and case, so "91/STATE_BOUNDARY.shp" and "STATE_BOUNDARY.SHP" both work)."""

    def __init__(self, path):
        self.path = path
        self.zip = zipfile.ZipFile(path) if os.path.isfile(path) else None
        names = self.zip.namelist() if self.zip else os.listdir(path)
        self.names = {os.path.basename(n).lower(): n for n in names if not n.endswith('/')}

    def name(self, member):
        n = self.names.get(member.lower())
        if n is None: raise SystemExit(f'{member} not found in {self.path}')
        return n

    def open(self, member):
        n = self.name(member)
        return self.zip.open(n) if self.zip else open(os.path.join(self.path, n), 'rb')

    def read(self, member):
        with self.open(member) as f: return f.read()

    def sha256(self, member=None):
        """sha256 of the ZIP itself (member None, ZIP input only) or of one member."""
        h = hashlib.sha256()
        f = open(self.path, 'rb') if member is None else self.open(member)
        with f:
            for chunk in iter(lambda: f.read(1 << 20), b''): h.update(chunk)
        return h.hexdigest()


def read_dbf(data):
    """dBASE III records as lists of dicts of stripped strings (fields of type C, N, F; deleted records skipped)."""
    n, header_len, rec_len = struct.unpack('<IHH', data[4:12])
    fields, i = [], 32
    while data[i] != 0x0D:
        fields.append((data[i:i + 11].split(b'\0')[0].decode('ascii'), data[i + 16]))
        i += 32
    out = []
    for r in range(n):
        o = header_len + r * rec_len
        if data[o:o + 1] == b'*': continue
        o += 1; row = {}
        for name, length in fields:
            row[name] = data[o:o + length].decode('utf-8', 'replace').strip(); o += length
        out.append(row)
    return out


def read_shp_polygons(f):
    """Streams the polygon records of a .shp file object: yields (record number, [ring, ...]), each ring a list of
    (x, y) floats exactly as stored. Polygon, PolygonZ and PolygonM are read (Z and M are skipped); a null shape
    yields no rings. The file is read one record at a time, so a large layer is never held whole."""
    head = f.read(100)
    if len(head) < 100 or struct.unpack('>i', head[:4])[0] != 9994: raise ValueError('not a shapefile')
    while True:
        rh = f.read(8)
        if len(rh) < 8: return
        number, words = struct.unpack('>2i', rh)
        rec = f.read(words * 2)
        shape_type = struct.unpack('<i', rec[:4])[0]
        if shape_type == 0:
            yield number, []; continue
        if shape_type not in (5, 15, 25): raise ValueError(f'record {number}: shape type {shape_type} is not a polygon')
        num_parts, num_points = struct.unpack('<2i', rec[36:44])
        parts = list(struct.unpack(f'<{num_parts}i', rec[44:44 + 4 * num_parts])) + [num_points]
        o = 44 + 4 * num_parts
        xy = struct.unpack(f'<{2 * num_points}d', rec[o:o + 16 * num_points])
        rings = []
        for a, b in zip(parts, parts[1:]):
            rings.append([(xy[2 * k], xy[2 * k + 1]) for k in range(a, b)])
        yield number, rings


# ----------------------------------------------------------------------------------------------------- projection


def read_prj(text):
    """The Lambert Conformal Conic parameters of an Esri .prj (WKT1): a, 1/f, the two standard parallels, the latitude
    of origin, the central meridian, false easting and northing and the scale factor (1 when absent)."""
    if 'Lambert_Conformal_Conic' not in text: raise SystemExit('the .prj is not Lambert Conformal Conic')
    p = {k.lower(): float(v) for k, v in re.findall(r'PARAMETER\["([^"]+)",\s*([-+0-9.eE]+)\]', text)}
    sph = re.search(r'SPHEROID\["[^"]*",\s*([-+0-9.eE]+),\s*([-+0-9.eE]+)\]', text)
    unit = re.findall(r'UNIT\["([^"]+)",\s*([-+0-9.eE]+)\]', text)
    if not unit or abs(float(unit[-1][1]) - 1.0) > 0 or not sph: raise SystemExit('the .prj has no metre unit or spheroid')
    return {'a': float(sph.group(1)), 'rf': float(sph.group(2)),
            'lat1': p['standard_parallel_1'], 'lat2': p['standard_parallel_2'], 'lat0': p['latitude_of_origin'],
            'lon0': p['central_meridian'], 'fe': p['false_easting'], 'fn': p['false_northing'],
            'k0': p.get('scale_factor', 1.0)}


class LCC:
    """Lambert Conformal Conic with two standard parallels on an ellipsoid (Snyder, Map Projections - A Working
    Manual, USGS PP 1395, 1987, equations 15-1 to 15-11 and 7-9). Degrees in, metres out, and back."""

    def __init__(self, a, rf, lat1, lat2, lat0, lon0, fe, fn, k0=1.0):
        f = 1 / rf
        self.a, self.e = a, math.sqrt(2 * f - f * f)
        self.lon0, self.fe, self.fn = math.radians(lon0), fe, fn
        p1, p2, p0 = map(math.radians, (lat1, lat2, lat0))
        m1, m2 = self._m(p1), self._m(p2)
        t1, t2, t0 = self._t(p1), self._t(p2), self._t(p0)
        self.n = (math.log(m1) - math.log(m2)) / (math.log(t1) - math.log(t2)) if lat1 != lat2 else math.sin(p1)
        self.aF = a * k0 * m1 / (self.n * t1 ** self.n)
        self.rho0 = self.aF * t0 ** self.n

    def _m(self, phi):
        s = math.sin(phi)
        return math.cos(phi) / math.sqrt(1 - self.e * self.e * s * s)

    def _t(self, phi):
        s = math.sin(phi)
        return math.tan(math.pi / 4 - phi / 2) / ((1 - self.e * s) / (1 + self.e * s)) ** (self.e / 2)

    def forward(self, lon, lat):
        rho = self.aF * self._t(math.radians(lat)) ** self.n
        theta = self.n * (math.radians(lon) - self.lon0)
        return self.fe + rho * math.sin(theta), self.fn + self.rho0 - rho * math.cos(theta)

    def inverse(self, x, y):
        dx, dy = x - self.fe, self.rho0 - (y - self.fn)
        rho = math.copysign(math.hypot(dx, dy), self.n)
        theta = math.atan2(dx, dy) if self.n > 0 else math.atan2(-dx, -dy)
        t = (rho / self.aF) ** (1 / self.n)
        phi = math.pi / 2 - 2 * math.atan(t)
        for _ in range(30):
            s = self.e * math.sin(phi)
            nxt = math.pi / 2 - 2 * math.atan(t * ((1 - s) / (1 + s)) ** (self.e / 2))
            if abs(nxt - phi) < 1e-15: phi = nxt; break
            phi = nxt
        return math.degrees(theta / self.n + self.lon0), math.degrees(phi)


# ------------------------------------------------------------------------------------------------------ selection


def load_reference_lines(path, proj):
    """The land-boundary lines (kinds world and claim) of the repository's Natural Earth outline, projected to the
    source's metres. Used only to tell land boundary from coast."""
    lines = []
    with open(path, encoding='utf-8') as fh: ref = json.load(fh)
    for f in ref['features']:
        if f['properties'].get('kind') not in ('world', 'claim'): continue
        g = f['geometry']
        for line in (g['coordinates'] if g['type'] == 'MultiLineString' else [g['coordinates']]):
            lines.append([proj.forward(lon, lat) for lon, lat in line])
    return lines


class Reference:
    """A grid index of reference line segments for the nearest-point test, with their free ends."""
    CELL = 25000.0

    def __init__(self, lines):
        self.segs, self.grid = [], defaultdict(list)
        ends = []
        for li, line in enumerate(lines):
            for k in range(len(line) - 1):
                (x0, y0), (x1, y1) = line[k], line[k + 1]
                si = len(self.segs)
                self.segs.append((x0, y0, x1, y1, k == 0, k == len(line) - 2))
                for cx in range(int(math.floor(min(x0, x1) / self.CELL)), int(math.floor(max(x0, x1) / self.CELL)) + 1):
                    for cy in range(int(math.floor(min(y0, y1) / self.CELL)), int(math.floor(max(y0, y1) / self.CELL)) + 1):
                        self.grid[(cx, cy)].append(si)
            if len(line) > 1: ends += [(li, line[0]), (li, line[-1])]
        vert = defaultdict(list)
        for li, line in enumerate(lines):
            for x, y in line: vert[(int(x // 1000), int(y // 1000))].append((li, x, y))
        self.free = set()
        for li, (x, y) in ends:
            joined = False
            for cx in (int(x // 1000) - 1, int(x // 1000), int(x // 1000) + 1):
                for cy in (int(y // 1000) - 1, int(y // 1000), int(y // 1000) + 1):
                    for lj, u, v in vert.get((cx, cy), ()):
                        if lj != li and math.hypot(u - x, v - y) <= FREE_END_M: joined = True
            if not joined: self.free.add((x, y))

    def nearest(self, x, y, radius):
        """(distance, beyond_free_end) to the nearest segment within radius, or (inf, False)."""
        best, beyond = math.inf, False
        r = int(math.ceil(radius / self.CELL))
        cx, cy = int(math.floor(x / self.CELL)), int(math.floor(y / self.CELL))
        seen = set()
        for gx in range(cx - r, cx + r + 1):
            for gy in range(cy - r, cy + r + 1):
                for si in self.grid.get((gx, gy), ()):
                    if si in seen: continue
                    seen.add(si)
                    x0, y0, x1, y1, first, last = self.segs[si]
                    dx, dy = x1 - x0, y1 - y0; l2 = dx * dx + dy * dy
                    t = 0.0 if l2 == 0 else ((x - x0) * dx + (y - y0) * dy) / l2
                    tc = min(1.0, max(0.0, t))
                    d = math.hypot(x0 + tc * dx - x, y0 + tc * dy - y)
                    if d < best:
                        end = (t < 0 and first and (x0, y0) in self.free) or (t > 1 and last and (x1, y1) in self.free)
                        best, beyond = d, end
        return best, beyond


def edge_classes(rings):
    """For a list of rings (each a list of exact (x, y), closed: last == first), returns for each ring a list of the
    edge owner: for edge k (vertices k, k+1) the index of another ring that holds it reversed, or None (external).
    Also returns anomalies: edges another ring holds in the same direction."""
    where = defaultdict(list)
    for ri, ring in enumerate(rings):
        for k in range(len(ring) - 1): where[(ring[k], ring[k + 1])].append(ri)
    owners, same = [], 0
    for ri, ring in enumerate(rings):
        row = []
        for k in range(len(ring) - 1):
            back = [rj for rj in where.get((ring[k + 1], ring[k]), ()) if rj != ri]
            if any(rj != ri for rj in where[(ring[k], ring[k + 1])] if rj != ri): same += 1
            row.append(back[0] if back else None)
        owners.append(row)
    return owners, same


def loops(rings, owners):
    """Links the external edges into loops: lists of (ring, edge) in order. Returns (loops, branching vertices)."""
    out_edges = defaultdict(list)
    for ri, row in enumerate(owners):
        for k, o in enumerate(row):
            if o is None: out_edges[rings[ri][k]].append((ri, k))
    used, result, branching = set(), [], 0
    for v in out_edges:
        if len(out_edges[v]) > 1: branching += 1
    for ri, row in enumerate(owners):
        for k, o in enumerate(row):
            if o is not None or (ri, k) in used: continue
            loop, cur = [], (ri, k)
            while cur is not None and cur not in used:
                used.add(cur); loop.append(cur)
                a, b = cur
                # next: the edge leaving this edge's end; the same ring's next edge first (keeps runs in one ring)
                nk = (b + 1) % len(owners[a])
                if owners[a][nk] is None and (a, nk) not in used and rings[a][b + 1] == rings[a][nk]:
                    cur = (a, nk); continue
                cand = [e for e in out_edges.get(rings[a][b + 1], ()) if e not in used]
                cur = cand[0] if cand else None
            result.append(loop)
    return result, branching


def seg_len(rings, e):
    ri, k = e; (x0, y0), (x1, y1) = rings[ri][k], rings[ri][k + 1]
    return math.hypot(x1 - x0, y1 - y0)


def cyclic_stretches(lab):
    """The maximal stretches of equal labels of a cyclic list: [(label, [indices])], each starting after a change
    (one stretch when all labels are equal)."""
    n = len(lab)
    if len(set(lab)) < 2: return [(lab[0], list(range(n)))] if n else []
    start = next(i for i in range(n) if lab[i] != lab[i - 1])
    out = []
    for m in range(n):
        i = (start + m) % n
        if m == 0 or lab[i] != out[-1][0]: out.append((lab[i], []))
        out[-1][1].append(i)
    return out


def classify(rings, owners, ref, report, names, proj):
    """The class of every external edge: 'land' or 'coast', by the selection rule of the module docstring."""
    near_cache = {}

    def near(v):
        if v not in near_cache:
            d, beyond = ref.nearest(v[0], v[1], LAND_KM * 1000)
            near_cache[v] = d <= LAND_KM * 1000 and not (beyond and d > END_KM * 1000)
        return near_cache[v]

    cls = {}
    lps, branching = loops(rings, owners)
    report['loops'] = len(lps); report['branching_vertices'] = branching
    report['bridged'] = []; report['ambiguous_dropped'] = []
    for loop in lps:
        lab = ['land' if near(rings[ri][k]) and near(rings[ri][k + 1]) else 'coast' for ri, k in loop]
        for want, other, limit, log in (('coast', 'land', BRIDGE_KM, 'bridged'),
                                        ('land', 'coast', MIN_RUN_KM, 'ambiguous_dropped')):
            stretches = cyclic_stretches(lab)
            if len(stretches) < 2: continue
            for c, members in stretches:
                if c != want: continue
                km = sum(seg_len(rings, loop[m]) for m in members) / 1000
                if km < limit:
                    ri, k = loop[members[0]]
                    report[log].append({'km': round(km, 3), 'edges': len(members), 'state': names[ri],
                                        'lonlat': [round(v, 4) for v in proj.inverse(*rings[ri][k])]})
                    for m in members: lab[m] = other
        for (ri, k), c in zip(loop, lab): cls[(ri, k)] = c
    return cls


def runs_of(flags):
    """Maximal runs of True among the edges of a closed ring: [(first edge, edge count)]; a run may wrap past the end."""
    n = len(flags)
    if n == 0 or not any(flags): return []
    if all(flags): return [(0, n)]
    start = next(i for i in range(n) if flags[i] and not flags[i - 1])
    out, i = [], 0
    while i < n:
        k = (start + i) % n
        if flags[k]:
            j = i
            while j + 1 < n and flags[(start + j + 1) % n]: j += 1
            out.append((k, j - i + 1)); i = j + 1
        else: i += 1
    return sorted(out)


# --------------------------------------------------------------------------------------------------------- output


def encode_polyline(coords, factor=10 ** 7):
    """Google's encoded polyline algorithm (lat, lon order), at the given precision."""
    out, plat, plon = [], 0, 0
    for lon, lat in coords:
        ilat, ilon = int(round(lat * factor)), int(round(lon * factor))
        for d in (ilat - plat, ilon - plon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63)); v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return ''.join(out)


def build(src, ne_path, decimals=7, encoding='geojson', states=None):
    """Returns (FeatureCollection dict, report dict)."""
    proj_params = read_prj(src.read(f'{LAYER}.prj').decode('ascii'))
    proj = LCC(**proj_params)
    attrs = read_dbf(src.read(f'{LAYER}.dbf'))
    with src.open(f'{LAYER}.shp') as f: records = list(read_shp_polygons(io.BufferedReader(f) if src.zip else f))
    if len(records) != len(attrs): raise SystemExit('the .shp and .dbf record counts differ')
    rings, meta = [], []
    for (number, parts), row in zip(records, attrs):
        for pi, ring in enumerate(parts):
            if len(ring) < 4 or ring[0] != ring[-1]: raise SystemExit(f'record {number} part {pi}: not a closed ring')
            rings.append(ring); meta.append((number, row.get('OBJECTID', ''), row.get('STATE', ''), pi))
    owners, same = edge_classes(rings)
    report = {'records': len(records), 'rings': len(rings), 'vertices': sum(len(r) for r in rings),
              'edges': sum(len(r) - 1 for r in rings), 'same_direction_shared_edges': same}
    ref = Reference(load_reference_lines(ne_path, proj))
    cls = classify(rings, owners, ref, report, [m[2] for m in meta], proj)
    sha = {'zip_sha256': src.sha256() if src.zip else None,
           'shp_sha256': src.sha256(f'{LAYER}.shp'), 'dbf_sha256': src.sha256(f'{LAYER}.dbf'),
           'prj_sha256': src.sha256(f'{LAYER}.prj')}
    counts = {'internal': 0, 'land': 0, 'coast': 0, 'state': 0}
    for row in owners:
        counts['internal'] += sum(1 for o in row if o is not None)
    for c in cls.values(): counts[c] += 1
    names = [m[2] for m in meta]
    features = []

    def add(kind, ri, first, count):
        ring = rings[ri]; n = len(ring) - 1
        idx = [(first + m) % n for m in range(count + 1)]
        coords = []
        for k in idx:
            lon, lat = proj.inverse(*ring[k])
            coords.append([round(lon, decimals), round(lat, decimals)])
        number, objectid, state, pi = meta[ri]
        props = {'kind': kind, 'source': SOURCE, 'product': PRODUCT, 'layer': LAYER, 'record': number,
                 'objectid': int(objectid) if objectid.isdigit() else objectid, 'state': state, 'part': pi,
                 'from': idx[0], 'to': idx[-1], 'vertices': len(idx), 'zip_sha256': sha['zip_sha256'] or sha['shp_sha256']}
        if encoding == 'polyline7':
            props['polyline7'] = encode_polyline(coords)
            features.append({'type': 'Feature', 'properties': props, 'geometry': None})
        else:
            features.append({'type': 'Feature', 'properties': props, 'geometry': {'type': 'LineString', 'coordinates': coords}})

    for ri, ring in enumerate(rings):
        land = [cls.get((ri, k)) == 'land' for k in range(len(ring) - 1)]
        for first, count in runs_of(land): add('claim', ri, first, count)
        if names[ri] == STATE_PAIR[0]:
            st = [o is not None and names[o] == STATE_PAIR[1] for o in owners[ri]]
            counts['state'] += sum(st)
            for first, count in runs_of(st): add('state', ri, first, count)
    if states is not None:  # a further selection of whole runs: only these states' rings
        unknown = set(states) - set(names)
        if unknown: raise SystemExit(f'--states: no such STATE in the layer: {sorted(unknown)}')
        features = [f for f in features if f['properties']['state'] in states]
    features.sort(key=lambda f: (f['properties']['kind'], f['properties']['record'], f['properties']['part'],
                                 f['properties']['from']))
    report.update({'edge_classes': counts, 'projection': proj_params, 'inputs': sha,
                   'thresholds': {'LAND_KM': LAND_KM, 'END_KM': END_KM, 'FREE_END_M': FREE_END_M,
                                  'BRIDGE_KM': BRIDGE_KM, 'MIN_RUN_KM': MIN_RUN_KM}})
    per = defaultdict(lambda: [0, 0])
    for f in features:
        per[f['properties']['kind']][0] += 1; per[f['properties']['kind']][1] += f['properties']['vertices']
    report['runs'] = {k: {'runs': v[0], 'vertices': v[1]} for k, v in sorted(per.items())}
    report['runs_by_state'] = sorted({(f['properties']['kind'], f['properties']['state'], f['properties']['vertices'],
                                       f['properties']['from'], f['properties']['to']) for f in features})
    fc = {'type': 'FeatureCollection', 'features': features}
    return fc, report


def dumps(fc):
    return json.dumps(fc, separators=(',', ':'), ensure_ascii=False, sort_keys=False) + '\n'


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('source'); ap.add_argument('out')
    ap.add_argument('--report'); ap.add_argument('--decimals', type=int, default=7)
    ap.add_argument('--encoding', choices=('geojson', 'polyline7'), default='geojson')
    ap.add_argument('--states', help='comma-separated STATE names whose runs are kept (default: all)')
    ap.add_argument('--ne', required=True,
                    help='the selection reference: in-boundaries.geojson of commit 52453f7 (see the selection rule)')
    a = ap.parse_args(argv)
    if a.encoding == 'polyline7' and a.decimals != 7: ap.error('polyline7 is defined at 7 decimals')
    states = [x.strip() for x in a.states.split(',')] if a.states else None
    fc, report = build(Source(a.source), a.ne, a.decimals, a.encoding, states)
    s = dumps(fc).encode('utf-8')
    with open(a.out, 'wb') as f: f.write(s)
    report['output'] = {'bytes': len(s), 'gzip9_bytes': len(gzip.compress(s, 9, mtime=0)),
                        'sha256': hashlib.sha256(s).hexdigest()}
    if a.report:
        with open(a.report, 'w') as f: f.write(json.dumps(report, indent=1, sort_keys=True) + '\n')
    print(json.dumps({k: report[k] for k in ('records', 'rings', 'vertices', 'edges', 'edge_classes', 'runs', 'loops',
                                             'branching_vertices', 'same_direction_shared_edges', 'output')}))
    print('bridged', len(report['bridged']), '| ambiguous dropped', len(report['ambiguous_dropped']))


if __name__ == '__main__':
    main()
