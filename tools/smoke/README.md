# Post-release smoke suite

A fast, safe check that every Hearthpane feature still works on every Portal after an OTA release.
Python 3 standard library only; it drives `adb` and `fleetctl`, and `gh` reads the latest release.

```bash
export SMOKE_FLEETCTL=/path/to/provisioning/fleetctl   # its fleet/*.json registry lists the Portals
export SMOKE_REPORT_DIR=/path/to/scratch-tmp/smoke      # reports go to <dir>/<timestamp>/report.json

python3 tools/smoke/smoke.py --quick        # version, APK sha, health, dev mode, settings sync (~10 s)
python3 tools/smoke/smoke.py --full -v      # everything each Portal's access allows (~1.5 min)
python3 tools/smoke/smoke.py --full --device "Family Area"
python3 -m unittest discover -s tools/smoke/tests   # the suite's own tests (no devices)
```

Exit status is `0` only when every check passed. `SKIP` means the Portal's access does not allow
the check (no adb, phone remote off); it does not fail the run. A FAIL, a WARN, a Portal locked by
another agent or an undo step that failed all give `1`. A setup error (no fleetctl, no registry,
no release) gives `3`.

## What it checks

| Check | How | Needs |
|---|---|---|
| `version.installed`, `version.apk_sha` | `/info` vs the latest official release's `hearthpane-latest.json`; the installed `base.apk` hashed (adb `sha256sum`, or streamed through `fleetctl pull`) | fleet |
| `health.fleet`, `health.dev_mode` | `fleetctl health` reports Good; `/info` devMode false | fleet |
| `health.crashes` | no FATAL EXCEPTION / ANR for Hearthpane in logcat since install (dropbox too with adb). WARN if the log window starts after the install on a fleet-only Portal | fleet |
| `api.*` | golden read-only routes: `/info`, `/diag`, `GET /update/ota`, `settings_sync status` | fleet |
| `sync.status`, `sync.digest` | phase SYNCED, not paused; one digest on every Portal | fleet |
| `cc.live`, `cc.dry_run` | entity count from the registry log line; dry run on, a `fake_smoke_*` leak on → the dry-run banner decision is logged → leak off, dry run off, `cc_debug` back as found | fleet |
| `cc.banner_preview` | Alerts › "Washer done" chip tapped: logged, drawn as a second window, gone after 8 s | adb |
| `calls.roster` | Calls › Rooms shows every other Portal of the registry as Available | adb |
| `jarvis.version`, `jarvis.provider` | same Jarvis versionCode everywhere; a shell-uid `content call` to the tool provider is refused and logged | fleet / adb |
| `screensaver.test_source` | on `--screensaver-device` (default Family Area): generated scenes + one pinned mode for 15 s, expected log lines, no crash, same pid, test source off, Home back | fleet (+adb) |
| `reminders.sync` | a phone test session adds an undated shared reminder, it reaches the other Portals, is deleted, the delete arrives; the session is revoked by its own id | fleet |
| `ota.status` | status sane, retry/error/waitingFor null, never a check or install | fleet |
| `rotation.hook` | `orientation` with no request (report only, forces nothing) | fleet |
| `restore.state` | after all undo steps: dry run/test plan/HA-unreachable off, dev mode off, no overlay, the front screen as found | fleet (+adb) |

## Safety rules (enforced in code)

- **Locks** per `/tmp/portal-device-lock.md`: `mkdir /tmp/portal-lock-<serial>` before anything; if
  it fails the Portal is reported `locked by X` and never touched — every adb/fleet call passes a
  lock guard first. Only a lock whose owner file is this run's is removed. Runs stay well under 30 min.
- adb refuses screenshots, screen recording, `am force-stop`, reboots, Back and `kill`.
- uiautomator only reads a resumed, non-Home Hearthpane screen, and never while an alert overlay or
  panel is drawn. UI checks run only if the Portal is idle on Home or in its screensaver; from the
  screensaver it goes Home with HOME and restarts the screensaver at the end.
- Nothing sounds: the dry run only logs banner decisions, the preview chip is silent by design,
  the reminder has no due time, test scenes have no audio.
- Never prints tokens, keys or IPs: registry tokens/hosts and minted session tokens are masked in
  every line and in the JSON report. No serials or IPs live in this folder; Portals come from the
  registry at runtime.
- Every change registers an undo step at once; undo runs on success, failure, Ctrl-C and SIGTERM.

## Known limits

- A deleted reminder stays as an invisible tombstone in the synced replica (that is how deletes
  sync). A fake Control Center entity stays (state off) in memory until the next HA reconnect.
- `revoke_id` removes the session; its token string stays in the test-session list until the next
  `phone_test_session revoke` (we don't use that: it would also end other agents' test sessions).
- A Portal whose phone remote is off is skipped for reminders; the suite does not turn it on.
- Without adb, the banner preview, Calls roster and provider checks are skipped.
