# Security policy

Doorprints handles personal data (the houses you visit and your location), so security reports are welcome.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository.

Include what you found, how to reproduce it and the impact you expect. You'll get an acknowledgement within 7 days.

## Scope

- Android app (`android/`), web app (`web/`), API (`backend/`), CI workflows (`.github/`).
- Out of scope: third-party services (Google, Gemini, OpenFreeMap, Nominatim) and self-hosted deployments with modified code.

See `docs/02-threat-model.md` for the current threat model and known findings.
