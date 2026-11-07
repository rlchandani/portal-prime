# Widget library: where it opens, where it lives, what each widget shows (2026-11-06)

Open `index.html`. **Prototype** is clickable end to end. **Widget catalog** shows all 15 kinds at S/M/L. Line numbers refer to the working tree on 2026-11-06, which was changing while I wrote this.

## Short answer: where does it live?
**It's a separate full-screen sheet over Home, not part of Settings.** `ApprovedHomeWidgetLibraryScreen` is a Compose `Dialog` (`ApprovedHomeWidgetLibrary.kt:50`, `:66`) owned by `HomeActivity`. It shows when `showLibrary` is true (`HomeActivity.kt:1402`). Home has to own it because adding an Android app widget needs Home's `AppWidgetHost` bind/configure callbacks.

Settings has a *Home & widgets › Home dashboard* page (`ApprovedSettingsNavigation.kt:93`, `:141`, rendered at `ImmortalSettingsActivity.kt:484`). That page only lists links to per-widget settings, plus a "Customize home" row. The row just starts `HomeActivity` (`:487`) and does **not** open edit mode or the library. A Settings intent with page `widgets` can ask Home to open the library (`HomeActivity.kt:474`, `consumeWidgetLibraryIntent`).

## Entry points (from source)
| From | Action | Goes to |
|---|---|---|
| Home header | **Customize** / **Done** toggles edit mode | `HomeActivity.kt:1302` |
| Any widget | Long-press enters edit mode | `HomeActivity.kt:672` |
| Edit mode, Favorite apps row | **Add widget** → library | `HomeActivity.kt:1040`, `:1352` |
| Library card | Built-in: added immediately. Android app widget: opens the installed-provider list, then bind/configure | `HomeActivity.kt:5355`, `:1404` |
| Library header | **Customize home** → Home in edit mode. **Back/Home** closes. Gear → Settings | `HomeActivity.kt:1402–1429` |
| Edit mode, each widget | **Move** (numbered list, any page), **Size** (cycles), **Settings**, **Remove** (confirm), plus a corner resize badge (drag, snaps to cells) | `HomeActivity.kt:689–727` |
| Widget header action ("Open", "Settings" in v2) | `openApprovedHomeSettings(page)` opens the Settings shell. Exceptions: Timers opens an in-place page (`:3624`) and Home Assistant opens its dashboard (`:4254`) | `ApprovedHomeUi.kt:458`, `ApprovedHomeNavigation.kt:31` |

## Sizes (true rules)
- The grid is 4×4 per page. The Photo memory hero (2×4) repeats on every page, and each page holds up to 4 other widgets (`HomePresentation.kt:26`).
- **When added:** `custom(kind)` gives 2×2 (Today, Quick controls and Photo get 2×1). Those defaults *display* as **1×2 Standard**, or 2×4 for the photo hero (`HomePresentation.kt:60`). The library has no size choice today.
- **Size button:** cycles 1×1 Small → 2×1 Medium → 2×2 Large → 1×1 (`HomeWidgetStore.kt:90`). A default 1×2 widget goes to 1×1 first.
- **Resize badge:** any span from 1×1 to 4×4 (`MAX_SPAN = 4`, `HomeWidgetStore.kt:68`). Next to the hero, at most 2×4 fits on a page.
- The rules are the same for every kind. Options appears only with Advanced on (`HomeActivity.kt:5338`).

| Kind | Default on Home | Supported spans |
|---|---|---|
| Today*, Home Assistant, Calendar, Weather, Alarm, World clock, Crypto*, Quick controls*, Timers, To-do, News*, Stocks*, Options* (Advanced only), Android app widget (added at 2×2) | 1×2 | 1×1, 2×1, 2×2 via Size, any up to 4×4 via drag |
| Photo memory | 2×4 hero | same as above |

\* Previously recommended to retire. These are tagged in the catalog, not hidden.

## Findings
1. **Settings › Customize home is a dead end.** It returns to Home without edit mode, so the user still has to discover long-press.
2. **Two different screens are called "Home & widgets".** The library title and the Settings category share the name but do different things.
3. **Quick controls' two settings actions go to different pages.** The header goes to Display (`HomeActivity.kt:3057`), while the edit-mode Settings action goes to Quick buttons.
4. **The edit-mode action bar covers almost all of a 1-row widget.** About 64 dp of a 156 dp cell, as the prototype shows.
5. **You don't see where a new widget lands.** It's appended to the last page while you stay on the current one. The prototype jumps to that page and says so.

## Recommendation
Keep the library **as a sheet over Home**. Adding a widget is a spatial action, and the app-widget host belongs to Home. Then:
- (a) Point Settings › *Customize home* at Home with edit mode **and** the library open, so the existing `widgets` intent path is actually used.
- (b) Rename the library "Add a widget".
- (c) Add the proposed **size step**, with live S / Standard / M / L previews, before adding. This is amber-tagged in the prototype and can be switched off to match today's app.
- (d) After adding, jump to the page the widget landed on.
- (e) Per-widget settings stay in Settings under Home & widgets.
