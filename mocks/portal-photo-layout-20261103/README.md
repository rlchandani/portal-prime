# Portal photo layout review

This follows the user's report of small photos inside visible grid cells and
large dark margins. Paper and the later scoped matte/frame changes are installed
in both rooms through1.143/code137. Saved Shuffle/fit remains; the installed
renderer and currently displayed Shuffle style are separate observations.

Run from any directory with Python 3.9 or newer:

```sh
python3 /path/to/portal-prime/mocks/portal-photo-layout-20261103/serve.py
```

Open <http://127.0.0.1:8892/renderer.html> for the implemented renderer or
<http://127.0.0.1:8892/> for the earlier design comparison. The dedicated server
exposes only this review folder and ten allowlisted screensaver assets.
No device, account, household photo or remote image request is used.

The comparison's **Current renderer** view executes frozen `styles.css`,
`lib-engine.js`, `modes.js` and `mode-impls.js` from commit `d7e18bcb`. A deterministic fixture supplies
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

The implemented preview executes the actual production entry point with a
simulated host bridge and local samples. Native overlay reservations and
localized clock backing are implemented, and source/lifecycle/build checks
pass. Android 9/10 appearance, customized native clock colors, density,
burn-in behavior and Portal performance still need device acceptance. See
`docs/portal-photo-layout-paper-2026-11-03.md` in the repository. Existing crop
and slim-frame deliveries retain their earlier acceptance and clocks.
