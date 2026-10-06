# Portal+ app icon correction

The Apps drawer had lost its visual consistency when removing app-cell backgrounds:
installed apps used raw Android artwork while App Store, Music, Calls, and Settings
became plain toolbar glyphs. The existing renderer also left opaque pixels outside
its intended outline.

Apps now uses Home's curated artwork, at 112dp, with 24sp regular labels and clear
app cells. App Store has blue bag artwork, Calls has a larger white phone on green,
and Settings has a charcoal gear on silver. Music retains its pink artwork.
“Immortal App Store” is shortened to “App Store” in the drawer.

## Rendering and inventory

`IconRenderer` uses an antialiased fourth-power superellipse, clearing its exterior
with an inverse path. The original filled `DST_IN` path did not cover the exterior.
A full-canvas `ALPHA_8` bitmap mask also failed the native pixel checks on the
connected Android 10 Portal+. Inverse-path `CLEAR` passes those checks.

The renderer retains four fixed-complexity paths and no mask bitmaps. Output stays
144 × 144 pixels by default, sufficient for the drawer's 112dp artwork at the
device's 180dpi density. Existing raster and adaptive-layer composition is retained.
Every render owns its output; icons are prepared during inventory loading on IO.
The unused raw `AppEntry.artwork` bitmap is removed, and original Android artwork
is loaded only when no curated drawable exists. This avoids retaining a second
144px raw icon bitmap per entry and its render; a 144px ARGB_8888 bitmap holds
81 KiB of pixel storage.

Installed entries and shortcut artwork come from one captured Home inventory and
publish as one `ApprovedAppsInventory` value. Recent Apps captures the same shared
inventory, with an IO fallback for rare unmatched entries. It has no independent
package icon cache that could survive Home's package-change invalidation.
Home favorites no longer apply a second rounded-rectangle crop to the rendered icon.

## Browser review

The live navigation demo uses 15 native PNG exports under
`mocks/portal-plus-navigation-live/assets/app-icons/`, with hashes and APK provenance
in `manifest.json`. Its installed-app inventory remains sample data; matching
artwork is actual cached native output. Explicit sample artwork can still be used
by regressions, and missing sample artwork keeps a legible fallback.

The frozen approval demos are untouched. The live demo accepts `?screen=apps`
to open this review directly.

## Scope

This corrects icon presentation and inventory consistency. It does not establish
completion of the broader performance acceptance or exhaustive settings audit.
The debug APK also includes the preexisting uncommitted `TvFocus.kt` performance
candidate; that file is outside this icon change's commit.

## Validation — 2026-10-05

- `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest --no-daemon`
  succeeded; 531 JVM tests, zero failures, errors, or skips.
- Native regression failed against the previous installed app, proving the opaque
  corner defect. All 24 pixel checks pass against the final app: legacy,
  translucent, adaptive, gradient, antialiasing, multiple sizes, and independent outputs.
- The final native runner passed 23 check groups, including that pixel suite,
  actual long-press actions, folder and Recents navigation, three stable tab revisits,
  the Apps Calls shortcut reaching Meta Home, and Home recreation retaining Apps.
  Nine original preference files remain byte-for-byte unchanged.
- The live browser suite passed 145 checks with no browser errors, including native
  shortcut PNG alpha checks, transparent app cells, focus, navigation, and fallback artwork.
- Independent source review found no remaining concrete issues after the atomic
  inventory and duplicate-crop corrections.
- Final installed APK matched the built artifact, SHA-256
  `9a3118f226f82560b930dbc85a648c41b884011ce397a94e1cda4481dc7e9806`.
  The observer exited successfully; the device was left on Apps for review.
