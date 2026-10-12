# Home widget placement snapshot — 2026-10-12

Issue 73 uses the original 21:52:19Z–22:22:19Z timebox without a reset.
Family Area is the only device target; Living Area and the phone remain unchanged.

## Change

`HomeWidgetStore.load` previously decoded the placement JSON whenever Home resumed,
and whenever the companion layout reader requested it. It now reuses the existing
`ParsedLayoutSnapshot` helper for unchanged JSON. Every call still reads the current
preference value. Saves, one-time dashboard migration, placement order, span coercion,
deduplication, and tolerant JSON parsing are unchanged.

The cache retains one raw string of at most 32,768 characters and one read-only list.
Widget values contain immutable scalar fields; no Context, Activity, widget host, or
provider is retained. A missing preference clears the snapshot. Oversized layouts
continue to work without being retained. The decode takes place under the helper's
lock so simultaneous readers share one snapshot. Callers cannot mutate that list.

## Validation

- Baseline: 8 focused tests, 6 intended failures, 2 passing controls.
- Candidate: all 8 focused tests pass; 2,026 JVM tests in 233 suites pass with no
  failures, errors, or skipped tests. Debug and signed benchmark builds pass.
- Tests cover 20 repeated warm reads, current preference reads, resize/reorder,
  preference deletion/restoration, ordinary save/readback, caller mutation,
  8 concurrent readers, oversized layouts, and malformed-to-valid input.
- Independent source review is clear. Candidate and accepted full profile inventories
  each contain 8,668 methods and 2,576 classes (app namespace: 285 methods/75 classes).
- Read-only Family preflight found 9 saved widgets and idle collector/pin cleanup.
- Family native proof passes both Home→Apps→Home and a distinct Settings→Home
  resume cycle. All 9 widget placements and full configuration are preserved.
  The exact installed APK, matching dex metadata, full same-APK canonical profile
  inventory, and ART `speed-profile` / `install-dm` compilation are verified.
- Two distinct installs completed by observed state after lost HTTP acknowledgments;
  no install was retried and no setting was edited.
- Stability monitor: 180/180 successful read-only API responses, 0 failures over
  93.997 seconds. Observed median 17.497 ms, p95 33.816 ms, maximum 49.864 ms.
  These uncontrolled Wi-Fi figures are not a before/after performance comparison.
- Final readback rechecks exact installed APK, source, ordinary build output,
  original widget-preference bytes, 9 placements, owned Home, and idle cleanup.

A warm-read identity test proves snapshot reuse; it does not establish Android frame
rate, interaction latency, website speed, or whole-app memory improvement.

## Evidence

Local ignored evidence: `build/fleet/diagnostics/home-widget-placement-snapshot-2026-10-12/`.
App candidate SHA-256: `aa6325370bc44c4be6e5d9be379c27da76c27fdf44f6c4c8199415193ced6831`.
Source fingerprint: `4c510637317e66c6c504863cdcb433e285a48584897312db11ac76f70f4fd060`.
No secret, raw device UI, or private preferences are included in the repository.
