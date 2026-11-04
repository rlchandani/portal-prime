# Owned Android Paper geometry fixture

`PaperActivity.java` is a temporary framework-only Android Activity used for
the installed 1.129 acceptance. It loads the target package's assets through
`createPackageContext`, generates six mixed-aspect PNGs with colored corners,
and owns its WebView, window, requests and bounded cleanup. Unknown resource
requests return an empty response rather than reaching the network.

The target is `com.immortal.launcher.debug`; the separate fixture package is
`com.immortal.paperfixture`. Native outputs were pulled before uninstalling the
fixture. No fixture APK or household state is checked in.

The recorded build used Java 21 `javac -source 8 -target 8` against Android
36 `android.jar`, followed by build-tools 36 `d8`, `aapt2 link`, `zipalign` and
`apksigner` with the ordinary debug certificate. Device operations were scoped
to the authorized Family Area Portal. Installation is a separate authorized
operation, not part of the host evidence checker.

Recheck the committed native corner evidence without extra dependencies:

```bash
python3 mocks/portal-photo-layout-20261103/native-fixture/corner-check.py
```

This reruns host PNG analysis only. The receipt covers direct mode geometry
with standalone clock-strip reservation and motion off. It does not establish
production entry-point/scheduler behavior, native FaceRenderer backing,
Shuffle coupling, API28 or Living Area appearance.
