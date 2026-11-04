# Home playback callback freshness

Optimized **1.134/code128** is now installed in both rooms. Both complete APK
readbacks match **25,884,988 bytes**, SHA-256
`2aca4c50dbfce037997d718c0a555725511c235d6570da3c4ea8795f855530d0`.
The original **06:55:22–07:25:22 UTC** issue clock covers the local implementation
and this delivery; it was not reset.

Home's active header mini-player now reads `NowPlayingHub.current` when its
queued UI work runs. A delayed initial replay or worker callback therefore
cannot replace a newer track, restore a cleared track, or restore an older
playing snapshot after a pause. This follows the existing screensaver consumer
pattern.

The change extracts the active Home callback into a small production factory.
Home still registers and removes its listener in the same `DisposableEffect`,
and still posts through its main-thread Handler. The shared hub, playback
transport, metadata selection, inactive legacy consumers and visual design are
unchanged.

| Target | Implementation and validation | Release / acceptance |
| --- | --- | --- |
| Active Home header | Production listener factory and one caller substitution | Local source change |
| Frozen JVM regression | Ten unchanged methods; captured-state baseline has five AssertionErrors and five passing controls, candidate passes all ten | Independently repeated using the actual candidate runtime JAR and recompiled frozen baseline factory |
| Full host gates | 4,402 JVM tests / 460 suites, zero failures, errors or skips; debug, optimized/R8 and vital lint pass in 1m 48s | Signed artifacts only; nine packaged slideshow assets match source and baseline |
| Ordinary behavior | Deferred rendering, playing and paused replay, inactive/null clearing and listener removal pass | Callback boundary only |
| Native callback boundary | Actual 1.134 debug APK factory/hub on Family API29; real owned HandlerThread/Looper | Six generated cases pass; equivalent captured callback fails four and passes two controls |
| Rendered Home and playback | Caller source reviewed | Physical Home, main-thread composition, live sessions/audio/transport, API28 and optimized callback execution remain open |
| Portal rooms | Exact optimized 1.134 fully hashed in both rooms | Complete saved state preserved; Living physical visual acceptance remains open |

The baseline factory is an equivalent extraction of the original Home lambda,
not an execution of the original Compose function. Original source, baseline
factory, unchanged test bytes and actual Gradle RED/GREEN XML are retained.
The first compiled-class archive used an obsolete build directory and copied
zero files; it supplies no baseline evidence. Independent replay details and
full build results are recorded in the structured proof.

The first local candidate APKs retained **1.133/code127** metadata and were not
installed. The versioned 1.134 rebuild subsequently passed all 4,402 JVM methods
across 460 suites, debug, optimized/R8 and vital lint in **1m42s**.
Both signatures match the iteration signer and all nine slideshow assets remain
exact. Debug APK SHA-256:
`3714906ae77d686712fd1299797a423850622fc957fe50779fb19ae28b139635`.

The frozen native runner loads the actual debug APK through an isolated
DexClassLoader. It exercises deferred replay, worker publication and queued
clear/replacement/pause/late callbacks with generated tracks. Its captured
baseline is an equivalent Java callback, not original Compose execution.
All twelve baseline/candidate results, render counts and classloader identities
match independent review. The owned UI thread, process and remote scratch retire.
Shell permissions remain; the fixture makes zero network or installed-hub calls.
No keep rules or optimized callback execution are claimed.

Family's one install returns Success. Living's acknowledgment is uncertain;
six read-only observations confirm the full APK from one install without retry.
All **43 Family and 30 Living preference files**, file sets and four complete
configuration maps remain identical. Names, Dev Mode and Family font,
accessibility, original Chromium activity, resumed frame and awake display remain.
Bounded logcat checks contain 1,635/1,976 lines and zero fatal/ANR markers.
Family's final frame is visually inspected; its capture stays private.
Copied registries are removed and original registry hashes remain unchanged.

Positive history distinguishes this subscription/dispatch scope from earlier
media metadata and transport work: the active Home callback originated in
`86694113`, while screensaver commit `88a1e780` already reads the current state
at dispatch. Earlier album/duration, startup/chooser, native playback and other
held scopes retain their original clocks.

The concurrent Toolkit storage-counter observation remains source-only and
unselected under this discovery window. No real kernel failure, runtime
reproduction, correction or new acceptance is claimed. Startup review admitted
no distinct repair.

The original **06:55:22–07:25:22 UTC, 4 November 2026** window covers discovery,
fixtures, implementation, validation, review, documentation, cleanup and commit.
It does not restart earlier issues. The whole-product goal remains active.

The queue has **125 tasks: 76 delivered, 39 locally verified, 2 awaiting
approval, 7 parked and 1 queued**. All 124 prior objects and their 418,403-byte
historical raw suffix remain exact, including the Weather default and earlier
clocks. The promotion retains the complete current suffix, including its two
leading delimiter bytes.

The user's latest priority is short, useful releases addressing reported
screensaver visuals. Target 10–15 minute slices within the existing 30-minute
hard issue limit, split large changes and park stalled work.
Paper renderer support is installed, and the current local sample preview shows
larger photos, thin frames and doodles. Both rooms retain saved **Shuffle**;
Family's inspected current stage is a fitted photo with dark margins.
Installed Paper support does not mean every Shuffle stage has that background,
and all reported screensaver problems are not declared fixed.

Strict MkDocs passes with zero warnings. The new backlog detail and current
note are inspected at 1,600 × 1,000 and 390 × 844; no captured console warnings
or errors. The settled mobile detail is 343 pixels wide at x16/y20. The full
document measures 391 pixels at the 390-pixel viewport, so this does not claim
whole-page overflow acceptance or verification of every historical task.

Owned preview tab, loopback server, documentation environment and generated
site are retired after verification. Source, regression evidence and local
candidate artifacts are retained; the preexisting Python cache is untouched.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/home-playback-freshness-verification.json`.
Versioned delivery proof:
`mocks/portal-widget-gallery-20261102/evidence/home-playback-freshness-promotion.json`.
