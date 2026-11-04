# Cinema matte and frame receipt

The actual production Cinema mode from the exact optimized1.140 APK is exercised
with four generated scenes: landscape and portrait with motion off, plus a wide
panorama with motion off/on. Images in `images/` contain only generated marks.
`evidence/verification.json` records gates, exact artifact identities, failed
fixture attempts, room delivery, configuration preservation and limits.

Preview: <http://127.0.0.1:8892/renderer.html>, select **Cinema Letterbox**.
The local server uses existing bundled sample photos and fixed asset allowlists.

Build the independent fixture from its folder:

```sh
bash build.sh /absolute/path/to/the/exact/performance.apk /absolute/path/to/android-sdk
```

It embeds the supplied APK as `assets/source.apk`, grants no permissions and
serves only generated photos plus allowlisted renderer assets. An explicit
device owner must check package absence, install/launch
`com.immortal.cinemafixture/.CinemaActivity`, read its bounded report under
`/sdcard/Android/data/com.immortal.cinemafixture/files/`, and uninstall it afterward.
Do not run it against a device without authorization.

The fixture binds photo requests and visible layer state, waits for the WebView
compositor callback, then checks PixelCopy pixels. One physical border pixel is
accepted at Android's fractional density. Fitted landscape/portrait scenes must
retain all four colored corners; panorama keeps the existing cover crop and
does not claim full corners. Native clock, scheduler, resize, API28, Living
visuals and performance are outside this receipt.
