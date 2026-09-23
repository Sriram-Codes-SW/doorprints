"""Builds in-boundaries.geojson: land boundaries as the Government of India depicts them, from Natural Earth
(public domain). kind=world: 1:50m admin-0 land boundary lines whose India point-of-view class is an international
boundary (FCLASS_IN when set, else FCLASS_ISO), outside the claim boxes; used below zoom 5 in place of the tiles'
boundaries. kind=claim: the land-boundary parts of India's own polygon from the 1:10m India point-of-view countries
file, inside the claim boxes; used at every zoom."""
import json, sys
NE = sys.argv[1]; OUT = sys.argv[2]
BOXES = {  # lon_min, lon_max, lat_min, lat_max
  'west': (72.4, 81.2, 32.35, 37.2),     # Jammu, Kashmir, Ladakh incl. PoK, Gilgit-Baltistan, Shaksgam, Aksai Chin
  'middle': (78.3, 81.2, 29.9, 32.35),   # Himachal Pradesh and Uttarakhand with Tibet, Kalapani
  'sikkim': (88.0, 89.3, 27.0, 28.2),    # Sikkim with Tibet, Doklam tri-junction
  'east': (91.5, 97.5, 26.5, 29.6),      # Arunachal Pradesh
}
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
feat = lambda kind, lines: {'type': 'Feature', 'properties': {'kind': kind},
    'geometry': {'type': 'MultiLineString', 'coordinates': [[[R(x), R(y)] for x, y in l] for l in lines]}}
out = {'type': 'FeatureCollection', 'features': [feat('world', world), feat('claim', claims)]}
s = json.dumps(out, separators=(',', ':')) + '\n'
open(OUT, 'w').write(s)
print('claim segments', len(claims), 'points', sum(len(l) for l in claims), '| world lines', len(world), '| bytes', len(s))
