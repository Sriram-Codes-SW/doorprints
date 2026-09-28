# Request to the Survey of India: using its boundary data in Doorprints (owner's draft)

| Field | Value |
|---|---|
| Document | Draft letter from the owner to the Survey of India, and how to send it |
| Version | 0.1 |
| Date | 2026-09-28 |
| Author | Claude (Code), lead |
| Status | Draft for the owner; not sent |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
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

## How to send

- Find the contact on the portal's FAQ page (`https://onlinemaps.surveyofindia.gov.in/FAQs.aspx`) or the Survey of
  India's contact page, and check the current address before sending. Keep a copy of what was sent and the date.
- Fill in the bracketed parts. Send from the owner's own address; no project account exists.
- Record the answer in [10](../10-sprint-log.md) S4b-BL-10. A yes, or a licence that clearly allows it, starts the
  rebuild described there (and TC-M-25 again); a no keeps Natural Earth, with SoI's data as the reference only.

## Draft

> **Subject:** Request for permission to use the Survey of India's Administrative Boundary Database in a free,
> open-source app
>
> Dear Sir or Madam,
>
> I am [name], an Indian citizen, and I maintain Doorprints, a free, non-commercial app that helps a person keep a
> private record of the houses they visit while looking for a home to rent or buy in India. It runs as a web app at
> https://doorprints.web.app and as an Android app. [Only if the repository is public: Its source code is public at
> https://github.com/Sriram-Codes-SW/doorprints.] It has no advertising and collects no data for itself.
>
> The app's map shows India's external boundary as the Government of India depicts it: all of Jammu and Kashmir and
> Ladakh, including the areas under the occupation of Pakistan and China, and all of Arunachal Pradesh are inside
> India, with no other line drawn. Today the outline comes from a public-domain world dataset, which is correct in
> extent but about 1.5 km off the true line in mountain areas.
>
> Under clause 8 (xiii) of the Department of Science & Technology's Guidelines for acquiring and producing Geospatial
> Data and Geospatial Data Services including Maps (F.No.SM/25/02/2020 (Part-I), 15 February 2021), the Survey of
> India's published maps and digital boundary data are the standard for political maps of India, and others may
> publish maps that adhere to that standard. Clause 8 (ii)(1) allows individuals to build applications with
> geospatial data and to publish and distribute them.
>
> I would like to follow that standard exactly. May I have the Survey of India's written permission to:
>
> 1. use the Administrative Boundary Database (whole country, district level, 1:1M), downloaded from the Online Maps
>    Portal, to prepare the outline of India's external boundary, and where needed state boundaries, shown on the
>    app's map; and
> 2. include that outline, as a small data file inside the app and its public source code, so that every copy of the
>    app shows the same boundary, with the credit "Boundary: Survey of India" (or any wording you prefer) on the map?
>
> The app will not sell, license or offer the Survey of India's data on its own, and will update the outline
> whenever the Survey of India publishes a revised boundary. If a different product or a formal licence is the right
> route, I would be grateful to be told which.
>
> Yours faithfully,
> [name]
> [address, email, phone]
> [date]

This draft records the owner's request, not legal advice.
