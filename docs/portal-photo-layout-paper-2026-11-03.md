# Paper photo layout: local implementation

Mosaic now displays six whole photographs in proportional rows, with slim
frames following the images. Empty outlined grid cells are removed. Framed
Print expands into the available region instead of retaining the earlier
60% width / 72% height limits. Both use the reviewed Paper background with
sparse, static original doodles.

The user's “Continue where you left off” follows the concrete design review;
the recommended Paper direction is used for this reversible local change.
The [working production preview](http://127.0.0.1:8892/renderer.html) uses local
scenic fixtures and a simulated host bridge. The earlier comparison's Current
view is frozen at `d7e18bcb`, so production edits cannot silently change the
before view.

## Implementation

Shared geometry examines at most 32 row partitions for six photos and at most
32 overlay reservations with 16 retained free-area candidates. Decoded photo
dimensions drive the layout. Replacement and resize recalculate geometry
without selecting or decoding again.

The existing two-slot dissolve, bounded initial decode admission, pause,
retirement and stale-publication owners remain. Optional motion lifts the
complete frame; it does not crop or pan inside the image. Motion-off and face
gates remain covered.

Android publishes immutable bounds from its UI thread through the existing
layout observer. Clock window coordinates and calendar root coordinates are
normalized once. Local backing appears only around visible native widgets;
Shuffle keeps Paper contrast until the outgoing Paper scene actually retires.
No setting, dependency, version or provisioning change is introduced.

## Verification

- Initial seven behavior cases produced six assertion failures and one control
  against the old renderer, then passed. Additional regressions cover tiny
  decorated frames, motion, resize, mixed native origins and Shuffle retirement.
- All 359 slideshow cases and 743 bundled application JavaScript cases pass.
- Fresh Android unit execution passes 4,359 cases / 453 suites with zero
  failures, errors or skips. Debug assembly passes.
- Eight bundled renderer assets match the source bytes in the final debug APK.
- Isolated Chromium passes eleven production geometry/motion cases at
  2160×1440, 1280×800, 800×1280 and 320×480, plus four preview control cases.
  Frames stay inside the viewport, separated and clear of the simulated clock.
  Generated screenshots were inspected; zero page errors.
- Independent review reports no remaining blocker and independently passes
  163 focused cases plus tiny Framed motion checks.

Early browser attempts used a hidden-element visibility wait and then an
incorrect snapshot rectangle shape; these are excluded fixture errors.
The final fixture uses the actual left/top/right/bottom bridge contract and
simulates the native backing's layout refresh. A missing-helper compile failure
is retained as development evidence, not a native pixel reproduction.

Source hashes, browser results and artifact checks are in
`mocks/portal-photo-layout-20261103/evidence/implementation-verification.json`.
Browser replacement and pause ownership are exercised by deterministic Node
lifecycle fixtures; the raster cases primarily cover settled geometry.

## Delivery limits

This is a local committed implementation and debug artifact. No Portal install,
phone operation, household media transfer, provider/account action, push or
publication occurred. Native clock customization, real Android 9/10 WebView
appearance, density, burn-in and Portal performance remain unverified.
Dark or gradient custom clock colors particularly need physical contrast checks.
Browser proof does not establish installed appearance or hardware performance.

The authorized implementation clock is **04:23:10–04:53:10 UTC November 4**,
including discovery, fixes, review, verification, cleanup and local commit.
The earlier review clock **01:01:58.039–01:31:58.039 UTC** and all older crop,
slim-frame and parked clocks remain. The queue still contains 119 tasks:
71 delivered, 38 locally verified, 2 approval, 7 parked and 1 queued.
Only the existing photo-layout task changes; all other 118 objects and the
Weather default remain.
