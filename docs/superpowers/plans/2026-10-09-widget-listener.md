# Home widget listener ownership

Continue the full performance and UX goal; this experiment covers one Home activation boundary.

## Evidence and hypothesis

Home unconditionally attaches `AppWidgetHost` even for Compose-only cards. Its observer receives the already-started lifecycle synchronously, then the explicit state check calls start again. Establish the duplication using the real AndroidX lifecycle and mocked framework boundary before changing behavior. This is a redundant-call hypothesis, not a claim about the dominant slow-frame cause.

## Scope

- Retain the accepted tab activation build and branch.
- Own one listener request per started interval, only when a saved Android app widget exists.
- Stop owned requests on hide, removal, destruction or disposal; preserve cleanup after start failure.
- Keep persisted layout, provider binding/configuration, gestures and source settings unchanged.
- Verify the Compose integration with a synthetic host; do not bind real providers or read household photos.

## Verification

- [x] RED: already-started duplicate request and Compose-only unnecessary calls.
- [x] GREEN: real lifecycle, widget add/remove, hide/return, disposal and throwing boundaries.
- [x] Full JVM tests and debug/test/benchmark builds.
- [x] Synthetic runtime listener fixture and retained-tab regression.
- [x] Independent review.
- [x] Matched warmed baseline/candidate host frame observations in both rooms.
- [x] Decide from functional/resource evidence and timing; verify both installed hashes.
- [x] Stop collectors, discard the owned emulator and preserve evidence.

Window FrameMetrics remain a host pipeline proxy. They do not prove compositor FPS, physical input latency, external website readiness or Dream recovery. Keep the broader goal active.

## Outcome checkpoint

Retain the listener ownership fix for lifecycle/resource correctness. Family timing
improves in both pairs; Living is mixed and missed-budget fractions remain around
16%. No consistent rendering improvement across both rooms is proven. The complete
trial table and limitations are in the [listener ledger](../../design/portal-plus-widget-listener.md).
The finite five-minute passive connection check completed: 20 samples per room,
zero failures and no remaining measurement resources. This is HTTP liveness
evidence; the wider performance/UX goal is active.
