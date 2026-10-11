# Settings: one sidebar, one content pane, and Back/Forward history

The approved Settings layout keeps one fixed category sidebar and replaces the right content pane
immediately. Its shared Back/Forward pill sits beside the current page title. There are no extra
collapsing columns or page slide animations. This change preserves the existing retained-pane
performance behavior and the settings registry, persistence and wire schema.

Category names now describe groups, while child row names describe individual editors:

| Category | Child editor |
| --- | --- |
| Photos & screensaver | Slideshow |
| Home & widgets | Home dashboard |
| Connections | Home Assistant dashboard |

Home dashboard now opens the existing inline settings editor instead of launching HomeActivity.
Its explicit Customize home action still takes the user to the dashboard for editing widgets.
The Home Assistant editor name distinguishes it from the launcher dashboard.

## History behavior

The immutable history retains at most 32 routes and a current position. Back/Forward restores
visited pages, including category overviews and cross-category visits. A new destination after
Back discards the forward branch; selecting the current route does not. Search highlights update
the current entry without adding an extra Back step. Advanced destinations are skipped while
Advanced is disabled. The Activity saves both the entries and position across recreation.

Header, paired remote and physical Back share the same local navigation model. At the beginning
of local history, physical/paired Back can use the existing platform behavior. Header controls
are disabled at their respective boundaries. A dynamic CompositionLocal delivers changing
button state only to consumers, without invalidating all retained editor bodies.

## Verification

- Debug and non-debuggable benchmark builds passed, with native R8 enabled.
- Full JVM gate: 1,759 tests across 200 suites; zero failures, errors or skips.
- The history model has 20 tests covering boundaries, cross-category visits, branch clearing,
  search highlight replacement, the 32-route limit and gated destinations.
- Continuity tests verify the inline Home dashboard route, distinct editor names, and preservation
  of the existing external fullscreen Lamp/Camera destinations.
- Fixed diagnostic marker regressions preserve the exclusion of editable/arbitrary text.
- Family Area passed 20 native navigation checks in 98.58 seconds, including installation and
  compilation verification. Every tested transition stayed in the same Settings window. Native
  checks exercised the header buttons, paired Back, renamed rows, cross-category navigation,
  forward-branch clearing, and inline Home dashboard/Home Assistant editors.
- Configuration and photo-source snapshots were unchanged. The candidate was retained, Home
  restored and the collector left inactive. Living Area and the phone received zero operations.
- Nine offline runner safety tests passed. The driver supports one rollback to the previous
  accepted artifact; rollback was not needed.

The initial full build found one obsolete continuity assertion expecting Home dashboard to open
HomeActivity. It was updated to assert the approved inline behavior before the final full gate.
No failed device trial or iterative timing experiment was needed for this revision.

The browser mock remains at `/portal-plus-settings-columns/?path=history&review=two-panes`.
Its 38 earlier core browser assertions and five later naming assertions are recorded separately;
source hashes distinguish those revisions. Browser settings remain sample state.

## Installed Family Area artifact

APK SHA-256: `96e02e3a6524447dc8dfdbc46f0d1ae8f457f92ee5c0240d86ea60605c00d179`.
Bootstrap SHA-256: `c6d00a6d9d679ff090f670db4a3926fb7d9b7bb495fddf5801ac22c3f74d5527`.
Package/signature remain unchanged. The APK is 25,386,112 bytes and has one DEX.
Its profile resolves 8,618 methods / 2,559 classes, including 266 application methods /
70 application classes. Exact bundled/reference sets and DEX checksums were verified against
the installed artifact. Own OAT metadata reports speed-profile / install-dm. This does not prove
compilation of each individual application method.

Build, source/artifact admission, unit gate and native postflight records are under
`build/fleet/diagnostics/settings-history-two-pane-2026-10-11/` in the primary workspace.
Postflight SHA-256: `f6e0c7eee6db50bd0e823dfbebf6040fad612d290336d7a5c4382be6c15bc21b`.

## Scope

This is a bounded approved navigation/naming change. No new frame-time percentage, tap-latency
measurement or universal 60 FPS claim is made. Activity recreation persistence was code-reviewed
and model-tested; a device process-death trial was not part of this sweep. Existing external
system screens and specialized connection forms remain their existing Activities. Settings
optimization as a whole is not claimed complete.
