# Portal+ widget swipe navigation

Validated October 7, 2026 on the connected Portal+, Android 10 / API 29,
2160 × 1440, density 180 dpi, 60 Hz. Work remains on
`codex/portal-plus-widget-page-order`.

## Behavior

- In the Home widget area, a physical right-to-left swipe advances from page 1
  to page 2; left-to-right returns to page 1. The first and last pages clamp
  without wrapping. Pages increase from left to right.
- The Favorite apps row shows the current page and actual total, such as
  `1 of 2`. Previous and More widgets buttons are removed.
- The same immediate swipe works in Customize. A stationary long press still
  owns widget dragging; held moves do not turn into page changes.
- Horizontal paging commits on release after accumulating 64 dp beyond the
  device's touch slop. Short or cancelled gestures do not change pages.
- Vertical scrolling inside widgets retains its existing handlers.
- Page-counter accessibility actions provide previous/next navigation within
  the available bounds. A focused counter supports Left/Right keys; boundary
  arrows remain available for ordinary focus navigation.

## Implementation

`ApprovedWidgetArea` retains the existing prepared pages and at most three
adjacent compositions. A 180 ms `EaseOutCubic` slide reads `Animatable` state
inside the deferred `absoluteOffset` placement lambda. The offset uses physical
pixels, so layout direction does not reverse the requested swipe direction.
Compose retains the page's display list, and the enclosing viewport clips
off-screen pages without a second transparency layer. Animation frames do not
update layout persistence, repack the grid, or restart widget network producers.

Outgoing pages lose semantics, focus eligibility, and pointer input. A page
count reduction or a jump beyond the retained neighboring layers snaps directly
to the destination, avoiding a transition through missing pages. Counter focus
survives keyboard paging.

`ApprovedPackedWidgetGrid` observes page activation only in edit mode, where
activation determines drag ownership. Normal paging reuses the packed grid.
Editing still observes tab/page deactivation and cancels its active drag.

The browser navigation mock and importing Tools redesign use the same physical
direction, clamping, counter-only controls and 180 ms whole-page slide. Browser
threshold distances are normalized to the 1920 px logical canvas. Existing
browser Move/Size dialogs remain simulations; native held reorder and edge
scrolling are verified on the device.

The direction was corrected on October 7 after the user's clarification. The
original implementation followed an earlier request for right-swipe advancement.
`HomePresentation.pageAfterSwipe` now maps a left swipe to the next index;
`pageOffset` places later pages to the right and moves both pages left when
advancing. Labels continue to use the same page index as widget membership.
The cached placement animation, duration, widget order and sizes are unchanged.

## Direction correction validation

The corrected benchmark APK is installed on the connected Portal+:

```text
applicationId: com.immortal.launcher.debug
SHA256: 6a9d411ee6b1e0bde74c8b735fb8e332a0fea36ef2749e14ca1f1aafe46fb76d
```

- Debug, benchmark and test APK builds passed.
- All 697 unit tests passed. The new paging regressions cover left-to-next,
  right-to-previous, both boundaries and the spatial placement of neighboring
  pages at the start, midpoint and end of a slide.
- The native `widgetPaging` probe passed 245 assertions on the saved two-page
  layout. It checks labels against visible widgets, physical swipe direction,
  cancellation, short gestures, boundaries, Home/Customize continuity,
  accessibility navigation followed by touch, and held-reorder cancellation.
  Widget preferences and their file remained unchanged.
- Browser verification passed 212 checks across the navigation and importing
  Tools mocks, with no browser errors. It verifies both animation directions
  alongside the page counter and visible widget identities.
- After six warm-up swipes, twenty measured swipes produced a rolling
  120-frame sample: median 7.72 ms, p95 21.12 ms, p99 28.46 ms, with nine frames
  above 16.67 ms. The full interval recorded 20 janky frames out of 260.
  These results overlap the earlier Home measurements below; this small
  direction correction establishes correct behavior, not fewer missed frames.

## Original verification

The benchmark APK used for the original widget paging validation is non-debuggable,
uses the existing debug package and signing key, and preserves device
configuration. The subsequent [Meta charcoal update](portal-plus-meta-colors.md)
records the currently installed colors and fresh device checks:

```text
applicationId: com.immortal.launcher.debug
SHA256: a0a9518aadde52e7c760215ceeb7fc295f802579592ca5cab7905537737ceff6
```

Builds and tests:

```bash
./gradlew :app:assembleDebug :app:assembleBenchmark \
  :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon
```

- All 603 unit tests passed, with zero failures, errors, or skips.
- The final native `widgetPaging` probe passed 231 assertions using the saved
  nine-widget, two-page layout. It checked physical direction, release timing,
  thresholds, first/last boundaries, cancellation, visible page contents,
  accessibility actions, Home/Customize geometry and selected-page continuity,
  stationary long press, held reorder preview/cancellation, unchanged host
  window, and absence of attempted Activity launches.
- Page-two vertical swipes produced real scrolling while retaining the page.
  Page one had no viewport advertising vertical overflow, so its checks
  established vertical intent without asserting scroll movement.
- The widget preferences and their file remained unchanged byte-for-byte,
  including after test cleanup.
- Manual right swiping over Photo Memory and left swiping over World Clock
  reached page two and page one respectively.
- Browser verification passed 212 navigation checks across both importing
  demos and 130 Tools checks, with zero browser errors.
- JavaScript syntax and `git diff --check` passed.

The native probe is run with:

```bash
adb -s SERIAL shell am instrument -w -e command widgetPaging \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
```

The probe requests a fresh task before `startActivitySync`. Home is a
`singleTask` Activity, and `CLEAR_TOP` can reuse an instance without satisfying
that test API's wait for a newly created Activity. An initial run stalled at
startup and was stopped; it is not counted as passing validation. The probe
uses `NEW_TASK | CLEAR_TASK` for deterministic test entry, without changing
production navigation or persisted configuration.

Raw native hierarchy, screenshots and household content remain in private test
cache. They are not included in this repository.

## Frame timing

Each trial used six warm-up swipes and twenty measured shell-injected swipes on
the same device layout. `dumpsys gfxinfo ... framestats` provides a rolling
120-frame buffer; these exact percentiles describe that buffer.

Initial trials before the placement change:

| Candidate | Median | p95 | p99 | Frames above 16.67 ms |
|---|---:|---:|---:|---:|
| Reviewed gesture and count-clamp build | 8.71 ms | 22.47 ms | 29.83 ms | 10 / 120 |
| Grid-activation optimization | 9.17 ms | 23.26 ms | 31.73 ms | 10 / 120 |

The grid dependency change did not establish a frame-time improvement. A
subsequent diagnostic trace identified transformed descendant-bound updates in
Compose UI 1.10.4. Its `RectManager` treats even a layer translation as a
non-identity positional transform and repeatedly maps descendant bounds through
ancestor matrices. Deferred placement can use cached integer positional bounds.
The comparison trace removed that transformed-bound routine from the top 200
inclusive CPU entries; placement work increased instead. Traces use sampling
and accessibility inspection, so these diagnostic totals are not benchmark
measurements, and recursive inclusive totals must not be summed.

Two fresh baseline trials and two final-build trials used the same saved layout,
warm-up, input coordinates, 260 ms swipes, and 150 ms pauses. The final build
also removes the redundant page transparency layer:

| Build / trial | Median | p95 | p99 | Above 16.67 ms | Full interval: janky / rendered |
|---|---:|---:|---:|---:|---:|
| Previous build / 1 | 8.77 ms | 20.43 ms | 21.66 ms | 9 / 120 | 19 / 239 |
| Previous build / 2 | 8.79 ms | 19.23 ms | 24.53 ms | 9 / 120 | 16 / 240 |
| Deferred placement / 1 | 8.14 ms | 20.23 ms | 23.24 ms | 9 / 120 | 20 / 260 |
| Deferred placement / 2 | 7.96 ms | 17.73 ms | 19.24 ms | 9 / 120 | 15 / 261 |

The median improved in both matched trials. Tail timings overlap and the total
number of janky frames across the two trials remains 35 for each build; the
change does not establish fewer missed frames. Slower frames remain near
transition starts. These small samples validate a modest ordinary-frame
improvement, not consistently smooth 60 Hz transitions. The wider Home
performance work in `portal-plus-quality-gates.md` remains open.

Scope: this validates the connected Portal+'s saved layout and built-in widget
gestures. Physical TV/RTL input, arbitrary third-party widget providers,
committed reorder/resize/removal, and native page-count mutation during a slide
are outside this probe. The count-change and keyboard edge fixes were reviewed
against the actual code.
