# Design and implementation contract

Revision: `portal-plus-ipad-01`. Status: **proposed; awaiting owner approval**.

The approval target is the **connected Portal+ in landscape**, with iPad-inspired navigation and iPhone-style settings rows. The approved reference images and prototype define the intended layout, colors, font assets, icon shapes, image crops, spacing, and illustrated states.

## Device calibration

| Property | Evidence / target |
| --- | --- |
| Physical display | 2160 × 1440 pixels, read through ADB |
| Density | 180 dpi, no override reported |
| Logical Android canvas | 1920 × 1280 dp; physical / logical scale = 1.125 |
| Firmware | Android 10, API 29 |
| Orientation | Landscape at inspection; rotation is enabled |
| Font scale | 1.0 is the reference assumption; the setting returned null, so a runtime configuration check is still required |
| App baseline | Debug app 1.64, version code 58 |
| Typography assets | Unmodified Roboto regular, medium, and bold copied read-only from this Portal |

A smaller browser viewport scales the whole device preview. It does not invent a different Portal layout. Portrait rotation, smaller Portal models, TV, and changed system text scale need separate native acceptance; this revision does not freeze those layouts.

## Everyday home

The default composition has a visible greeting and time, one large photo slot, four smaller cards, six favorite apps, and a four-item dock: Home, Apps, Photos, Tools. Settings and Customize remain visible.

The large slot occupies the first column and both rows. Photos uses this slot whenever present. If Photos is removed, the first remaining widget uses it. The smaller cards maintain a predictable row order; Customize offers Move and Remove, Add widget, and a visible Done action. Comfortable and Compact adjust spacing without discarding layout.

Five is the proposed first-page capacity, not permission to delete configured widgets. Native migration must preserve all existing widget IDs, options, order, provider bindings, and hidden or extra widgets. Existing configurations larger than five require a recoverable collection or secondary page; its final presentation must be reviewed before shipping. The mock illustrates a five-widget default, not that migration UI.

The mock's widget library includes the existing content families: photos, weather, calendar/Today, music, household controls, world clocks, countdowns, alarms, tasks, news, stocks, crypto, options, and timers. These use representative fixtures. Android provider widgets remain available and require separate size negotiation; their internal appearance belongs to the provider.

Favorites are a separate six-item selection. The full installed-app collection stays available in Apps, including folders. Third-party app icons in production should use their actual identity; the mock uses a consistent vector placeholder family.

## Settings and navigation

Eight presentation categories replace the long first-level list: Appearance; Photos & screensaver; Home & widgets; Sound & wake; Connections; Input & accessibility; Device; Advanced.

The existing ten settings domains remain intact. The settings registry continues to drive persistence, validation, side effects, on-device UI, and the phone remote. A category groups existing specs and navigation destinations; it is not a replacement persistence layer.

Rows use a label, optional explanation, trailing value or control, and a disclosure arrow only when navigation is available. Toggles activate from the whole row. Common options remain near their task; specialist configuration sits one level deeper. Search opens and highlights the exact setting, rather than just finding its category.

Rich source forms, album and clock pickers, and complex editors use existing or dedicated Activities reached through `NavSpec`. Browser view state is disposable and must not be copied into a second Android navigation model.

The mock establishes the shared row, form, picker, dialog, and focus patterns. It does not enumerate every field of every current editor. All current settings and integrations remain reachable; unillustrated settings adopt these patterns while preserving behavior.

## Shared visual measurements

`tokens.js` contains the palettes and principal measurements; `native.css` and reference images resolve screen-specific values. Their values take precedence over prose approximations.

| Element | Reference value in logical dp / sp |
| --- | --- |
| Main content padding | 56 |
| Settings sidebar | 368 |
| Settings row minimum | 100; grows for content |
| Primary button target | 68; secondary controls retain at least a 48dp floor |
| Card / inner / dialog radius | 24 / 16 / 28 |
| Default grid gap | 24 |
| Page heading / body / secondary / metadata | 44 / 26 / 22 / 18; explicit per-component exceptions in CSS |
| Roboto weights | 400, 500, 700 |
| Settings background, light / dark | #f2f3f7 / #14262d |
| Settings surface, light / dark | #ffffff / #213840 |
| Settings text, light / dark | #182c39 / #f4f7f9 |
| Accent, light / dark | #176abb / #9acaff |
| Home cards / accent | #223b42 / #b4dcd0 |

Use the same font files, explicit line height, and no extra Android font padding. Match icon paths, stroke widths, opacity, border placement, and `ContentScale.Crop` with center alignment. Map gradient coordinates and color stops explicitly; a default diagonal Compose gradient can differ from the browser's angle.

Surfaces use opaque fills, tints, borders, gradients, and scrims. No live background blur, Liquid Glass shader, remote fonts, emoji icons, or API 31-only effect is necessary.

## Interaction and state acceptance

- Home editing changes the visible composition; favorites remain independent of installed apps.
- Source setup retains the old source until confirmation. Show/hide password retains values. Pending work cannot navigate after Cancel. Validation and real server failures stay beside the relevant field.
- Album selection supports multiple albums, a count, and an explicit confirmation.
- Digital, Minimal, and Photo only establish the clock-picker pattern. Minimal is a proposed digital preset. Existing Flip and other supported clock choices must stay reachable; the mock is not an exhaustive face catalog.
- Timer supports duration, Start, Pause, Reset, and a visible countdown. Native completion must reuse existing timing and notification behavior.
- Offline Home keeps cached photos and weather, labels saved weather, and explains failed household actions. Store downloads are unavailable until connectivity returns.
- First-use cards offer contextual setup without making Home unusable.
- Home Assistant control and MQTT publishing have separate setup and status. Pending, confirmed, failed, and reconciled entity states require real native integration.
- Keyboard and remote navigation have visible focus. Modal focus stays inside the dialog and returns on dismissal. Native Back and visible Back lead to the same destination.
- Reduced-motion preferences suppress decorative movement. The mock has no idle animation; the 160ms token is a proposed native transition duration, not a measured native performance result.

## What sign-off commits us to

Native Immortal screens must match the approved geometry, colors, assets, typography metrics, and illustrated interaction design on this Portal+. The implementation must be compared using the supplied 2160 × 1440 images and the same fixtures.

Browser and Android rasterizers can produce different edge antialiasing even with the same font. A bit-for-bit image match is not established. This is not a license for different spacing, sizes, crops, or controls: visible mismatches must be fixed. If a hardware or platform constraint requires a design change, show the difference and obtain approval before treating it as accepted.

Android keyboards, permission prompts, system Wi-Fi/settings, package-installer dialogs, and third-party apps are outside Immortal's visual control. The design covers the Immortal screen before and after those handoffs. Real data changes text lengths, counts, dates, and images; verification uses fixed sample data without changing the device clock.

## Native delivery gates

1. Preserve the current Git state and user work. Create a new `codex/portal-plus-ipad-ux` branch before Android edits; see the implementation handoff.
2. Implement reusable tokens and components; keep registry bindings and existing side effects.
3. Validate `assembleDebug` and the relevant JVM tests, then install the approved debug build alongside the release using the existing signing and installation workflow.
4. Capture the actual Portal UI at 2160 × 1440 with matching fixtures. Compare all reference screens and states with overlays/diffs; report any remaining differences.
5. Exercise real touch, directional focus, Back, keyboard entry, rotation, font scaling, long content, empty/error cases, and service integration.
6. Check scrolling and transitions on hardware. No browser-only claim establishes native frame rate, GPU cost, TalkBack behavior, or photo-screen burn-in protection.
7. Preserve current settings, clock faces, screensaver rules, provider widgets, app launching, installation daemon, phone remote, and fleet functionality. Obtain owner acceptance of the installed result.
