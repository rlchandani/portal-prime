#!/bin/bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
SOURCE=${1:?Pass exact baseline debug APK}
SDK=${2:-/opt/homebrew/share/android-commandlinetools}
TOOLS="$SDK/build-tools/36.0.0"
mkdir -p "$BASE/classes" "$BASE/dex" "$BASE/frozen-dex" "$BASE/assets"
if [[ -e "$BASE/frozen-dex/classes.dex" ]]; then echo 'Frozen DEX exists; use repack.sh' >&2; exit 2; fi
javac -source 8 -target 8 -cp "$SDK/platforms/android-36/android.jar" -d "$BASE/classes" "$BASE/CinemaViewportActivity.java"
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 29 --lib "$SDK/platforms/android-36/android.jar" --output "$BASE/dex" "$BASE/classes/com/immortal/cinemaviewportfixture/"*.class
cp "$BASE/dex/classes.dex" "$BASE/frozen-dex/classes.dex"
bash "$BASE/repack.sh" "$SOURCE" "$SDK"

python3 -B "$BASE/freeze.py" "$SOURCE" "$SDK"
