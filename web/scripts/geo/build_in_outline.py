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

"""Builds the outline of India that a place lookup tests a point against (S4b-BL-174c): src/app/shared/india-outline.geojson
(the website) and android/shared/.../location/IndiaOutlineData.kt (the apps, from the same ring, so the two cannot differ).

The ring is made of nothing but the lines already in web/public/geo/in-boundaries.geojson (ADR-22), the outline as the
Government of India depicts it: the Pakistan line (the kind `world` line from Sir Creek to the Punjab), the four claim
pieces (Jammu and Kashmir with Gilgit-Baltistan, Ladakh with Aksai Chin and Shaksgam, and the north-east), the stretches
shared with Afghanistan, Nepal, Bhutan and Myanmar, and the line with Bangladesh, in that order, each piece turned to
continue the one before (the largest hand-over is 27 km, at Singalila). The file has no coastline, so the sea side is
closed by a few straight lines drawn in open water, well off every coast of India and clear of Sri Lanka (through the
Palk Strait), Bangladesh and Pakistan; no town is in the sea, so those lines change no answer. The Andaman and Nicobar
Islands and Lakshadweep are two boxes in the sea around them, with no other country's land inside.

The ring is simplified by DOUGLAS_PEUCKER_DEG (about 220 m) and rounded to 4 decimals (11 m): a point within a few
hundred metres of the line may fall on either side; a lookup only has to tell a town of India from a town of a neighbour.
The result is checked: the ring has no crossing, and CHECK holds a town of India and one of a neighbour on each side.

Usage: build_in_outline.py <output .json> <output .kt>          (writes both)
       build_in_outline.py --check <.json> <.kt>                (fails if either differs from what the data gives)"""
import json, math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, '..', '..', 'public', 'geo', 'in-boundaries.geojson')
DOUGLAS_PEUCKER_DEG = 0.002
KOTLIN_HEADER_SOURCE = os.path.join(HERE, '..', '..', '..', 'android', 'shared', 'src', 'commonMain', 'kotlin', 'app', 'doorprints', 'shared', 'location', 'PlaceLookup.kt')

# The pieces of the land outline, in order, from Sir Creek anticlockwise round the Punjab: (kind, index in that kind's
# line list). Each is turned to start where the one before ended. World line 353 (a Sikkim-West Bengal bit inside the
# country) and 216 (two coincident points) are not part of the outline.
PIECES = [('world', 337), ('claim', 3), ('world', 358), ('claim', 4), ('claim', 0), ('world', 352), ('world', 351),
          ('world', 354), ('claim', 1), ('world', 355), ('world', 79), ('world', 356), ('claim', 2), ('world', 357),
          ('world', 158), ('world', 307)]
# (lon, lat) from the end of the last piece (the Bangladesh line meets the sea in the Sundarbans) round the Bay of Bengal,
# through the Palk Strait (Jaffna, Delft and Talaimannar are outside; Rameswaram is inside), round Kanyakumari and up the
# Arabian Sea to Sir Creek, the start.
SEA = [(88.97, 21.6), (88.9, 21.2), (87.5, 18.0), (84.0, 13.0), (81.5, 11.5), (80.8, 10.8), (79.94, 9.98),
       (79.55, 9.5), (79.4, 9.0), (78.9, 8.5), (77.9, 7.6), (77.4, 7.0), (76.5, 7.0), (70.5, 10.0), (67.0, 22.5)]
# South, west, north, east, in degrees.
ISLANDS = [(6.5, 92.1, 13.8, 94.3), (8.0, 71.5, 12.4, 74.0)]

# (name, lat, lon, inside): towns of India that must be in and of its neighbours that must be out, the border towns
# on both sides of each line, and the places the Government of India depicts as India (Gilgit, Muzaffarabad, Skardu).
CHECK = [
    ('Siliguri', 26.7271, 88.3953, True), ('Alipurduar', 26.4912, 89.5271, True), ('Phuentsholing', 26.8517, 89.3894, False),
    ('Imphal', 24.817, 93.9368, True), ('Churachandpur', 24.3333, 93.6833, True), ('Tamu', 24.2167, 94.3, False),
    ('Amritsar', 31.634, 74.8723, True), ('Lahore', 31.5497, 74.3436, False), ('Sialkot', 32.4945, 74.5229, False),
    ('Jammu', 32.7266, 74.857, True), ('Srinagar', 34.0837, 74.7973, True), ('Leh', 34.1526, 77.5771, True),
    ('Kargil', 34.5539, 76.1349, True), ('Gilgit', 35.92, 74.31, True), ('Skardu', 35.3, 75.63, True),
    ('Muzaffarabad', 34.37, 73.47, True), ('Aksai Chin', 35.1, 79.3, True), ('Tawang', 27.5859, 91.8594, True),
    ('Itanagar', 27.0844, 93.6053, True), ('Kibithu', 28.2, 97.0, True), ('Gangtok', 27.3389, 88.6065, True),
    ('Motihari', 26.6493, 84.9167, True), ('Birgunj', 27.0104, 84.8779, False), ('Kathmandu', 27.7172, 85.324, False),
    ('Pokhara', 28.2096, 83.9856, False), ('Dhaka', 23.8103, 90.4125, False), ('Chittagong', 22.3569, 91.7832, False),
    ('Agartala', 23.8315, 91.2868, True), ('Kolkata', 22.5726, 88.3639, True), ('Khulna', 22.8456, 89.5403, False),
    ('Thimphu', 27.4728, 89.639, False), ('Paro', 27.4305, 89.4134, False), ('Lhasa', 29.65, 91.1, False),
    ('Kabul', 34.5553, 69.2075, False), ('Islamabad', 33.6844, 73.0479, False), ('Karachi', 24.8607, 67.0011, False),
    ('Bhuj', 23.242, 69.6669, True), ('Kanyakumari', 8.0883, 77.5385, True), ('Rameswaram', 9.2876, 79.3129, True),
    ('Colombo', 6.9271, 79.8612, False), ('Jaffna', 9.6615, 80.0255, False), ('Mannar', 8.9763, 79.9042, False),
    ('Male', 4.1755, 73.5093, False), ('Yangon', 16.8409, 96.1735, False), ('Sittwe', 20.1462, 92.8985, False),
    ('Port Blair', 11.6234, 92.7265, True), ('Indira Point', 6.7456, 93.8403, True), ('Kavaratti', 10.5626, 72.6369, True),
    ('Minicoy', 8.2845, 73.0498, True), ('Mumbai', 19.076, 72.8777, True), ('Chennai', 13.0827, 80.2707, True),
    ('Delhi', 28.6139, 77.209, True), ('Dibrugarh', 27.4728, 94.912, True), ('Aizawl', 23.7271, 92.7176, True),
]


def pieces_ring():
    data = json.load(open(SOURCE))
    lines = {f['properties']['kind']: f['geometry']['coordinates'] for f in data['features'] if f['properties']['kind'] != 'state'}
    ring = lines['world'][PIECES[0][1]][::-1]  # from Sir Creek
    for kind, i in PIECES[1:]:
        line = lines[kind][i]
        if math.dist(ring[-1], line[-1]) < math.dist(ring[-1], line[0]):
            line = line[::-1]
        assert math.dist(ring[-1], line[0]) < 0.25, (kind, i)  # no hand-over of more than about 27 km
        # Where the end of one piece and the start of the next overlap (a connector of a few km that runs past the point
        # where the next begins), join at the closest pair of points among the last and first 40 instead of at the ends.
        dist, ti, tj = min((math.dist(ring[len(ring) - 1 - a], line[b]), a, b) for a in range(min(40, len(ring))) for b in range(min(40, len(line))))
        del ring[len(ring) - ti:]
        ring += line[tj:] if dist > 0 else line[tj + 1:]
    return [tuple(p) for p in ring]


def douglas_peucker(points, eps):
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = points[a], points[b]
        far, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            dx, dy = bx - ax, by - ay
            t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
            d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > far:
                far, at = d, i
        if far > eps:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(points, keep) if k]


def crossings(ring):
    """Pairs of non-adjacent edges of the closed ring that cross (brute force over a grid of the edges)."""
    n = len(ring)
    edges = [(ring[i], ring[(i + 1) % n]) for i in range(n)]

    def ccw(a, b, c):
        return (c[1] - a[1]) * (b[0] - a[0]) - (b[1] - a[1]) * (c[0] - a[0])

    bad = []
    cells = {}
    for i, (a, b) in enumerate(edges):
        for cx in range(int(min(a[0], b[0])), int(max(a[0], b[0])) + 1):
            for cy in range(int(min(a[1], b[1])), int(max(a[1], b[1])) + 1):
                cells.setdefault((cx, cy), []).append(i)
    seen = set()
    for members in cells.values():
        for x in range(len(members)):
            for y in range(x + 1, len(members)):
                i, j = members[x], members[y]
                if (i, j) in seen or j - i <= 1 or (i == 0 and j == n - 1):
                    continue
                seen.add((i, j))
                (a, b), (c, d) = edges[i], edges[j]
                if ccw(a, b, c) * ccw(a, b, d) < 0 and ccw(c, d, a) * ccw(c, d, b) < 0:
                    bad.append((i, j))
    return bad


def inside(polygons, lat, lon):
    """True when (lat, lon) is inside any of the rings (even-odd within one ring)."""
    for ring in polygons:
        hit = False
        j = len(ring) - 1
        for i in range(len(ring)):
            (xi, yi), (xj, yj) = ring[i], ring[j]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                hit = not hit
            j = i
        if hit:
            return True
    return False


def build():
    ring = douglas_peucker(pieces_ring(), DOUGLAS_PEUCKER_DEG) + SEA
    ring = [(round(x, 4), round(y, 4)) for x, y in ring]
    dedup = [p for i, p in enumerate(ring) if i == 0 or p != ring[i - 1]]
    polygons = [dedup] + [[(w, s), (e, s), (e, n), (w, n)] for s, w, n, e in ISLANDS]
    assert not crossings(polygons[0]), crossings(polygons[0])
    wrong = [name for name, lat, lon, want in CHECK if inside(polygons, lat, lon) != want]
    assert not wrong, wrong
    return polygons


def json_text(polygons):
    note = ('Where India is, as the Government of India depicts it (docs/03 ADR-22), for telling a place of India from one of a '
            'neighbour. Built by web/scripts/geo/build_in_outline.py from web/public/geo/in-boundaries.geojson: the land '
            'outline from that file, the sea side closed in open water, the Andaman and Nicobar Islands and Lakshadweep as '
            'boxes. Read by place-search.ts (website); the apps hold the same rings in IndiaOutlineData.kt.')
    closed = [[list(p) for p in ring] + [list(ring[0])] for ring in polygons]
    return (json.dumps({'type': 'FeatureCollection', 'format': 'doorprints-india-outline/1', 'note': note,
                        'features': [{'type': 'Feature', 'properties': {'kind': 'india-outline'},
                                      'geometry': {'type': 'MultiPolygon', 'coordinates': [[r] for r in closed]}}]},
                       separators=(',', ':')) + '\n')


def kotlin_text(polygons):
    notice = ''.join(open(KOTLIN_HEADER_SOURCE).readlines()[:17])  # the licence notice of a Kotlin file, as it is there
    body = ',\n'.join('    "' + ';'.join('%s,%s' % (repr(y), repr(x)) for x, y in ring) + '"' for ring in polygons)
    return (notice + '\npackage app.doorprints.shared.location\n\n'
            '// Generated by web/scripts/geo/build_in_outline.py from web/public/geo/in-boundaries.geojson; do not edit.\n'
            '// Each string is one ring of "latitude,longitude" pairs separated by ";" (ADR-22, S4b-BL-174c). The web twin is\n'
            '// web/src/app/shared/india-outline.geojson; IndiaOutlineTest pins that the two hold the same points.\n\n'
            '/** The rings of [IndiaOutline]: the mainland, the Andaman and Nicobar Islands, Lakshadweep. */\n'
            'internal val INDIA_OUTLINE_RINGS: List<String> = listOf(\n' + body + ',\n)\n')


def main(argv):
    check = argv[0] == '--check'
    json_out, kt_out = argv[-2], argv[-1]
    polygons = build()
    want = {json_out: json_text(polygons), kt_out: kotlin_text(polygons)}
    for path, text in want.items():
        if check:
            if open(path).read() != text:
                sys.exit(path + ' differs from what in-boundaries.geojson gives; run build_in_outline.py')
        else:
            open(path, 'w').write(text)
    print('ok: %d points in the mainland ring' % len(polygons[0]))


if __name__ == '__main__':
    main(sys.argv[1:])
