# Slideshow stall attribution

The long shuffled runs still contain intermittent queued-frame tails. A single worst frame per host cannot describe every later stall, especially when first construction is the maximum. These diagnostics support investigation; they do not establish performance acceptance.

## Observation path

The actual bundled scheduler invokes `onMode` only after a prepared scene is promoted. The asset page optionally sends that code-defined ID to the existing native bridge. Native admission allows exactly the twelve IDs in `SlideshowStyle`; a contract test compares them to the actual bundled mode registry.

The bridge selects its capture at receipt, then schedules one guarded Main callback. The callback checks controller revision, face-store ownership, WebView identity, engine ownership, host activity and user pause state. Retired or replaced controllers cannot report into the current engine. Capture replacement, closure or expiry cannot redirect a queued report to another capture. Diagnostic failures stay contained.

The journal keeps at most 128 events, fixed host identities, finite style identities, and integer timestamps. All valid events still contribute counts after the retention cap. It stores bridge receipt and Main acceptance separately. With no active capture, it schedules no Main work. It allocates no collector or timers outside the existing explicit performance session.

Each host histogram also retains its 32 slowest valid detailed frames at or above 100 ms. Same-callback components are copied into reused primitive slots; JSON and sorting occur only on explicit reads. Eligible/omitted counts expose truncation. Existing aggregate, invalid-detail and single-worst-frame semantics remain intact.

## Verification

The compilable no-op style seam reproduced 10 failures in 17 native tests; the unchanged entry page reproduced 14 failures in 16 scheduler/entry tests. The slow-frame seam reproduced four failures in five tests.

The combined integration passed 1,429 native tests with no failures/errors/skips and produced debug and benchmark APKs. The selected combined Node suites passed 130 tests with one explicit optional-browser skip; the isolated generated-content Chrome test separately passed. All 14 source-engine fixtures passed. Generated assets matched packaged APK bytes; the background WebView preparation experiment remained disabled. Existing scheduler, mode implementation and CSS assets matched the previously installed baseline. Three additional tests exercise retained callbacks through the actual production paused queue, including same-owner resume delay, capture/owner replacement and queue closure.

The private soak protocol passed fourteen virtual-clock ownership, expiry, response-loss, privacy and Home-window cleanup cases. The delayed-Home fixture first reproduced premature cleanup; corrected cleanup waits for the known frame window to leave and the preflight window identity to return. A stuck frame cannot be reported as successful cleanup. Its slow-frame exporter passed nine boundary cases. Unknown identities, malformed timestamps, excessive journal lengths and nonnumeric components cannot leave the exporter. Original settings and source data remain in memory.

The integrated web remote also updates meaningful operation progress within existing keyed Apps and Activity cards. A successful install still reads current metadata once. The generated-content Chrome probe retained all app icons with zero new icon reads for changing progress and identical-metadata completion; changed metadata took one full render and 24 icon reads, compared to two renders and 28 reads in its pre-progress baseline. The probe verified control bounds at 402 × 874. This is work-count/identity evidence, not physical phone latency or delivered FPS. See [progress evidence](../../scripts/tests/remote_mobile_progress_evidence.md).

Hardware deployment and the new 900-second shuffled captures are the next validation step.

## Interpretation limits

A promotion report is not a hardware presentation timestamp, delivered FPS, physical touch latency, or proof of subsequent photo advancement. The style before the first report is unknown. The events may bracket a delayed Main callback; frames inside that interval require cautious interpretation. Frame callback timestamps also describe receipt, not vsync. An outgoing composition remains present during the dissolve, and an incoming composition may prepare before promotion.

Host names associate reports with histogram groups; they are not unique window identities. Simultaneous instances of the same host cannot be distinguished by this journal. The bounded tail set omits smaller eligible frames. Window timing components and startup spans overlap and must not be summed. `commands` means graphics command submission, not GPU execution.

The JavaScript scheduler still randomizes the photo deck even when source ordering is fixed. A fixed style does not establish matched-content causality. Process PSS excludes a separate Chromium renderer. Neither diagnostics nor green correctness tests prove the complete performance/UX goal.
