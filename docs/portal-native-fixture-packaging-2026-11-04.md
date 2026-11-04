# Repeatable native fixture packaging

`scripts/portal_native_fixture.py` replaces the hand-written compile/package steps
for generated native tests. It keeps the Android jar on Java's classpath, includes
nested classes in D8, snapshots inputs, stops on the first failed stage and
publishes a complete unsigned APK without replacing an existing output.
Temporary staging is retired on success and failure.

Run from the repository root with explicit local paths:

```sh
python3 -B scripts/portal_native_fixture.py \
  --source /path/to/FixtureActivity.java \
  --manifest /path/to/AndroidManifest.xml \
  --assets /path/to/generated-assets \
  --source-apk /path/to/app-debug.apk \
  --android-jar /path/to/android-36/android.jar \
  --build-tools /path/to/build-tools/36.0.0 \
  --javac /path/to/jdk/bin/javac \
  --output /path/to/new-fixture.apk
```

Repeat `--source` for additional Java files. The asset directory must not contain
the reserved `source.apk`. Output is unsigned and aligned; the helper performs
no signing, installation, device invocation, authentication or publication.
The existing Python CI discovery includes its regression tests automatically.

Eleven offline tests cover payload identity, input changes, classpath policy,
failed/missing products, timeout/execution errors, deadline validation, output
collisions and symbolic links. A real SDK run packages the preceding generated
wallpaper fixture in **2.70 seconds**. Its DEX, eight JPEGs and embedded debug
1.168 APK are exact against the previously accepted inputs. Its manifest has
zero permissions. This is packaging acceptance; the new unsigned APK was not
executed, and no improvement in whole-release time is measured.

The initial missing-module result precedes this new implementation. A subsequent
test-only macOS path-alias comparison failure was corrected without weakening
file identity. Neither is claimed as product behavioral RED.

The short photo/editor and converter/alarm audits found no admitted new bug;
older issue clocks remain held. Original clock **16:13:41 UTC**, target
**16:28:41 UTC**, hard deadline **16:43:41 UTC**. App source, version, public
manifest, devices and their saved settings are untouched by this unit.

Evidence: `mocks/portal-fixture-packaging-20261104/evidence/`.
