#!/usr/bin/env bash
#
# Bump the app version in BOTH release metadata files, in lockstep:
#
#   tools/scripts/bump-version.sh <versionName> "<one-line release notes>"
#   e.g. tools/scripts/bump-version.sh 1.318 "Screensaver fades are smoother."
#
#   - app/build.gradle.kts: versionCode = current + 1, versionName = <versionName>
#   - version.json:         same versionCode/versionName, the stable hearthpane.apk URL, notes
#
# Then runs scripts/check-version-sync.sh (the Release guard CI check). On any failure
# both files are restored. It does not commit, tag, build or publish; publishing stays
# with tools/scripts/ota-release.sh (which reuses version.json's notes by default).
set -euo pipefail
cd "$(dirname "$0")/../.."

readonly GRADLE_FILE="app/build.gradle.kts"
readonly MANIFEST_FILE="version.json"
readonly MAX_NOTES=280  # OtaConstants.MAX_NOTES / ota-release.sh limit

die() { echo "✗ $*" >&2; exit 1; }

version_name="${1:-}"
notes="$(printf '%s' "${2:-}" | tr -s '[:space:]' ' ' | sed -e 's/^ //' -e 's/ $//')"
[ -n "$version_name" ] || die "usage: tools/scripts/bump-version.sh <versionName> \"<release notes>\""
[ -n "$notes" ] || die "release notes are required (second argument)"
[ "${#notes}" -le "$MAX_NOTES" ] || die "release notes are ${#notes} characters; the OTA limit is $MAX_NOTES"
echo "$version_name" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' \
  || die "versionName must look like 1.318 or 1.318.1 (got: $version_name)"
[ -f "$GRADLE_FILE" ] && [ -f "$MANIFEST_FILE" ] || die "run from a checkout with $GRADLE_FILE and $MANIFEST_FILE"

cur_code="$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"
cur_name="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$cur_code" ] || die "could not read versionCode from $GRADLE_FILE"
[ -n "$cur_name" ] || die "could not read versionName from $GRADLE_FILE"
[ "$version_name" != "$cur_name" ] || die "versionName $version_name is unchanged from current — pick a new one"
new_code=$((cur_code + 1))

backup="$(mktemp -d)"
cp "$GRADLE_FILE" "$backup/gradle"
cp "$MANIFEST_FILE" "$backup/manifest"
restore() {
  cp "$backup/gradle" "$GRADLE_FILE"
  cp "$backup/manifest" "$MANIFEST_FILE"
}
trap 'status=$?; [ "$status" -eq 0 ] || restore; rm -rf "$backup"' EXIT

python3 -I - "$GRADLE_FILE" "$MANIFEST_FILE" "$cur_code" "$new_code" "$cur_name" "$version_name" \
  "$notes" "${RELEASE_REPO:-rlchandani/portal-prime}" "${RELEASE_APK_ASSET:-hearthpane.apk}" <<'PY'
import json, re, sys
gradle, manifest, cur_code, new_code, cur_name, new_name, notes, repo, asset = sys.argv[1:]

text = open(gradle, encoding="utf-8").read()
def replace_first(pattern, replacement, source):
    updated, count = re.subn(pattern, replacement, source, count=1, flags=re.M)
    if count != 1:
        sys.exit(f"could not update {pattern!r} in {gradle}")
    return updated
text = replace_first(rf'^(\s*)versionCode = {cur_code}\b', rf'\g<1>versionCode = {new_code}', text)
text = replace_first(rf'^(\s*)versionName = "{re.escape(cur_name)}"',
                     lambda m: f'{m.group(1)}versionName = "{new_name}"', text)
open(gradle, "w", encoding="utf-8").write(text)

data = json.load(open(manifest, encoding="utf-8"))
data.update(versionCode=int(new_code), versionName=new_name,
            apkUrl=f"https://github.com/{repo}/releases/latest/download/{asset}", notes=notes)
with open(manifest, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY

bash scripts/check-version-sync.sh
echo "Bumped $cur_name ($cur_code) → $version_name ($new_code). Commit with: chore: bump version to $version_name"
