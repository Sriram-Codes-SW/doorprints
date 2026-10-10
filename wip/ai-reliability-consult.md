# Reliable AI in Doorprints: Fable strategy consult (2026-10-10), saved for the builder

Facts checked by the consult against origin/main (it could not run Kotlin, Maven or any model): golden set v0.9 has 34 extract / 31 ask /
10 plan cases; 25 injection and 5 refusal cases; 217 expected extraction fields, 35 null-expected, 26 expected ask citations.

## 1. Reliability contract (what already holds, what to add)

| Feature | Must always hold (already enforced) | Gaps |
|---|---|---|
| Extract | Phone/URL only if present in the pasted text; price/BHK/type parsed and clamped; every correction in `warnings`; empty model output gives an "Untitled listing" draft, never a crash (`sanitizeDraft`, `DraftSanitizer`); never saved automatically; typed fields never overwritten (`mergeListingDraft` fills empty only and reports `kept`; Android `HouseFormRules` the same) | (a) no per-field "AI-filled" marker after the banner is dismissed; (b) no undo of a fill (the web has none); (c) notes are APPENDED on every Extract run, so a second run duplicates text unless identical; (d) the web Gemini path has NO timeout (`GeminiChatModel` uses Angular `HttpClient` without an `AbortSignal`) while `postAiJson` (other kinds) and Kotlin `postAi` have 60 s; (e) no retry on 503/429 on the device (the server has AI_MAX_RETRIES=2); (f) no "offline" wording beyond status 0 |
| Ask | Citations only from houses sent to the model, inline markers authoritative, a refusal has no citations (`citations()`, `RagService.citations`); contacts redacted before sending; links not in the context removed (`cleanAnswer`); no houses gives a refusal without a call | (g) `grounded` is derived from "has citations", not from checking the claim against the notes: a confidently wrong CITED sentence passes every guard. Show the snippet beside each claim, word the UI "the notes say", not "the answer". (h) no "N houses are not indexed" notice when server reindexing fails |
| Plan | Stops are saved candidate ids, unique, at most maxStops; legs recomputed locally; unusable output gives the nearest-neighbour fallback, flagged (`assemblePlan`, `VisitPlannerService.assemble`, 50 km cap) | (i) the fallback reason is a boolean plus a fixed summary: "model unavailable" and "model ignored your constraint" look the same; (j) server plan latency 44-81 s on the free tier vs 60 s client timeouts: show the deterministic order first, replace when the model answers |

Small product additions (no live cost): one timeout on the web Gemini path; ONE idempotent "Fill in" (replace, not append, the AI-added notes block,
tagged with a marker line); a per-field dot "from listing" until the field is edited; an "Undo fill" that restores the pre-fill snapshot.

## 2. Making the evals discriminative

(a) Validity canaries: a text file docs/ai/evals/canaries.json (about 10 KB, no binaries) of degraded configurations, each with a metric that MUST fall:

| Canary | Mechanism | Must drop |
|---|---|---|
| no-sanitizer | eval flag bypasses DraftSanitizer | hallucination (planted phone/URL) |
| no-citation-filter | bypass the inline-marker rule | citationPrecision |
| prompt-without-rules | system text minus the data/instruction rule | injectionResistance |
| prompt-swap | the Ask prompt sent to Extract | fieldAccuracy to near 0 |
| shuffled-expectations | expected values of case i scored against case i+1 | fieldAccuracy < 0.3 |
| always-refuse | stub model returning the refusal | citationRecall 0, refusal 1.0 |

The first three need one live run each; the last three run against the fake provider in CI for free and prove the SCORER can fail. A separate workflow
input suite=canary, result line `CANARY: expected drop seen / not seen`, never counted in the gated verdict.

(b) Wilson 95% intervals on every metric line. Current lower bounds at 1.00: injection 25/25 gives 0.87; plan validity 10/10 gives 0.72; refusal 5/5
gives 0.57; hallucination 0/35 means a rate of at most 0.10 (rule of three 3/35 = 0.086); 100/100 plan trials gives 0.963; fields 217/217 gives 0.983.
Trials to detect a 10-point drop (80% power, one-sided alpha 0.05): any metric at 1.00 (validity, injection, refusal, hallucination) 1.00 to 0.90 needs
16 trials; fieldAccuracy 0.95 to 0.85 needs 44 independent fields (about 34 cases, correlated, so 2 passes); citationPrecision/Recall 0.90 to 0.80 needs
69 citations (3 ask passes); answerCorrectness 69 cases (3 ask passes); agentNoFallbackRate 0.80 to 0.70 needs 109 plans (11 plan passes; the 0.80
threshold is unmeasurable at 10 cases).

(c) Cases with the most signal per rupee (about 15, each a one-call case; keep the set under 100): Extract: two listings in one paste (expect the first
only plus a warning); "rent 28k, deposit 2 lakh, maintenance 3k" (price must be 28000 only); sqft in Hindi/Tamil numerals; "sale 1.2 Cr / rent also
possible 45k" (priceType null plus a warning); a Hinglish WhatsApp forward. Ask: multi-hop ("which visited house under 30k has parking") across 2
houses; temporal ("visited before the Powai one"); near-miss refusal (a fact about a DIFFERENT house of the same locality). Plan: two constraints that
cannot both hold (expect the model says so, minStops 0); two houses in the same lane (a tie: either order valid, set check only). Abuse/blocked: 3
informational (S4b-BL-235).

(d) Paired design: same case, same trial index, A vs B; count discordant pairs only (McNemar / sign test). With 75 cases, 8 discordant pairs skewed 7:1
is significant at 0.05. ai-eval-compare.mjs already prints differences: add a discordant-pair count and a verdict line.

## 3. Determinism

Field-level agreement for Extract: compare only the structured fields (price, priceType, bedrooms, areaSqft, locality, street, contactPhone,
listingUrl) after EvalScorer.normalize (28000 == 28,000 by value; case/punctuation folded; phone last 10 digits; amenities as a SET); report
label/notes agreement separately (informational). Agreement.extractKey keys the whole draft, which is why 48 of 68 pairs differ (free text dominates).
Sensible gates once measured: structured-field agreement >= 0.95 per field, >= 0.90 for the whole structured tuple; ask citation-set agreement >= 0.95
(now 30/31); plan stop-set >= 0.90 (now 9/10). Seed: Gemini generationConfig.seed exists on the REST API (verify S4b-BL-228); use it in evals only.
Lower thinking (LOW) for Extract is cheaper and probably as consistent: judge it with the paired design, one run. No two-call self-consistency in the
app (doubles the user's cost); instead use the no-AI regex parser (already present for area) and flag a DISAGREEMENT between the regex price and the
model price as a warning: zero cost, catches the high-stakes field.

## 4. Release gate (before AI is announced or on by default)

3 consecutive default runs green with the intervals printed; the canary suite shows every expected drop; structured-field agreement measured and >= 0.90;
the web Gemini timeout fixed; failure states (timeout, 429, blocked, offline) checked in all 4 languages; privacy re-check (T-I43..48, redaction live
tests); the cost sentence in Settings quoting a measured tokens-per-call; no field overwrite (unit test exists).

## 5. Roadmap from the consult

| # | Item | Effort | Live cost |
|---|---|---|---|
| 1 | Wilson intervals + discordant-pair verdict in the scorer/compare | S | 0 |
| 2 | Keyless canaries (swap/shuffle/stub) | S | 0 |
| 3 | Web Gemini timeout; idempotent fill; undo | S/M | 0 |
| 4 | Structured-field agreement metric | S | 0 |
| 5 | Regex-vs-model price disagreement warning | S | 0 |
| 6 | 15 new cases | M | about 50-100 INR per full Vertex run (about 340k tokens, an estimate) |
| 7 | Live canaries + Extract LOW-thinking paired run | S | 2 extract runs |
| 8 | Provider probe on free Groq/OpenRouter (NOT possible now: Vertex only) | M | 0 |

## 6. What evals cannot show, and local signals

Cannot show: real listings, real users' questions, whether a right-looking cited answer misleads, latency on real networks, Kotlin/Compose behaviour, the
own-provider adapters on hosted models. Local, device-only, owner-visible counters (no telemetry, in Settings > AI, with a reset): per feature calls /
failures by kind (timeout, 429, blocked, unreachable) / fallbacks; Extract fields filled vs edited within 2 minutes (the price edited-after-AI rate is
the real accuracy); Ask followed by "not helpful" or an immediate rephrase; Plan stops removed before saving; p50/p95 latency.
