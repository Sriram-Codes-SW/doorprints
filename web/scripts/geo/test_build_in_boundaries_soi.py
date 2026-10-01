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

"""Tests of build_in_boundaries_soi.py and tools/soi-verify.py (Python 3 standard library only).

Run: python3 -m unittest discover web/scripts/geo
The real-data fixture testdata/soi-fixture.json is an excerpt of the Survey of India's OVSF/1M/7 (see its "note")."""
import importlib.util, io, json, math, os, struct, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_in_boundaries_soi as B  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, '..', '..', '..'))
NE = os.path.join(ROOT, 'web', 'public', 'geo', 'in-boundaries.geojson')


def put(path, data):
    with open(path, 'wb' if isinstance(data, bytes) else 'w') as f: f.write(data)


def get_json(path):
    with open(path, encoding='utf-8') as f: return json.load(f)


FIXTURE = get_json(os.path.join(HERE, 'testdata', 'soi-fixture.json'))
sys.dont_write_bytecode = True  # no tools/__pycache__ from loading the verifier
_spec = importlib.util.spec_from_file_location('soi_verify', os.path.join(ROOT, 'tools', 'soi-verify.py'))
V = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(V)
PROJ = B.LCC(**B.read_prj(FIXTURE['prj']))

# District headquarters of OVSF/1M/7 (DISTRICT_HQ: the stored point in metres and the LONGITUDE and LATITUDE
# columns of its .dbf, which carry 11 significant digits).
KNOWN = [
    ('ALIPUR (Delhi)', 3724816.068542543, 4524993.095647499, 77.1352360483, 28.8010851642),
    ('THIRUVANANTHAPURAM', 3658675.834192872, 2300548.086855972, 76.9483866385, 8.49055485089),
    ('BHUJ', 2964226.72090638, 3956617.4738925993, 69.6689937165, 23.2507064865),
    ('PORT BLAIR', 5390576.858920895, 2714168.038552936, 92.734831559, 11.6659880118),
    ('GUWAHATI', 5149537.514680807, 4283614.700549949, 91.7318117114, 26.1697290898),
]


def write_shapefile(folder, records, prj=FIXTURE['prj']):
    """A minimal STATE_BOUNDARY shapefile: records = [(objectid, state, [ring, ...])], rings of (x, y)."""
    body = b''
    for number, (_, _, rings) in enumerate(records, 1):
        pts = [p for r in rings for p in r]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        starts, acc = [], 0
        for r in rings: starts.append(acc); acc += len(r)
        content = struct.pack('<i4d2i', 5, min(xs), min(ys), max(xs), max(ys), len(rings), len(pts))
        content += struct.pack(f'<{len(rings)}i', *starts) + b''.join(struct.pack('<2d', *p) for p in pts)
        body += struct.pack('>2i', number, len(content) // 2) + content
    head = struct.pack('>7i', 9994, 0, 0, 0, 0, 0, (100 + len(body)) // 2) + struct.pack('<2i', 1000, 5) + bytes(64)
    put(os.path.join(folder, 'STATE_BOUNDARY.shp'), head + body)
    fields = [('OBJECTID', b'N', 10), ('STATE', b'C', 50)]
    rec_len = 1 + sum(f[2] for f in fields)
    dbf = struct.pack('<B3BIHH20x', 3, 126, 10, 1, len(records), 32 + 32 * len(fields) + 1, rec_len)
    for name, t, length in fields:
        dbf += name.encode().ljust(11, b'\0') + t + bytes(4) + bytes([length, 0]) + bytes(14)
    dbf += b'\r'
    for oid, state, _ in records:
        dbf += b' ' + str(oid).rjust(10).encode() + state.encode().ljust(50)
    put(os.path.join(folder, 'STATE_BOUNDARY.dbf'), dbf + b'\x1a')
    put(os.path.join(folder, 'STATE_BOUNDARY.prj'), prj)


def write_reference(path, lines_xy):
    """A Natural Earth-like reference file (kind=world) from lines in the projection's metres."""
    lines = [[list(PROJ.inverse(x, y)) for x, y in line] for line in lines_xy]
    put(path, json.dumps({'type': 'FeatureCollection', 'features': [
        {'type': 'Feature', 'properties': {'kind': 'world'}, 'geometry': {'type': 'MultiLineString', 'coordinates': lines}}]}))


# Two states 50 km square side by side, sharing the edge x = 50 km (both rings clockwise, as shapefile outer rings
# are); a reference land-boundary line runs along their south side, y = 0.
X0, Y0, K = 4.0e6, 4.0e6, 1000.0
SQ_A = [(0, 0), (0, 30), (0, 50), (25, 50), (50, 50), (50, 25), (50, 0), (25, 0), (0, 0)]
SQ_B = [(50, 0), (50, 25), (50, 50), (75, 50), (100, 50), (100, 30), (100, 0), (75, 0), (50, 0)]
M = lambda ring: [(X0 + x * K, Y0 + y * K) for x, y in ring]


def synthetic(folder):
    write_shapefile(folder, [(1, 'ARUNACHAL PRADESH', [M(SQ_A)]), (2, 'ASSAM', [M(SQ_B)])])
    ref = os.path.join(folder, 'ne.geojson')
    write_reference(ref, [[(X0 - 5 * K, Y0), (X0 + 105 * K, Y0)]])
    return ref


class Projection(unittest.TestCase):
    def test_known_points(self):
        for name, x, y, lon, lat in KNOWN:
            got = PROJ.inverse(x, y)
            self.assertAlmostEqual(got[0], lon, delta=1e-9, msg=name)
            self.assertAlmostEqual(got[1], lat, delta=1e-9, msg=name)

    def test_round_trip_on_the_fixture(self):
        for piece in FIXTURE['pieces']:
            for x, y in piece['xy']:
                u, v = PROJ.forward(*PROJ.inverse(x, y))
                self.assertLess(math.hypot(u - x, v - y), 1e-6)

    def test_verifier_projection_agrees(self):
        fwd = V.Forward(FIXTURE['prj'])
        for piece in FIXTURE['pieces']:
            for x, y in piece['xy'][::10]:
                lon, lat = PROJ.inverse(x, y)
                u, v = fwd(lon, lat)
                self.assertLess(math.hypot(u - x, v - y), 1e-6)

    def test_prj_parameters(self):
        p = B.read_prj(FIXTURE['prj'])
        self.assertEqual((p['lat1'], p['lat2'], p['lat0'], p['lon0'], p['fe'], p['fn'], p['k0']),
                         (12.472944, 35.172806, 24.0, 80.0, 4000000.0, 4000000.0, 1.0))


class Reader(unittest.TestCase):
    def test_records_parts_rings_of_the_fixture(self):
        pieces = FIXTURE['pieces']
        records = [(i, p['state'], [[tuple(v) for v in p['xy']] + [tuple(p['xy'][0])]]) for i, p in enumerate(pieces, 1)]
        records.append((99, 'TWO PARTS', [r[2][0] for r in records[:2]]))
        with tempfile.TemporaryDirectory() as d:
            write_shapefile(d, records)
            src = B.Source(d)
            with src.open('STATE_BOUNDARY.shp') as f: got = list(B.read_shp_polygons(f))
            rows = B.read_dbf(src.read('STATE_BOUNDARY.dbf'))
        self.assertEqual(len(got), len(pieces) + 1)
        self.assertEqual([len(rings) for _, rings in got], [1] * len(pieces) + [2])
        for (number, rings), p in zip(got, pieces):
            self.assertEqual(len(rings[0]), len(p['xy']) + 1)
            self.assertEqual(rings[0][:len(p['xy'])], [tuple(v) for v in p['xy']])  # exact floats
        self.assertEqual(got[-1][1], records[-1][2])
        self.assertEqual([r['STATE'] for r in rows][-1], 'TWO PARTS')
        self.assertEqual(rows[0]['STATE'], pieces[0]['state'])


class Selection(unittest.TestCase):
    def test_shared_edges_are_internal(self):
        owners, same = B.edge_classes([M(SQ_A), M(SQ_B)])
        self.assertEqual(same, 0)
        self.assertEqual([o for o in owners[0]], [None, None, None, None, 1, 1, None, None])
        self.assertEqual([o for o in owners[1]], [0, 0, None, None, None, None, None, None])

    def test_loops_link_the_external_edges(self):
        rings = [M(SQ_A), M(SQ_B)]
        owners, _ = B.edge_classes(rings)
        loops, branching = B.loops(rings, owners)
        self.assertEqual((len(loops), len(loops[0]), branching), (1, 12, 0))

    def test_runs_wrap_past_the_ring_start(self):
        self.assertEqual(B.runs_of([True, False, False, True, True]), [(3, 3)])
        self.assertEqual(B.runs_of([False, True, True, False, True]), [(1, 2), (4, 1)])
        self.assertEqual(B.runs_of([True] * 3), [(0, 3)])
        self.assertEqual(B.runs_of([False] * 3), [])

    def test_build_selects_whole_runs(self):
        with tempfile.TemporaryDirectory() as d:
            ref = synthetic(d)
            fc, report = B.build(B.Source(d), ref)
        got = [(f['properties']['kind'], f['properties']['state'], f['properties']['from'], f['properties']['to'],
                f['properties']['vertices']) for f in fc['features']]
        self.assertEqual(got, [('claim', 'ARUNACHAL PRADESH', 6, 0, 3), ('claim', 'ASSAM', 6, 0, 3),
                               ('state', 'ARUNACHAL PRADESH', 4, 6, 3)])
        # the shipped vertices are the source's, inverse-projected, rounded to 7 decimals, in order
        a = fc['features'][0]['geometry']['coordinates']
        want = [[round(c, 7) for c in PROJ.inverse(*M(SQ_A)[k])] for k in (6, 7, 8)]
        self.assertEqual(a, want)

    def test_classifier_on_real_coast_and_land(self):
        ref = B.Reference(B.load_reference_lines(NE, PROJ))
        for piece in FIXTURE['pieces']:
            if piece['expect'] == 'mixed': continue
            near = [ref.nearest(x, y, B.LAND_KM * 1000) for x, y in piece['xy']]
            ok = [d <= B.LAND_KM * 1000 and not (beyond and d > B.END_KM * 1000) for d, beyond in near]
            self.assertEqual(set(ok), {piece['expect'] == 'land'}, piece['what'])

    def test_cyclic_stretches(self):
        lab = ['land'] * 5 + ['coast'] * 2 + ['land'] * 5 + ['coast'] * 20
        st = B.cyclic_stretches(lab)
        self.assertEqual([(c, len(m)) for c, m in st], [('land', 5), ('coast', 2), ('land', 5), ('coast', 20)])
        lab = ['coast'] * 3 + ['land'] * 4 + ['coast'] * 2  # the stretch over the list's end is one stretch
        self.assertEqual([(c, len(m)) for c, m in B.cyclic_stretches(lab)], [('land', 4), ('coast', 5)])


class Output(unittest.TestCase):
    def build_bytes(self, d, **kw):
        fc, _ = B.build(B.Source(d), os.path.join(d, 'ne.geojson'), **kw)
        return B.dumps(fc).encode()

    def test_deterministic_with_a_trailing_newline(self):
        with tempfile.TemporaryDirectory() as d:
            synthetic(d)
            one, two = self.build_bytes(d), self.build_bytes(d)
        self.assertEqual(one, two)
        self.assertTrue(one.endswith(b'}\n'))

    def test_polyline7_decodes_to_the_same_coordinates(self):
        with tempfile.TemporaryDirectory() as d:
            synthetic(d)
            plain = json.loads(self.build_bytes(d))
            enc = json.loads(self.build_bytes(d, encoding='polyline7'))
        for f, g in zip(plain['features'], enc['features']):
            self.assertEqual(V.decode_polyline7(g['properties']['polyline7']), f['geometry']['coordinates'])

    def test_verifier_passes_and_fails_on_a_vertex_moved_by_one_metre(self):
        with tempfile.TemporaryDirectory() as d:
            synthetic(d)
            out = os.path.join(d, 'out.geojson')
            put(out, self.build_bytes(d))
            quiet = io.StringIO()
            sys.stdout, saved = quiet, sys.stdout
            try:
                self.assertEqual(V.main(['soi-verify', d, out]), 0)
                fc = get_json(out)
                lon, lat = fc['features'][0]['geometry']['coordinates'][1]
                fc['features'][0]['geometry']['coordinates'][1] = [lon, round(lat + 1 / 110574.0, 7)]  # 1 m north
                put(out, json.dumps(fc))
                self.assertEqual(V.main(['soi-verify', d, out]), 1)
                del fc['features'][0]['geometry']['coordinates'][1]  # a vertex removed
                fc['features'][0]['properties']['vertices'] -= 1
                put(out, json.dumps(fc))
                self.assertEqual(V.main(['soi-verify', d, out]), 1)
            finally:
                sys.stdout = saved
            self.assertIn('VIOLATION', quiet.getvalue())


if __name__ == '__main__':
    unittest.main()
