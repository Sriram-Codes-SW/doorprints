# Screenshot flake: a stable frame is not a loaded frame (S4b-BL-138, S4b-BL-100)

> Session record (2026-10-06). The harness is `android/app/src/test/java/app/doorprints/screenshots/ScreensScreenshotTest.kt`.

## Symptom

`ScreensScreenshotTest.iosCompareEmpty[ta-dark=false]` failed once in a full `:app:testDebugUnitTest -Proborazzi.test.verify=true`
run (Roborazzi: "ios_compare_empty_ta_light.png is changed") and passed alone. `compare[ta-dark]` and `viewings[en-dark]` had flaked
the same way before; each was fixed for its own screen with a `readyText` wait.

## Root cause

`awaitStableFrame` accepts a frame when two settled captures in a row are equal. A screen fed by Room draws a loading frame
before the first answer: Compare draws its heading only, the house list nothing at all. That frame is as stable as the final one.
`settle` decides that Room and the coroutine workers are idle from a thread's state and a 25 ms floor, so (most likely; the
interleaving itself was not observed) a worker that had been handed Room's answer but not yet run looks idle under CPU load, and
the loading frame is captured twice and shot.
The wait on the screen's own text (`readyText`) existed only on `compare` and `viewings`; every other shot, `shootIos` included,
relied on the timing guard alone. The failing images are loading frames, not a font difference; why Tamil and Telugu failed more often than the other
languages is not established (they run after English and Hindi in the same JVM, so heavier GC or JIT pressure is a guess).

## Reproduction (this machine, 4 cores)

Eight `yes > /dev/null` burners, the whole class (`--no-build-cache`, `cleanTestDebugUnitTest` before each run, 200 tests, 41 skipped):

| Before the fix | Result |
| --- | --- |
| Runs 1 to 4 | pass |
| Run 5 | `iosCompareEmpty[ta-dark=true]` and `houses[te-dark=true]` changed |
| Run 6 | `iosCompareEmpty[ta-dark=false]`, `iosCompareEmpty[ta-dark=true]`, `houses[ta-dark=true]`, `houses[te-dark=true]` changed |

The "New" image of `houses[ta-dark=true]` is the heading ("Houses") on an empty screen; the actual of `iosCompareEmpty[ta-dark=false]`
is the Compare heading alone. Neither is a pixel difference of the finished screen. Running only `iosCompareEmpty` under the same
load did not reproduce it in 6 runs: the race needs the whole class's thread and GC pressure.

## Fix

`shoot`, `show`, `shootIos` and `shootForm` take `readyText` as a required argument, so a new shot cannot omit it silently:
a string (in the shot's language, from the app's own resources) that is on screen only once the data is in, or `STATIC` for a
screen that shows no stored data. The wait runs `settle()` in a loop (it also advances the effects dispatcher) and fails after
20 s naming the text. Texts: the saved house's label for the list, Compare and the form; the empty-state title for the empty
shots; the first saved row for brokers, areas, criteria, questions, share contacts, offline areas; Export's count sentence
(a content description); the AI own-key option, the lock and path-trace labels for the settings sections.

## After the fix

The same load (eight burners) and the same command, 8 consecutive full-class runs: all 8 pass (200 tests, 41 skipped, 0 failures),
against 2 failing runs in 6 before. No reference image was changed. Eight passes do not prove the race gone (before the fix the
rate was 2 in 6 runs), but the failing shots now cannot be taken before their data shows.
