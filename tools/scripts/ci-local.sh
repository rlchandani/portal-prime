#!/usr/bin/env bash
#
# Run every GitHub Actions check (.github/workflows/*.yml) locally, so a push to main can
# be gated on the same results CI will report. Exits non-zero if any check fails.
#
#   tools/scripts/ci-local.sh [--clean-checkout] [--skip-gradle] [--skip-browser] [--offline]
#
#   --clean-checkout  run in a fresh detached worktree of HEAD, like CI: committed files
#                     only, no local.properties, cold build (catches compile-heap OOMs and
#                     uncommitted-file false greens). Needs ANDROID_HOME or sdk.dir.
#   --skip-gradle   skip :app:testDebugUnitTest (the slowest step; CI still runs it)
#   --skip-browser  skip the Playwright pairing-keyboard browser test (remote-browser.yml)
#   --offline       skip the catalog validator's network checks (catalog.yml runs them)
#
# Tool dependencies are installed once into CI_LOCAL_CACHE (default
# ${XDG_CACHE_HOME:-~/.cache}/portal-ci-local): a Python venv with MkDocs Material and
# cryptography, plus Playwright and its Chromium. Requires python3 (3.12+), node (22+),
# rustc, a JDK and the Android SDK. Missing tools fail; only the PowerShell parse is
# reported SKIPPED without pwsh (CI still parses under PowerShell 7 and 5.1).
set -uo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"

skip_gradle=0 skip_browser=0 offline=0 clean_checkout=0
for arg in "$@"; do
  case "$arg" in
    --clean-checkout) clean_checkout=1 ;;
    --skip-gradle) skip_gradle=1 ;;
    --skip-browser) skip_browser=1 ;;
    --offline) offline=1 ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

if [ "$clean_checkout" -eq 1 ]; then
  sdk="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}"
  [ -n "$sdk" ] || { echo "✗ --clean-checkout needs ANDROID_HOME (or sdk.dir in local.properties)" >&2; exit 2; }
  checkout="$(mktemp -d "${TMPDIR:-/tmp}/portal-ci-checkout.XXXXXX")"
  git worktree add --detach --quiet "$checkout/repo" HEAD || exit 2
  echo "▸ clean checkout of $(git rev-parse --short HEAD) at $checkout/repo"
  forwarded=()
  for arg in "$@"; do [ "$arg" = "--clean-checkout" ] || forwarded+=("$arg"); done
  ANDROID_HOME="$sdk" bash "$checkout/repo/tools/scripts/ci-local.sh" ${forwarded[@]+"${forwarded[@]}"}
  status=$?
  git worktree remove --force "$checkout/repo" && rm -rf "$checkout"
  exit "$status"
fi

readonly PLAYWRIGHT_VERSION="1.62.1"  # keep in sync with remote-browser.yml
# Exactly the "Run unit tests" command in tests.yml, heap flags included, so a CI
# OutOfMemoryError reproduces here (scripts/test_portal_ci_local_parity.py enforces it).
readonly GRADLE_UNIT_TEST_CMD="./gradlew :app:testDebugUnitTest --no-daemon -Dorg.gradle.jvmargs=-Xmx6g -Pkotlin.compiler.execution.strategy=in-process"
readonly MAX_GRADLE_PROCESSES=2
readonly GRADLE_WAIT_S=15
cache="${CI_LOCAL_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/portal-ci-local}"
venv="$cache/venv"
work="$(mktemp -d "${TMPDIR:-/tmp}/portal-ci-local.XXXXXX")"
trap 'rm -rf "$work"' EXIT
export PYTHONDONTWRITEBYTECODE=1

passed=() failed=() skipped=()
run() {  # run <name> <command...>
  local name="$1"; shift
  echo; echo "▸ $name"
  if "$@"; then passed+=("$name"); else failed+=("$name"); echo "✗ FAILED: $name" >&2; fi
}
skip() { echo; echo "▸ $1 — SKIPPED ($2)"; skipped+=("$1"); }
need() { command -v "$1" >/dev/null 2>&1 || { echo "✗ $1 not found on PATH" >&2; return 1; }; }

ensure_venv() {
  [ -x "$venv/bin/mkdocs" ] && "$venv/bin/python" -c 'import cryptography' 2>/dev/null && return 0
  need python3 || return 1
  mkdir -p "$cache" && python3 -m venv "$venv" \
    && "$venv/bin/pip" install -q --disable-pip-version-check -r requirements-docs.txt cryptography
}

# ---- tests.yml ------------------------------------------------------------------------------
slideshow_js() {
  need node && node --test scripts/test_slideshow_sources.mjs scripts/test_slideshow_labels.mjs \
    scripts/tests/slideshow_*.test.mjs scripts/tests/slideshow_*.test.cjs
}
remote_js() { need node && node --test app/src/test/js/*.test.mjs; }
portal_python() {
  [ -d /private/tmp ] || { echo "✗ /private/tmp is required by the profile tooling tests" >&2; return 1; }
  need python3 && python3 -m unittest discover -s scripts -p 'test_portal_*.py'
}
gradle_unit() {
  # Never run more than MAX_GRADLE_PROCESSES Gradle JVMs on this machine at once.
  while [ "$(pgrep -f GradleMain | wc -l | tr -d ' ')" -ge "$MAX_GRADLE_PROCESSES" ]; do
    echo "  waiting: $MAX_GRADLE_PROCESSES Gradle processes already running"; sleep "$GRADLE_WAIT_S"
  done
  # Word-split on purpose: the command is a flat token list.
  # shellcheck disable=SC2086
  $GRADLE_UNIT_TEST_CMD
}

# ---- catalog.yml / release-guard.yml / docs.yml ---------------------------------------------
catalog_tests() { python3 -m unittest discover -s scripts -p 'test_portal_catalog_*.py'; }
catalog_validate() {
  if [ "$offline" -eq 1 ]; then python3 scripts/validate_catalog.py
  else python3 scripts/validate_catalog.py --network; fi
}
version_sync() { bash scripts/check-version-sync.sh; }
docs_strict() { ensure_venv && "$venv/bin/mkdocs" build --strict -d "$work/site"; }

# ---- provisioning.yml -----------------------------------------------------------------------
fleet_tests() {
  need rustc && ensure_venv || return 1
  rustc -O provisioning/fleet.rs -o "$work/fleetctl" \
    && rustc --test provisioning/fleet.rs -o "$work/fleet-tests" \
    && "$work/fleet-tests" || return 1
  local py="$venv/bin/python" t
  for t in test_fleet_http test_fleet_ports test_fleet_registry_encoding; do
    IMMORTAL_FLEET_CLI="$work/fleetctl" "$py" "provisioning/$t.py" || return 1
  done
  for t in test_room_fleet test_deploy_snapshot test_room_registry_encoding \
           test_fleet_backup test_fleet_sealed_restore test_fleet_restore; do
    "$py" "provisioning/$t.py" || return 1
  done
  bash -n provisioning/fleet-backup.sh && bash -n provisioning/fleet-restore.sh
}
provision_lint() {
  # Bash syntax, Windows-script ASCII guard, and pwsh parse (SKIPPED without pwsh).
  bash scripts/check-provisioning.sh && need node && node --test scripts/provision-access.test.mjs
}
provision_pwsh() { pwsh -NoProfile -File ./scripts/test-provision-access.ps1; }

# ---- remote-browser.yml ---------------------------------------------------------------------
remote_browser() {
  need node && need npm || return 1
  local deps="$cache/remote-dependencies" browsers="$cache/remote-browsers"
  if [ ! -f "$deps/node_modules/playwright/package.json" ] \
     || ! grep -q "\"version\": \"$PLAYWRIGHT_VERSION\"" "$deps/node_modules/playwright/package.json"; then
    npm install --prefix "$deps" --ignore-scripts --no-audit --no-fund --omit=optional \
      "playwright@$PLAYWRIGHT_VERSION" || return 1
  fi
  PLAYWRIGHT_BROWSERS_PATH="$browsers" node "$deps/node_modules/playwright/cli.js" install chromium \
    || return 1
  IMMORTAL_REMOTE_CHROME=1 NODE_PATH="$deps/node_modules" PLAYWRIGHT_BROWSERS_PATH="$browsers" \
    node --test scripts/tests/remote_pairing_keyboard_browser.test.mjs
}

run "tests: slideshow sources and labels (node)" slideshow_js
run "tests: remote and screensaver JavaScript guards (node)" remote_js
run "tests: Portal Python regression guards" portal_python
run "Catalog validation: catalog tests" catalog_tests
run "Catalog validation: validate_catalog.py$([ "$offline" -eq 1 ] || echo ' --network')" catalog_validate
run "Release guard: version.json ⇄ build.gradle.kts" version_sync
run "Docs: mkdocs build --strict" docs_strict
run "Provisioning: fleet CLI and room helper tests" fleet_tests
run "Provisioning: lint (bash, ASCII, pwsh parse, Calls access)" provision_lint
if command -v pwsh >/dev/null 2>&1; then
  run "Provisioning: Calls access (PowerShell 7)" provision_pwsh
else
  skip "Provisioning: Calls access (PowerShell 7)" "pwsh not installed: brew install powershell"
fi
if [ "$skip_browser" -eq 1 ]; then skip "remote browser: pairing keyboard" "--skip-browser"
else run "remote browser: pairing keyboard" remote_browser; fi
if [ "$skip_gradle" -eq 1 ]; then skip "tests: :app:testDebugUnitTest" "--skip-gradle"
else run "tests: :app:testDebugUnitTest" gradle_unit; fi

echo
echo "════ ci-local: ${#passed[@]} passed, ${#failed[@]} failed, ${#skipped[@]} skipped"
for s in ${skipped[@]+"${skipped[@]}"}; do echo "  - skipped: $s"; done
for f in ${failed[@]+"${failed[@]}"}; do echo "  ✗ failed:  $f"; done
[ "${#failed[@]}" -eq 0 ]
