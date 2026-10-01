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

"""Builds in-boundaries.geojson: the world's land boundaries as the Government of India classifies them, from
Natural Earth (public domain), for the map below zoom 5 only (kind=world), where the base map's tiles carry Natural
Earth's ISO view with no country codes and cannot be filtered (docs/03 ADR-22).

Since 2026-10-01 India's northern and north-eastern boundary (Jammu and Kashmir, Ladakh, Himachal Pradesh,
Uttarakhand, Sikkim and Arunachal Pradesh) and the Assam-Arunachal Pradesh state line come from the Survey of India
(in-boundaries-soi.json, build_in_boundaries_soi.py), at every zoom. This file keeps only what that data does not
cover, so the two never show side by side:
  1. the 1:50m admin-0 land boundary lines whose India point-of-view class is an international boundary (FCLASS_IN
     when set, else FCLASS_ISO), outside the four claim boxes (BOXES: inside them Natural Earth's lines are the ISO
     view's Line of Control, Line of Actual Control and claim lines, and the Survey of India lines cover the rest);
  2. minus every vertex within TRIM_KM of a Survey of India land-boundary line (kind=claim): the stretches that line
     now draws outside the boxes (Uttarakhand-Nepal south of 29.9 N, the Jammu end of the Pakistan line);
  3. each piece that step 1 or 2 cut short ends at the Survey of India vertex nearest its last kept vertex, when that
     is within JOIN_KM, so the line still meets India's boundary below zoom 5 (Uttar Pradesh-Nepal, Nepal-China,
     West Bengal-Bhutan, Bhutan-China, Nagaland-Myanmar, Myanmar-China). The joined vertex is a copy, rounded like the
     rest of this file; the Survey of India file is never changed. Pieces shorter than MIN_KM are left out.
Gone with this change (S4b-BL-114): the claim kind (Natural Earth 1:10m inside the boxes), the state kind (the Natural
Earth Assam-Arunachal line) and the SHARED stretches with their connectors (find_shared_stretches.py, S4b-BL-11/16/17):
the Survey of India lines replace all three, and from zoom 5 the apps hide the tiles' own lines along them instead
(in-soi-corridor.geojson, build_in_soi_corridor.py).

Usage: build_in_boundaries.py <natural-earth-vector checkout, commit ca96624> <output file> [--soi in-boundaries-soi.json]
Python 3 standard library only."""
import json, math, os, sys

BOXES = {  # lon_min, lon_max, lat_min, lat_max
    'west': (72.4, 81.2, 32.35, 37.2),     # Jammu, Kashmir, Ladakh incl. PoK, Gilgit-Baltistan, Shaksgam, Aksai Chin
    'middle': (78.3, 81.2, 29.9, 32.35),   # Himachal Pradesh and Uttarakhand with Tibet, Kalapani
    'sikkim': (88.0, 89.3, 27.0, 28.2),    # Sikkim with Tibet, Doklam tri-junction
    'east': (92.0, 97.5, 26.5, 29.6),      # Arunachal Pradesh (from 92.0 E: the Bhutan-Assam line west of it is not the Survey of India's)
}
TRIM_KM = 10.0  # a Natural Earth vertex this close to a Survey of India land-boundary line is left out
JOIN_KM = 40.0  # a cut end this close to a Survey of India vertex is joined to it
MIN_KM = 3.0    # a piece shorter than this after the cut is left out (a stub beside the Survey of India line)
KM_PER_DEG = 111.195
HERE = os.path.dirname(os.path.abspath(__file__))
SOI_DEFAULT = os.path.normpath(os.path.join(HERE, '..', '..', 'public', 'geo', 'in-boundaries-soi.json'))


def decode_polyline7(s):
    """Google's encoded polyline (lat, lon order) at 1e-7 degree, back to [(lon, lat), ...]."""
    out, i, lat, lon = [], 0, 0, 0
    while i < len(s):
        vals = []
        for _ in range(2):
            result = shift = 0
            while True:
                b = ord(s[i]) - 63; i += 1
                result |= (b & 0x1F) << shift; shift += 5
                if b < 0x20: break
            vals.append(~(result >> 1) if result & 1 else result >> 1)
        lat += vals[0]; lon += vals[1]
        out.append((lon / 1e7, lat / 1e7))
    return out


def soi_lines(path, kind='claim'):
    """The decoded lines of one kind of the Survey of India file, as [(lon, lat), ...] each."""
    with open(path, encoding='utf-8') as f: fc = json.load(f)
    return [decode_polyline7(f['properties']['polyline7']) for f in fc['features'] if f['properties']['kind'] == kind]


def km(a, b):
    """Distance in km between two (lon, lat) points (equirectangular at their mean latitude; exact enough below 50 km)."""
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((a[0] - b[0]) * k, a[1] - b[1]) * KM_PER_DEG


class Near:
    """Lines indexed on a grid of CELL degrees, for the nearest-segment and nearest-vertex tests."""
    CELL = 0.25

    def __init__(self, lines):
        self.lines, self.grid = lines, {}
        for li, line in enumerate(lines):
            for k in range(len(line) - 1):
                (x0, y0), (x1, y1) = line[k], line[k + 1]
                for cx in range(math.floor(min(x0, x1) / self.CELL), math.floor(max(x0, x1) / self.CELL) + 1):
                    for cy in range(math.floor(min(y0, y1) / self.CELL), math.floor(max(y0, y1) / self.CELL) + 1):
                        self.grid.setdefault((cx, cy), []).append((li, k))

    def _segments(self, p, radius_km):
        r = int(math.ceil(radius_km / KM_PER_DEG / math.cos(math.radians(min(abs(p[1]), 80))) / self.CELL))
        cx, cy = math.floor(p[0] / self.CELL), math.floor(p[1] / self.CELL)
        seen = set()
        for gx in range(cx - r, cx + r + 1):
            for gy in range(cy - r, cy + r + 1):
                for e in self.grid.get((gx, gy), ()):
                    if e not in seen:
                        seen.add(e); yield e

    def distance(self, p, radius_km):
        """km from p to the nearest segment found within radius_km (inf when none is)."""
        best, k = math.inf, math.cos(math.radians(p[1]))
        for li, i in self._segments(p, radius_km):
            (x0, y0), (x1, y1) = self.lines[li][i], self.lines[li][i + 1]
            dx, dy, px, py = (x1 - x0) * k, y1 - y0, (p[0] - x0) * k, p[1] - y0
            l2 = dx * dx + dy * dy
            t = 0.0 if l2 == 0 else max(0.0, min(1.0, (px * dx + py * dy) / l2))
            best = min(best, math.hypot(px - t * dx, py - t * dy) * KM_PER_DEG)
        return best

    def vertex(self, p, radius_km):
        """(km, vertex) of the nearest vertex within radius_km, or (inf, None)."""
        best = (math.inf, None)
        for li, i in self._segments(p, radius_km):
            for q in (self.lines[li][i], self.lines[li][i + 1]):
                d = km(p, q)
                if d <= radius_km and d < best[0]: best = (d, q)
        return best


def inbox(pt):
    for n, (a, b, c, d) in BOXES.items():
        if a <= pt[0] <= b and c <= pt[1] <= d: return n
    return None


def world_pieces(ne):
    """Step 1: the India point-of-view international land boundaries of Natural Earth 1:50m, cut at the boxes, as
    (points, cut at the start, cut at the end)."""
    with open(os.path.join(ne, 'geojson', 'ne_50m_admin_0_boundary_lines_land.geojson'), encoding='utf-8') as f:
        w = json.load(f)
    out = []
    for f in w['features']:
        p = f['properties']; cls = p.get('FCLASS_IN') or p.get('FEATURECLA') or ''
        if not cls.startswith('International boundary'): continue
        g = f['geometry']; lines = g['coordinates'] if g['type'] == 'MultiLineString' else [g['coordinates']]
        for line in lines:
            cur, cut = [], False
            for pt in line:
                if inbox(pt):
                    if len(cur) > 1: out.append((cur, cut, True))
                    cur, cut = [], True
                else: cur.append(tuple(pt))
            if len(cur) > 1: out.append((cur, cut, False))
    return out


def trim(pieces, near):
    """Steps 2 and 3: [(points, cut at the start, cut at the end)] -> lines without the vertices within TRIM_KM of the
    Survey of India lines, each cut end joined to the nearest Survey of India vertex within JOIN_KM."""
    out = []
    for points, cut_start, cut_end in pieces:
        keep = [near.distance(p, TRIM_KM) > TRIM_KM for p in points]
        i = 0
        while i < len(points):
            if not keep[i]:
                i += 1; continue
            j = i
            while j + 1 < len(points) and keep[j + 1]: j += 1
            line = list(points[i:j + 1])
            if i > 0 or cut_start:
                q = near.vertex(line[0], JOIN_KM)[1]
                if q is not None: line.insert(0, q)
            if j < len(points) - 1 or cut_end:
                q = near.vertex(line[-1], JOIN_KM)[1]
                if q is not None: line.append(q)
            if len(line) > 1 and sum(km(a, b) for a, b in zip(line, line[1:])) >= MIN_KM: out.append(line)
            i = j + 1
    return out


def rounded(line):
    """Rounded to 5 decimals (about 1 m), without repeated points."""
    out = []
    for x, y in line:
        p = [round(x, 5), round(y, 5)]
        if not out or out[-1] != p: out.append(p)
    return out


def build(ne, soi_path):
    """The FeatureCollection: one Feature of kind world, a MultiLineString."""
    near = Near(soi_lines(soi_path))
    lines = [l for l in map(rounded, trim(world_pieces(ne), near)) if len(l) > 1]
    return {'type': 'FeatureCollection', 'features': [
        {'type': 'Feature', 'properties': {'kind': 'world'}, 'geometry': {'type': 'MultiLineString', 'coordinates': lines}}]}


def main(argv):
    soi = SOI_DEFAULT
    if '--soi' in argv:
        k = argv.index('--soi'); soi = argv[k + 1]; argv = argv[:k] + argv[k + 2:]
    if len(argv) != 2: raise SystemExit('usage: ' + __doc__.split('Usage: ')[1].split('\n')[0])
    fc = build(argv[0], soi)
    s = json.dumps(fc, separators=(',', ':')) + '\n'
    with open(argv[1], 'w') as f: f.write(s)
    lines = fc['features'][0]['geometry']['coordinates']
    print('world lines', len(lines), 'points', sum(len(l) for l in lines), '| bytes', len(s))


if __name__ == '__main__':
    main(sys.argv[1:])
