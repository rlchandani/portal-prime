#!/usr/bin/env bash
#
# Publish a Hearthpane wireless (OTA) update as a GitHub Release.
#
#   tools/scripts/ota-release.sh [--dry-run] [--notes "One-line what's new"]
#
# Portals poll https://github.com/rlchandani/portal-prime/releases/latest/download/hearthpane-latest.json
# (see app/.../OtaConstants.kt and docs/plans/ota-github-releases-2026-11-08.md). This script:
#
#   1. Guards: clean tree, on feat/home-v2-refinements or main, HEAD pushed to origin, tag free,
#      versionCode (app/build.gradle.kts) greater than the published manifest's.
#   2. Runs the gate: unit tests, performance APK, lintVitalPerformance.
#   3. Checks the APK: output metadata (package/versionCode/versionName) and
#      `apksigner verify --print-certs` — exactly one signer whose SHA-256 equals the pinned digest.
#   4. Writes hearthpane-latest.json (schema 1, sha256, size, apkUrl, minSdk, notes).
#   5. `gh release create hearthpane-v<versionName> --latest` with hearthpane.apk + hearthpane-latest.json.
#   6. Downloads both back through the public releases/latest/download/ URLs and re-verifies them.
#
# --dry-run does everything except creating the release (and its download-back check).
# Never prints secrets: signing comes from the Gradle signing config; nothing here reads a password.
set -euo pipefail

readonly REPO="rlchandani/portal-prime"
readonly PACKAGE="com.immortal.launcher.debug"
readonly PINNED_SIGNER_SHA256="4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6"
readonly TAG_PREFIX="hearthpane-v"
readonly APK_ASSET="hearthpane.apk"
readonly MANIFEST_ASSET="hearthpane-latest.json"
readonly RELEASE_BRANCHES="feat/home-v2-refinements main"
readonly MAX_NOTES=280
readonly VERIFY_ATTEMPTS=18  # GitHub can take over a minute to point latest/ at a new release
readonly VERIFY_DELAY_S=10
readonly LATEST_BASE="https://github.com/$REPO/releases/latest/download"
readonly GRADLE_GATE=(:app:testDebugUnitTest :app:assemblePerformance :app:lintVitalPerformance
  --no-daemon -q -Dorg.gradle.jvmargs=-Xmx4g -Pkotlin.daemon.jvmargs=-Xmx3g)

die() { echo "✗ $*" >&2; exit 1; }
step() { echo; echo "▸ $*"; }
ok() { echo "  ✓ $*"; }

dry_run=0
notes=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1 ;;
    --notes) [ $# -ge 2 ] || die "--notes needs a value"; notes="$2"; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
  shift
done

root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "run inside the portal repository"
cd "$root"

# ---- tools ----------------------------------------------------------------------------------
# Newest build-tools apksigner from the first SDK root that has one: $APKSIGNER, $ANDROID_HOME,
# local.properties sdk.dir, the default macOS SDK, then Homebrew's command-line tools.
find_apksigner() {
  if [ -n "${APKSIGNER:-}" ]; then echo "$APKSIGNER"; return; fi
  local local_sdk=""
  [ -f local.properties ] && local_sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
  local root
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$local_sdk" "$HOME/Library/Android/sdk" \
      /opt/homebrew/share/android-commandlinetools; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    local found
    found="$(ls -d "$root"/build-tools/*/ 2>/dev/null | sort -V | while read -r d; do
      [ -x "${d}apksigner" ] && echo "${d}apksigner"; done | tail -1)"
    [ -n "$found" ] && { echo "$found"; return; }
  done
}
apksigner="$(find_apksigner || true)"
[ -n "$apksigner" ] && [ -x "$apksigner" ] || die "apksigner not found; set APKSIGNER or ANDROID_HOME"
for tool in git python3 curl shasum; do command -v "$tool" >/dev/null || die "$tool is required"; done
if [ -z "${JAVA_HOME:-}" ]; then
  jbr="$(ls -d "$HOME"/Library/Java/JavaVirtualMachines/jbr-21*/Contents/Home 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$jbr" ] && export JAVA_HOME="$jbr"
fi
[ -n "${JAVA_HOME:-}" ] || die "set JAVA_HOME to a JDK 21"
if [ "$dry_run" -eq 0 ]; then
  command -v gh >/dev/null || die "gh is required to publish"
  gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
fi

# ---- guards ---------------------------------------------------------------------------------
step "Guards"
[ -z "$(git status --porcelain --untracked-files=normal)" ] || die "working tree is not clean"
ok "clean tree"
branch="$(git symbolic-ref --short -q HEAD || true)"
case " $RELEASE_BRANCHES " in
  *" $branch "*) ok "on $branch" ;;
  *) msg="release from one of: $RELEASE_BRANCHES (on: ${branch:-detached HEAD})"
     [ "$dry_run" -eq 1 ] || die "$msg"
     echo "  ! $msg; a real release would stop here" ;;
esac
[ -n "$branch" ] || die "detached HEAD"
head_sha="$(git rev-parse HEAD)"

gradle_file="app/build.gradle.kts"
version_code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$gradle_file" | head -1)"
version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$gradle_file" | head -1)"
min_sdk="$(sed -nE 's/^[[:space:]]*minSdk = ([0-9]+).*/\1/p' "$gradle_file" | head -1)"
[ -n "$version_code" ] && [ -n "$version_name" ] && [ -n "$min_sdk" ] || die "couldn't read versions from $gradle_file"
echo "$version_name" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || die "unexpected versionName: $version_name"
tag="$TAG_PREFIX$version_name"
ok "building $version_name ($version_code), tag $tag"

work="$(mktemp -d "${TMPDIR:-/tmp}/hearthpane-ota.XXXXXX")"
trap 'rm -rf "$work"' EXIT

published_code=0
status="$(curl -sSL -o "$work/published.json" -w '%{http_code}' "$LATEST_BASE/$MANIFEST_ASSET" || true)"
case "$status" in
  200) published_code="$(python3 -I -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["versionCode"]))' "$work/published.json")" \
         || die "published manifest is unreadable" ;;
  404) ok "no published manifest yet" ;;
  *) die "couldn't read the published manifest (HTTP ${status:-none})" ;;
esac
[ "$version_code" -gt "$published_code" ] || die "versionCode $version_code is not greater than published $published_code (bump first)"
ok "versionCode $version_code > published $published_code"

if command -v gh >/dev/null && gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
  die "release $tag already exists"
fi
ok "tag $tag is free"

remote_ok=0
if git fetch -q origin "$branch" 2>/dev/null && git merge-base --is-ancestor "$head_sha" FETCH_HEAD; then remote_ok=1; fi
if [ "$remote_ok" -eq 1 ]; then ok "HEAD is on origin/$branch"
elif [ "$dry_run" -eq 1 ]; then echo "  ! HEAD is not on origin/$branch yet; a real release would stop here"
else die "push $branch first: the release tag must point at a commit on GitHub"; fi

# ---- gate -----------------------------------------------------------------------------------
step "Gate (unit tests, performance APK, lintVitalPerformance)"
./gradlew "${GRADLE_GATE[@]}"
ok "gate passed"

# ---- APK checks -----------------------------------------------------------------------------
step "APK checks"
apk="app/build/outputs/apk/performance/app-performance.apk"
metadata="app/build/outputs/apk/performance/output-metadata.json"
[ -f "$apk" ] && [ -f "$metadata" ] || die "performance APK not found at $apk"
read -r meta_pkg meta_code meta_name < <(python3 -I -c '
import json, sys
m = json.load(open(sys.argv[1]))
e = m["elements"][0]
print(m["applicationId"], e["versionCode"], e["versionName"])' "$metadata")
[ "$meta_pkg" = "$PACKAGE" ] || die "APK package is $meta_pkg, expected $PACKAGE"
[ "$meta_code" = "$version_code" ] && [ "$meta_name" = "$version_name" ] \
  || die "APK is $meta_name ($meta_code), expected $version_name ($version_code); stale build?"
ok "package $meta_pkg, $meta_name ($meta_code)"

"$apksigner" verify --print-certs "$apk" > "$work/certs.txt" 2>&1 || die "apksigner verify failed"
signers="$(sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$/\1/p' "$work/certs.txt" | tr 'A-F' 'a-f')"
[ "$(printf '%s\n' "$signers" | grep -c .)" -eq 1 ] || die "expected exactly one signer"
[ "$signers" = "$PINNED_SIGNER_SHA256" ] || die "signer digest $signers does not match the pinned digest"
ok "signed with the pinned certificate"

out="$work/release"
mkdir -p "$out"
cp "$apk" "$out/$APK_ASSET"
sha="$(shasum -a 256 "$out/$APK_ASSET" | awk '{print $1}')"
size="$(wc -c < "$out/$APK_ASSET" | tr -d ' ')"
ok "sha256 $sha, $size bytes"

# ---- manifest -------------------------------------------------------------------------------
step "Manifest"
[ -n "$notes" ] || notes="$(git log -1 --format=%s)"
apk_url="https://github.com/$REPO/releases/download/$tag/$APK_ASSET"
python3 -I - "$out/$MANIFEST_ASSET" "$PACKAGE" "$version_code" "$version_name" "$sha" "$size" "$apk_url" \
  "$min_sdk" "$notes" "$MAX_NOTES" <<'PY'
import json, re, sys
path, pkg, code, name, sha, size, url, min_sdk, notes, max_notes = sys.argv[1:]
assert re.fullmatch(r"[0-9a-f]{64}", sha), "bad sha"
assert int(size) > 0, "bad size"
assert url.startswith("https://github.com/"), "bad url"
manifest = {
    "schema": 1, "packageName": pkg, "versionCode": int(code), "versionName": name,
    "sha256": sha, "size": int(size), "apkUrl": url, "minSdk": int(min_sdk),
    "notes": " ".join(notes.split())[: int(max_notes)],
}
with open(path, "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
PY
cat "$out/$MANIFEST_ASSET"

if [ "$dry_run" -eq 1 ]; then
  step "Dry run: not creating release $tag on $REPO"
  echo "  would run: gh release create $tag --repo $REPO --latest --target $head_sha --title \"Hearthpane $version_name\" $APK_ASSET $MANIFEST_ASSET"
  exit 0
fi

# ---- publish --------------------------------------------------------------------------------
step "Publishing $tag"
gh release create "$tag" --repo "$REPO" --latest --target "$head_sha" \
  --title "Hearthpane $version_name" --notes "$notes" \
  "$out/$APK_ASSET" "$out/$MANIFEST_ASSET"
ok "release created"

# ---- download-back verification -------------------------------------------------------------
step "Verifying the public download URLs"
verified=0
for attempt in $(seq 1 "$VERIFY_ATTEMPTS"); do
  if curl -fsSL -o "$work/back.json" "$LATEST_BASE/$MANIFEST_ASSET" \
      && curl -fsSL -o "$work/back.apk" "$LATEST_BASE/$APK_ASSET" \
      && cmp -s "$work/back.json" "$out/$MANIFEST_ASSET" \
      && [ "$(shasum -a 256 "$work/back.apk" | awk '{print $1}')" = "$sha" ]; then
    verified=1; break
  fi
  echo "  … not visible yet (attempt $attempt/$VERIFY_ATTEMPTS)"; sleep "$VERIFY_DELAY_S"
done
[ "$verified" -eq 1 ] || die "the public latest/download URLs don't serve the new release yet; check $tag on GitHub"
ok "latest/download serves $version_name ($version_code), sha256 verified"
