# Slideshow stall attribution

The long shuffled runs still contain intermittent queued-frame tails. A single worst frame per host cannot describe every later stall, especially when first construction is the maximum. These diagnostics support investigation; they do not establish performance acceptance.

## Observation path

The actual bundled scheduler invokes `onMode` only after a prepared scene is promoted. The asset page optionally sends that code-defined ID to the existing native bridge. Native admission allows exactly the twelve IDs in `SlideshowStyle`; a contract test compares them to the actual bundled mode registry.

The bridge selects its capture at receipt, then schedules one guarded Main callback. The callback checks controller revision, face-store ownership, WebView identity, engine ownership, host activity and user pause state. Retired or replaced controllers cannot report into the current engine. Capture replacement, closure or expiry cannot redirect a queued report to another capture. Diagnostic failures stay contained.

The journal keeps at most 128 events, fixed host identities, finite style identities, and integer timestamps. All valid events still contribute counts after the retention cap. It stores bridge receipt and Main acceptance separately. With no active capture, it schedules no Main work. It allocates no collector or timers outside the existing explicit performance session.

Each host histogram also retains its 32 slowest valid detailed frames at or above 100 ms. Same-callback components are copied into reused primitive slots; JSON and sorting occur only on explicit reads. Eligible/omitted counts expose truncation. Existing aggregate, invalid-detail and single-worst-frame semantics remain intact.

## Verification

The compilable no-op style seam reproduced 10 failures in 17 native tests; the unchanged entry page reproduced 14 failures in 16 scheduler/entry tests. The slow-frame seam reproduced four failures in five tests.

The final integration passed 1,426 native tests with no failures/errors/skips and produced debug and benchmark APKs. The selected combined JavaScript suites passed 121 tests, and all 14 source-engine fixtures passed. Generated assets matched packaged APK bytes; the background WebView preparation experiment remained disabled. Existing scheduler, mode implementation, CSS and remote controller assets matched the previously installed baseline.

The private soak protocol passed twelve virtual-clock ownership, expiry, response-loss and privacy cases. Its slow-frame exporter passed nine boundary cases. Unknown identities, malformed timestamps, excessive journal lengths and nonnumeric components cannot leave the exporter. Original settings and source data remain in memory.

Hardware deployment and the new 900-second shuffled captures are the next validation step.

## Interpretation limits

A promotion report is not a hardware presentation timestamp, delivered FPS, physical touch latency, or proof of subsequent photo advancement. The style before the first report is unknown. The events may bracket a delayed Main callback; frames inside that interval require cautious interpretation. Frame callback timestamps also describe receipt, not vsync. An outgoing composition remains present during the dissolve, and an incoming composition may prepare before promotion.

Host names associate reports with histogram groups; they are not unique window identities. Simultaneous instances of the same host cannot be distinguished by this journal. The bounded tail set omits smaller eligible frames. Window timing components and startup spans overlap and must not be summed. `commands` means graphics command submission, not GPU execution.

The JavaScript scheduler still randomizes the photo deck even when source ordering is fixed. A fixed style does not establish matched-content causality. Process PSS excludes a separate Chromium renderer. Neither diagnostics nor green correctness tests prove the complete performance/UX goal.
