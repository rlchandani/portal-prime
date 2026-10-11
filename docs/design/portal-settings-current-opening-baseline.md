# Current Settings opening baseline — 11 October 2026 UTC

## Scope and accepted artifact

The unwanted detail-panel translation/fade has been removed and verified separately in
[the motion-fix report](portal-settings-instant-detail-validation.md). Category selection and
ordinary embedded settings still replace content within the same Settings Activity. Opening
Settings from Home creates a fresh Settings window. These are separate operations.

This report measures the current accepted artifact, not a new performance candidate. The
source is commit 28f69e5, app-source digest
`c7ecf36eca9ad958588af4d38b66adb0afe634a1ad1cc73ff6a3310950298722`.
The installed benchmark APK is 37,133,972 bytes, SHA-256
`a564577a0a3f0d17b1f68adfa8cef3e0d2590525ac0ee8e1aa97d7889b0c02b7`. Native Filmstrip remains enabled, native Home disabled.

Only Family Area was profiled. Living Area and the phone received zero test operations in
this work. No Home experiment, preference change, application upgrade or installation was
performed for this measurement.

## Unsampled warmed-entry baseline

Twenty fresh Settings entries warmed the existing process; ten further fresh entries were
measured with main-stack sampling disabled. Every entry used the source-known Home gear,
owned its fifteen-second capture and verified its stop. Polling began at least one second
after tap acknowledgement, then allowed another 1.1 seconds to settle. No process or ART
profile reset was requested. Setup schema/source reads can warm domain initialization.

| Observation | Median ms | Entry range ms |
| --- | ---: | ---: |
| Largest recorded Settings frame | 109.329 | 80.242–135.553 |
| Shell composition | 19.285 | 15.926–25.375 |
| Shell child measurement | 12.144 | 5.852–31.193 |
| Search-field composition | 4.922 | 3.704–7.756 |
| Search-field measurement | 1.736 | 0.845–5.384 |
| Sidebar measurement | 3.205 | 1.211–8.029 |
| Detail measurement | 3.026 | 1.008–7.442 |
| Search-index preparation | 0.317 | 0.258–0.674 |

Ten timed entries produced 30 Settings Window frame callbacks, including 20 FIRST_DRAW
flags. These flags are not unique displayed frames or delivered FPS. Every timed entry
recorded one shell composition, one shell measurement, eight sidebar item invocations,
one index preparation with 218 entries, and one version publication. The final stopped
snapshots exactly matched the pre-stop UI-work, preparation and Settings-frame records.
There were no invalid/discarded UI observations or frame callback drops.

The spans overlap: search composition is inside shell composition; child measurements
are inside shell measurement. Their medians, maxima and component averages must not be
summed into a critical path. None of these observations measures physical tap-to-visible
latency, exact first usable pixels, CPU time or a general startup speed improvement.
The old 75 ms first-use search measurement does not describe this warmed current cohort.

## Short startup attribution

A separate single capture sampled three fresh Settings entries, retaining 45 chronological
non-native-poll stack observations. No timeline records were omitted. Stack retrieval itself
had median 1 ms and maximum 64 ms; 420 cadence slots were skipped. This overhead prevents
using the sampled run as speed acceptance or CPU percentages.

The sampled entry first-draw callbacks had totals 92.053, 97.836 and 122.845 ms, with
layout components 58.067–69.752 ms. The nearby stacks included text/layout, initial
composition, window creation/drawing, input-method focus and accessibility/framework
calls. Composition can execute during Android's initial traversal; the FrameMetrics
layout component is broader than the instrumented child-measure span. The next queued
callback can inherit delay from that traversal. Callback timestamps do not establish
presentation time or uniquely identify a causal application method.

The trace does not support attributing the remaining delay to the removed panel animation
or to search-index preparation. It also does not prove that a particular text field,
accessibility service, Binder call or font path is the dominant cost.

## Decision

Keep the accepted motion fix. No new performance change is accepted from this baseline.
The sidebar uses eight small, literal vector resources through painterResource, which parses
resources on the main thread. Inspection of the resolved Compose library shows a fresh
ImageVectorCache in a fresh top-level ComposeViewContext. A narrowly bounded icon cache
could avoid reparsing on later openings, but the measured row composition is roughly a
millisecond per row; no total-opening benefit has been demonstrated. A larger Settings or
Home navigation rewrite is not justified by this evidence.

Reference: https://developer.android.com/develop/ui/compose/resources
Reference: https://developer.android.com/develop/ui/compose/phases

All 30 baseline captures and the attribution capture ended inactive. Full configuration,
settings schema and photo-source groups remained unchanged, Home was restored, and the
installed APK hash was independently verified unchanged. Evidence and source snapshots are
private under ignored build/fleet/diagnostics/settings-entry-current-2026-10-11; no setting
values, household images, screenshots, UI text or credentials are in this public report.

Settings opening performance and the broader performance objective remain open. This report
is a current diagnostic baseline and a record of hypotheses ruled out; it is not completion
or performance sign-off.
