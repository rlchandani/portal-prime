#!/bin/bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
CANDIDATE=${1:?Pass the exact candidate PERFORMANCE APK}
SDK=${2:-/opt/homebrew/share/android-commandlinetools}
TOOLS="$SDK/build-tools/36.0.0"
mkdir -p "$BASE/classes" "$BASE/dex" "$BASE/assets"
cp "$CANDIDATE" "$BASE/assets/source.apk"
javac -source 8 -target 8 -cp "$SDK/platforms/android-36/android.jar" -d "$BASE/classes" "$BASE/GalleryActivity.java"
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-36/android.jar" --output "$BASE/dex" "$BASE/classes/com/immortal/galleryfixture/"*.class
"$TOOLS/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$BASE/AndroidManifest.xml" -A "$BASE/assets" -o "$BASE/unsigned.apk"
(cd "$BASE/dex" && zip -q -j "$BASE/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$BASE/unsigned.apk" "$BASE/aligned.apk"
"$TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$BASE/gallery-fixture.apk" "$BASE/aligned.apk"
"$TOOLS/apksigner" verify "$BASE/gallery-fixture.apk"
