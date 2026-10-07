# Browser validation

Executed October 4, 2026 against the local prototype, using Playwright.

## Layout and resource checks

34 page combinations checked:

- Everyday: Home, Settings, Source, Customize, Apps, Store, Photos at 1440 × 960, 2160 × 1440, 1024 × 768, and 390 × 844.
- Gallery and Control: populated, offline, and empty home states at 1440 × 960.

These checks reported no horizontal body overflow, missing images, unnamed icon-only buttons, JavaScript page errors, or failing HTTP resources in the tested combinations. Vertical scrolling on smaller screens is expected. Comparison previews scale a 1440 × 960 frame; target sizing should be judged in the full-screen prototype and later on the Portal.

Populated home geometry was checked after refinements: favorite apps end above the floating dock in all three 1440 × 960 layouts. Screenshots were visually reviewed for Everyday, Gallery, Control, light/dark settings, compact settings, source setup, and the comparison workspace.

## Interaction checks

Passed:

- Settings search opens the right category and highlights the specific row.
- Settings switch to dark appearance.
- Invalid sample source details produce an inline error.
- Valid sample details reach album selection.
- A selected album appears in the source summary.
- Widget removal updates the home when Done is selected.
- Move reorders the editing preview without dragging.
- App search filters the library.
- Store actions preview Get → Adding → Open.
- Offline recovery returns to connected sample state.
- Arrow keys move button focus.
- Dialog opens and Escape closes it.
- Focus survives a state-changing button rerender.
- Comparison metadata follows navigation inside the frame.
- Direction switching preserves the selected review screen.
- First-use scenario and research-note controls work.
- Four report tables and 18 external source links render.
- Report has no horizontal body overflow at 390 × 844.
- Direct source entry selects Photos & screensaver in the sidebar.
- Compact settings retain a usable slider and horizontal theme selector.

The test synchronization was adjusted to wait for iframe navigation messages and to account for scrollbar width when checking overflow.

## Sampled contrast

Calculated using WCAG relative luminance for opaque color pairs:

| Sample | Ratio |
| --- | --- |
| Light settings primary text on white | 14.27:1 |
| Light settings secondary text on white | 5.61:1 |
| Dark settings secondary text on its card | 7.65:1 |
| Home secondary text on its card | 7.10:1 |
| Light primary-button text | 6.55:1 |

These sampled pairs exceed 4.5:1. This is not a full accessibility audit or certification. Dynamic image overlays, focus order, TalkBack, font scaling, physical touch targets, and all native states need separate acceptance.

## Scope and preservation

JavaScript syntax checks passed for `prototype.js` and `review.js`.

`git diff --exit-code -- app provisioning catalog.json version.json` passed after the prototype work. Production files were not edited. New deliverables are contained in this mock folder; pre-existing mock files were left intact.

No APK was built or installed for this design study. No device setting or service was changed. Source credentials, store installation, music, and smart-home interactions in the suite are local simulations.
