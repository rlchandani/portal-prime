#!/usr/bin/env bash
#
# fleet-restore.sh — restore a Portal after a clean reinstall, from a
# fleet-backup.sh snapshot.
#
# Prereqs: the Portal has been reinstalled with a fleet-capable Immortal build
# and re-registered (./provision.sh --fleet over USB), so fleet/<serial>.json
# holds the CURRENT token and the /screensaver & /calendar endpoints exist.
#
# Modes (combine freely; default = --apps --prefs):
#   --apps     reinstall the third-party apps that were installed (from apps.json)
#   --prefs    push the saved shared_prefs back verbatim → FULL fidelity, restores
#              every credential (Immich/SMB/WebDAV, multiroom, etc.). fleet_agent.xml
#              is skipped so the freshly-provisioned token is kept. A reboot follows
#              so the app reloads prefs from disk.
#   --config   instead of raw prefs, re-apply only the endpoint-covered screensaver
#              + calendar subset (no stored credentials). Use when you don't want to
#              overwrite the fresh install's prefs wholesale.
#   --dry-run  print what would happen, change nothing.
#
# Usage:
#   ./fleet-restore.sh 819LCM01Z09E4D12                       # apps + prefs + reboot
#   ./fleet-restore.sh "Portal Mini" --config --dry-run
#   ./fleet-restore.sh 819LCM01Z09E4D12 --backup backups/819LCM01Z09E4D12/20260620-200000
set -euo pipefail
umask 077
trap '' PIPE
report() { printf '%s\n' "$*" || true; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"
FLEETCTL="${FLEETCTL:-./fleetctl}"
FLEET_DIR="${IMMORTAL_FLEET_DIR:-./fleet}"
PYTHON3="${PYTHON3:-python3}"
PREFS_DIR="/data/data/com.immortal.launcher/shared_prefs"

usage() { sed -n '2,30p' "$0"; exit 2; }

target="${1:-}"; [ -n "$target" ] || usage; shift || true

# Resolve one unambiguous CLI selector locally before any restore can mutate data.
command -v "$PYTHON3" >/dev/null 2>&1 || {
  report "error: Python 3 is required to validate restore data" >&2
  exit 1
}
serial="$("$PYTHON3" "$here/fleet-backup-data.py" restore-target "$FLEET_DIR" "$target")" || exit 1

backup="backups/$serial/latest"
do_apps=0; do_prefs=0; do_config=0; dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) backup="$2"; shift 2;;
    --apps)   do_apps=1; shift;;
    --prefs)  do_prefs=1; shift;;
    --config) do_config=1; shift;;
    --dry-run) dry=1; shift;;
    *) echo "unknown option: $1" >&2; usage;;
  esac
done
# Default: reinstall apps and restore prefs (full-fidelity, incl. credentials).
[ $do_apps -eq 0 ] && [ $do_prefs -eq 0 ] && [ $do_config -eq 0 ] && { do_apps=1; do_prefs=1; }

[ -d "$backup" ] || { echo "error: backup not found: $backup" >&2; exit 1; }
[ -x "$FLEETCTL" ] || { echo "error: $FLEETCTL not built (rustc -O fleet.rs -o fleetctl)" >&2; exit 1; }

pkgs=""
scratch=""
cleanup() { if [ -n "$scratch" ]; then rm -rf "$scratch"; fi; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [ $do_apps -eq 1 ]; then
  # Validate the entire plan before any install or preference write can begin.
  pkgs="$("$PYTHON3" "$here/fleet-backup-data.py" restore-packages "$backup/apps.json")" || exit 1
fi
if [ $dry -eq 0 ]; then
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/immortal-restore-apps.XXXXXX")" || exit 1
  frozen="$("$PYTHON3" "$here/fleet-backup-data.py" restore-target "$FLEET_DIR" "$serial" "$scratch/registry")" || exit 1
  [ "$frozen" = "$serial" ] || exit 1
  # Fleetctl reloads its registry on every call. Keep this run pinned to the
  # validated single record even if the original registry changes mid-restore.
  export IMMORTAL_FLEET_DIR="$scratch/registry"
fi

report "restoring '$serial' from $backup  (apps=$do_apps prefs=$do_prefs config=$do_config dry-run=$dry)"
run() { if [ $dry -eq 1 ]; then report "  + $*"; else "$@"; fi; }

# 1) Reinstall third-party apps (never Immortal itself). apps.json lists the whole
#    catalog with a Boolean or legacy versionCode marker, independently of formatting.
if [ $do_apps -eq 1 ]; then
  report "-- apps --"
  if [ -z "$pkgs" ]; then report "  (no third-party apps were installed)"; fi
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    report "  install $p"
    if [ $dry -eq 1 ]; then
      run "$FLEETCTL" install "$p" --device "$serial"
      continue
    fi
    if ! "$FLEETCTL" install "$p" --device "$serial" > "$scratch/install.json" 2> "$scratch/install-errors.log" ||
       ! "$PYTHON3" "$here/fleet-backup-data.py" install-result "$scratch/install.json" "$p"; then
      report "error: installation unconfirmed for $p; inspect the device before retrying" >&2
      exit 1
    fi
    if ! "$FLEETCTL" apps --device "$serial" > "$scratch/presence.json" 2> "$scratch/presence-errors.log" ||
       ! "$PYTHON3" "$here/fleet-backup-data.py" installed-presence "$scratch/presence.json" "$p"; then
      report "error: installed presence unconfirmed for $p; inspect the device before retrying" >&2
      exit 1
    fi
    report "  confirmed present: $p"
  done <<< "$pkgs"
fi

# 2) Push raw shared_prefs back = full-fidelity restore of config + credentials.
if [ $do_prefs -eq 1 ]; then
  echo "-- prefs (full restore) --"
  pushed=0
  for x in "$backup"/shared_prefs/*.xml; do
    [ -e "$x" ] || continue
    n="$(basename "$x")"
    if [ "$n" = "fleet_agent.xml" ]; then
      echo "  skip $n (keep freshly-provisioned token)"
      continue
    fi
    echo "  push $n"
    run "$FLEETCTL" push "$x" "$PREFS_DIR/$n" --device "$serial"
    pushed=$((pushed + 1))
  done
  if [ $pushed -gt 0 ]; then
    echo "  rebooting so prefs reload from disk"
    run "$FLEETCTL" action reboot --device "$serial"
  fi
fi

# 3) Alternative soft restore: re-apply the endpoint-covered screensaver/calendar
#    subset from the saved prefs (no stored credentials, no reboot).
if [ $do_config -eq 1 ] && [ -f "$backup/shared_prefs/immortal_screensaver.xml" ]; then
  echo "-- config (screensaver + calendar via endpoints) --"
  ss="$backup/shared_prefs/immortal_screensaver.xml"
  sval() { sed -n "s/.*<string name=\"$1\"[^>]*>\([^<]*\)<.*/\1/p" "$ss" | head -1; }
  bval() { sed -n "s/.*<boolean name=\"$1\"[^>]*value=\"\([^\"]*\)\".*/\1/p" "$ss" | head -1; }
  ival() { sed -n "s/.*<int name=\"$1\"[^>]*value=\"\([^\"]*\)\".*/\1/p" "$ss" | head -1; }

  source_v="$(sval source)"; folder_v="$(sval folder_path)"; album_v="$(sval album_url)"
  fit_v="$(sval fit)"; interval_v="$(ival interval_sec)"; shuffle_v="$(bval shuffle)"
  video_v="$(bval include_video)"; cal_url="$(sval calendar_url)"; cal_range="$(sval calendar_range)"

  args=()
  case "$source_v" in default) args+=(--source default);; esac
  [ -n "$folder_v" ]   && args+=(--folder "$folder_v")
  [ -n "$album_v" ]    && args+=(--album-url "$album_v")
  [ -n "$fit_v" ]      && args+=(--fit "$fit_v")
  [ -n "$interval_v" ] && args+=(--interval "$interval_v")
  [ -n "$shuffle_v" ]  && args+=(--shuffle "$shuffle_v")
  [ -n "$video_v" ]    && args+=(--videos "$video_v")
  if [ ${#args[@]} -gt 0 ]; then
    echo "  screensaver ${args[*]}"
    run "$FLEETCTL" screensaver set "${args[@]}" --device "$serial"
  fi
  if [ -n "$cal_url" ]; then
    cargs=(--url "$cal_url"); [ -n "$cal_range" ] && cargs+=(--range "$cal_range")
    echo "  calendar ${cargs[*]}"
    run "$FLEETCTL" calendar set "${cargs[@]}" --device "$serial"
  fi
  echo "  note: --config does NOT restore Immich/SMB/WebDAV/multiroom credentials;"
  echo "        use --prefs for those."
fi

report "done."
