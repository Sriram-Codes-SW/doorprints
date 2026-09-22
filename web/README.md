# House Hunt — web

Angular 22 single-page app for the House Hunt API: a map + list of houses, house details (checklist,
rating, photos, visits) and a side-by-side comparison. Uses MapLibre GL with the free
[OpenFreeMap](https://openfreemap.org) "liberty" style (no API key).

## Develop

```bash
npm install
npx ng serve        # http://localhost:4200
```

On first open the app asks for your API base URL (e.g. `http://localhost:8080`) and API key. By default they
are kept only for this browser tab (sessionStorage); tick "Remember on this device" to keep them in
localStorage. The API must list the web app's origin in `APP_CORS_ORIGINS`.

## Optional AI features

When the server runs with `APP_AI_ENABLED=true` (see [docs/ai/ai-design.md](../docs/ai/ai-design.md)), the app
shows **Ask** (questions about your saved houses, with linked sources), **Plan visits** (an ordered walking route
on a map) and **Import from listing text** on the new-house form. With AI off (the default) these are hidden.

## Languages and accessibility

The UI is available in English, हिन्दी, தமிழ் and తెలుగు (switcher in the header; the choice is kept in
localStorage). Strings live in `src/app/i18n/` — `en.ts` is the source of truth and the build fails if another
language misses a key. The app targets WCAG 2.2 AA; see
[docs/05-ux-accessibility-i18n.md](../docs/05-ux-accessibility-i18n.md) for tokens, the checklist and how to add
a string or a language.

## Build

```bash
npm run build       # output: dist/web/browser
```

## Deploy (free static hosting)

The output in `dist/web/browser` is plain static files. Because it is a single-page app, unknown paths
must fall back to `index.html`:

- **Cloudflare Pages / Netlify** — build command `npm run build`, output directory `dist/web/browser`.
  `public/_redirects` (`/* /index.html 200`) is copied into the build and handles the SPA fallback.
- **GitHub Pages** — copy `index.html` to `404.html` so deep links work (`npm run build:pages` builds
  and makes that copy). If the site lives under a sub-path, build with that base href instead:
  `npx ng build --base-href /house-hunt/ && cp dist/web/browser/index.html dist/web/browser/404.html`.

`public/_headers` adds a strict Content-Security-Policy, HSTS and other security headers on Cloudflare Pages and
Netlify. GitHub Pages cannot send headers; prefer Cloudflare Pages if you can.

Remember to add the deployed origin (e.g. `https://house-hunt.pages.dev`) to the API's
`APP_CORS_ORIGINS`, and use an HTTPS API URL — browsers block calls from an HTTPS page to an HTTP API.

Address lookup ("Fill address from map") uses the public OpenStreetMap Nominatim service and is only
called when you press the button, in line with its usage policy.
