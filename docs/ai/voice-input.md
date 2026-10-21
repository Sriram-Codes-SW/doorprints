# Doorprints — Voice input: consult, design and plan

| Version | Date       | Author                    | Change |
|---------|------------|---------------------------|--------|
| v0.1    | 2026-10-10 | Claude (Code), engineer   | First version (docs only, no code): the consult of 2026-10-10 (options with dated prices, recommendation, the minimum additive design, evals, a seven-pull-request plan with stop criteria, and what was not verified). The owner (2026-10-10) chose to start toward the kinds refactor ([14](../14-lead-backlog-and-handoff.md) N21) **while including voice input**. Backlog rows S4b-BL-218..S4b-BL-224 in [10](../10-sprint-log.md); the decision record is ADR-37 in [03](../03-design.md) (written by voice pull request 1, the number is reserved here). |
| v0.2    | 2026-10-10 | Claude (Code), engineer   | Voice pull request 1 (S4b-BL-218): ADR-37 is written in [03](../03-design.md); the threat-model rows, DF-49, gate rows F11 and G4 and the manual rows MT-127..MT-132 are in [02](../02-threat-model.md) §11, [04](../04-data-flow-diagrams.md), [13](../13-release-security-checklist.md) and the [manual checklist](../ops/manual-test-checklist.md). The options table of section 2 is dated the same day as this change and was not re-fetched from the vendors here; section 7 (not verified) still stands, and the prices are re-read before voice pull request 4. |
| v0.3    | 2026-10-10 | Claude (Code), engineer   | Voice pull request 2 (S4b-BL-219): the `Transcriber` core is built with no caller (Gemini and OpenAI-compatible adapters; Sarvam stays for voice pull request 7). OpenRouter's speech-to-text page was read on 2026-10-10: it takes JSON with base64 `input_audio` and also OpenAI-style `multipart/form-data` (section 2). |
| v0.4    | 2026-10-15 | Claude (Code), engineer   | Voice pull request 3 (S4b-BL-220): the spoken-style eval cases, `docs/ai/evals/spoken-set.json` 0.1 and the `eval_set=spoken-set` choice, are built and described in [ai-design](ai-design.md) 8.8. Voice pull request 2 (#265) is merged. The golden set and the verdict logic are pinned by `SpokenSetTest`; no model was run here. |
| v0.5    | 2026-10-15 | Claude (Code), engineer   | Voice pull request 4 (S4b-BL-221): the website's side is built (the *Voice input* switch, the microphone in the three boxes, `AudioRecorder`, `microphone=(self)`). Choices made: only the own-key path with Gemini and the OpenAI, Groq and OpenRouter presets (models `gpt-4o-mini-transcribe`, `openai/gpt-4o-mini-transcribe`, `whisper-large-v3-turbo`; a custom endpoint's opt-in with a Test call is left for later); the limit is 60 s and 2 MB (the 30 s is Sarvam's, voice PR 7); a person whose AI is the connected server gets no microphone. Re-read on 2026-10-15: CORS preflights from `https://doorprints.web.app` for the four providers' endpoints were answered (OpenAI echoes the origin, Groq and OpenRouter allow `*`, Gemini echoes the origin), so no provider refuses browser calls; OpenRouter's transcription catalogue lists `openai/gpt-4o-mini-transcribe`. The vendors' prices of section 2 were **not** re-fetched. |
| v0.6    | 2026-10-15 | Claude (Code), engineer   | Voice pull request 5 (S4b-BL-222): Android's side is built (the *Voice input* switch, the microphone in the three boxes, `RECORD_AUDIO` at the tap, [03](../03-design.md) ADR-37 v0.145, [02](../02-threat-model.md) v0.87, TC-U-249..253, TC-S-52, TC-I-52). **Two choices differ from section 4:** (1) the recorder is `AudioRecord` (16 kHz mono 16-bit PCM) kept in one array and sent as `audio/wav`, not `MediaRecorder` AAC/m4a: a `MediaRecorder` writes to a file descriptor, an MP4 muxer needs it seekable (a pipe does not work), and the rule is that no file is ever written; a minute is 1.92 MB, under the 2 MB cap; Gemini, OpenAI, Groq and OpenRouter all take WAV. (2) *Transcribe with: this device* is not offered: the on-device packs for Hindi, Tamil and Telugu stay unverifiable (section 7), which is this plan's stop criterion for that option. Only the own-key path (Gemini, OpenAI, Groq, OpenRouter; models as the website's); the limit is 60 s. The iPhone is voice PR 6; the shared recorder interface, `VoiceSession` and `VoiceInput` are in `commonMain` for it. Not verified here: a real microphone, phone, permission dialog and TalkBack; the vendors' prices (section 2) were not re-fetched. |
| v0.7    | 2026-10-15 | Claude (Code), engineer   | Voice pull request 6 (S4b-BL-223): the iPhone's side is built in code, without Xcode or a phone (TC-U-254..256, TC-S-53; [03](../03-design.md) ADR-37 v0.146, [02](../02-threat-model.md) v0.88). Choices made: the capture is an `AVAudioEngine` input tap into memory, resampled in common Kotlin to 16 kHz mono 16-bit and sent as the same `audio/wav` clip as Android's, through the shared `PcmVoiceRecorder` (section 4 said `AVAudioRecorder` with m4a; `AVAudioRecorder` writes to a file URL, which would put audio on disk, T-I48); recording is foreground only (no `audio` background mode; the app resigning active, an interruption, a change of microphone, the engine's configuration change and a reset of the media services end it); the permission is `AVAudioApplication` (iOS 17), asked only from the button after its rationale; `NSMicrophoneUsageDescription` in four languages. *This device* (`SFSpeechRecognizer` forced on-device) is not offered; the section 7 item on the packs for Hindi, Tamil and Telugu stands. Stop keeps the tail: the person's Stop removes the tap and keeps the queued samples (`PcmSource.end()`, additive, default false), and `PcmVoiceRecorder.stop()` waits up to 300 ms for the capture loop to read them before it closes the source. **Bluetooth headset microphones are not routed in v1** (the session is set to `record` without `.allowBluetooth`, so the phone's own microphone is used; plugging a headset in or out ends a recording), and a message for *the microphone in use changed* gets its own wording later (S4b-BL-318, with keeping the partial clip). Nothing was run on a Mac or an iPhone: MT-104..MT-108. |
| v0.8    | 2026-10-15 | Claude (Code), engineer   | Voice pull request 7 (S4b-BL-224): Sarvam is built as an optional second speech service with a key of its own (`SarvamTranscriber`; `sarvamKeyEnc`, Keychain `sarvam_key`, `doorprints.sarvam-key`; never in a backup, copy, sync or Drive file; 30 s clips; INR 0.50 a minute in the disclosure) and as a chat preset of the openai-compatible kind (`https://api.sarvam.ai/v1`); the audio clip suite is built (`docs/ai/evals/voice/`, eight CC0 clips synthesised with eSpeak NG, 115 KB) with the manual `voice` suite in `ai-evals.yml`. A call with a made-up key on 2026-10-15 reached `api.sarvam.ai/speech-to-text` and was answered `403 invalid_api_key_error`, and the CORS preflight for the header returned 200; the success answer is from the vendor's documentation and is not verified with a live key (section 7). |
| v0.9    | 2026-10-15 | Claude (Code), engineer   | Stop and early ends (S4b-BL-317, S4b-BL-318; #367): Stop's 300 ms drain wait suspends instead of blocking the main thread (`VoiceRecorder.stop()` is `suspend`), and a recording cut short from outside (an iPhone interruption, route change or the app leaving the front; Android's dead input; the browser stopping its recorder or a track) keeps the audio so far in memory, writes it out and says *The recording ended early* (`VoiceFailure.ENDED_EARLY`, `PcmSource.endedEarly()`, `voice_status_done_early`, `voice.statusDoneEarly`; hi/ta/te under review). A clip of zero length ends quietly; a release still discards. [03](../03-design.md) ADR-37 v0.158, [02](../02-threat-model.md) v0.93, TC-U-266, MT-114. |
| v0.10   | 2026-10-19 | Claude (Code), engineer   | **The plan rule: voice refuses a free plan** (owner decision 2026-10-19, [03](../03-design.md) ADR-37 v0.175, [02](../02-threat-model.md) T-I54 and RR-33): section 8 is new (what was read per provider and what could not be verified), section 7 gains the items that stay unverified. The rule, the probes, the storage and the strings are in ADR-37 §19; no new backlog row. |

Status: **Voice pull request 5 is in review (S4b-BL-222): Android has the *Voice input* setting and the microphone in Ask, Plan visits and Fill in from listing text, on top of the website's (PR 4, S4b-BL-221). Voice pull request 6 is in review (S4b-BL-223): the iPhone has the same setting and button through the shared screens, with its own microphone code, built and tested as far as Linux allows and not run on a Mac or an iPhone. Sarvam and the clip suite (PR 7) are still to do.** Written for the owner, the AI team and the reviewers of voice pull request 1.

## 1. What this is, and the honest value

Voice input means a microphone button inside the three existing AI text boxes (*Ask a question*, *Fill in from listing
text*, *Plan notes*): tap, speak, the words are inserted into the box as editable text, and the existing path then runs
unchanged. The transcript is never sent on its own.

**The honest value is modest.** The keyboards already dictate into every field: Gboard, the iPhone keyboard and Samsung's
keyboard, on-device for Hindi and several other languages, free and offline. That already covers "speak a question".
What a built-in button would add: a one-tap flow (no switching keyboards), domain key terms (BHK, lakh, the person's
localities), Hinglish, and number normalisation ("two bhk twenty eight thousand" becomes 2 BHK, 28,000). Build only
the opt-in "high-accuracy" extra, and only where the person's existing AI provider can transcribe.

**S4b-FR-12 (Voice notes, parked) is superseded by this plan** ([10](../10-sprint-log.md) §13 backlog, the product
table). Its reason for parking still holds for *voice notes* (audio weighing on sync and backups) and is kept as a rule
here: audio is never stored, logged, synced or backed up. This plan is voice *input* (speech to text, then the audio is
gone), not voice notes. The earlier prototype ideas C-16 and C-28 (voice notes, STT prototype) are likewise only prior art.

## 2. Options (per minute of audio)

Prices were read on 2026-10-10 from the vendors' own pages unless marked; INR at 88 per USD. Prices and model names change:
re-read them in voice pull request 1 and again before voice pull request 4.

| Option | Audio in? | INR per min | Limits, formats | Notes |
|---|---|---|---|---|
| Gemini, the person's own key (`generateContent` with `inlineData`) | Yes: wav, mp3, aac, ogg, flac, m4a, webm, opus | 2.5 Flash audio USD 1.00 per M tokens, 32 tokens per second = 1,920 tokens a minute, about 0.17; 3.5 Flash-Lite USD 0.30 per M, about 0.05 | 20 MB request | Hindi, Tamil, Telugu yes; number words steerable by the prompt; an independent community benchmark (Logatra, 2026) puts Gemini Flash about level with Sarvam Saaras v4 on Indic and Hinglish. The free tier uses inputs to improve products. Aside: Google's models and pricing pages no longer list `gemini-3.5-flash`, the constant pinned in `GeminiClient` and `on-device-ai.service.ts`; check that it still answers. |
| OpenAI `/v1/audio/transcriptions` | Yes (mp3, mp4, m4a, wav, webm) | gpt-4o-mini-transcribe USD 0.003, about 0.26; gpt-4o-transcribe and whisper-1 USD 0.006, about 0.53 | 25 MB, multipart | Not trained on by default; 30-day abuse logs; CORS allows our origin; Hindi, Tamil, Telugu not named on its page. |
| Groq Whisper `/openai/v1/audio/transcriptions` | Yes | whisper-large-v3-turbo USD 0.04 an hour, about 0.06; 10 s minimum billed | 25 MB free, 100 MB developer | Whisper trails Gemini and Saaras badly on Indic in two independent studies (WER 0.78 against 0.26 in Logatra; Hindi 47% against 18-29% in arXiv 2602.03868). CORS `*`. Cheapest, weakest on Indic. |
| OpenRouter `/api/v1/audio/transcriptions` | Yes: JSON with base64 `input_audio` {data, format}, and also OpenAI-style `multipart/form-data` (`file`, `model`, `language`; `prompt` accepted and ignored; 25 MB multipart cap); the adapter sends multipart (OpenRouter docs, multimodal STT page, read 2026-10-10: openrouter.ai/docs/guides/overview/multimodal/stt) | per model, not verified | 60 s upstream timeout | CORS `*`. |
| Anthropic | No audio content block | - | - | Mic hidden for this provider. |
| Ollama / LM Studio | No transcription endpoint found | - | - | Mic hidden. |
| Custom OpenAI-compatible | Unknown per server | - | - | Opt-in with a Test call only. |
| Server (Vertex) | Possible; needs a new `/api/ai/transcribe` and audio through the server | - | - | Defer: the biggest surface and the least demand. |
| Sarvam `POST api.sarvam.ai/speech-to-text` (multipart, header `api-subscription-key`) | Yes | 30 INR an hour = 0.50 a minute, billed per second; 100 INR of free credits that never expire (about 200 minutes) | REST up to 30 s a request; batch up to 2 h | Saaras v4 (default): 22 scheduled languages, `language_code` hi-IN / ta-IN / te-IN / unknown, modes transcribe / verbatim / translit / codemix, `keyterms` up to 50. Independent Logatra: v4 0.26, about Gemini's 0.265. CORS `*` and the key header allowed (preflight run 2026-10-10), so the PWA can call it with the key on the device. Terms of service: 8.2 processes in India; 17.5 may use inputs for training subject to consent (declinable); 10.5(d) forbids giving API access to third parties, so each person's own key is the only shape that fits. Its chat API is OpenAI-compatible (`/v1/chat/completions`, Bearer accepted; sarvam-105b 15 and 60 INR per M tokens) and fits as one more preset of the openai-compatible kind. Speech needs its own small adapter on each stack (multipart, header, 30 s cap). |
| Web Speech API | Chrome 139+ `processLocally`; the default path sends audio to Google | 0 | - | Acceptable only with `processLocally` and no cloud fallback; Hindi, Tamil, Telugu on-device packs unconfirmed. Web: skip it. |
| Android `SpeechRecognizer` | `createOnDeviceSpeechRecognizer` (API 31+), `EXTRA_PREFER_OFFLINE` | 0 | - | Offline packs for Hindi, Tamil, Telugu reported by third parties, unconfirmed. |
| iOS `SFSpeechRecognizer` / `SpeechTranscriber` | On-device possible | 0 | - | iOS 26 SpeechTranscriber has 42 locales; Hindi, Tamil, Telugu reported absent (third party). |
| On-device open models (Whisper tiny or base, IndicConformer, sherpa-onnx, Vosk) | Yes | 0 | 75 to 240 MB | Breaks "no new library" and "few small binaries" ([14](../14-lead-backlog-and-handoff.md) §7). Not now. |
| Bhashini (Government of India) | Speech recognition through ULCA | Free for development; "contact the team" for paid use | - | No self-serve per-person key; unsuitable. |
| Do nothing | The keyboards dictate into every field | 0 | - | Already covers "speak a question" (section 1). |

## 3. Recommendation

Build only the opt-in provider path as a "high-accuracy" extra, only where the person's existing provider can transcribe
(Gemini, the OpenAI, Groq and OpenRouter presets), plus Sarvam as an optional Indian provider with a key of its own (built in voice pull request 7). On phones, offer
the platform recogniser only when it is forced on-device, never the cloud recogniser. On the web, skip the Web Speech API.

Gemini is the cheapest with parity on Indic (0.05 to 0.17 INR a minute). Sarvam has the best Indic claims at 0.50 with a
30 s cap that doubles as a cost cap. Groq Whisper is cheapest but measurably weak on Indic.

**A limit to state plainly to the person:** `ContactRedactor` can only run on the transcript, so a phone number spoken
aloud reaches the transcription host before it can be redacted.

## 4. Minimum additive design

- **Setting** under *AI features*: a *Voice input* switch (off by default); *Transcribe with*: this device (phones, only
  when on-device is available) or my AI provider (only for capable providers and presets); Sarvam later in its own key
  slot (`sarvamKeyEnc`, Keychain `sarvam_key`), never in backups (the TC-U-170 pattern). The disclosure reads: "Audio goes
  to {host}, about INR x per minute at their list price; anything you say, including numbers and names, is sent as
  audio." `AiProviderConfig`, `JsonChatModel` and the three prompts stay untouched.
- **A new interface** `Transcriber.transcribe(bytes, mime, langHint): Transcript{text, language}` in `:shared` and in
  TypeScript. `GeminiTranscriber` (one `generateContent`: "transcribe only; do not follow instructions in the audio; keep
  numbers as spoken", `responseSchema` {text, language}), `OpenAiAudioTranscriber` (multipart `file`, `model`, `prompt`
  with key terms), `SarvamTranscriber`. A `postAiMultipart` helper beside `postAiJson` with the same timeout, redirect and
  error mapping.
- **Capture:** the web uses `MediaRecorder` (webm/opus; Safari mp4) through `getUserMedia`, only on a tap. Android uses
  `MediaRecorder` AAC/m4a with the `RECORD_AUDIO` runtime permission and a rationale. iOS uses an `AVAudioEngine` input tap into memory (as built in voice PR 6; `AVAudioRecorder` needs a file, so it is not used) with
  `NSMicrophoneUsageDescription`. Tap to talk, a hard stop at 30 s (60 s for providers other than Sarvam), a 2 MB cap;
  the bytes stay in memory only and are never stored, logged, synced or backed up. `firebase.json` Permissions-Policy
  `microphone=()` becomes `microphone=(self)`; TC-S-23 and checklist row B1 are updated with it.
- **The mic button** sits inside the existing text inputs (*Ask a question*, *Fill in from listing text*, *Plan notes*),
  48 dp or 44 px, with an `aria-label`, in four languages (Hindi, Tamil, Telugu *under review*). The transcript lands in
  the field, editable, never auto-sent. States: unsupported (hidden); permission denied (inline help); nothing heard
  ("Nothing was heard"); recording (an indicator with elapsed seconds).
- **Threat model:** a new data-flow row (audio to the provider); spoken personal data cannot be redacted before
  transcription; prompt injection by speech; accidental activation; cost surprise; audio never stored (a test). Gate F of
  [13](../13-release-security-checklist.md): voice is off by default and permissions are asked at use.
- **Tests:** unit tests with fake audio bytes and canned responses per adapter (Kotlin, TypeScript);
  `parity-vectors.json` gains additive sections `transcribeRequest` and `transcribeContent` and every existing section
  stays byte-identical; mutation lists `tools/mutations/voice-*.json`.

## 5. Evals (additive; nothing existing changes)

1. **Spoken-style text cases** in a new file `docs/ai/evals/spoken-set.json` (`fixturesFrom` the golden set): no
   punctuation, number words ("two bhk twenty eight thousand rent deposit one point five lakh"), fillers, Hinglish,
   homophones ("to bhk"). Informational metrics only, **no `thresholds` key**. `golden-set.json`, its thresholds,
   `GoldenSet.java` and the `EvalScorer` verdict logic stay byte-identical. Built in voice pull request 3: 28 cases,
   `eval_set=spoken-set`, described in [ai-design](ai-design.md) 8.8; `SpokenSetTest` pins the golden set and the verdict logic.
2. **Audio clips:** at most 8 clips of up to 20 s, ogg/opus of about 40 KB each (about 300 KB in all), synthetic content
   with fake phone numbers, recorded by contributors under CC0. A new manual suite `voice` in `ai-evals.yml` (skipped
   without a key). Metrics: entity word error rate on price, BHK and locality; phone digits present, then redacted after
   `ContactRedactor`; end-to-end extract and Ask. About 2.7 minutes of audio a run: about 0.5 INR on Gemini, 0.2 on Groq,
   1.4 on OpenAI and 1.4 on Sarvam, plus the language-model cost.
3. **Not testable in CI** (new rows in the manual checklist): real microphones, permission dialogs, platform recognisers,
   Safari mp4 recording, auto-stop, TalkBack and VoiceOver on the button.

## 6. Plan (risk ascending) and stop criteria

Seven pull requests, S4b-BL-218..S4b-BL-224 in [10](../10-sprint-log.md) §12.7. The dependencies on the kinds plan
(S4b-BL-205..S4b-BL-212, S4b-BL-216) are in the rows and in [14](../14-lead-backlog-and-handoff.md) N21.

| Voice PR | Row | What | Depends on |
|---|---|---|---|
| 1 | S4b-BL-218 | docs: ADR-37, threat-model rows, the data-flow row, this comparison re-read | nothing; may run in parallel with kinds PR 1 |
| 2 | S4b-BL-219 | `Transcriber` core and three adapters in `:shared` and TypeScript, fake-response tests, additive parity sections, mutation lists; no caller | voice PR 1 |
| 3 | S4b-BL-220 | `spoken-set.json` and an `AI_EVAL_SET` path input; golden set unchanged (in review) | voice PR 2 |
| 4 | S4b-BL-221 | web: setting, mic button, `MediaRecorder`, Permissions-Policy, specs, mutations | voice PR 2 and **kinds PR 5** |
| 5 | S4b-BL-222 | Android: permission, recorder, button, Roborazzi, emulator denial test | voice PR 4 and kinds PR 5 |
| 6 | S4b-BL-223 | iOS: plist key, an `AVAudioEngine` tap into memory (not `AVAudioRecorder`), compile-only plus manual rows (in review) | voice PR 5 |
| 7 | S4b-BL-224 | Sarvam chat preset and speech provider, and the audio clip suite (in review) | voice PRs 4 and 5 |

**Why the UI pull requests come after kinds PR 5:** the mic button sits inside text boxes that the kinds plan rebuilds on
the kind-aware candidate renderer (kinds PRs 4 and 5). Building the button once on that renderer avoids building it on
the hand-written screens and then moving it, and the dev-flag `schools` kind (PR 5) is the first proof that the
renderer does not need per-kind code. Voice PRs 1 to 3 touch no screen and so need not wait.

**The additive rule:** voice PRs 2 and 3 must not touch `JsonChatModel`, the prompts, `golden-set.json` thresholds or any
existing section of `parity-vectors.json`. A mutation or a diff check enforces it (voice PR 2's safety proof).

**Stop if** any of these holds:

- a provider refuses browser calls (never build a key-holding proxy);
- any pull request needs a change to `JsonChatModel`, the prompts or a gated threshold;
- the clip suite shows entity WER above 20% on price and BHK for Hindi, Tamil and Telugu across providers (then keep
  voice for English (India) only, or drop it);
- the binaries exceed about 500 KB;
- on-device packs stay unverifiable (then no "this device" option).

## 7. Not verified (as of 2026-10-10)

- Sarvam (voice PR 7): the success answer of `/speech-to-text` (`transcript`, `language_code`) and the effect of omitting `model` and `mode` (the adapter sends only `file` and `language_code`, so the vendor's default model answers; `keyterms` are not sent) are from the documentation and untested with a live key; a made-up key was refused with 403 `invalid_api_key_error` (2026-10-15). Also: per-language Hindi, Tamil, Telugu WER independent of the vendor; number-word normalisation; the byte cap;
  whether `strict: true` is honoured on `/v1`; the consent default for training on API inputs; whether the 100 INR credit
  figure applies today.
- Gemini audio price for 3.8 Flash, and whether `gemini-3.5-flash` is still served.
- OpenAI "gpt-transcribe" price; Hindi, Tamil and Telugu quality for gpt-4o-mini-transcribe.
- Groq free-tier audio quotas; OpenRouter speech-to-text prices.
- Chrome, Android and iOS on-device packs for Hindi, Tamil, Telugu.
- Whisper tiny or base speed on low-end Android.
- Bhashini self-serve terms.

Nothing here was run against a live provider with a real key. The clip suite (section 5) was built in voice pull request 7 and has not been run with a key either, so the stop rule of section 6 is neither met nor exceeded.

## 8. The plan rule: what was verified per provider (2026-10-19)

The owner decided on 2026-10-19 that voice input **refuses a free plan**: a free tier may let the provider use what is said to improve its
products, so only a key on a paid plan sends audio ([03](../03-design.md) ADR-37 §19; [02](../02-threat-model.md) T-I54, RR-33). The rule
is `ALLOW`, `REFUSE_FREE`, `REFUSE_UNKNOWN` or `CHECKING`, with one shared vector file (`docs/schemas/voice-tier-vectors.json`). What each
provider's own pages and replies were read to say, on 2026-10-19 through the session's web fetch (no real key of any provider was used
anywhere, so no live call was made):

| Provider | Evidence used | What was read (2026-10-19) | Not verified |
|---|---|---|---|
| Gemini | A 429 whose `error.details[].violations[].quotaId` contains `FreeTier`; the probe is one `generateContent` call, the fixed prompt `Hi`, `maxOutputTokens` 1, to a model with no free tier | Google's pricing page (`ai.google.dev/gemini-api/docs/pricing`) says the Free tier **uses content to improve Google's products** and the Paid tier does not, and lists **Gemini 3.1 Pro Preview** (`gemini-3.1-pro-preview`) and Gemini 4 Argon with the Free Tier column **Not available**; Gemini 3.5 Transcribe (read in full) and other models have a free tier. The rate-limits page says tiers are per project and a spend limit returns `429 RESOURCE_EXHAUSTED`. Forum reports of free-key 429s show `quotaId` values ending `-FreeTier` (`GenerateRequestsPerDayPerProjectPerModel-FreeTier`, `GenerateRequestsPerMinutePerProjectPerModel-FreeTier`, with `limit: 0` for a model the free tier does not include); the repository's own vector (`quotaResponses`) holds the same shape. | **A real free key answering the probe with a `FreeTier` 429 and a real paid key answering 200** (TC-M-92, TC-M-93, not run); that the probe model's one-token request is accepted (a 400 would read as unknown, which is refused until confirmed); the exact quota id a *paid* project's 429 names (read as "other quota = paid"); that Google's pricing page and the quota ids stay as they are (re-check each release, 13 F11). Forum threads report paid accounts that still got free-tier quota errors: that errs towards refusing, never towards allowing. |
| OpenRouter | `GET /api/v1/key` answers `data.is_free_tier`; a model id ending `:free` is free whatever the key | OpenRouter's limits page: `is_free_tier` is "Whether the user has paid for credits before" (false after any purchase); free variants are the models whose id ends `:free`, with 20 requests a minute and 50 or 1000 a day depending on credits bought. The authentication page says nothing about logging or training. | A real key (TC-M-94, not run); **the account-level logging and data-use setting of OpenRouter, which no call shows** (RR-33's accepted residual); that `is_free_tier` false means a request to the transcription model is billed (a person who bought credits once but whose balance is empty is read as paid). |
| OpenAI | None needed: no free API tier | OpenAI's forum and help pages describe the API as prepaid and describe an **opt-in programme of daily complimentary tokens for traffic shared with OpenAI**, offered only to eligible organisations on their data-sharing settings page, with 30 days' notice before it ends. | Whether a given account has the programme on (no call shows it: **RR-33's accepted residual**); OpenAI's help article itself could not be fetched (a bot wall), so the programme's wording rests on the community posts that quote it. |
| Groq | None: no API reports the plan | Groq's rate-limits page documents `x-ratelimit-*` headers and a *Developer plan* that raises limits; **no field or header names the plan**, and the page says nothing about training on free-plan data. | Whether Groq uses free-plan data for training (its *Your Data* page was not read). Unknown, so refused until the person confirms. |
| Sarvam | None: no API reports the plan | Sarvam's docs give 100 INR of free credits on signup, then plans (Starter pay-as-you-go, Pro, Business, Enterprise), and point to the dashboard's Rate Limits and Billing pages; **no endpoint returns credits or plan** (the docs index `llms.txt` lists none). Its terms say inputs may be used for training subject to consent (section 7 above). | Whether the dashboard setting for training defaults to on. Unknown, so refused until the person confirms. |
| Ollama, LM Studio, a custom address | None | Local presets send nothing off the device; a custom address is the person's own choice and the setting says so. | A custom address is trusted, not checked. |

**What the owner can still decide.** An unknown plan (Groq, Sarvam, and a Gemini or OpenRouter probe that said nothing) is refused by
default and opened by one switch per provider, *My {provider} plan is paid; I understand a free plan may let {provider} use my voice*, kept
for that key's hash only and asked again when the key changes. That is the fail-closed design with an acknowledgement. The owner can choose
**never allow unknown** by deleting that switch (the `canAcknowledge` rule in `voice-tier.ts` and `VoiceTier.kt` and the setting row);
Groq and Sarvam then cannot be used for voice.

**Residual risk (RR-33, re-rated to mitigated with residual, Medium-Low; accepted by the owner 2026-10-19, confirmed 2026-10-20, conditional on this control staying in the release):** invisible opt-in programmes (OpenAI's
complimentary tokens, OpenRouter's account-level logging), provider policy drift, and *unknown plan + a stated paid plan* trust. The
standing control is the re-check of this section at each release ([13](../13-release-security-checklist.md) F11).

