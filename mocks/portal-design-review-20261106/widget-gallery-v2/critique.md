# Home v2: a conservative refinement (2026-11-06)

v1 (`../widget-gallery/`) changed too much. v2 starts from the current Home as captured on the Family Portal and only fixes things that are demonstrably wrong or duplicated. Open `index.html`. It defaults to side by side with changes shown; use `?view=before|after&changes=0&solo=1` for a full-bleed device.

## Kept as is
- Header: wordmark, room name, photo-frame button, Connected, Customize, and all six round buttons.
- Greeting, "Everything within reach.", the large clock and the date.
- The grid layout, card material, borders, radius, and the current ApprovedDarkPalette. The yellow weather icon stays.
- Every card: Photo memory (with caption), Home Assistant, Today, Calendar and Weather.
- Favorite apps: all six apps, the heading and All apps.
- Home/Apps/Tools navigation and the debug button.

## Changes (numbers match the overlay)
| # | Type | Change | Why |
|---|---|---|---|
| 1 | Removed | Today's "Add your calendar" prompt and button | It appeared twice on one page. The Calendar card keeps it. |
| 2 | Removed | Home Assistant header "Open" | It calls the same `openDashboard` as "Open dashboard" two lines below. |
| 3 | Changed | Today's feels-like/moon/sunrise/sunset line becomes a 2×2 fact grid | Same four facts, readable from a distance. |
| 4 | Changed | Header "Open" becomes "Settings" (Today, Calendar, Weather), in grey | In `ApprovedHomeWidgetHeading` it calls `openApprovedHomeSettings(...)`. It never opened the content. |
| 5 | Changed | "Open dashboard" and "Add calendar" are flush with the text and sit on a soft pill | `ApprovedHomeTextButton`'s 20 dp padding indented them. |
| 6 | Changed | Photo caption gets a neutral, taller scrim | Keeps the caption legible on bright photos and removes the teal cast. |
| 7 | Changed | Favorites tiles go from 88 to 72 dp, icons from 56 to 46 dp, heading from 26 to 24 sp | You asked for smaller and good-looking. Nothing is removed. |
| 8 | Moved | "1 of 2" moves under the widget grid | It's the widget page counter (`page.index`), not favorites paging. |
| 9 | Added | Page dots next to the counter | Gives widget swiping a visible cue. |

## Recommended, deliberately not done (opt in)
- **"Feels like" is mislabeled.** Today builds it from `current.temp` (`HomeActivity.kt:3148`), so it shows the actual temperature. Either label it "Now" or use the apparent temperature. This is a truthfulness bug, not a style choice.
- Merge Today into Weather. Today's remaining facts are weather and sky.
- Drop "Everything within reach." so the widgets get roughly 40 dp more height.
- Hide the debug button in non-debug builds.
- Add a weather condition word ("Cloudy"). This needs a WMO code-to-text map. The data exists, but no text mapping does.
- The v1 ideas (removing header icons, a full-bleed photo with no caption, cutting widget kinds) stay in `../widget-gallery/` for reference.

Privacy: the real capture was only viewed, never copied. The photo tile uses the bundled `photoframe_fallback/02_denali.jpg`.
