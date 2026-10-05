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
menu, committing only the selected destination.

Saved sizes are joined to current original indices through the frozen editor's
Size controls and labels; private widget keys are neither inferred nor changed.
Additional photo widgets remain distinct paged items with their own sizes and
actions. The frozen Home omits those additional photos, so the projection uses its
photo markup for each indexed instance and extends the visible page count. Its
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
the projected Apps page; other categories and Options settings remain available.
Alarm, countdowns, intercom, stories, timers, notes, converter, ISS, aurora and
speed details render in the Tools content area. Cameras and Lamp mark their
fullscreen handoffs. Photos, Music and other fullscreen destinations mark their
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

Run the isolated browser checks with a local Playwright installation:

```sh
PLAYWRIGHT_MODULE=/path/to/playwright node mocks/portal-plus-navigation-live/verify.browser.cjs
```

The gate retains all 65 navigation/Apps checks and adds isolated widget geometry,
page-local sizing, packing/gutter, indexed action and duplicate-photo assertions.
It also covers pointer, real touch, keyboard, cancellation, Apps projection and
dock behavior. It prints the passed count and browser errors, including console
and failed-request errors.
Scroll checks constrain the short sample Apps grid to exercise actual overflow.
The checks do not write screenshots, reports, references, or approved files.
