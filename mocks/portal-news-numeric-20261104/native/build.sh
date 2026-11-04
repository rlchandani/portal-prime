#!/bin/bash
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
SDK=${1:-/opt/homebrew/share/android-commandlinetools}
mkdir -p "$BASE/classes"
javac -source 8 -target 8 -cp "$SDK/platforms/android-36/android.jar" -d "$BASE/classes" "$BASE/NewsNumericProbe.java"
java -cp "$SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 28 --lib "$SDK/platforms/android-36/android.jar" --output "$BASE/probe.jar" "$BASE/classes/com/immortal/newsnumericprobe/NewsNumericProbe.class"
