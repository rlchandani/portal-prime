# Portal+ Meta charcoal colors

Validated October 7, 2026 on the connected Portal+, on
`codex/portal-plus-approved-ux`.

The user approved using the original Meta OS colors for the launcher background,
widgets and shared surfaces. Pixel samples from the original Meta Home established
three colors: background `#14161B`, outer card `#2B2B2B`, and inner app tile
`#363636`. These measured colors now drive the shared dark palette.

## Palette and behavior

| Role | Dark color |
|---|---|
| Background and sidebar | `#14161B` |
| Widget cards, Home favorites and dock | `#2B2B2B` |
| Inner controls and header buttons | `#363636` |
| Selection | `#414141` |
| Primary text and action accent | `#FFFFFF` |
| Secondary text | `#B8B8B8` |
| Dividers | `#484848` |

Home uses `ApprovedDarkPalette` directly rather than maintaining teal overrides.
Apps, Settings and Tools consume the same shared colors. Tools retains its
existing semantic error color. Home Assistant controls use neutral surfaces;
the bulb glyph and explicit On/Off text communicate the active state.

The existing Dark wallpaper was selected through the device's Appearance UI.
Its solid fill and missing-photo fallback now use the shared background color.
Native wallpaper defaults, migrations and the other explicit wallpaper choices
remain intact. The mocks default to Dark to show the approved device state.
The light palette remains unchanged.

This changes opaque fills and existing theme bindings. It adds no blur,
transparency layers, animations, dependencies or background work.

## Native verification

Device: Portal+, Android 10 / API 29, 2160 × 1440, 180 dpi, 60 Hz.
The installed benchmark variant is non-debuggable and unminified, and retains
the existing debug application ID, signing key and user data:

```text
applicationId: com.immortal.launcher.debug
SHA256: b8806f7952981c03118e1a8a2cc4400ea3c62ed0565a503aac784758c341d35f
```

Build and unit tests:

```bash
./gradlew :app:assembleDebug :app:assembleBenchmark \
  :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon
```

All 603 unit tests passed with zero failures, errors or skips.

Rendered screenshot samples matched the expected colors exactly on five native
surfaces: background `#14161B`, widget card `#2B2B2B`, inner Home Assistant
control `#363636`, dock `#2B2B2B`, and header chrome `#363636`.

The native `appearance` probe passed 95 assertions, including rendered dark
chrome, Home/Apps/Tools theme changes, a retained Converter draft, Settings
round trips, Activity recreation and sixteen measured theme toggles. Its
cleanup restored the original shared-theme preference.

The native `widgetPaging` probe passed 231 assertions on the saved two-page
layout. It exercised Home and Customize paging, gesture thresholds,
cancellation, boundaries, long-press ownership and page-two vertical scrolling.
The saved widget preferences file remained unchanged byte-for-byte.

Both probes enter a fresh task with `NEW_TASK | CLEAR_TASK`. This avoids
`startActivitySync` waiting for a newly created Home Activity when `singleTask`
would otherwise reuse an existing instance. Production navigation is unchanged.

Native screenshots, hierarchy dumps and household content remain in private
test cache and are not included in this repository.

## Contrast and performance

An sRGB contrast calculation using the current native palette screened primary
text, secondary text and action accent against background, surface, surface2
and selected: twelve pairs. All exceeded 4.5:1; the lowest was **5.146:1** for
secondary text on selected. This is evidence for opaque token combinations,
not a certification of image-backed labels, disabled controls or every semantic
state. The earlier [contrast audit](portal-plus-contrast-audit.md) records the
previous teal palette.

A fresh unprofiled frame sample used six warm-up swipes and twenty measured
swipes on the same saved layout. Inputs lasted 260 ms, with 150 ms pauses.
Android's `gfxinfo` rolling buffer contained 120 measured frames:

| Median | p95 | p99 | Above 16.67 ms | Full interval: janky / rendered |
|---:|---:|---:|---:|---:|
| 8.03 ms | 19.92 ms | 24.91 ms | 8 / 120 | 19 / 260 |

The preceding [widget paging trials](portal-plus-widget-paging.md) had medians
of 8.14 ms and 7.96 ms, with 9 / 120 frames above 16.67 ms in each trial.
This small sample is consistent with their ordinary-frame timing; it does not
establish a performance improvement or guarantee smooth 60 Hz transitions.
Transition-start stalls remain: nine detected starts had a median of 22.53 ms.
The broader Home performance gates remain open.

## Browser parity

The complete mock, navigation demo and Tools redesign share the native dark
tokens. Verification passed 912 complete-mock checks, 212 navigation checks
and 130 Tools checks, with zero reported browser errors. Rendered pixel evidence
covers fourteen dark routes. Fourteen light routes retained identical
screenshot bytes across the palette change, and twenty-one explicit wallpaper
selection checks passed.

Home favorite cards use `#2B2B2B`, matching their native `ApprovedHomeSurface`
container. Inner controls use `#363636`; these roles must remain distinct.
Mock parity evidence is stored under
`mocks/portal-plus-navigation-live/neutral-parity/` and
`mocks/portal-plus-tools-redesign/neutral-parity/`.
