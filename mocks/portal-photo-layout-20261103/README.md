# Portal photo layout review

This follows the user's report of small photos inside visible grid cells and
large dark margins. It is a functional local design study, not an installed
screensaver change.

Run from any directory with Python 3.9 or newer:

```sh
python3 /path/to/portal-prime/mocks/portal-photo-layout-20261103/serve.py
```

Open <http://127.0.0.1:8892/>. The dedicated server exposes only this mock folder
and four allowlisted screensaver assets; it does not serve the repository root.
No device, account, household photo or remote image request is used.

The **Current renderer** view executes the unchanged local `styles.css`,
`lib-engine.js`, `modes.js` and `mode-impls.js`. A deterministic fixture supplies
sample photos, disables motion and extends dwell. This proves the local static
WebView rendering; it does not reproduce Android's native overlays, viewport,
face metadata, natural Shuffle selection or installed version.

The **Proposed** view uses six larger whole photographs, proportional rows,
a narrow frame and a sparse static SVG background. Single photo expands within
the available region above a sample clock. Paper, Graphite and Sage are choices
for review. Display formats and sample shapes exercise the margins.

Nine samples derive from three existing bundled fallback images under
`app/src/main/assets/photoframe_fallback/`. Portrait, square and panorama samples
are cropped fixtures; the proposal displays each resulting sample in full.
They illustrate different shapes, not nine distinct household photos.

Production work still needs visual selection, native overlay reservations,
bounded photo residency, existing face/motion behavior, replacement lifecycle,
Android 9/10 rendering and device acceptance. Browser geometry and a static
SVG are not evidence of Portal performance. Existing crop and slim-frame
deliveries retain their earlier acceptance and clocks.
