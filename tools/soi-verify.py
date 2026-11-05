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

"""Independent check that in-boundaries-soi.json holds the Survey of India's vertices unaltered.

For a reviewer of the Survey of India (docs/ops/soi-review-pack.md) or anyone else: it needs only Python 3 and the
OVSF/1M/7 download (the ZIP as downloaded, or its extracted folder). It shares no code with the build
(web/scripts/geo/build_in_boundaries_soi.py): the shapefile reader and the projection are written again here, and
the projection goes the other way (forward: from the shipped longitude and latitude back to the database's metres).

For every shipped line (a GeoJSON feature) it:
  1. finds the source ring named by its properties (layer STATE_BOUNDARY, shapefile record, part) and the vertex range
     "from".."to" (a range with to < from runs past the ring's first vertex; vertices = the number of vertices);
  2. checks the line has exactly that many vertices, in the source's order (none added, removed or reordered);
  3. projects each shipped (longitude, latitude) forward to Lambert Conformal Conic metres with the parameters of
     STATE_BOUNDARY.prj and checks it lies within 2 cm of the source vertex (the 7-decimal rounding moves a vertex by
     at most about 0.75 cm);
  4. checks the selection: a kind=claim edge is held by no other ring in the opposite direction (it is not a line two
     states share), and a kind=state edge is held reversed by the ASSAM polygon.
It prints the sha256 of the inputs and of the shipped file, the counts and the largest difference, and exits 1 on any
violation.

Usage: python3 tools/soi-verify.py <OVSF/1M/7 ZIP or folder> <in-boundaries-soi.json>"""
import hashlib, json, math, os, re, struct, sys, zipfile

TOLERANCE_M = 0.02


def members(path):
    """Opens the source files by base name, case-insensitive, from the ZIP or the folder."""
    if os.path.isfile(path):
        z = zipfile.ZipFile(path)
        names = {os.path.basename(n).lower(): n for n in z.namelist() if not n.endswith('/')}
        return lambda m: z.read(names[m.lower()])
    names = {n.lower(): n for n in os.listdir(path)}

    def read(m):
        with open(os.path.join(path, names[m.lower()]), 'rb') as f: return f.read()
    return read


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            b = f.read(1 << 20)
            if not b: return h.hexdigest()
            h.update(b)


def polygon_rings(shp):
    """{record number: [ring, ...]} with each ring a list of (x, y) exactly as stored (Polygon, PolygonZ, PolygonM)."""
    out, pos = {}, 100
    while pos + 8 <= len(shp):
        number, words = struct.unpack_from('>ii', shp, pos)
        body = pos + 8
        kind = struct.unpack_from('<i', shp, body)[0]
        rings = []
        if kind in (5, 15, 25):
            nparts, npoints = struct.unpack_from('<ii', shp, body + 36)
            starts = struct.unpack_from(f'<{nparts}i', shp, body + 44)
            pts = body + 44 + 4 * nparts
            bounds = list(starts) + [npoints]
            for p in range(nparts):
                rings.append([struct.unpack_from('<dd', shp, pts + 16 * i) for i in range(bounds[p], bounds[p + 1])])
        elif kind != 0:
            raise SystemExit(f'record {number}: shape type {kind} is not a polygon')
        out[number] = rings
        pos = body + 2 * words
    return out


def dbf_column(dbf, column):
    """One text column of a dBASE file, by record order."""
    count, head, size = struct.unpack_from('<IHH', dbf, 4)
    offset, pos, width = 1, 32, None
    while dbf[pos] != 0x0D:
        name = dbf[pos:pos + 11].rstrip(b'\0').decode('ascii'); length = dbf[pos + 16]
        if name == column: width = (offset, length)
        offset += length; pos += 32
    if width is None: raise SystemExit(f'no column {column} in the .dbf')
    return [dbf[head + r * size + width[0]: head + r * size + width[0] + width[1]].decode('utf-8', 'replace').strip()
            for r in range(count)]


class Forward:
    """Lambert Conformal Conic (2SP) on an ellipsoid, forward only, written with the isometric latitude
    psi = asinh(tan phi) - e * atanh(e sin phi), so that t = exp(-psi) (equivalent to Snyder's t, a different route)."""

    def __init__(self, prj):
        num = r'([-+0-9.eE]+)'
        par = {k.lower(): float(v) for k, v in re.findall(r'PARAMETER\["([^"]+)",\s*' + num + r'\]', prj)}
        a, inv_f = map(float, re.search(r'SPHEROID\["[^"]*",\s*' + num + r',\s*' + num + r'\]', prj).groups())
        e2 = (2 - 1 / inv_f) / inv_f
        self.e = math.sqrt(e2)
        lat1, lat2, lat0 = (math.radians(par[k]) for k in ('standard_parallel_1', 'standard_parallel_2', 'latitude_of_origin'))
        self.lon0 = math.radians(par['central_meridian'])
        self.x0, self.y0 = par['false_easting'], par['false_northing']
        k0 = par.get('scale_factor', 1.0)
        m = lambda p: math.cos(p) / math.sqrt(1 - e2 * math.sin(p) ** 2)
        self.n = (math.log(m(lat1) / m(lat2))) / (self.psi(lat2) - self.psi(lat1))
        self.c = a * k0 * m(lat1) / self.n * math.exp(self.n * self.psi(lat1))
        self.r0 = self.c * math.exp(-self.n * self.psi(lat0))

    def psi(self, phi):
        return math.asinh(math.tan(phi)) - self.e * math.atanh(self.e * math.sin(phi))

    def __call__(self, lon, lat):
        r = self.c * math.exp(-self.n * self.psi(math.radians(lat)))
        g = self.n * (math.radians(lon) - self.lon0)
        return self.x0 + r * math.sin(g), self.y0 + self.r0 - r * math.cos(g)


def decode_polyline7(s):
    """Google's encoded polyline (lat, lon order) at 1e-7 degree, back to [[lon, lat], ...]."""
    out, i, lat, lon = [], 0, 0, 0
    while i < len(s):
        vals = []
        for _ in range(2):
            shift = result = 0
            while True:
                b = ord(s[i]) - 63; i += 1
                result |= (b & 0x1F) << shift; shift += 5
                if b < 0x20: break
            vals.append(~(result >> 1) if result & 1 else result >> 1)
        lat += vals[0]; lon += vals[1]
        out.append([lon / 1e7, lat / 1e7])
    return out


def main(argv):
    if len(argv) != 3: raise SystemExit(__doc__.split('Usage: ')[1])
    src, shipped = argv[1], argv[2]
    read = members(src)
    shp, dbf, prj = read('STATE_BOUNDARY.shp'), read('STATE_BOUNDARY.dbf'), read('STATE_BOUNDARY.prj').decode('ascii')
    zip_sha = sha256_file(src) if os.path.isfile(src) else None
    print('source:', src)
    if zip_sha: print('  zip sha256               ', zip_sha)
    for name, data in (('STATE_BOUNDARY.shp', shp), ('STATE_BOUNDARY.dbf', dbf), ('STATE_BOUNDARY.prj', prj.encode())):
        print(f'  {name:<25}', hashlib.sha256(data).hexdigest())
    print('shipped:', shipped, '\n  sha256                   ', sha256_file(shipped), f'({os.path.getsize(shipped)} bytes)')
    rings_by_record = polygon_rings(shp)
    states = dbf_column(dbf, 'STATE')
    fwd = Forward(prj)
    # every directed edge of every ring -> the rings that hold it, for the selection check
    edges = {}
    for rec, rings in rings_by_record.items():
        for part, ring in enumerate(rings):
            for a, b in zip(ring, ring[1:]): edges.setdefault((a, b), []).append((rec, part))
    problems, counts, worst = [], {}, 0.0
    with open(shipped, encoding='utf-8') as fh: fc = json.load(fh)
    if fc.get('type') != 'FeatureCollection': problems.append('not a FeatureCollection')
    for fi, f in enumerate(fc.get('features', [])):
        p = f.get('properties') or {}
        label = f"feature {fi} ({p.get('kind')}, {p.get('state')}, record {p.get('record')} part {p.get('part')} {p.get('from')}..{p.get('to')})"
        if p.get('product') != 'OVSF/1M/7' or p.get('layer') != 'STATE_BOUNDARY':
            problems.append(f'{label}: product/layer is not OVSF/1M/7 STATE_BOUNDARY'); continue
        if zip_sha and p.get('zip_sha256') != zip_sha: problems.append(f'{label}: zip_sha256 differs from the source ZIP')
        try:
            ring = rings_by_record[p['record']][p['part']]
        except (KeyError, IndexError, TypeError):
            problems.append(f'{label}: no such record/part in STATE_BOUNDARY'); continue
        if states[p['record'] - 1] != p.get('state'): problems.append(f'{label}: STATE attribute differs')
        coords = decode_polyline7(p['polyline7']) if 'polyline7' in p else (f.get('geometry') or {}).get('coordinates')
        n = len(ring) - 1  # the last vertex repeats the first
        start, end, count = p['from'], p['to'], p['vertices']
        span = (end - start) % n + 1
        if span == 1 and count == n + 1: span = n + 1  # the whole ring, closed
        if count != span or not coords or len(coords) != count:
            problems.append(f'{label}: {len(coords or [])} vertices shipped, {count} declared, the range holds {span}')
            continue
        src_pts = [ring[(start + i) % n] for i in range(count)]
        for i, ((lon, lat), (x, y)) in enumerate(zip(coords, src_pts)):
            for v in (lon, lat):
                if round(v, 7) != v: problems.append(f'{label}: vertex {i} has more than 7 decimals')
            u, w = fwd(lon, lat)
            d = math.hypot(u - x, w - y)
            worst = max(worst, d)
            if d > TOLERANCE_M: problems.append(f'{label}: vertex {(start + i) % n} is {d:.3f} m from the source')
        for a, b in zip(src_pts, src_pts[1:]):
            back = [h for h in edges.get((b, a), ()) if h != (p['record'], p['part'])]
            if p['kind'] == 'claim' and back:
                problems.append(f'{label}: edge at vertex {ring.index(a)} is shared with record {back[0][0]} (a state line)')
            if p['kind'] == 'state' and not any(states[r - 1] == 'ASSAM' for r, _ in back):
                problems.append(f'{label}: an edge is not shared with ASSAM')
        c = counts.setdefault(p['kind'], [0, 0]); c[0] += 1; c[1] += count
    for kind, (lines, verts) in sorted(counts.items()): print(f'kind={kind}: {lines} lines, {verts} vertices')
    print(f'largest difference from the source: {worst * 100:.3f} cm (limit {TOLERANCE_M * 100:.0f} cm)')
    if problems:
        for msg in problems[:50]: print('VIOLATION', msg)
        print(f'{len(problems)} violation(s)'); return 1
    print('OK: every shipped vertex is a source vertex, in order, none added or removed, within the limit')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
