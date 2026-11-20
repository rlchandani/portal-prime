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
#   --prefs    restore the saved preferences → FULL fidelity, every credential included.
#              A sealed snapshot (sealed-backup.json, Hearthpane 1.275+) is opened in memory
#              with this Mac's backup key, checked, and handed to the Portal re-sealed to a
#              one-time key (POST /backup/import); the Portal validates and applies it. Only
#              onto the Portal it came from, unless --cross-device. The fleet token is never
#              part of a backup, so the freshly provisioned one is kept. Reboot afterwards.
#              An older plaintext snapshot (shared_prefs/*.xml) is refused: the Portal no
#              longer accepts raw writes into its private data (/fs/write). Take a sealed
#              backup with ./fleet-backup.sh first; --apps and --config still read it.
#   --cross-device  with a sealed snapshot: restore onto another Portal, household settings
#              only. Device-local and secret items (room name, phone sessions, the household
#              key, tokens, data files) stay behind; Who's there templates are never backed up.
#   --same-serial  with a sealed snapshot of THIS serial: restore everything although the
#              Portal reports a different app identity (a reinstall with another signing key
#              gives the app a new ANDROID_ID). You vouch it is the same Portal.
#   --config   instead of raw prefs, re-apply only the endpoint-covered screensaver
#              + calendar subset (no stored credentials). Older plaintext snapshots only.
#   --dry-run  print what would happen, change nothing (a sealed snapshot is opened and
#              checked locally; the Portal isn't contacted).
#
# Usage:
#   ./fleet-restore.sh 819LCM01Z09E4D12                       # apps + prefs
#   ./fleet-restore.sh "Portal Mini" --prefs --dry-run
#   ./fleet-restore.sh "Portal Mini" --prefs --cross-device --backup backups/819LCM01Z09E4D12/latest
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

usage() { sed -n '2,41p' "$0"; exit 2; }

target="${1:-}"; [ -n "$target" ] || usage; shift || true

# Resolve one unambiguous CLI selector locally before any restore can mutate data.
command -v "$PYTHON3" >/dev/null 2>&1 || {
  report "error: Python 3 is required to validate restore data" >&2
  exit 1
}
serial="$("$PYTHON3" "$here/fleet-backup-data.py" restore-target "$FLEET_DIR" "$target")" || exit 1

backup="backups/$serial/latest"
do_apps=0; do_prefs=0; do_config=0; dry=0; cross=0; same_serial=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) backup="$2"; shift 2;;
    --apps)   do_apps=1; shift;;
    --prefs)  do_prefs=1; shift;;
    --config) do_config=1; shift;;
    --dry-run) dry=1; shift;;
    --cross-device) cross=1; shift;;
    --same-serial) same_serial=1; shift;;
    *) echo "unknown option: $1" >&2; usage;;
  esac
done
# Default: reinstall apps and restore prefs (full-fidelity, incl. credentials).
[ $do_apps -eq 0 ] && [ $do_prefs -eq 0 ] && [ $do_config -eq 0 ] && { do_apps=1; do_prefs=1; }

[ -d "$backup" ] || { echo "error: backup not found: $backup" >&2; exit 1; }
[ -x "$FLEETCTL" ] || { echo "error: $FLEETCTL not built (rustc -O fleet.rs -o fleetctl)" >&2; exit 1; }
sealed=0
if [ -e "$backup/sealed-backup.json" ] || [ -L "$backup/sealed-backup.json" ]; then sealed=1; fi
if [ $((cross + same_serial)) -gt 0 ] && { [ $sealed -eq 0 ] || [ $do_prefs -eq 0 ]; }; then
  report "error: --cross-device and --same-serial need --prefs and a sealed snapshot (Hearthpane 1.275+)" >&2
  exit 2
fi
if [ $cross -eq 1 ] && [ $same_serial -eq 1 ]; then
  report "error: --cross-device and --same-serial can't be combined" >&2
  exit 2
fi
if [ $do_prefs -eq 1 ] && [ $sealed -eq 0 ]; then
  report "error: $backup is an older plaintext snapshot (no sealed-backup.json)." >&2
  report "       Hearthpane refuses raw writes into its private data, so --prefs restores only a" >&2
  report "       sealed snapshot. Take a sealed backup first (./fleet-backup.sh, Hearthpane 1.275+)" >&2
  report "       and restore that. From this snapshot, --apps and --config (screensaver and" >&2
  report "       calendar, no credentials) still work." >&2
  exit 2
fi
if [ $sealed -eq 1 ] && [ $do_config -eq 1 ]; then
  report "error: a sealed snapshot restores with --prefs (--config reads an older plaintext snapshot)" >&2
  exit 2
fi

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
config_planned=0
ss_args=()
cal_args=()
if [ $dry -eq 0 ] || [ $do_prefs -eq 1 ] || { [ $do_config -eq 1 ] && [ -f "$backup/shared_prefs/immortal_screensaver.xml" ]; }; then
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/immortal-restore-apps.XXXXXX")" || exit 1
fi
if [ $do_prefs -eq 1 ]; then
  # Open and check the sealed archive in memory before installs or any device call.
  "$PYTHON3" "$here/fleet-backup-seal.py" inspect "$backup" || exit 1
fi
if [ $do_config -eq 1 ] && [ -f "$backup/shared_prefs/immortal_screensaver.xml" ]; then
  # Decode and validate the complete subset before installs, prefs or reboot.
  # Check the producer directly: process substitution can hide parser failures.
  "$PYTHON3" "$here/fleet-backup-data.py" restore-config \
    "$backup/shared_prefs/immortal_screensaver.xml" "$scratch/screensaver.args" "$scratch/calendar.args" || exit 1
  while IFS= read -r -d '' value; do ss_args+=("$value"); done < "$scratch/screensaver.args"
  while IFS= read -r -d '' value; do cal_args+=("$value"); done < "$scratch/calendar.args"
  config_planned=1
fi
if [ $dry -eq 0 ]; then
  frozen="$("$PYTHON3" "$here/fleet-backup-data.py" restore-target "$FLEET_DIR" "$serial" "$scratch/registry")" || exit 1
  [ "$frozen" = "$serial" ] || exit 1
  # Fleetctl reloads its registry on every call. Keep this run pinned to the
  # validated single record even if the original registry changes mid-restore.
  export IMMORTAL_FLEET_DIR="$scratch/registry"
fi

report "restoring '$serial' from $backup  (apps=$do_apps prefs=$do_prefs config=$do_config cross-device=$cross same-serial=$same_serial dry-run=$dry)"
run() { if [ $dry -eq 1 ]; then report "  + $*"; else "$@"; fi; }
run_config() {
  local domain="$1"; shift
  if [ $dry -eq 1 ]; then
    run "$FLEETCTL" "$domain" set "$@" --device "$serial"
    return
  fi
  if ! "$FLEETCTL" "$domain" set "$@" --device "$serial" > "$scratch/config-$domain.json" 2> "$scratch/config-$domain-errors.log" ||
     ! "$PYTHON3" "$here/fleet-backup-data.py" config-result "$scratch/config-$domain.json"; then
    report "error: $domain configuration unconfirmed; inspect the device before retrying" >&2
    exit 1
  fi
  report "  confirmed: $domain"
}

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

# 2) Sealed snapshot: the Portal opens, validates, scopes and applies it.
if [ $do_prefs -eq 1 ]; then
  report "-- prefs (sealed import) --"
  seal_args=()
  [ $cross -eq 1 ] && seal_args+=(--cross-device)
  [ $same_serial -eq 1 ] && seal_args+=(--same-serial)
  if [ $dry -eq 1 ]; then
    report "  + import the sealed backup${seal_args[*]:+ (${seal_args[*]})}"
  elif ! "$PYTHON3" "$here/fleet-backup-seal.py" import "$scratch/registry/$serial.json" "$backup" ${seal_args[@]+"${seal_args[@]}"}; then
    report "error: sealed import unconfirmed; inspect the device before retrying" >&2
    exit 1
  fi
fi

# 3) Alternative soft restore: re-apply the endpoint-covered screensaver/calendar
#    subset from the saved prefs (no stored credentials, no reboot).
if [ $config_planned -eq 1 ]; then
  echo "-- config (screensaver + calendar via endpoints) --"
  if [ ${#ss_args[@]} -gt 0 ]; then
    report "  screensaver"
    run_config screensaver "${ss_args[@]}"
  fi
  if [ ${#cal_args[@]} -gt 0 ]; then
    report "  calendar"
    run_config calendar "${cal_args[@]}"
  fi
  echo "  note: --config does NOT restore Immich/SMB/WebDAV/multiroom credentials;"
  echo "        use --prefs for those."
fi

report "done."
