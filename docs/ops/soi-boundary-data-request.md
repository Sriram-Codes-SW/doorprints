# Request to the Survey of India: using its boundary data in Doorprints (owner's draft)

| Field | Value |
|---|---|
| Document | Draft letter from the owner to the Survey of India, and how to send it |
| Version | 0.6 |
| Date | 2026-10-01 |
| Author | Claude (Code), lead |
| Status | **Answered** on 2026-10-01 (04:44 UTC) by the Survey of India's Online Maps Portal team: no prior permission is needed for the Administrative Boundary Database, **no alteration or modification of the dataset is permitted**, and due acknowledgement is required. The plan is in *The reply and the plan* below |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.6 | 2026-10-01 | Claude (Code), lead | Owner supplied the DST guidelines PDF ("Final Approved Guidelines on Geospatial Data", authority F.No.SM/25/02/2020 (Part-I), 15 February 2021, 5 pages) to help read the reply: new subsection *Reading the reply against the 2021 guidelines*; P2 now says what that PDF does and does not settle. |
| 0.5 | 2026-10-01 | Claude (Code), lead | **The reply came** (owner message of 2026-10-01; Gmail thread "Re: Request for permission to use the Administrative Boundary Database (OVSF/1M/7) in a free, open-source app", from `mtr.soi@gov.in`, 2026-10-01 04:44 UTC, copied to the NGDC, NGDR & UGI Directorate): the conditions, what they change in the build, and the plan P0..P6 (new section *The reply and the plan*, with a follow-up letter to send). |
| 0.4 | 2026-09-29 | Claude (Code), lead | **Sent** by the owner (2026-09-28 22:11 UTC) to `mtr.soi@gov.in` with `map_india_z4.png`, `map_kashmir_z6.png` and `map_arunachal_z7.png` attached (checked in the owner's Sent folder). The two links show the right addresses as text; their targets still pass through Google's redirect (the connector's wrapping), which after about a day shows Google's redirect notice before the site. No follow-up needed for that. |
| 0.3 | 2026-09-29 | Claude (Code), lead | Owner request to do the whole process (email access given): the portal read on 2026-09-28. **No account is needed** for the free database (its FAQ: free to all users, downloadable without registration from *Quick Access*); the download dialog asks for a CAPTCHA and a tick-box, which the owner does. Registration, if ever wanted, needs the owner's mobile OTP and an ID card (PDF) and SoI's approval, so it was not done. Contact found: The Director, NGDR & UGI Directorate (`mtr.soi@gov.in`). The letter is a **Gmail draft** in the owner's account (not sent), with product OVSF/1M/7 named; the web map screenshots were handed to the owner to attach. Steps rewritten to match. |
| 0.2 | 2026-09-29 | Claude (Code), lead | Owner request: steps for the web and the Android app. The letter names both apps (web address, Android package `app.doorprints`) and the iOS app to come (CMP-8), so one permission covers all three; the repository is public, so the source-code sentence is no longer conditional; new *Steps* section with what to attach. |
| 0.1 | 2026-09-28 | Claude (Code), lead | First draft (owner request of 2026-09-28 to follow the DST geospatial guidelines of 2021; [10](../10-sprint-log.md) S4b-BL-10 step (3), [03](../03-design.md) §11.1). |

## Why

Doorprints shows India's external boundary as the Government of India depicts it ([03](../03-design.md) ADR-22). Its
outline is Natural Earth's India point of view at 1:10m: it holds every area India claims, but its line is a median of
about 1.55-1.6 km from the true line ([02](../02-threat-model.md) RR-16). The DST guidelines of 15 February 2021 make
the Survey of India's maps and boundary data *the standard* for political maps of India (clause 8 xiii), and the SoI
portal lists its Administrative Boundary Database at no charge. What neither says is whether an open-source app may
build its outline from that data and ship the result (the app's source and its web build are public, under
`AGPL-3.0-only` once N4 lands). SoI's Digital Licence asks for written permission for free distribution. This letter
asks for that permission.

## Steps (web and Android together)

One request covers both apps, because both ship the same boundary file (`web/public/geo/in-boundaries.geojson` and
its byte-identical Android copy, `android/app/src/main/assets/geo/in-boundaries.geojson`, pinned by
`IndiaBoundaryDataTest`), and the iOS app of CMP-8 will ship it too.

1. **No account is needed.** The portal's FAQ says the Administrative Boundary Database is free to all users and can
   be downloaded without registration (checked 2026-09-28). Registration (for priced products) would need your mobile
   number's OTP, an ID card upload (PDF) and SoI's approval of the profile; it was not done.
2. **Download the free database yourself:** `https://onlinemaps.surveyofindia.gov.in` → *Quick Access* →
   *Administrative Boundary Database* → the first product, **OVSF/1M/7** ("Entire country Upto Distt. level with HQ",
   ₹0, shapefile, 1:1M) → *DOWNLOAD* → read the declaration before ticking its box, type the CAPTCHA → *Download*.
   The CAPTCHA and the tick-box are yours to do (a session does not solve CAPTCHAs or accept terms for you). Keep the
   ZIP outside the repository: until SoI says yes, it is only the reference TC-M-25 and the outline check (S4b-BL-10
   step (2)) measure against. To have a Claude session measure the outline against it, attach the ZIP in that session.
3. **The contact** (portal *Contact Us* and FAQ, 2026-09-28): The Director, NGDR & UGI Directorate, Survey of India,
   Hathibarkala Estate, New Cantt Road, Dehradun, Uttarakhand 248001; `mtr.soi@gov.in`; +91-135-2970896.
4. **Done: sent by the owner on 2026-09-28 22:11 UTC** to `mtr.soi@gov.in` with `map_india_z4.png`,
   `map_kashmir_z6.png` and `map_arunachal_z7.png` attached. History of the step: the letter was a draft in your Gmail (subject "Request for permission to use the Administrative Boundary
   Database (OVSF/1M/7) in a free, open-source app", to `mtr.soi@gov.in`). Replace `[YOUR FULL NAME]` (twice),
   `[POSTAL ADDRESS]`, `[PHONE]` and `[DATE]`, attach the screenshots (step 5), read it once more and send it yourself.
   **Finish it in Gmail itself, not through a Claude session's Gmail connector:** the connector rewrites every link it
   writes into a `https://www.google.com/url?q=…&ust=…` redirect whose `ust` expires (checked on 2026-09-28 in the stored
   raw message), and it can only attach files passed inline, which is impractical for screenshots. In Gmail, point each
   link at its plain address (*Change* on the link) and attach the files there; Gmail rebuilds the plain-text copy on
   send.
5. **Done (attached, see step 4).** What to attach, for a later request: the web map at the whole-of-India view and zoomed
   over Jammu and Kashmir and Ladakh and over Arunachal Pradesh (the live UI test's screenshots `map_india_z4.png`,
   `map_kashmir_z6.png`, `map_arunachal_z7.png`, or your own), the Android Map tab the same way (a phone screenshot,
   or the Map image in the latest `Android emulator` run's `android-emulator-results` artifact), and the
   self-certification ([03](../03-design.md) §11.1) if they ask how the guidelines are followed.
6. **Record the answer**: tell the next Claude session (or note it in [10](../10-sprint-log.md) S4b-BL-10) with the
   date. A yes, or a licence that clearly allows it, starts the rebuild of `in-boundaries.geojson` from SoI's data
   for both apps at once (the files stay byte-identical), with the attribution SoI asks for on both maps, then
   TC-M-25 on the live site and on a phone. A no keeps Natural Earth, with SoI's data as the reference only.

## The reply and the plan

### What the Survey of India wrote (2026-10-01, 04:44 UTC, from `mtr.soi@gov.in`, Team Online Maps Portal, NGDR & UGI Directorate, Dehradun)

1. "There is **no requirement to obtain prior permission** from Survey of India for the use of Survey of India
   Administrative Database." It "applies to the SoI Administrative Boundary Database only" (not to other SoI products).
2. "**No alteration or modification to the Survey of India dataset is permitted.**"
3. "**Due acknowledgement to Survey of India** should also be provided in the publication for the use of the data."
4. "You may accordingly proceed with the publication, subject to the above conditions and **National Geospatial Policy
   2022 Guidelines**."

So the question of the letter is answered (no permission needed, no licence fee), with three conditions: unaltered data,
acknowledgement, and the National Geospatial Policy 2022 guidelines. This is the owner's correspondence, not legal
advice; the reply is the record to cite in [10](../10-sprint-log.md) S4b-BL-10 and [03](../03-design.md) §11.1.

### What it changes in the build (the part that needs care)

The current outline is **not** a file that could be swapped for SoI's data. `web/scripts/geo/build_in_boundaries.py`
builds it from Natural Earth by cutting it with claim boxes, simplifying, cutting "shared stretches" out of it where
the base map's own tile line is more precise (S4b-BL-11, -16, -17) and joining the pieces with short connectors; the
held-areas polygon (`in-held-areas.geojson`) is derived the same way. Every one of those steps **alters** a boundary
dataset. With SoI's data they are not allowed. The rule for SoI's data is therefore:

- **Ship SoI's geometry exactly as SoI published it:** the same vertices in the same order and the same attributes, no
  simplification, no moving, joining, cutting, clipping, buffering or "correcting" of any line, no added connector.
- **Allowed without asking (our reading, to be confirmed by question 1 below):** a lossless change of container
  (shapefile to GeoJSON or another text/binary format) with the coordinates written at the source precision; lossless
  compression (gzip or brotli by the host, deflate inside the APK and the iOS bundle); a script that only *selects* whole
  features. Not allowed: a re-projection that moves the numbers (check the shapefile's `.prj`; if it is not WGS 84,
  ask first), dropping vertices, rounding coordinates.
- The base map's own boundary lines near India are **not** SoI data, so the app may hide them (as it does now by rule,
  [03](../03-design.md) ADR-22); with SoI's line as the one line, the "shared stretch" machinery is not needed and is
  retired rather than adapted.
- SoI's data in the repository is **not** under the AGPL: `NOTICE` and the app's About page say so, name the Survey of
  India, and keep the conditions above next to the file.

### Reading the reply against the 2021 guidelines (the DST PDF the owner supplied, 2026-10-01)

The PDF is the same text that [03](../03-design.md) §11.1 already certifies against (clause numbers as in the PDF, section 8).
Read next to the reply it says:

| Reply says | The guidelines say | What follows for Doorprints |
|---|---|---|
| No prior permission is needed (for the Administrative Boundary Database) | 8(ii)(1): "no requirement for prior approval, security clearance, license or any other restrictions" on the preparation, dissemination, publication and updating of geospatial data and maps, and people "shall be free to ... build applications and develop solutions ... by way of selling, distributing, sharing ... publishing"; 8(xii): the Survey of India is to simplify procedures and abolish licence forms; 8(xi): public-funds data is to be accessible "without any restrictions on their use" to Indian entities | The reply and the guidelines agree; the answer to the letter's question is yes. We are an *Indian Entity* (8(vii)(f): an Indian citizen), which 8(xi) names |
| No alteration or modification | 8(xiii): "For political Maps of India ... SoI published maps or SoI digital boundary data are **the standard** to be used, which shall be made easily downloadable for free and their **digital display and printing shall be permissible**. Others may publish such maps **that adhere to these standards**." | "Adhere to the standard" is the sense of the reply: a map of India's boundary that departs from SoI's line is not within the standard. So the safe reading of "no alteration" is: show SoI's line exactly, do not edit its geometry. Display on a screen at different zooms is the permitted use named in 8(xiii); a lossless change of file format is the kind of "useful format" 8(xii) asks the Survey of India to offer. Neither is stated outright, which is why the optional follow-up letter (S4b-BL-112) asks |
| Due acknowledgement | Not in the 2021 guidelines | A condition of the reply alone; P5 supplies it |
| Subject to the National Geospatial Policy 2022 guidelines | **Not in this PDF.** This PDF is the 2021 guidelines; the 2022 policy and its guidelines are separate documents | P2 must read those before the build; this PDF cannot stand in for them |

Other clauses that were checked for this use and need no action: the accuracy thresholds in 8(iv)(a) (1 metre horizontal) matter only for data *finer* than the threshold (8(vii), (viii), (ix): Indian entities, storage in India); the 1:1M boundary database is far coarser, so none of that applies, but the app must not claim finer accuracy for the line. 8(iii) and its Explanation: there is "no negative list of prohibited areas"; a negative list of sensitive *attributes* may be notified by DST, so P2 also checks DST's site for it before release. 8(ii)(1) names self-certification, which is [03](../03-design.md) §11.1. Violations are "dealt with under the applicable laws" (8(xv)), and the Criminal Law (Amendment) Act, 1961 (a map of India not in conformity with the Survey of India's maps) still applies, as ADR-22 says.

### The plan

| Step | Who | What | Done when |
|---|---|---|---|
| P0 | Owner | Download OVSF/1M/7 as in *Steps* above (CAPTCHA and tick-box are yours), keep the ZIP outside the repository, and **attach it in a session**. Note the product's date/version on the portal page and the file name. Optionally send the follow-up letter below first (it settles questions 1-4; the safe default holds if you do not). | The ZIP is in the session |
| P1 | Claude | Inspect without changing anything: CRS (`.prj`), feature count, attributes, vertex count, bounding box, size; check the extent against ADR-22 (all of Jammu and Kashmir and Ladakh with the areas Pakistan and China hold, and all of Arunachal Pradesh, inside India) and measure the old Natural Earth outline's offset from it (RR-16 method, [02](../02-threat-model.md)). If SoI's data lacks something ADR-22 needs, say so; we do not add to it. Record the sha256 of the ZIP and of each extracted file. | A short findings note in [10](../10-sprint-log.md) S4b-BL-111 |
| P2 | Claude | Read the **National Geospatial Policy 2022** and its guidelines (the reply makes them a condition; the 2021 guidelines PDF does not cover them) and write a clause table (what applies to a free app showing a boundary, what we do) into [03](../03-design.md) §11.1. | The table is in §11.1 |
| P3 | Claude | Design change to ADR-22 (an ADR-22 amendment): draw SoI's external boundary, unaltered, at every zoom; hide the base map's country lines wherever they border India (by the tiles' own attributes); decide, with the owner, how the held-areas rule works without a derived polygon (for example, the mask is SoI's own India polygon used read-only as a filter, which question 2 below asks about). Size budget: lossless text of the whole-country outline plus state lines must fit the repository rule (few, small binaries) - measure in P1; if it is large, ship only the features needed (whole features, no editing) and say which. | ADR-22 amended; the owner agrees |
| P4 | Claude | Build: `web/scripts/geo/build_in_boundaries_soi.py` (selection and container change only, deterministic, prints the sha256 of what it read and wrote); `web/public/geo/in-boundaries.geojson` and its byte-identical Android copy (iPhone via the shared copy) replaced; the sha256 pinned in `IndiaBoundaryDataTest`; a test that the shipped coordinates are exactly the source's (a fixture of a few hundred vertices cut from the real file, listed in the test); the old Natural Earth script and `find_shared_stretches.py` removed with their SHARED lists; web `india-boundaries.ts`, Android `IndiaViewRules.kt` and the iPhone map follow the amended rules, with their tests. | Tests green on web, Android, iPhone compile |
| P5 | Claude, owner | **Acknowledgement:** "Boundary: Survey of India" (or the wording SoI prefers) in the map attribution on web, Android and iPhone (replacing "Natural Earth"), in the About page / `NOTICE` / README, and in the guide (four languages). **Re-run TC-M-25** on the live site and on phones, TC-M-26 after the web deploy, and the iPhone check on a device. | TC-M-25/26 pass; the owner signs off |
| P6 | Claude | Close the records: [02](../02-threat-model.md) RR-16 (offset gone), [03](../03-design.md) §11.1 and ADR-22, [06](../06-test-plan.md), [10](../10-sprint-log.md) S4b-BL-10 done and S4b-BL-50 closed, [14](../14-lead-backlog-and-handoff.md) §6 and N11, `CHANGELOG`. A standing item: when SoI publishes a revised boundary, repeat P1-P5 (add it to the release checklist). | Docs updated |

Order: after the stacked pull requests are merged and the manual list (N15) has started, because P3-P5 touch the same map
code on all three stacks. P0 can happen any time, and P1-P2 need no code.

### Follow-up letter (optional; the owner sends it, as the first letter; not sent by a session)

> **Subject:** Re: Request for permission to use the Administrative Boundary Database (OVSF/1M/7) in a free, open-source
> app (to `mtr.soi@gov.in`, copy the same officers)
>
> Dear Sir or Madam,
>
> Thank you for your reply of 1 October 2026. We will use the Administrative Boundary Database without alteration and
> will acknowledge the Survey of India on the map and in the app, and we will follow the National Geospatial Policy 2022
> guidelines. So that we do not alter the data by mistake, may we confirm four points?
>
> 1. Converting the shapefile to another file format (for example GeoJSON), with every coordinate and attribute kept as
>    published, and compressing the file without loss, is not an alteration. Is that right?
> 2. Using only whole features of the database (for example the outline of India and the state boundaries), and
>    using the India polygon only to decide where the app hides other maps' lines, without changing the polygon, is not an
>    alteration. Is that right?
> 3. A map library draws the data at lower detail when the map is zoomed out (it does this on the screen only; the
>    file is not changed). Is that acceptable?
> 4. Is "Boundary: Survey of India" on the map and in the app's About page the acknowledgement you want, and should it
>    name the product (OVSF/1M/7) and the version or date of the data?
>
> Yours faithfully,
> [name, address, phone, date]

Until an answer comes, the safe default above holds (container change and lossless compression only; whole features
only; acknowledgement as in P5).

## Draft

> **Subject:** Request for permission to use the Administrative Boundary Database (OVSF/1M/7) in a free, open-source
> app (to `mtr.soi@gov.in`)
>
> To
> The Director
> NGDR & UGI Directorate, Survey of India
> Hathibarkala Estate, New Cantt Road
> Dehradun, Uttarakhand 248001
>
> Dear Sir or Madam,
>
> I am [YOUR FULL NAME], an Indian citizen, and I maintain Doorprints, a free, non-commercial app that helps a person keep a
> private record of the houses they visit while looking for a home to rent or buy in India. It runs as a web app at
> https://doorprints.web.app and as an Android app (package app.doorprints, not yet on Google Play); an iPhone
> version built from the same code is in progress. Its source code is public at
> https://github.com/Sriram-Codes-SW/doorprints. It has no advertising and collects no data for itself.
>
> The app's map, on the web and on Android, shows India's external boundary as the Government of India depicts it:
> all of Jammu and Kashmir and Ladakh, including the areas under the occupation of Pakistan and China, and all of
> Arunachal Pradesh are inside India, with no other line drawn. Today the outline comes from a public-domain world
> dataset, which is correct in extent but about 1.5 km off the true line in mountain areas. Screenshots of the web
> map are attached.
>
> Under clause 8 (xiii) of the Department of Science & Technology's Guidelines for acquiring and producing Geospatial
> Data and Geospatial Data Services including Maps (F.No.SM/25/02/2020 (Part-I), 15 February 2021), the Survey of
> India's published maps and digital boundary data are the standard for political maps of India, and others may
> publish maps that adhere to that standard. Clause 8 (ii)(1) allows individuals to build applications with
> geospatial data and to publish and distribute them.
>
> I would like to follow that standard exactly. May I have the Survey of India's written permission to:
>
> 1. use the Administrative Boundary Database for the entire country up to district level (product OVSF/1M/7, 1:1M),
>    downloaded free of charge from the Online Maps Portal, to prepare the outline of India's external boundary, and where needed state boundaries, shown on the
>    app's map; and
> 2. include that outline, as a small data file inside the web app, the Android app, the iPhone app and their public
>    source code, so that every copy of the app shows the same boundary, with the credit "Boundary: Survey of India"
>    (or any wording you prefer) on the map?
>
> The app will not sell, license or offer the Survey of India's data on its own, and will update the outline
> whenever the Survey of India publishes a revised boundary. If a different product or a formal licence is the right
> route, I would be grateful to be told which.
>
> Yours faithfully,
> [YOUR FULL NAME]
> [POSTAL ADDRESS]
> [PHONE]
> [DATE]

This draft records the owner's request, not legal advice.
