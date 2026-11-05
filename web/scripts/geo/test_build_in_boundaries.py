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

"""Tests of build_in_boundaries.py and of the three map files the apps ship (S4b-BL-114; Python 3 standard library
only): the Survey of India file decodes to the vertex counts and checksums that the web and Android tests pin too
(india-boundaries.spec.ts, SoiPolylineTest.kt), the Natural Earth file holds only world lines and none along the Survey
of India lines, and the corridor's polygons hold every Survey of India land-boundary vertex.

Run: python3 -m unittest discover web/scripts/geo"""
import json, math, os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_in_boundaries as N  # noqa: E402
import build_in_boundaries_soi as B  # noqa: E402

GEO = os.path.normpath(os.path.join(HERE, '..', '..', 'public', 'geo'))
ANDROID_GEO = os.path.normpath(os.path.join(HERE, '..', '..', '..', 'android', 'app', 'src', 'main', 'assets', 'geo'))
M31 = 2147483647

# Pinned on all three platforms: kind -> (lines, vertices, s1, s2) of checksum().
SOI_EXPECTED = {'claim': (6, 28751, 176943357, 571665185), 'state': (1, 8252, 1805079313, 1863970484)}


def checksum(lines):
    """Adler-style sums (mod 2^31 - 1) over the vertices as 1e-7 degree integers, longitude then latitude, in order;
    the same function as soiChecksum (web) and SoiPolyline.checksum (Android)."""
    s1 = s2 = 0
    for line in lines:
        for lon, lat in line:
            for v in (round(lon * 1e7), round(lat * 1e7)):
                s1 = (s1 + v % M31) % M31
                s2 = (s2 + s1) % M31
    return s1, s2


def read(name):
    with open(os.path.join(GEO, name), encoding='utf-8') as f: return json.load(f)


def inside(p, ring):
    """Even-odd point in polygon."""
    x, y, c = p[0], p[1], False
    for (x0, y0), (x1, y1) in zip(ring, ring[1:]):
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0): c = not c
    return c


class Polyline(unittest.TestCase):
    def test_known_vector(self):
        # Google's documented example, at 1e-5: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
        pts = [(-120.2, 38.5), (-120.95, 40.7), (-126.453, 43.252)]
        self.assertEqual(B.encode_polyline(pts, 10 ** 5), '_p~iF~ps|U_ulLnnqC_mqNvxq`@')

    def test_round_trip_at_1e7(self):
        pts = [(68.1985123, 23.8132456), (97.4, 28.2), (-0.0000001, -89.9999999), (179.9999999, 0.0)]
        self.assertEqual(N.decode_polyline7(B.encode_polyline(pts)), pts)


class SurveyOfIndiaFile(unittest.TestCase):
    def test_counts_and_checksums(self):
        for kind, (lines, vertices, s1, s2) in SOI_EXPECTED.items():
            got = N.soi_lines(os.path.join(GEO, 'in-boundaries-soi.json'), kind)
            self.assertEqual((len(got), sum(len(l) for l in got)), (lines, vertices), kind)
            self.assertEqual(checksum(got), (s1, s2), kind)

    def test_each_run_says_how_many_vertices_it_has(self):
        for f in read('in-boundaries-soi.json')['features']:
            p = f['properties']
            self.assertEqual(len(N.decode_polyline7(p['polyline7'])), p['vertices'], (p['state'], p['from']))
            self.assertEqual(p['source'], 'Survey of India, Administrative Boundary Database')

    def test_android_copies_are_the_same_bytes(self):
        for name in ('in-boundaries-soi.json', 'in-boundaries.geojson', 'in-soi-corridor.geojson', 'in-held-areas.geojson'):
            with open(os.path.join(GEO, name), 'rb') as a, open(os.path.join(ANDROID_GEO, name), 'rb') as b:
                self.assertEqual(a.read(), b.read(), name)


class NaturalEarthFile(unittest.TestCase):
    def test_only_world_lines_none_along_the_survey_of_india_lines(self):
        fc = read('in-boundaries.geojson')
        self.assertEqual([f['properties']['kind'] for f in fc['features']], ['world'])
        near = N.Near(N.soi_lines(os.path.join(GEO, 'in-boundaries-soi.json')))
        for line in fc['features'][0]['geometry']['coordinates']:
            # only a joined end vertex may lie on (or near) a Survey of India line
            for p in line[1:-1]:
                self.assertGreater(near.distance(p, N.TRIM_KM), N.TRIM_KM - 0.05, p)

    def test_trim_cuts_and_joins(self):
        near = N.Near([[(80.0, 30.0), (80.0, 30.5), (80.0, 31.0)]])  # a line north-south along 80 E
        # a world line from the west that runs up to the line and then along it: cut where it comes within TRIM_KM,
        # and joined to the Survey of India vertex nearest its last kept vertex (29 km away)
        world = [(78.0, 30.5), (79.0, 30.5), (79.7, 30.5), (79.99, 30.6), (80.01, 30.9)]
        self.assertEqual(N.trim([(world, False, False)], near), [[(78.0, 30.5), (79.0, 30.5), (79.7, 30.5), (80.0, 30.5)]])
        # no Survey of India vertex within JOIN_KM: cut, not joined
        far = [(78.0, 30.5), (79.5, 30.5), (79.99, 30.6)]
        self.assertEqual(N.trim([(far, False, False)], N.Near([[(80.0, 29.0), (80.0, 31.0)]])), [[(78.0, 30.5), (79.5, 30.5)]])
        # a piece shorter than MIN_KM is left out
        self.assertEqual(N.trim([([(80.2, 30.5), (80.21, 30.5)], True, True)], N.Near([[(90.0, 0.0), (90.0, 1.0)]])), [])


class Corridor(unittest.TestCase):
    def test_every_survey_of_india_land_vertex_is_inside_a_polygon(self):
        rings = [f['geometry']['coordinates'][0] for f in read('in-soi-corridor.geojson')['features']]
        self.assertEqual(len(rings), 3)
        for ring in rings: self.assertEqual(ring[0], ring[-1])
        for line in N.soi_lines(os.path.join(GEO, 'in-boundaries-soi.json')):
            for p in line[1:-1]:  # the chains' ends lie on the polygons' flat ends
                self.assertTrue(any(inside(p, r) for r in rings), p)


if __name__ == '__main__':
    unittest.main()
