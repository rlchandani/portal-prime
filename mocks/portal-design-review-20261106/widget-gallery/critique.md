# Home widget gallery: design review (2026-11-06)

**Verdict.** The 11-02 studio is a careful *audit*: 15 kinds, 78 states, sources traced. But it is not a *decision tool*. It shows every widget in three platform interpretations and asks no question the household cares about: which of these belong on a shared object in the living room? It also previews each widget alone, in a review frame, never next to a photo or next to each other. So the most important design property, how the family reads as one thing from three metres away, goes unexamined. The redesign answers that question: **eight widgets, three sizes, one accent, shown on a real Home.**

## What works
- The research is honest and thorough. State inventories, source paths and "illustrative data" labelling set the right standard for truthfulness.
- The approved Weather hierarchy is sound: a large temperature, then the condition, then city with high/low, then the forecast.
- The 48 dp touch floor, text scaling and the "no backdrop blur" rule are correct for Portal hardware.

## Problems, ranked
1. **No point of view about inclusion.** All 14 kinds are presented as equals. On a family Portal, Options (delta bands) next to Timers is a category error.
2. **Never composed.** No widget is shown on Home, beside the photo, the greeting clock or the dock, so duplication can't be seen. Today repeats Weather + Calendar + the greeting; World clock's local row repeats the 80 sp clock.
3. **Dishonest materials in Quick controls.** "Lights / Scenes / Climate" tiles look like controls but only launch the HA app (`QuickControlsWidget`). The real controls already exist in the HA widget.
4. **Widgets carry chrome.** Each card has a title row and icon ("Weather", plus a second weather icon), and the approved medium has a full-width **Weather settings** button. On a glance surface, settings belong to long-press and Customize.
5. **Text over the photo.** Photo memory overlays "Photo memory / Your selected photo source" on the one thing the room is for.
6. **Platform columns dilute the decision.** There is no iOS app, and Android/iOS/Portal render nearly identically. Three columns triple the reading for no gain.
7. **No family-level system.** Sizes are argued per widget; there is no shared type scale, no rule for when a label appears, and colour is used decoratively (yellow sun, green "status").

## Proposed family
| Keep | Size(s) | Notes |
|---|---|---|
| Photo | 2×4 hero, 2×2, 2×1, 1×1 | Full bleed, no text. **Caption (date/place) = requires new capability** (EXIF/album metadata isn't read today). |
| Weather (approved) | S/M/L | Hierarchy unchanged; heading and settings button removed; monochrome icons. |
| Calendar | S/M/L | "Up next"; relative "Now / In 33 min" derived from start time. Per-person colour = new capability, not shown. |
| Timer | S/M/L | One unnamed timer, which is what TimerStore holds. Named or multiple timers = new capability. The ringing state is the only full-colour card. |
| To-do | S/M/L | Count-led. Phone sync = new capability. |
| Home controls (HA) | S/M/L | Real toggles; scene is a button; unavailable is disabled. Replaces Quick controls. |
| World clock | S/M/L | Other cities only; offset in words. |
| Alarm | S only | One alarm = one size. |

**Cut from the Home library:** Today, Quick controls, News, Stocks, Crypto, Options. Existing placements keep rendering until the owner removes them. **Android app widget** stays in the library as an escape hatch, outside the family.

## Family rules (implementable on the current grid)
- **Sizes come from `ApprovedPackedWidgetGrid`** (4×4, 24 dp gap) and `nextWidgetSpan` (1×1 → 2×1 → 2×2). With the compact greeting row and no favourites strip, a cell is 434×235 dp. Photo 2×4 is already legal under `MAX_SPAN = 4`.
- **Type (sp):** Hero 96 / 80, Title 34, Body 26, Meta 22. Nothing is smaller than 22. Numerals are tabular.
- **Material:** warm neutrals (evolved from the approved cool `#14161b`/`#2b2b2b` to `#141311`/`#24221f`; light `#ebe9e4`/`#fbfaf7`), radius 36 dp shared with the photo, no border, shadow or blur.
- **One accent, one meaning:** green (`#6fcb9f` dark, `#1f7a50` light, from the approved `--green`) appears only for live state: a running timer, a light that's on, an event happening now, the ringing timer.
- **Labels only when content isn't self-evident** ("Up next", "Alarm", "left to do").

## Decisions for the human
1. Approve removing the heading and **Weather settings** button from the *approved* Weather widget (configuration via long-press/Customize).
2. Approve the cut list, especially Stocks/Crypto/Options, which someone added on purpose.
3. Composition changes outside widgets: merge the header and greeting into one row (reusing `compact` greeting, dropping "Everything within reach."), and drop the favourites strip from Home (Apps already holds it).
4. Warm-neutral palette shift vs the approved neutral tokens.
5. Fund either photo metadata captions or not. The design works without them.

Files: `index.html` (`?show=home&theme=dark|light` renders the device alone at 2160×1440), `shots/home-dark.png`, `shots/home-light.png`, `shots/specimens-dark.png`, `shots/specimens-light.png`. Photos are the app's bundled `photoframe_fallback` assets. All data is sample.
