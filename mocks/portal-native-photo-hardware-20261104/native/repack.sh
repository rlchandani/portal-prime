#!/bin/bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
SOURCE=${1:?Pass exact original debug APK}
SDK=${2:-/opt/homebrew/share/android-commandlinetools}
TOOLS="$SDK/build-tools/36.0.0"
test -f "$BASE/frozen-dex/classes.dex"
cp -f "$SOURCE" "$BASE/assets/source.apk"
"$TOOLS/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$BASE/AndroidManifest.xml" -A "$BASE/assets" -o "$BASE/unsigned.apk"
(cd "$BASE/frozen-dex" && zip -q -j "$BASE/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$BASE/unsigned.apk" "$BASE/aligned.apk"
"$TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$BASE/attached-photo-fixture.apk" "$BASE/aligned.apk"
"$TOOLS/apksigner" verify "$BASE/attached-photo-fixture.apk"
