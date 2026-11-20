#!/usr/bin/env bash
#
# fleet-backup.sh — snapshot a Portal's Immortal state before a risky operation
# (e.g. a debug-key reinstall, which wipes app data). Pairs with fleet-restore.sh.
#
# For each target it saves, under backups/<serial>/<timestamp>-<unique-id>/:
#   info.json           — /info (identity, version, install state, installed apps)
#   apps.json           — /apps (catalog + what's installed → the reinstall list)
#   registry.json       — the local fleet/<serial>.json (host/port/token)
#   sealed-backup.json  — the Portal's preferences, data files and household settings,
#                         sealed ON THE PORTAL to this Mac's backup key (AES-256-GCM, data
#                         key wrapped with RSA-OAEP-256). Only this Mac's private key opens it;
#                         the plaintext never touches the Portal's storage, the wire or this disk.
#
# The first run creates this Mac's backup key (RSA-3072) in the login Keychain (or in
# ~/.config/hearthpane/fleet-backup-key, mode 600, when the Keychain isn't available) and
# enrols its public key with each Portal (POST /backup/key; the first enrolled key wins).
# Needs Hearthpane 1.275+ and Python's `cryptography` package. 1.274 and later refuse the
# old /fs/read of shared_prefs, which held the household key, tokens and sessions.
#
# Usage:
#   ./fleet-backup.sh                 # back up every registered device
#   ./fleet-backup.sh all             # same
#   ./fleet-backup.sh "Portal Mini"   # one device by name
#   ./fleet-backup.sh 819LCM01Z09E4D12  # …or by serial
#
# registry.json holds the fleet token in cleartext. The backups/ tree is git-ignored;
# keep it private. Keep the backup key too: without it a sealed snapshot can't be restored.
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
  local serial="$1"
  "$FLEETCTL" info --device "$serial" > "$stage/info.json" 2>/dev/null || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" json info "$stage/info.json" || return 1
  "$FLEETCTL" apps --device "$serial" > "$stage/apps.json" 2>/dev/null || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" json apps "$stage/apps.json" || return 1
  cp "$FLEET_DIR/$serial.json" "$stage/registry.json" || return 1
  # Enrol this Mac's backup key if needed, then fetch and verify the Portal-sealed export.
  "$PYTHON3" "$here/fleet-backup-seal.py" export "$FLEET_DIR/$serial.json" "$stage" || return 1
  "$PYTHON3" "$here/fleet-backup-data.py" manifest "$stage" "$serial" "$STAMP" "$APP_PACKAGE" || return 1
}

prepare() {
  local serial="$1"
  "$PYTHON3" "$here/fleet-backup-data.py" directory "backups/$serial" || return 1
  stage="$(mktemp -d "backups/$serial/.partial-$STAMP.XXXXXX")" || return 1
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
report "WARNING: registry.json holds the fleet token — keep backups/ private (git-ignored)."
exit "$failed"
