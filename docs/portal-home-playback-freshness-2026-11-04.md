# Home playback callback freshness

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
| Rendered Home and playback | Caller source reviewed | Physical UI, Android Handler scheduling, live media sessions and transport remain open |
| Portal rooms and companion | Existing delivered Portal 1.133/code127 retained | No installation, phone, device, provider, account, push or publication action |

The baseline factory is an equivalent extraction of the original Home lambda,
not an execution of the original Compose function. Original source, baseline
factory, unchanged test bytes and actual Gradle RED/GREEN XML are retained.
The first compiled-class archive used an obsolete build directory and copied
zero files; it supplies no baseline evidence. Independent replay details and
full build results are recorded in the structured proof.

Both local candidate APKs retain **1.133/code127** metadata. They are distinct
from the already installed artifacts and are not promoted releases. Versioning,
native acceptance and device promotion remain separate work.

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

The queue has **125 tasks: 75 delivered, 40 locally verified, 2 awaiting
approval, 7 parked and 1 queued**. All 124 prior objects and their 418,403-byte
raw suffix remain exact, including the Weather default and earlier clocks.

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
