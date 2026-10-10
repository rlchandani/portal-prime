# Foreground accessibility window performance

## Objective

Reduce redundant Binder/root acquisition during ordinary navigation and remote status checks without caching stale window identity or weakening pinned-app/screensaver recovery. Existing pin persistence and user navigation semantics remain intact.

## Design and boundaries

- Keep the full live application-root reader for active, lost or expired pins and native/overlay cover or pending reveal states. Preserve all release, retirement and failed-cleanup barriers.
- Make foreground-only selection inspect cheap window metadata first, then acquire only candidate roots needed for the first usable focused window or active fallback. Recycle every acquired window and node, including error paths.
- Route unguarded observation/status and foreground-only pin selection through that reader. Full pin identity/reveal validation remains unchanged.
- No preference, API schema, browser process, accessibility permission, household-media or GitHub changes.

## Verification

1. Capture the installed build using matched browser/Home sequences with and without status queries; report the query overhead and workload limitations rather than treating remote query cost as ordinary app work.
2. Add minimal-root and lifetime tests; run them against the original reader for an actual failing RED result, then implement and run the full JVM gate and debug/benchmark builds.
3. Independent review of guard selection, foreground precedence, root failures and pooled-object lifetimes.
4. Deploy the same signed candidate to both Portals, verify bytes, repeat profiles after warm-up and perform pin/cover/tap/Home regression plus a longer navigation soak.
5. Record timing distributions and any failures honestly. Keep the broad performance goal active unless all of its requirements are proven.
