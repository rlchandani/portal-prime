# Portal+ complete design review — revision 03

This review replaces the representative revision 01 mock. It is a source-mapped,
interactive browser prototype for the connected Portal+. It does not modify
Android source, install an APK, or write settings to the device.

Start with [the interactive review](http://127.0.0.1:8765/portal-plus-complete/).
Use [the source coverage audit](http://127.0.0.1:8765/portal-plus-complete/coverage.html)
to find a specific setting or Activity. Use
[the visual gallery](http://127.0.0.1:8765/portal-plus-complete/gallery.html)
to review individual screenshots and open their interactive states.

## Coverage

- 78 destinations: 57 settings/editor panels and 21 launcher, preview, app and tool surfaces.
- All 93 registry entries: 85 settings/navigation specs and 8 derived fields.
- 168 control definitions, including custom editor fields and preserved automatic fields.
- All 49 manifest Activities mapped to a review destination.
- 184 picker, popup, confirmation, handoff and state examples.
- 74 custom setter references mapped alongside the declarative registry.
- 26 bundled catalog entries; installed and optional apps stay distinct.
- All nine configured widget families, with editing, ordering, sizing and paging.
- Eight settings categories, eight onboarding steps, fourteen screensaver styles,
  five photo clock faces and six digital clock faces.
- 757 references at the target's physical 2160 × 1440 resolution.

These counts come from `inventory.json`, `coverage.json`, `reference-plan.json`
and `references.json`. The [conditional state audit](http://127.0.0.1:8765/portal-plus-complete/state-audit.html)
records native visibility exceptions and data-dependent branches alongside the
registry. Backend replies and private pages inside installed applications remain
explicit evidence boundaries.

## Review behavior

Use Light/Dark, the review-state selector, screen search and the popup selector.
Settings search locates the matching row. If its parent is off or its required
data is missing, it explains the prerequisite and links to the parent editor;
search never enables a setting or creates a feed.
Dialogs support keyboard focus, Tab and Escape. Source forms, cancellation,
number validation, weekday selection, app installation, widget editing,
timers, notes, stories and connection errors can be exercised locally.

All data stays in browser memory. Reset preview reloads sample values.
Public sample QR codes resolve to `example.invalid`, not a live authentication
link. Password fields and private device connection values are not populated.

Home's favorite apps use 88 dp horizontal tiles with 56 dp icons. Navigation
has reserved layout space and a 24 dp gap above it, including offline banners
and custom widget sizes. The previous 18 Home images, CSS and manifest are
preserved in `revisions/03/home-before-compaction/`.

The `expanded` scenario exposes conditional controls for review. It deliberately
turns on options that may be off on the physical device. Current private values,
entities, albums and feeds are not copied into the mock. Observed installed
voice labels are represented; native implementation must enumerate voice IDs
from the device instead of hardcoding the fixture list.

## Device evidence and interface ownership

Read-only ADB verified Portal+, Android 10/API 29, 2160 × 1440, density 180,
Immortal debug 1.64/version 58, launchable packages and the nine widget families.
The native design canvas is 1920 × 1280 dp at 1.125 pixels per dp.

USB UI interaction covered all 21 Immortal settings sections, nested source
forms and pickers, all eight Chime parent combinations, and other conditional
parents. Original settings were restored. The existing Home Assistant
connection discovered 1004 entities; only public group names and counts are
published. Its connection fields, private entities and household controls
were left untouched. Forty-two installed offline voice labels are represented.
The Home widget editors and Tools destinations were also inspected. A native
Speed test opening discrepancy is documented in the state audit; its metric
pane is source-mapped rather than claimed as observed on this installed APK.

Music preserves the existing YouTube Music web app. VLC and the installed
Home Assistant minimal app are real installed entries. Transistor radio is a
catalog option. Browser, Calls, third-party app internals, Android permissions,
uninstall dialogs and widget-provider interfaces retain their existing owners.
Labelled boundary panels are review annotations, not new shipping popups.

Automatic approval review rejected reading stored fleet/remote credentials to
query the authenticated installed schema, describing it as credential probing.
That action did not execute. No credential values were read. The audit uses the
checked-out source and approved public/nonsecret device reads; installed version
numbers alone cannot establish byte-for-byte source equivalence.

## Native implementation after approval

See [contract.html](contract.html) and [IMPLEMENTATION-HANDOFF.md](IMPLEMENTATION-HANDOFF.md).
Create a new `codex/` branch **before** Android changes. Preserve the current
branch, existing working changes, preferences and widget identities.

The layout uses Android-compatible solid surfaces, fixed dimensions, bundled
fonts and vector icons. Photo animation previews reuse the existing WebView
engine. Native pixel parity remains to be established through implementation
and on-device comparison against the approved references.

## Reproducing the review

Serve the `mocks` directory on port 8765. Regenerate the source coverage with
`node mocks/portal-plus-complete/audit-coverage.cjs`, then the reference plan with
`node mocks/portal-plus-complete/plan-references.cjs`.

`verify.browser.js` and `capture.browser.js` are async Playwright functions
accepting a page. Capture runs in batches and keeps its cursor in that browser
page. `verification.json` records assertions; `capture-report.json` records
rendered reference states. `publish-references.py` validates PNG dimensions,
creates thumbnails and writes the gallery manifest with hashes.
`conditional.browser.js` exercises conditional states and native editor
exceptions. `build-state-audit.py --journal device-walkthrough.json` regenerates
the state audit from its sanitized published journal and source coverage.
`home-layout.browser.js` checks navigation separation, label fit and touch
targets across 60 Home states; its results are in `home-layout-verification.json`.

## Native drill-down audit

Revision 03 adds actual USB interaction through the main settings sections,
parent-switch combinations, source forms, pickers and nested editors.
`device-walkthrough.json` publishes sanitized labels and restoration records;
`state-audit.html` explains every mapped visibility family and native exception.
The browser checks reproduce those conditions using local fixture data.
The superseded revision 02 screenshot evidence is retained in `revisions/02/`;
the current gallery is the revision 03 review target.

The audit currently includes 33 visibility/state families, 138 sanitized
native walkthrough records, 912 general browser checks and 293 conditional
checks. All 93 registry links were checked for invalid editors; derived,
navigation, boolean and deliberately hidden fields route to their proper pages.
