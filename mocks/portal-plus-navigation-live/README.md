# Browser navigation demo

Open <http://127.0.0.1:8765/portal-plus-navigation-live/> with the existing server
serving `mocks/`. This is a browser demo with sample fixtures, not live Android,
a device connection, or an emulator.

The approved `portal-plus-complete` scripts, styles, fonts, images and fixtures
are loaded read-only. Its renderer runs in a hidden staging element in the same
document. This demo projects actual fixture HTML into its own visible shells;
there are no screenshot transitions, iframe reloads, copied fixture scripts,
reference regeneration, or changes to the complete/signoff previews.

The visible launcher header and dock remain mounted through Home/Apps/Tools.
The three tab containers remain mounted and use 160 ms horizontal transitions.
Visited tab content is retained; local app/tool drill-ins use their tab's history.
Home and Customize project the same frozen Home cards into one deterministic
four-column first-fit layout. The base area has four rows and a 24 px gutter;
short cards leave reusable space beneath them independently of taller neighbors.
Every unsized photo uses 2 × 4 and every other unsized card uses 1 × 2.
Each saved size is an explicit preset, including 2 × 1 and 2 × 2; resizing one
card never switches the defaults of its neighbors or another page. Entering
Customize and pressing Done preserve card geometry, the selected page and its
scroll offset. The mounted header Customize button becomes Done; Add widget
appears in the existing Favorite apps row. Previous/More widgets remain available
while editing. Greeting, favorites and the content viewport keep their positions.
Size, Remove, Move and Settings controls overlay the real cards and invoke the
frozen renderer's existing indexed actions and dialogs. Move uses its existing
menu, committing only the selected destination. This browser simulation does
not emulate native held dragging or edge auto scroll.

Saved sizes are joined to current original indices through the frozen editor's
Size controls and labels; private widget keys are neither inferred nor changed.
Additional photo widgets remain distinct paged items with their own sizes and
actions. Each page combines the shared first photo with four paged items, then
sorts that visible subset by current saved index. Moving Photo therefore changes
its actual position in both Home and Customize; a saved photo-first dashboard
keeps its original layout. The frozen Home omits additional photos, so the
projection uses its photo markup for each indexed instance and extends the
visible page count. Its
existing page action keeps the fixture page synchronized within the fixture's
older count. No frozen API, fixture file, or native code is changed.

Apps projects installed app tiles without the separate Options controls or the
duplicate builtin Tools tile; Tools remains in the footer dock. A regular tap
launches an installed app's existing handoff simulation. Touch/pointer hold
(550 ms within 10 browser CSS pixels), right-click, or focused Shift+F10/Menu key
opens the frozen renderer's existing app action dialog. A recognized hold
suppresses its release click. Movement, scrolling, release, pointer cancellation,
another contact, navigation, window focus loss, or page visibility changes cancel a
pending hold. Touch scrolling remains enabled. These refinements apply only to
the projected Apps grid; other categories and Options settings remain available.
Apps tiles and icon containers have transparent backgrounds, no individual card
borders/shadows, and no artificial colored squares. Builtins and missing-art
fallbacks use plain glyphs in the launcher text palette (`var(--text)`, native
IosLabel `#F4F7F9`), without colored tile fills.
The frozen installed-app inventory supplies glyphs, so its default icons are
explicitly labelled sample fallbacks. Supplied image markup or an installed
fixture's optional `artwork` URL is displayed with `object-fit: contain`, without
a background plate, clipping or tint; the supplied image keeps its original
pixels and is not replaced by Home icon curation. A failed image falls back to a
glyph in the same legible text palette.
Artwork URLs are restricted to this origin or inline image data. This does not
extract artwork from a Portal or claim that fixture glyphs are real app icons.
Home favorites, Store icons and app folders retain their approved styling.
The live projection hides Google Search app entries by both package identities:
`com.android.quicksearchbox` and `com.google.android.googlequicksearchbox`.
This also removes captured shortcuts from folders, favorites pickers, recent
apps, app search results and package choices. The original inventory and frozen
renderer remain unchanged. Internal Apps/catalog/Settings search fields and
other results remain available; the shared search glyph is not removed.

Tools keeps one mounted, stacked workspace header within 56 px horizontal /
36 px vertical outer padding. Back sits on its own row with a 68 px minimum
height, followed by a 26 px gap and a 44 px title with 56 px line height,
-0.5 px letter spacing and 34 px bottom padding. The scroll body follows below
the fixed header with no second inset or generic description. Fixture-specific
sample labels remain in the body or the demo toolbar.
Its list DOM stays mounted behind the current detail;
Back/Escape restores its scroll, and tab switches retain the current tool.
Tool content changes do not animate the workspace or add a nested slide.
The browser route mapping, aligned with the native Tools owner's supplied
boundaries, is:

| Tools tile | In-tab controls route | Display boundary |
| --- | --- | --- |
| Alarm | `alarm` | `alarm-ring` fullscreen simulation |
| Cameras | `camera` | View → `camera-preview` fullscreen simulation |
| Countdowns | `countdowns` | Sample controls |
| Lamp | `lamp` | Preview → `lamp-preview` fullscreen simulation |
| Bedtime story | `stories` picker → `stories` with a `reader` title | Dark in-workspace sample reader; no device TTS |
| Intercom | `intercom` | Frozen sample audio action; no network audio |
| Timers | `timers` | Fixture countdowns |
| Leave a note | `notes` | Sample note/memo actions |
| Converter | `converter` | Fixture calculations/rates |
| ISS passes | `iss` | Sample passes |
| Aurora outlook | `aurora` | Sample readings |
| Speed test (Advanced) | `speed` | Sample result; no bandwidth test |

Cameras' add/remove form and Lamp's brightness/warmth/red controls run in the
Tools pane. Camera View and Lamp Preview alone mark fullscreen handoffs; returning
keeps the controls route and fixture values. No camera stream, screen brightness
or hardware light is driven. This mapping is the browser implementation; the
native Tools owner's supplied mapping agrees with these boundaries: all twelve
tiles enter the shared workspace, only saved-camera View and Lamp Preview leave
for display, and Bedtime Back returns reader → picker → Tools. The frozen story
dialog's text and reading controls are projected into the dark Tools body below
the same fixed heading. Browser speech controls remain explicitly simulated.
Photos, Music and other fullscreen destinations mark their
fullscreen handoffs. Third-party and system interfaces retain the approved
boundary annotations, explicitly labelled as simulations.

Settings has a separately retained header/sidebar and at most four detail DOM
panels, evicted in least-recently-used order. Details use 160 ms opacity/24 px
translation transitions. Back/Escape returns to the local parent, then the
originating launcher tab. Category selection starts that category's local stack.
Scroll offsets survive Back, tab switches, and Settings cache eviction.
Home/Apps/Tools always use a dark launcher palette. The Settings appearance
selector changes only Settings and its internal editors; launcher colors and
launcher dialogs stay dark. Fixture changes refresh cached content when revisited.
Reduced-motion preferences suppress transitions.

Shared Settings pages follow the implementation owner's current boundary:
display/appearance/wallpaper/weather/calendar/quickbar/touch/device/system/managed/
debug/speed/screensaver/sources/styles/faces/clock/chime/welcome/sunrise/alarm/sleep/
world/health/multiroom/mqtt/boot/countdowns/crypto/todo/news/stocks/options/alpaca/ha/
intercom. HA controls (`ha`) share the pane; the entity chooser (`ha-entities`)
remains a separate `HaSettingsActivity` simulation.

Credential forms (Immich/SMB/WebDAV and Immich's album stage), folder/dismiss/URL
entries, Phone Pair, Help, APK browser and the HA entity chooser use a labelled
separate internal Activity simulation. Only one such content window is kept,
with a 160 ms fade over the
already-rendered themed parent. This demonstrates a window boundary without a
black preview; it does not establish Android window timing or pixel parity.
Connections, permissions, installation, media, voices and hardware
results remain sample behavior supplied by the original renderer. Nothing talks
to a Portal. Nested tool pages do not add a new slide animation that native Tools
does not have.

`window.__portalNavigationDemo` exposes route/cache diagnostics and navigation
for browser verification. It is not a device API.

Run the isolated browser checks with the authorized runtime and existing
loopback server:

```sh
PLAYWRIGHT_MODULE=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright node mocks/portal-plus-navigation-live/verify.browser.cjs
```

The script rejects any other runtime override or origin; every browser context
blocks requests outside `http://127.0.0.1:8765`. If browser execution is blocked,
report that limitation without switching runtimes or bypassing restrictions.

The gate defines 143 checks: it retains all 101 navigation/Apps/widget/photo-order
checks and adds 42 parity checks, with the
camera/Lamp fullscreen checks now targeting their explicit display routes.
It adds transparent tile/container/artwork, legible text-palette glyph, failed-image fallback,
focus, unchanged Home/Store styling, all twelve Tools routes, common workspace
identity, retained list scroll, tab return, camera form/display and Lamp
control/display regressions, plus dark Bedtime reader → picker → Tools returns
and fixed workspace chrome while the reader body scrolls.
The artwork regression injects an explicit inline
sample image; it is not a native app icon capture.
Google Search regressions use the captured stock shortcut, change its label
in memory and add the alternate package fixture to prove package-based filtering
in Apps, the Settings folder, both pickers and internal app search. Internal
Settings search must still find brightness controls. No frozen fixture file is
written. Widget tests establish the
approved photo-first default through the existing Move action because the frozen
sample's raw order lists Photo last. Coverage includes widget geometry,
page-local sizing, packing/gutter, indexed actions, pointer, real touch, keyboard,
cancellation, Apps projection and dock behavior.
It prints the passed count and browser errors, including console
and failed-request errors.
Scroll checks constrain the short sample Apps grid to exercise actual overflow.
The checks do not write screenshots, reports, references, or approved files.
