# S4b-BL-121 privacy page — notes (branch feat/privacy-page)

## Decisions
- Four languages, because about.html is multilingual: full English section (governs), shorter hi/ta/te sections
  covering every point, headings marked *under review* like about.html, each saying the English text applies on a
  difference. Limited Use sentence kept verbatim in English (lang="en") in every section.
- Same pattern as about.html: inline CSS tokens, light/dark via prefers-color-scheme, no script, no external
  stylesheet/font, works without JS. In-page language nav (#h-hi/#h-ta/#h-te) plus a link to about.html.
- Contact: no email exists in any published page, so none added; contact = GitHub issues page and GitHub's private
  vulnerability reporting (as SECURITY.md).
- Honest about status: "Google Drive backup is being added to Doorprints now; this page already says how it works."
- Also discloses the other third parties (OpenFreeMap, Nominatim, phone geocoders, optional Gemini, Firebase Hosting):
  covers PRV-007's "web privacy page backlog".
- Limited Use: also says not used to develop/improve/train AI/ML models, no human reads it.
- Website deletion L2/L3 only with PRF passkey, otherwise "use your phone" (docs/15 §10.1).
- Retention: 7 daily / 4 weekly / 6 monthly, older to Drive bin (30 days) (docs/15 §1.4 items 4-5).
- Children: made for adults; collects no one's data.
- Changes: date at top + link to the file's GitHub history.
- In-app Settings link: NOT added. No privacy link slot exists (web Your data > About has only Source and Licence;
  Android/iOS LegalNotice.kt likewise); adding one needs new i18n keys in 4 languages and screenshot changes.
- Play data-safety mapping: NOT put on the page (risk of mis-stating Play's classification); left to the owner.
- Linked from: landing page (index.html app-root list), about.html (top bar + each language section), sitemap
  (priority 0.5, lastmod 2026-10-02), robots.txt comment.

## Doc rows to add (later pass)
- docs/10 S4b-BL-121: Open -> Done (or Part: in-app links and Play mapping open).
- docs/15 change log + §2.3 ("the website has none yet" -> privacy.html exists), §2.4 checklist note.
- docs/13 I17: page exists; consent screen link still the owner's.
- docs/01 PRV-007: web privacy page now exists (disclosure of third parties).
- docs/06: test row for the seo.spec privacy.html block (TC for S4b-BL-121); docs/14 §2 N17 progress.
- CHANGELOG Unreleased: "A privacy policy page at /privacy.html, in four languages".
- guide settings-and-privacy.md: link to the privacy policy.
- docs/05 (i18n: hi/ta/te privacy text under review), docs/12 if brand-word list tracks pages.

## Open questions / owner checks
- Owner signs off the text (ticket says so).
- Google consent screen (Branding): privacy policy URL https://doorprints.web.app/privacy.html; home page link;
  a user support email is still required by Google there (the page itself names none).
- Play data-safety form mapping: owner to decide; the page states no data reaches the developer.
- Whether house data that came from Drive and is then sent to Gemini (AI on) counts as a "transfer" under Limited Use;
  AI is opt-in and user-initiated; worth a reviewer's look.
- Follow-ups: in-app "Privacy policy" link in web Your data > About and Android/iOS Settings > About (new ticket).
