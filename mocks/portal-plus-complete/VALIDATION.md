# Review verification and limits

## Browser verification

`verification.json` records 912 passing assertions and zero failures, page
runtime errors or external network requests in that verification run.
`verify.browser.js` is the reproducible Playwright function.

The checks cover:

- All 78 destinations in light and dark themes, including horizontal bounds.
- Reachability of all control definitions, with the Portal+ battery option
  correctly absent and automatic fields retained as read-only audit rows.
- All 184 picker, dialog, confirmation, boundary and state examples, including
  actual dialog visibility and absence of unresolved source/template values.
- Single Immich album selection, successful connection, cancellation ignoring
  a late result, and offline failure keeping the existing photo source.
- MQTT's TLS port convenience, timer start/cancel, to-do clearing, folder naming,
  widget identity through reorder/resize, voice memo states and actual story text.
- Device-name validation, all thirteen world-clock cities, source converter
  arithmetic, six-digit sample pairing PINs and locally rendered QR assets.
- Installation cancellation, completion adding radio to the app library,
  installer failure/retry, and permission refusal preserving the previous value.
- Centered full-screen clocks and correctly positioned Back overlays.
- Exact setting-search destination, prerequisite guidance without preference
  changes or invented calendar feeds, dialog focus and Escape dismissal.
- Gallery state links, asynchronous install completion, flow initialization
  running once, and full device links retaining the selected popup.

`conditional-verification.json` adds 293 passing assertions for parent gates,
native editor exceptions, all source/style cache and Fit combinations, Chime
actions, URL providers, HA grouping/search and supported domains, folder
navigation, 42 voice choices, and connection reply branches.
It also checks Alarm's five-minute wrap, optional camera/crypto names and
fallbacks, RTSP validation, the native default delta band, Clear completed,
and Lamp's red/hidden control states.

`home-layout-verification.json` records 360 passing layout assertions across
60 Home states at 2160 × 1440. Both themes, both widget pages, six scenarios,
all eight custom size options under the offline banner, no favorites and no
photo widget are checked. Favorites clear the dock by at least 24 dp, app
labels fit, touch targets remain at least 48 dp, and navigation stays within
the device canvas. `home-layout.browser.js` reproduces these checks.

`advanced-settings-verification.json` records 3,585 passing assertions with no
failures or runtime errors. It checks the simple default, explicit opt-in by
switch/search/deep link, all tiered destinations and controls, preservation of
all values and source selections, native parent gates, read-only managed fields,
and browser-memory/explicit-URL behavior. The exhaustive original suites opt
into Advanced; the new suite separately verifies the default experience.

These assertions establish a usable prototype and exercise relevant behavior.
They are not exhaustive tests of every value combination or a native APK.
The audit contains 33 visibility/state families and 138 sanitized native
walkthrough records. All 93 registry links were checked for inappropriate
editors; representative derived, navigation, boolean and hidden-field links
were opened without runtime errors or unwanted edit dialogs.

## Reference verification

`capture.browser.js` records 795 screenshots in bounded batches.
`capture-report.json` records the reached state of each planned capture.
`publish-references.py` verifies every planned image exists at 2160 × 1440,
builds thumbnails and records SHA-256 hashes in `references.json`.

The reference set contains:

- 156 destination/theme captures.
- 368 popup/state/theme captures.
- 14 additional settings-category captures.
- 50 additional catalog-detail captures.
- 14 additional onboarding-step captures.
- 14 screensaver styles, five photo clock faces and six digital clock faces.
- 20 empty, offline, permission and now-playing state captures.
- 110 conditional-state captures, including all eight Chime combinations in both themes.
- 38 additional simple-settings and Advanced-reveal captures in both themes.

Visual inspection covers the main shell, grouped settings, connection forms,
long lists, representative popup patterns, clock previews and photo engine.
All 18 Home references were refreshed for compact favorites and reserved dock
space; now-playing, offline and the second widget page were visually inspected.
Automated checks and screenshots do not prove subjective quality or pixel
equality with a future native implementation.

## Source and hardware evidence

The source inventory maps all 93 registry entries, 49 manifest Activities and
74 custom setter references. The catalog has 26 entries. File hashes and the
source commit are retained alongside references.

Device identity reads and the sanitized USB walkthrough verify Portal+, Android 10/API 29, density 180,
2160 × 1440 pixels, Immortal 1.64/version 58, 25 launchable components, twenty
distinct boot-selectable packages, and nine configured widget families.

The USB walkthrough covers every main Immortal settings section and the
source/picker/editor destinations. It includes all eight Chime combinations,
parent-switch roundtrips, photo-face variants, Classic/Fit, yearly/nonyearly
countdowns, and cancelled valid/invalid calendar drafts. Existing Home
Assistant discovery returned 1004 entities across 12 populated groups;
private entity names and IDs are omitted. Unconfigured source-server success
and error replies remain fixtures, with source mapping.
Configured Alarm/Crypto editors and all twelve Tools entry points were
inspected; Lamp red-mode visibility and converter category changes were
observed without saved edits. The Speed test tile did not expose its metric
pane during USB checks, so that pane remains source-mapped and requires native
verification during implementation.

The approved target is 1920 × 1280 dp at 1.125 pixels per dp. Use the same font
scale, content and controlled animation frame when comparing native screenshots.

## Explicit limits

- Stored credentials and the authenticated installed schema were not read.
  Native Home Assistant discovery was inspected using its unchanged existing
  connection; private addresses, tokens, entity names and IDs are not published.
  Automatic approval
  review rejected stored credential access as credential probing; that action
  did not execute.
- The source commit and installed version number do not prove identical APK
  source. Any discrepancy found during implementation must be mapped and
  reviewed before native completion.
- Browser, Portal Calls, YouTube Music, installed app internals and Android-owned
  interfaces retain their own UI. The review's boundary annotations do not ship.
- Every discovered Immortal setting and popup family has a mapping; this does
  not claim all private third-party and firmware submenus have been redesigned.
- Sample albums, home entities, rates, media, notes and connection
  results are fixtures, not current device configuration.
- No APK was built or installed for this mock-only work. Android source and
  tracked Git files were not edited. Native checks are required after approval.
- Native visual parity has not been established. The reference set defines the
  target for implementation on a new `codex/` branch.

## Device state restoration

Temporary preference switches are logged with their restored original values.
The native walkthrough also exposes intentional interface differences between
the registry and bespoke editors. Read `state-audit.html` and the sanitized
`device-walkthrough.json`; backend fixture checks are separated from hardware
observations. No source, credential, permission or APK replacement is part of
this review.

## Advanced presentation scope

The 14-page/56-control tier policy changes visibility only. It introduces a
proposed presentation preference, not a native backend capability. No native
registry entries were removed, and the existing 93-entry source audit remains
intact. Default settings and the full Advanced view are separate visual targets.
