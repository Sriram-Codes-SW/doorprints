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

"""Builds in-soi-corridor.geojson (S4b-BL-99): the polygons around the Survey of India's land-boundary lines
(in-boundaries-soi.json, kind=claim) inside which the apps hide the base map's own country lines, so from zoom 5
India's northern and north-eastern boundary is drawn once, by the Survey of India line, and not a second time by the
OpenStreetMap line of the tiles beside it (docs/03 ADR-22 rule 2; web src/app/shared/india-boundaries.ts
SOI_CORRIDOR_RULE, Android IndiaViewRules.soiCorridorFilter).

A mask, never drawn and never offered as data: the apps add
  ["!", ["all", <India's line>, ["any", ["within", P1], ["within", P2], ...]]]
to the filter of Liberty's `boundary_2`, where <India's line> is a tile line with India (or no country: the tiles often
leave India's side empty) on a side, or the Pakistan-Afghanistan line, which in India's view is the Gilgit-Baltistan
line of the Wakhan. `within` is true only for a tile feature that lies wholly inside one polygon (build_in_held_areas.py
explains the renderers' rule), so a feature that runs on past a Survey of India line's end (Uttar Pradesh-Nepal at the
Uttarakhand tri-junction, West Bengal-Nepal at Sikkim) stays drawn, whole, in the tiles that hold that end: a stretch
of tile line beside ours of at most one tile there (from zoom 11 about 20 km; at zoom 5-10 the tiles' lines are longer
and are drawn; along Nepal and Bhutan the two lie a median 20-30 m apart, docs/06 TC-M-25).

Each polygon: one chain of the claim lines (consecutive runs that share an end vertex are one chain: Jammu and
Kashmir to Uttarakhand, Sikkim, Arunachal Pradesh), buffered by WIDTH_KM with flat ends (so the line that goes on past
a chain's end is not inside it) in a local equirectangular plane, its holes filled, simplified by TOLERANCE_KM, rounded to 4 decimals
(about 10 m). The vertices of the Survey of India lines are only read, never written.

Needs: pip install shapely.
Usage: build_in_soi_corridor.py [in-boundaries-soi.json] <output file>"""
import json, math, os, sys
from shapely.geometry import LineString, MultiLineString, Polygon, mapping
from shapely.ops import linemerge

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from build_in_boundaries import SOI_DEFAULT, soi_lines  # noqa: E402

WIDTH_KM = 5.5       # half-width of the corridor: the tiles' lines along ours lie within about 4 km (the Wakhan)
TOLERANCE_KM = 1.0   # simplification of the corridor's outline (so at least 4.5 km of it is left on each side)
KM_PER_DEG = 111.195


def chains(lines):
    """The claim runs joined where one ends at another's first or last vertex, as lists of (lon, lat)."""
    merged = linemerge(MultiLineString([LineString(l) for l in lines]))
    parts = merged.geoms if hasattr(merged, 'geoms') else [merged]
    return [list(p.coords) for p in parts]


def corridor(chain):
    """One chain's corridor as a shapely Polygon in (lon, lat)."""
    lat0 = sum(y for _, y in chain) / len(chain)
    k = math.cos(math.radians(lat0)) * KM_PER_DEG
    plane = LineString([(x * k, y * KM_PER_DEG) for x, y in chain])
    poly = plane.buffer(WIDTH_KM, cap_style='flat', join_style='round', quad_segs=4)
    # A flat end where the line turns back on itself can leave a sliver of a few km² apart: the largest part is kept.
    if poly.geom_type == 'MultiPolygon': poly = max(poly.geoms, key=lambda g: g.area)
    # Holes (where a chain loops round a small area) are filled: a mask that also covers them changes nothing.
    poly = Polygon(poly.exterior).simplify(TOLERANCE_KM)
    if poly.geom_type != 'Polygon' or not poly.buffer(0.01).covers(plane):
        raise SystemExit(f'the corridor of the chain at {chain[0]} is not one polygon around the whole chain')
    back = lambda ring: [(round(x / k, 4), round(y / KM_PER_DEG, 4)) for x, y in ring.coords]
    out = Polygon(back(poly.exterior))
    if not out.is_valid: raise SystemExit(f'corridor of the chain at {chain[0]} is not valid after rounding')
    return out


def build(soi_path):
    features = []
    for chain in sorted(chains(soi_lines(soi_path)), key=lambda c: (c[0][0], c[0][1])):
        g = mapping(corridor(chain))
        features.append({'type': 'Feature', 'properties': {'kind': 'corridor'},
                         'geometry': {'type': 'Polygon', 'coordinates': [[list(p) for p in r] for r in g['coordinates']]}})
    return {'type': 'FeatureCollection', 'features': features}


def main(argv):
    if len(argv) not in (1, 2): raise SystemExit('usage: ' + __doc__.split('Usage: ')[1].split('\n')[0])
    src, out = (SOI_DEFAULT, argv[0]) if len(argv) == 1 else argv
    fc = build(src)
    s = json.dumps(fc, separators=(',', ':')) + '\n'
    with open(out, 'w') as f: f.write(s)
    print('polygons', len(fc['features']), 'vertices',
          sum(len(r) for f in fc['features'] for r in f['geometry']['coordinates']), '| bytes', len(s))


if __name__ == '__main__':
    main(sys.argv[1:])
