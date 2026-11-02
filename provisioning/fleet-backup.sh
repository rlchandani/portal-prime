#!/usr/bin/env bash
#
# fleet-backup.sh — snapshot a Portal's Immortal state before a risky operation
# (e.g. a debug-key reinstall, which wipes app data). Pairs with fleet-restore.sh.
#
# For each target it saves, under backups/<serial>/<timestamp>-<unique-id>/:
#   info.json       — /info (identity, version, install state, installed apps)
#   apps.json       — /apps (catalog + what's installed → the reinstall list)
#   registry.json   — the local fleet/<serial>.json (host/port/token)
#   shared_prefs/*.xml — every readable Immortal pref file. THIS is the config +
#                        credential store (fleet token, screensaver sources incl.
#                        Immich/SMB/WebDAV creds, multiroom user/pass, weather, …).
#
# The agent runs as com.immortal.launcher, so it can read its own /data/data
# shared_prefs over /fs/read — no root or adb needed.
#
# Usage:
#   ./fleet-backup.sh                 # back up every registered device
#   ./fleet-backup.sh all             # same
#   ./fleet-backup.sh "Portal Mini"   # one device by name
#   ./fleet-backup.sh 819LCM01Z09E4D12  # …or by serial
#
# The backups/ tree holds tokens + passwords in cleartext — it is git-ignored;
# keep it private and treat it like any other secret store.
set -euo pipefail
umask 077
# A closed progress-output pipe must not stop capture or reverse a committed result.
trap '' PIPE
report() { printf '%s\n' "$*" || true; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"
FLEETCTL="${FLEETCTL:-./fleetctl}"
FLEET_DIR="${IMMORTAL_FLEET_DIR:-./fleet}"
PYTHON3="${PYTHON3:-python3}"
APP_PACKAGE="${IMMORTAL_BACKUP_PACKAGE-com.immortal.launcher}"
PREFS_DIR="/data/data/$APP_PACKAGE/shared_prefs"
STAMP="$(date +%Y%m%d-%H%M%S)"

command -v "$PYTHON3" >/dev/null 2>&1 || {
  echo "error: Python 3 is required to validate backup data" >&2
  exit 1
}
"$PYTHON3" "$here/fleet-backup-data.py" package "$APP_PACKAGE" || exit 1

[ -x "$FLEETCTL" ] || {
  echo "error: $FLEETCTL not found/executable — build it first:" >&2
  echo "       rustc -O fleet.rs -o fleetctl   (or: cargo build --release)" >&2
  exit 1
}

target="${1:-all}"

# Resolve the set of serials to back up from the registry filenames.
serials=()
if [ "$target" = "all" ]; then
  for f in "$FLEET_DIR"/*.json; do
    [ -e "$f" ] || continue
    serials+=("$(basename "$f" .json)")
  done
elif [ -f "$FLEET_DIR/$target.json" ]; then
  serials+=("$target")                       # matched a serial directly
else
  for f in "$FLEET_DIR"/*.json; do            # try to match a friendly name
    [ -e "$f" ] || continue
    nm="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" | head -1)"
    [ "$nm" = "$target" ] && serials+=("$(basename "$f" .json)")
  done
fi
[ ${#serials[@]} -gt 0 ] || { echo "error: no device matching '$target' in $FLEET_DIR" >&2; exit 1; }

stage=""
cleanup() { if [ -n "$stage" ]; then rm -rf "$stage"; fi; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

capture() {
  local serial="$1" n
  "$FLEETCTL" info --device "$serial" > "$stage/info.json" 2>/dev/null || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" json info "$stage/info.json" || return 1
  "$FLEETCTL" apps --device "$serial" > "$stage/apps.json" 2>/dev/null || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" json apps "$stage/apps.json" || return 1
  cp "$FLEET_DIR/$serial.json" "$stage/registry.json" || return 1
  "$FLEETCTL" ls "$PREFS_DIR" --device "$serial" > "$stage/listing.json" 2>/dev/null || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" names "$stage/listing.json" "$PREFS_DIR" > "$stage/preferences.txt" || return 1
  while IFS= read -r n; do
    "$FLEETCTL" pull "$PREFS_DIR/$n" "$stage/shared_prefs/$n" --device "$serial" >/dev/null 2>&1 || return 1
    "$PYTHON3" "$here/fleet-backup-data.py" xml "$stage/shared_prefs/$n" || return 1
  done < "$stage/preferences.txt"
  "$PYTHON3" "$here/fleet-backup-data.py" manifest "$stage" "$serial" "$STAMP" "$APP_PACKAGE" || return 1
}

prepare() {
  local serial="$1"
  "$PYTHON3" "$here/fleet-backup-data.py" directory "backups/$serial" || return 1
  stage="$(mktemp -d "backups/$serial/.partial-$STAMP.XXXXXX")" || return 1
  mkdir "$stage/shared_prefs" || return 1
}

backed_up=0
failed=0
"$PYTHON3" "$here/fleet-backup-data.py" directory backups || exit 1
for serial in "${serials[@]}"; do
  case "$serial" in
    ""|"."|".."|*/*|*\\*) report "error: invalid registry filename" >&2; failed=1; continue;;
  esac
  report "=== backing up $serial ==="
  if prepare "$serial" && capture "$serial" && "$PYTHON3" "$here/fleet-backup-data.py" publish "$stage" "$STAMP"; then
    stage=""
    backed_up=$((backed_up + 1))
  else
    report "  error: backup incomplete; previous snapshot retained" >&2
    cleanup
    stage=""
    failed=1
  fi
done

report ""
report "backed up $backed_up device(s) to $here/backups/"
report "WARNING: these files contain tokens and cleartext credentials — keep private (git-ignored)."
exit "$failed"
