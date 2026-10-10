# Settings visibility preparation work

Keep the existing settings registry, native row order, parent/hardware gates and Advanced hint behavior. Preparation currently evaluates visibility before cheaper presentation gates, then repeats visibility while discovering the Advanced hint. In the screensaver domain, a visibility predicate reaches `DreamPolicy.hasBattery`, a synchronous sticky-battery receiver query.

- Extract the visibility selection into a pure, snapshot-scoped helper consumed by the Compose renderer.
- Short-circuit excluded/non-renderable/presentation/disabled-parent rows before visibility when that result is not needed for the hint.
- Evaluate visibility at most once per spec per preparation. Preserve the hint's original eligibility, including its behavior for derived or screensaver-disabled fields; no hardware cache across preparations.
- Prove evaluation counts and identical selection/hint/order with deterministic JVM tests, including a RED run using the old filter behavior.
- Run the full Android unit gate and debug/benchmark builds, review the bounded patch, and deploy the identical signed candidate to both rooms.
- Verify normal Home/Apps/browser/screensaver/pin return paths and empty collector/cleanup state. Do not infer a device-latency or FPS gain from fewer predicate calls.

Parallel source audit and isolated hardware profiling continue to identify the larger navigation bottleneck. A bounded sampled capture is capped at 60 seconds. No household media, visible content, credentials or browser documents are exported.
