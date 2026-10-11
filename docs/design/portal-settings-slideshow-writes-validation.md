# Slideshow settings: apply through the registry outside the input thread

Both native Slideshow editor bodies previously called `domain.apply` synchronously from the
input callback, then reloaded their settings. For the screensaver domain that includes
`SettingsGuard.afterScreensaverApply`: Android screensaver ownership checks, and overnight
rescheduling/session changes when the relevant keys are edited. The same registry is already
used from remote HTTP worker threads.

The UI now submits a discrete `ScreensaverSettingEdit` to `ScreensaverSettingsWrites`.
This process-owned adapter reuses `LatestSystemWrites` on Dispatchers.IO, keyed by domain ID
and control key so calendar/screensaver edits cannot overwrite one another's pending values.
Latest unsent values retain the existing writer's event ordering. The registry still performs
validation, synchronized apply and the domain's single post-apply hook. No setter, preference,
wire format or remote route was bypassed or changed; JSON construction also happens on IO.
The queue retains the application Context and finishes accepted writes after a pane departs.

Pane-owned completion/resume readers wait for the queue to drain, then reload the actual
`ScreensaverConfig.Settings` snapshot on IO. Edit-revision guards prevent an older completion
from publishing over a newer edit. The initial cached snapshot is retained; no new global
snapshot cache is introduced. Boolean controls and the main preview button are disabled while
loading/saving. The footer shows pending or failed saves. Values are read back from persistence
rather than presented as a successful optimistic save. A failure counter records validation
rejection/exception without storing exception payloads or setting values. Cancellation of a
pane readback cannot cancel the process-owned writer.

`SettingsList`/its model gain a default-enabled Boolean-control gate; other domains retain
their previous behavior. One pre-existing positional caller was changed to name `onApply`.
Fixed public markers expose ready/unavailable state, plus only the existing non-secret Shuffle
Boolean. They never export credentials, chosen source values or raw labels and do not grant
Home input eligibility.

## Verification

| Requirement | Evidence |
| --- | --- |
| Synchronous UI registry apply | Before: one apply on the calling/input thread per edit; after: zero, submitted to the IO writer |
| Worker regression | The caller-thread assertion failed before implementation; setter and single domain hook now run outside that thread |
| New JVM regressions | Registry validation/recovery, separate domain keys, exception recovery, cancelled pane observer with retained save |
| Selector privacy | Two additional tests; no private values or Home input eligibility |
| Build/full JVM gate | Debug and optimized benchmark builds passed; 1,798 tests / 206 suites; zero failures, errors or skips |
| Final offline native guards | 32 passed, including nine preserved safety guards |
| Native Family Area sequence | 7 checks passed: entry, Photos category, original Shuffle UI/store agreement, changed UI/store agreement, Back, Forward with retained value, original value restored |
| Edits / configuration | Exactly 2 Shuffle edits; complete original configuration and source snapshots restored |
| Ownership | Same Settings window throughout; 0 vertical gestures needed |
| Final state | Candidate retained, Home restored, collector inactive; source unchanged |
| Living Area / phone | 0 operations / 0 operations |

The initial host compile required a named renderer argument, then the default compiler heap
was insufficient. The successful full build and matching bootstrap used one worker,
`kotlin.compiler.execution.strategy=in-process` and a 4 GiB Gradle heap. Repository build
configuration was not changed. The compiler-memory failure log remains in the evidence root.

Two bounded native attempts are preserved. The first stopped before deployment on an incorrect
Shuffle response parser (0 checks, 0 installs, 0 edits); Fleet GET `/screensaver` uses
`{ok:true,screensaver:…}`. A corrected protocol fixture failed against the original parser,
then passed with strict envelope/Boolean/alias validation. The second passed two navigation
checks, then rejected duplicate public label/container metadata for the one switch; it made
0 edits and restored the previous accepted APK/configuration/Home/inactive collector.

The final selector ignores non-switch label nodes and resolves only consistent identical or
nested checked-control aliases to their innermost geometry. Separate rectangles, disagreeing
states, unknown owners and ambiguous other markers remain fatal. Actual enabled-switch Boolean
and persisted value must agree twice. This works when the status footer is scrolled out of view;
disabled pending switches are not admitted. The final run needed no scrolling. All attempts used
the same immutable candidate and app source; only the bounded driver was corrected.

The native check waits for a completed save before navigating. The pane-departure/in-flight-save
case is proven by the controlled JVM regression, not by a blocked Android-provider experiment.
Only Shuffle was physically edited; every other scalar/overnight side effect was not individually
exercised in this sweep. This demonstrates removal of registry/service work from the input path
and functioning real persistence/history; it does not establish a new FPS, latency, heap or
long-run stability percentage.

## Artifact and scope

Candidate: `e8d01febf80f9ec37e477b68546744962e1385d6bdbe1b3fdd5ee248aab438a9`.
Bootstrap: `1a1098689d8c856128585639883e48eab3d973cb5aa00fe87df905bb2a9360bc`.
Source: `cf616643e718df80f8d8130164475ffed3a720b08052af4621de150121f5ec01`.
Successful postflight: `4066bf517af115e7512d64909966eb38e2ecaeb7885e2d0e81c4f4badc7f0384`.

The existing signed non-debuggable `com.immortal.launcher.debug` candidate is
25,402,496 bytes and one DEX. Exact SDK/bundled/reference profile
sets and DEX mapping passed: 8,656 methods /
2,572 classes, including
285 app methods / 75 app classes.
Individual app-method compilation is not proven. The obsolete six-symbol R8-name gate is not
used as the admission gate; complete resolved sets are verified instead.

Issue started 22:35:47 UTC; final device proof was confirmed by 23:03:57 UTC, within 30 minutes.
The broader performance goal remains incomplete.
Evidence roots under `~/portal-prime/build/fleet/diagnostics/`:
`settings-slideshow-writes-2026-10-11`, `settings-slideshow-writes-wire-retry-2026-10-11`,
`settings-slideshow-writes-switch-retry-2026-10-11`.
