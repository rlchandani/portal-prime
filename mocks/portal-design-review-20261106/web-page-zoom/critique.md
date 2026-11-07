# Web page size: critique and redesign

## What went wrong, beyond the engineering
The candidate treated "zoom" as a number to store, not as something a person sees. It offered an Automatic option with no definition, plus 50–200% steps nobody can picture from across a kitchen. It also had no way to see the result. You picked a percentage, left settings, waited for the screensaver and guessed. Because the outcome was never visible during the design, nobody noticed it changed nothing: Automatic, 75% and 125% all rendered at scale 1.8, a 1200 CSS px layout and identical pixels. A control that does nothing is dishonest. One that does nothing and can't show it is unfinished.

Why 1.8: the page in `PhotoFrameController.startWebPage` runs with `useWideViewPort` + `loadWithOverviewMode`. A `width=device-width` page gets 2160 px / density 1.8 = 1200 CSS px, and overview mode fits that width to the screen. Visual-scale levers like `setInitialScale` and `zoomBy` get clamped back to "fit width" by overview mode. Even when one takes effect, it magnifies and crops. It never reflows. That is consistent with the flat 1.8 result.

## The real problem (issue 150)
"Zoom the dashboard to make it fill the screen and keep it coherent with the HA app." The person isn't asking for magnification. They want the dashboard to choose a different layout: fewer, bigger cards that fill the frame and read from 3 m away, or more cards for a close-up wall panel. Responsive dashboards change layout based on CSS width, so the only control that works is one that changes the page's CSS width. A secondary need: a fixed-width page must never be cut off.

## Recommended experience
- **Fitting is automatic, with no setting.** After load, if `scrollWidth > innerWidth`, widen the layout viewport to the content width so the page fits. Nothing is ever cropped sideways.
- **One "Page size" control with five named stops:** Smallest, Smaller, Default, Larger, Largest (75/90/100/125/150%). Default stays exactly what ships today. Each stop has a one-line sentence describing the result ("Fewer, bigger cards. Easier to read across the room."). No percentages appear in the primary UI.
- **Live preview next to the control,** plus "Adjust while viewing the page". This opens the real page full-screen with a quiet bottom bar (−, stops, +, Done). The page reflows under your finger. That is how you learn what each stop means.
- It lives on the existing Web page page (`ApprovedSettingsPage("web")`) as one SettingSpec (`webPageSize`, an int index, default 2). It is not a parallel system.

## Recommended implementation: B, viewport width
Inject a document-start script with `WebViewCompat.addDocumentStartJavaScript` (androidx.webkit 1.17.1 is already a dependency; gate on `WebViewFeature.DOCUMENT_START_SCRIPT`). The script sets, or rewrites through a MutationObserver, `<meta name=viewport content="width=W">`, where W = round(viewWidthPx / density / size). Keep wide viewport and overview mode on, so Chromium fits W to the screen. Scale becomes 1.8 × size, raster stays crisp, layout really reflows, and `vh`/`vw` stay correct. Run the fit-to-content pass from `onPageFinished` via `evaluateJavascript`. Fallback if the feature is missing: re-apply in `onPageCommitVisible`, accepting one reflow.

Trade-offs considered:
- **A, CSS `zoom` on `<html>`:** crisp and reflows, but in Chrome 138 `100vh` layouts overflow or leave gaps by the zoom factor, and fixed elements and some JS geometry get skewed. Keep it as a fallback only.
- **`setInitialScale` / `zoomBy`:** visual magnification only. It crops at >100% and is clamped to fit by overview mode. This is what failed.
- **`textZoom`:** scales only text and breaks card layouts.
- **Scaling the View transform:** reflows, but upscaling blurs text.

## Native acceptance test (must pass on Family 2160×1440 and on Living before any room promotion)
Use a generated fixture with `width=device-width`, a 200 CSS px probe square, an `auto-fill minmax(250px)` grid and a `height:100vh` bar. For Smallest, Default and Largest:
1. `innerWidth` = round(1200 / size) ±1, and the WebView scale = 1.8 × size ±0.01.
2. Measured in the PNG, the probe is 360 × size px.
3. The grid column count changes: Smallest > Default > Largest.
4. The bottom edge of the 100vh bar sits at 1440 px, with no gap and no overflow.
5. A 1600 px fixed-width page at Default fits fully, with no horizontal scroll.
6. A page with no viewport meta behaves like 1–3.
7. Tap-to-exit still works.
8. The setting survives frame restart.
9. On a 1280×800 Portal, Default is pixel-identical to today.

Then run one real Home Assistant dashboard on each room, with captures checked by eye.
