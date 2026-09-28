# Request to the Survey of India: using its boundary data in Doorprints (owner's draft)

| Field | Value |
|---|---|
| Document | Draft letter from the owner to the Survey of India, and how to send it |
| Version | 0.4 |
| Date | 2026-09-29 |
| Author | Claude (Code), lead |
| Status | **Sent** by the owner on 2026-09-28 22:11 UTC (29 September, 03:41 IST) to `mtr.soi@gov.in`, with the three web map screenshots; awaiting the reply |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
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
