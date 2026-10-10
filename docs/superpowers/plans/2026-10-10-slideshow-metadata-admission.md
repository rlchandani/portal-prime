# Legacy slideshow metadata preparation and admission verification

The broader device performance goal remains active. This slice removes a verified Main-thread
metadata preparation path and measures its actual Android use; it does not sign off the whole
slideshow, Settings, browser, fleet UI, or long-session memory behavior.

## Evidence and invariants

- Reviewed candidate changes PhotoFrameController and extracts a bounded preparation coordinator.
- Separate worker checkout passed 1,321 tests and debug plus benchmark builds before diagnostics.
- Both installed devices currently select the local-folder legacy Shuffle renderer.
- Preserve photo order, duplicate entries, optional dimensions, US rounding, authentication,
  face analysis policy, overlays, interval, and all existing user settings.
- Android WebView construction/attachment/destruction stay on Main. Metadata cache hydration
  and JSON assembly run on the existing IO executor. Superseded work cannot attach or warm.
- No photo bytes, screenshots, visible UI text, source paths/URLs, or credentials leave devices.

## Execution

1. Finish independent lifecycle review of the three-file implementation and resolve blockers.
2. Add bounded optional numeric metadata/preparation and WebView-admission diagnostics, with
   inactive, expired, replaced, stopped-session and thread attribution tests. Observe meaningful
   failing assertions before completing capture integration; run the full unit and build gates.
3. Build an instrumentation-only baseline from the installed Settings footer commit. Save exact
   source hashes, test XMLs and debug/benchmark APK hashes in ignored private evidence storage.
4. Commit the reviewed implementation locally and integrate its three source/test files into
   the baseline, preserving identical diagnostic hooks; build and verify the combined candidate.
5. Compare baseline/candidate/baseline/candidate on both devices with ten verified fresh-preview
   warmups and ten timed fresh-preview entries per block, using the existing process and settings.
   Verify actual metadata thread, Main admission, HTML page completion, retirement, window
   ownership, collector shutdown and configuration equality. Preserve failures/adverse timings.
6. Validate interrupted startup, Apps/Home return, pinned browser covering/uncovering and a long
   bounded slideshow soak. Separately investigate first-photo readiness and transition quality.
7. Commit the concrete validation results and keep the broader goal active.

## Measurement limits

Page-finished means engine HTML load completion, not first-photo visibility. Elapsed spans may
overlap and are not CPU time. Window frame metrics do not measure delivered slideshow/browser
FPS or physical touch latency. Warmed existing-process comparisons do not prove cold-start
performance. Process memory does not establish external Chromium renderer memory behavior.
