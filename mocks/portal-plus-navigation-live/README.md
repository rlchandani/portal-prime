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

The gate retains the original 39 navigation checks and adds isolated pointer,
real touch, keyboard, cancellation, Apps projection and dock assertions. It prints
the passed count and browser errors, including console and failed-request errors.
Scroll checks constrain the short sample Apps grid to exercise actual overflow.
The checks do not write screenshots, reports, references, or approved files.
