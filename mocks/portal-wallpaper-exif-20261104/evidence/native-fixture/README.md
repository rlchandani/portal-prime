# Generated native wallpaper regression

The frozen fixture calls the actual debug APK private local-wallpaper reader with
in-memory preferences and generated JPEGs. It needs no household media or settings.
The root driver requires the explicit authorized Family endpoint and retires its
separate package. Pass the exact source APK embedded as assets/source.apk.

Compile with the Android jar on the **classpath**, retaining host boot classes:
`javac -source 8 -target 8 -classpath "$FIXTURE_ANDROID_JAR" -d classes WallpaperExifActivity.java`.
Then D8 the generated classes with that Android jar as `--lib`, package the
zero-permission manifest/generated assets/source APK, align and sign with a separate
fixture key. Use the same DEX and JPEG bytes in both phases. The original
bootclasspath command failed before device use and is excluded in the receipt.

`root-driver.py run` retains behavioral failures for baseline collection; candidate
acceptance additionally requires report.ok, eight passing cases, normal retirement
and independent PNG replay. This is bitmap-leaf acceptance, without mounted Home,
blur, optimized-runtime, API28 or Living visual acceptance.
