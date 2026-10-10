# Quiescent release observation work

## Objective and invariant

Avoid synchronous accessibility window/root acquisition during an already-quiescent release, including duplicated Home fallback/Intent dismissal. Preserve both admissions, all release statements and current pin semantics.

A trailing release observation may be skipped only if, before clearing state, there was no pin (including lost/expired), overlay/native cover, pending reveal, pending/failed retirement, registered Immortal Dream/preview, or raw system-bar visibility. On the skipped path publish unavailable/unselected/uncovered pin-control state. Normal accessibility-event observation, explicit status requests and visible-bar failed-launch eligibility remain fresh.

## Scope

Production changes are limited to PinnedAppFrameHost.release and a raw QuickBar bar-visibility accessor. Do not add an already-dismissed Intent flag, cache window identity, remove either admission, change persistence or touch browser content.

## Evidence

1. Capture the currently installed f25431d build with the same warmed query/no-query browser/Home workloads before changing production.
2. Add actual host/routing tests for zero quiescent window acquisitions, pin/cover/reveal/retirement/owned-surface guards, visible-bar eligibility, failed launches and intervening ownership. Run RED against the original production, then full GREEN and debug/benchmark builds.
3. Independent review. Deploy the same signed candidate to both rooms and verify installed bytes. Repeat warmed profiles and a longer navigation/Settings/screensaver soak plus physical pin/cover/tap/bar/failed-launch recovery checks.
4. Record failures and frame timing distributions, including mixed or worse results. Keep the broad goal active; these tests do not prove browser document readiness, all UX quality or delivered compositor FPS.

## Recorded result

The candidate, tests, installed hashes, mixed measurements and physical regression outcomes are recorded in [the validation report](../../design/portal-quiescent-release-performance-validation.md). The broader performance goal remains active.
