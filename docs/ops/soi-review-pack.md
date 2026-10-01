# Survey of India review pack: how Doorprints uses OVSF/1M/7

| Field | Value |
|---|---|
| Document | Statement for the Survey of India's reviewers: the data used, what was done to it, how to check it, and our questions |
| Version | 0.1 |
| Date | 2026-10-01 |
| Author | Claude (Code), lead, for the owner (Sriram, maintainer of Doorprints) |
| Status | Draft, not sent. Branch `feat/soi-boundary-data`; the data file is built but not yet shown in the apps |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-01 | Claude (Code), lead | First version, after the Survey of India's reply of 2026-10-01 ([soi-boundary-data-request.md](soi-boundary-data-request.md): no prior permission needed; no alteration or modification of the dataset; acknowledgement; National Geospatial Policy 2022). Pipeline `web/scripts/geo/build_in_boundaries_soi.py`, checker `tools/soi-verify.py`, data `web/public/geo/in-boundaries-soi.json`. |

## 1. What we did to your data, step by step

Each step can be checked with `tools/soi-verify.py` (section 4), which shares no code with the build.

1. **Read** the layer `STATE_BOUNDARY` of OVSF/1M/7 exactly as stored: 40 records, 1,322 rings, 1,719,455 vertices,
   Lambert Conformal Conic on WGS 84 in metres, with the parameters of `STATE_BOUNDARY.prj`. *Checked:* the checker
   reads the same file with its own reader and prints its sha256.
2. **Found the lines two states share**: an edge (two consecutive vertices of a ring) is a shared state line when
   another ring holds the same two vertices, compared exactly as stored, in the opposite direction. 1,088,904 of the
   1,718,133 edges are shared; the other 629,229 are India's outer edges (land boundary and coast). *Checked:* no
   shipped land-boundary edge is held by another ring in the opposite direction.
3. **Selected** India's international land boundary among the outer edges, leaving out the coast (section 6 gives
   the rule; a Natural Earth file helped decide land or coast, but no Natural Earth coordinate is written), and the
   edges ARUNACHAL PRADESH shares with ASSAM. What is kept are **whole runs of consecutive vertices** of a source
   ring: 29 land-boundary runs (306,042 vertices) and 1 state-line run (8,252 vertices). *Checked:* each shipped line
   names its record, part and vertex range; the checker finds those vertices and checks the count and the order.
4. **Re-projected** each kept vertex to WGS 84 longitude and latitude with the exact inverse of the projection
   (ellipsoidal formulas, double precision) and **rounded** to 7 decimals (about 1 cm). *Checked:* the checker projects
   every shipped point forward again and finds it within 2 cm of your vertex; the largest difference is 0.741 cm.
5. **Wrote** them as a GeoJSON file, one line per run, with the source named in each line (product, layer, record,
   state, part, vertex range, sha256 of the ZIP). *Checked:* the checker prints the file's sha256.

Nothing else was done. No vertex was moved, added, removed, simplified, smoothed, snapped or "corrected"; the order
inside a run is yours; nothing was clipped by a box; no connecting line was drawn between runs; no attribute was
changed. The coast, the state lines other than Assam-Arunachal Pradesh, the districts, the headquarters and the towns
are not shipped. (DISTRICT_HQ was read only to test the projection: its stored points and its LONGITUDE and LATITUDE
columns agree to 1e-10 degree for all 780 headquarters.)

## 2. The data

| Item | Value |
|---|---|
| Product | Administrative Boundary Database, OVSF/1M/7, "Entire country Upto Distt. level with HQ", 1:1M, shapefile, free of charge; downloaded by the owner from the Online Maps Portal |
| File | `File_962036_download.zip`, 80,777,293 bytes, sha256 `e2225e73e00852d81141960eea69afeb78313003123c5f2420fca9ab583206ce` (40 files in folder `91/`) |
| Layer used | `STATE_BOUNDARY.shp` sha256 `cfaf5a51b3e771088adc133abce3d8f2f96121c65e5f0e4548eab27ebccdbc21`, `.dbf` `76b5bc94ff6b4045e541f6159b75abe191dde3b27f6f9ae740f24f0e008e2c04`, `.prj` `f2cda3b9e845e55e62760d99ee2155285fa30b6f13531f42151e456d72b23aad` |
| Projection | `LCC_WGS84`: standard parallels 12.472944 and 35.172806, latitude of origin 24, central meridian 80, false easting and northing 4,000,000 m, scale factor 1 |
| Dates | The portal gives no version. The ZIP's state and district files are dated 2026-09-12; `STATE_BOUNDARY.shp.xml` says created 2026-09-12 and modified 2025-10-16; MAJOR_TOWNS and STATE_HQ are dated 2021-04-28, DISTRICT_HQ 2026-07-03 |

The shapefiles are not in the source repository. A 537-vertex excerpt (six pieces, your metres unchanged, with their
record, part and vertex range) is kept as a test fixture, `web/scripts/geo/testdata/soi-fixture.json`.

## 3. What we ship and where it appears

| Kind | Lines | Vertices | What |
|---|---|---|---|
| `claim` | 29 | 306,042 | India's international land boundary: with Pakistan (Gujarat, Rajasthan, Punjab, Jammu and Kashmir, Ladakh), Afghanistan and China (Ladakh), China (Himachal Pradesh, Uttarakhand, Sikkim, Arunachal Pradesh), Nepal, Bhutan, Bangladesh and Myanmar, and the edge of the Bangladesh enclave of Dahagram-Angarpota |
| `state` | 1 | 8,252 | The Assam-Arunachal Pradesh state line (ARUNACHAL PRADESH ring, vertices 10014 to 18265) |

All of Jammu and Kashmir and Ladakh, including the areas under the occupation of Pakistan and China, and all of
Arunachal Pradesh are inside the line, because the line is your polygons' own edge (Ladakh's run reaches 37.088 N and
80.345 E). The file is `web/public/geo/in-boundaries-soi.json` (sha256 `d12d8ed8…20dac6`). The apps (the web app at
https://doorprints.web.app, the Android app and the iPhone app in progress) will draw it as India's boundary over the
base map, at every zoom, the base map's own lines for India's border hidden, with **"Boundary: Survey of India"** on
the map and the line in section 7 in the About screen, the NOTICE file and the README. The apps do not offer the data
for download on its own. Until that change is made the apps keep their current outline.

## 4. How to check it

With Python 3 and the ZIP as downloaded (or its extracted folder), from the repository:

```
python3 tools/soi-verify.py File_962036_download.zip web/public/geo/in-boundaries-soi.json
```

Expected output (2026-10-01; paths shortened):

```
source: File_962036_download.zip
  zip sha256                e2225e73e00852d81141960eea69afeb78313003123c5f2420fca9ab583206ce
  STATE_BOUNDARY.shp        cfaf5a51b3e771088adc133abce3d8f2f96121c65e5f0e4548eab27ebccdbc21
  STATE_BOUNDARY.dbf        76b5bc94ff6b4045e541f6159b75abe191dde3b27f6f9ae740f24f0e008e2c04
  STATE_BOUNDARY.prj        f2cda3b9e845e55e62760d99ee2155285fa30b6f13531f42151e456d72b23aad
shipped: web/public/geo/in-boundaries-soi.json
  sha256                    984c02202a735f350e67902f004cdd0b9e78116d2fba2e3a776158eb1112df4d (7484685 bytes)
kind=claim: 29 lines, 306042 vertices
kind=state: 1 lines, 8252 vertices
largest difference from the source: 0.741 cm (limit 2 cm)
OK: every shipped vertex is a source vertex, in order, none added or removed, within the limit
```

Any moved, added, removed or reordered vertex makes it print `VIOLATION` and exit with status 1. To rebuild the file:
`python3 web/scripts/geo/build_in_boundaries_soi.py File_962036_download.zip out.geojson --report report.json`
(the output is byte-identical on every run).

## 5. The size problem

The full-detail land boundary is large, mostly because of the Bangladesh border (West Bengal alone has 116,611 of
the vertices, about one every 20 m; Tripura 51,047). Our budget for a file every user downloads is about 600 KB, or
250 KB compressed.

| Option | Raw | gzip -9 | Keeps every vertex unaltered? |
|---|---|---|---|
| Full detail, 7 decimals (the file built) | 7,484,685 | 2,329,401 | Yes |
| Full detail, 6 decimals (about 11 cm) | 6,855,605 | 1,965,760 | Positions rounded to about 11 cm; fails our 2 cm check |
| Full detail, compact encoding (`--encoding polyline7`: the same 7-decimal values, delta-encoded) | 1,684,242 | 1,208,576 | Yes (the checker decodes and checks it) |
| Only Jammu and Kashmir, Ladakh, Himachal Pradesh, Uttarakhand, Sikkim and Arunachal Pradesh, plus the Assam-Arunachal line, compact (`--states …`) | 224,099 | 161,054 | Yes, a narrower selection of whole runs; the rest of the border would come from the base map as today |
| The old Natural Earth file, for comparison | 415,606 | – | – |

No option that keeps all 306,042 vertices fits the budget; only generalising the line (fewer vertices) would, and we
have not done that (questions 9 and 10).

## 6. How land boundary and coast were told apart

An outer edge is *land* when both its vertices lie within 12 km of a land-boundary line of the Natural Earth file the
app ships today (1:50m international boundaries in India's view, and India's 1:10m outline in the claim areas), except
past the free end of such a line by more than 1 km (where a land boundary meets the sea). Along India's outline, a
coast stretch shorter than 30 km between two land stretches counts as land, and a land stretch shorter than 2 km
between two coast stretches counts as coast. Measured: the kept vertices lie a median 2.1 km from the Natural Earth
lines (90 % within 4.5 km); every outer edge of every state without a coast came out as land; 13 short gaps were
bridged (where the Natural Earth lines are cut: Kathua, the Wakhan, the Karakoram near the Shaksgam, Kalapani, the
Darjeeling hills, Doklam, Jomotsangkha and Longwa; 0.3 to 15 km each); none were dropped. The Natural Earth file
decides only which of your edges are kept.

## 7. Acknowledgement we propose

On every map: **Boundary: Survey of India**. In the About screen, the NOTICE file and the README: *Boundaries: Survey
of India, Administrative Boundary Database (OVSF/1M/7), reproduced without alteration. Copyright Survey of India,
Government of India.* NOTICE also says that the Survey of India's data is not part of Doorprints and not under the
app's open-source licence (AGPL-3.0).

## 8. Things in the data that look unusual (left exactly as they are)

1. Spelling of two attributes: `DISPUTED (RAJATHAN & GUJARAT)` (Rajasthan), and `DISPUTED (WEST BENGAL , BIHAR &
   JHARKHAND)` (a space before the comma).
2. The four `DISPUTED` features are inter-state areas, all of whose edges are shared with states; nothing of them is
   shipped. Parts 0 and 1 of `DISPUTED (RAJATHAN & GUJARAT)` (near 73.84 E 21.01 N and 73.78 E 21.07 N) border Gujarat
   and Maharashtra, not Rajasthan; its part 17 (6 vertices, about 19 m², 75.16 E 25.04 N) lies between Rajasthan and
   `DISPUTED (MADHYA PRADESH & RAJASTHAN)`, far from Gujarat.
3. Very small rings on the international boundary: ASSAM part 0 (4 vertices, about 26 m², 89.711 E 26.161 N, at the
   West Bengal-Assam-Bangladesh junction) and MANIPUR parts 0 and 1 (5 and 4 vertices, about 155 m² and 3 m²,
   94.679 E 25.452 N, by the Nagaland-Manipur-Myanmar junction). Their outer edges are shipped as 3-vertex runs, with
   two short NAGALAND runs between them (vertices 5593-5597 and 5599-5612).
4. No gaps and no overlaps were found between state polygons: every edge is either shared exactly, in the opposite
   direction, or on India's outline; no edge is shared in the same direction; the outline has one hole, the
   Dahagram-Angarpota enclave (WEST BENGAL part 42, 654 vertices, 18.3 km²); 5 vertices where islands touch at one
   point, all in Andaman and Nicobar. No repeated consecutive vertices.
5. `STATE_BOUNDARY.shp.xml` gives a modification date (2025-10-16) earlier than its creation date (2026-09-12).
   `DISTRICT_HQ.prj` has no Scale_Factor line (the others give 1.0); DISTRICT_BOUNDARY is stored with heights
   (PolygonZ), STATE_BOUNDARY without.

## 9. Runs where the selection was not clear-cut

| Place | Record, part, vertices | What we did |
|---|---|---|
| Sir Creek (Gujarat-Pakistan, at the sea) | GUJARAT, record 24, part 110: the run starts at vertex 66951 (68.1985 E, 23.8132 N) | The vertices before it, about 66400-66950, run south along Sir Creek to about 23.57 N (about 45 km); they are left out because the Natural Earth line stops at 23.857 N |
| Sundarbans (West Bengal-Bangladesh, at the sea) | WEST BENGAL, record 18, part 40: the run ends at vertex 114105 (89.0379 E, 22.0938 N) | The bank south of it (the Raimangal and Hariabhanga estuary) and the islands are left out as coast |
| Dahagram-Angarpota (Cooch Behar) | WEST BENGAL, record 18, part 42, whole ring (654 vertices) | Shipped as land boundary |
| Tiny rings (section 8, item 3) | ASSAM 10/0 vertices 2-1; MANIPUR 11/0 3-1 and 11/1 2-1; NAGALAND 14/0 5593-5597 and 5599-5612 | Shipped as they are |
| Junctions of states on the border | every run ends where a state line meets the border | The 29 runs meet at shared vertices; they are not joined into one line |

## 10. Questions for the Survey of India

Each can be answered with a yes or no or a few words.

1. Is re-projecting your vertices exactly from the database's Lambert Conformal Conic (WGS 84) to WGS 84 longitude
   and latitude, written to 7 decimal places (about 1 cm), consistent with "no alteration or modification"?
2. May we use only part of the dataset: India's international land boundary, as runs of your consecutive vertices
   left unchanged, and not the coastline, the other state lines, the districts or the towns?
3. Is it acceptable that a public-domain Natural Earth file was used only to decide which of your outer edges are land
   boundary and which are coast (no Natural Earth point is shipped)?
4. Is the test we used to find state lines acceptable: an edge is a state line when another state's ring holds the
   same two vertices in the opposite direction?
5. May we also keep the Assam-Arunachal Pradesh state line from your data (the app draws it because the base map
   leaves it out)?
6. At Sir Creek, where does your land boundary end at the sea: should the vertices of GUJARAT part 110 from about
   66400 to 66950 (along the creek, down to about 23.57 N) be drawn as boundary, or only from vertex 66951 northwards
   as now?
7. In the Sundarbans, should any edge of WEST BENGAL south of vertex 114105 of part 40 (89.0379 E, 22.0938 N), or of
   the islands next to the Raimangal and Hariabhanga, be drawn as the international boundary?
8. Is it right to draw the edge of the Dahagram-Angarpota enclave (WEST BENGAL part 42) and the small rings at the
   Assam and Manipur junctions (ASSAM part 0, MANIPUR parts 0 and 1) as international boundary, exactly as stored?
9. The full-detail land boundary is about 7.5 MB (2.3 MB compressed). Is a generalised version (fewer vertices,
   reduced for screen display only, with the full-detail file kept and the map labelled as based on your data)
   acceptable, and if so, is there a tolerance you require or a generalised product you would rather we use?
10. If generalising is not acceptable: may we ship your vertices only in the Himalayan states and Arunachal Pradesh
    (Jammu and Kashmir, Ladakh, Himachal Pradesh, Uttarakhand, Sikkim, Arunachal Pradesh) and use the base map's
    line for the rest of India's land border?
11. May we later compute India's outline (the union of your state polygons) and use it only inside the app, as a
    mask to hide base-map lines inside India, never drawn and never offered as data?
12. May the 7-decimal data file and a 537-vertex excerpt used for tests be kept in the app's public source repository,
    marked as your data and not under the app's licence?
13. Is "Boundary: Survey of India" on the map, and *Boundaries: Survey of India, Administrative Boundary Database
    (OVSF/1M/7), reproduced without alteration. Copyright Survey of India, Government of India.* in the About screen,
    the right acknowledgement, or would you like other wording?
14. The portal gives no version. May we cite the data as *OVSF/1M/7, files dated 12 September 2026, downloaded
    [date]*, or is there a version or edition name we should use (the metadata says created 2026-09-12, modified
    2025-10-16)?
15. Is it acceptable that your land boundary is drawn over a base map from OpenStreetMap, whose coastline, other
    countries' boundaries and place names are not your data, with the base map's own lines for India's border
    hidden?
16. Should the attribute spellings and the placement of the `DISPUTED` parts in section 8 be reported to you for a
    correction, and should we use your corrected file when it is published?
17. When you publish a revised boundary, is updating our file from the new download (with the same steps and a new
    verification) enough, or do you want to be told each time?
