# Owned native clock-contrast fixture

`ContrastActivity.java` runs actual production FaceRenderer classes from an exact
debug APK copied into the temporary fixture's assets as `source.apk`. A
framework-only DexClassLoader keeps the complete debug Kotlin runtime together.
The target package context supplies matching resources and fonts; a ContextWrapper
redirects every preference read to the fixture's empty `generated` preferences.
The renderer is inactive before starting, with weather and battery disabled.

The generated face has a dark clock/date and white caption in the same position.
The fixture samples the actual bucket Drawable, draws the native root into a
software Canvas bitmap, checks Paper padding/background exit and re-entry, then
stops its renderer and finishes. It owns a 15-second cleanup deadline. The recorded
exit/re-entry toggles have no intervening layout; they check state restoration,
not remeasured geometry. It neither loads household photos nor modifies their
settings. Native music, dividers, hidden metadata, low opacity, API28, optimized
runtime and actual slideshow/controller coupling are outside this fixture.

Build with Java 21 `javac -source 8 -target 8` against Android36 `android.jar`,
then build-tools36 `d8`, `aapt2 link`, `zipalign` and the ordinary debug signer.
Alongside `source.apk`, supply `assets/mapping.json` with the unminified class and
member names used by the Java source. The successful configuration and exact APK
hash are recorded in the aggregate contrast evidence.

The separate fixture package is `com.immortal.contrastfixture`. Only the authorized
Family Area Portal was used. Outputs were collected before the fixture package
was uninstalled. No fixture or source APK is committed.
