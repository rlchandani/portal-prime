# Polaroid background receipt

The actual production Polaroid mode from the exact optimized1.141 APK is exercised
with three generated scenes: one landscape, one portrait and six mixed photos.
All images in `images/` contain generated marks. The structured receipt records
gates, exact artifacts, excluded fixture attempts, delivery and preservation.

Preview: <http://127.0.0.1:8892/renderer.html>, select **Polaroid prints**.
The existing local server uses bundled samples and fixed asset allowlists.

Build the independent fixture from its folder:

```sh
bash build.sh /absolute/path/to/the/exact/performance.apk /absolute/path/to/android-sdk
```

It embeds the supplied APK as `assets/source.apk`, grants no permissions and
serves only generated photos and fixed renderer assets. An authorized device
owner must check package absence, install/launch
`com.immortal.polaroidfixture/.PolaroidActivity`, read its bounded report under
`/sdcard/Android/data/com.immortal.polaroidfixture/files/`, and uninstall it.

The bootstrap supplies Polaroid's exact existing shuffle helper. Actual CSS
matrices and origins map photo corners/paper points into the screenshot.
The compositor fence precedes PixelCopy; background sampling excludes paper
bounds and80 CSS pixels of shadow. All photo corners and paper points must pass
the unchanged18 summed RGB-distance threshold. Existing gradient and whole-photo
geometry are checked independently of the new pseudo background.

This is direct initial-scene acceptance. Native clock/placement, scheduler,
resize, swapping, API28, Living visuals and performance remain separate.
