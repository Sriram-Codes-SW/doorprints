Work in progress and helper scripts from the session of 2026-10-10. Not source code; no CI runs on this branch.
- kinds-pr2-backend.patch: the half-built server part of kinds step 2 (S4b-BL-206). Apply with: git apply --3way wip/kinds-pr2-backend.patch
- resolve-docs.py: resolves the docs version-row conflicts when merging main into a pull request branch (check for duplicated S4b-BL rows afterwards).
- eval-stage*.sh, wait-pr.sh, report.sh: sequencing the Vertex eval runs and reading their reports (they call gh api; run them as tracked background tasks).
- voice-consult.md, ai-reliability-consult.md: the Fable consults of that day.
- live-ui-landscape.js: the live UI test limited to a few viewports (copy of tools/live-ui/live-ui.js with a filter).
The hand-off itself is docs/14 section 11. Delete this branch when it is no longer needed.
