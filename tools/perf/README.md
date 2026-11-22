# On-device benchmarks (`tools/perf`)

One tool for every Portal performance measurement we used to run by hand (the methods in
[`docs/perf/portal-perf-2026-11-21.md`](../../docs/perf/portal-perf-2026-11-21.md)), plus a
per-Portal job queue so many agents can submit work without fighting over the device lock.
Python 3 standard library only; it drives `adb`, `fleetctl` and `gh`. Always run it with `python3 -I`.

## Recipe for agents

```bash
B=/Volumes/xHome/Github/portal/tools/perf/bench.py   # or your worktree's copy
id=$(python3 -I $B submit --device 2221B01C7T01C0 --scenario home-tabs --official --apk /abs/branch.apk --reps 4)
python3 -I $B wait "$id"                 # blocks; prints the tables and the A/B verdict
python3 -I $B compare <result.json>      # re-print the verdict later
```

A worker must be serving the Portal: `python3 -I $B worker --device <serial>` (one per Portal,
runs until stopped; `submit` warns when none is running). Do **not** take the device lock yourself
for a benchmark: the worker takes it for you, once per job, and waits politely while another agent
holds it.

## Commands

| Command | What it does |
|---|---|
| `scenarios` | lists scenarios and their screenshot policy |
| `submit --device S --scenario N [--official] [--apk A.apk[,A.dm]]... [--reps N] [--no-interleave] [--meminfo] [--atrace [--atrace-categories CATS] [--atrace-buffer-kb KB]] [--simpleperf] [--screenshots] [--poll-s SEC] [--cap-min M]` | writes a job to `scratch-tmp/perf-queue/<serial>/pending/`, prints its id |
| `worker --device S [--once] [--exit-when-empty]` | serves that Portal's queue (refuses to start if another worker is alive) |
| `wait JOB_ID [--timeout-min M] [--json]` | blocks until the job is done; exit 0 only if it ran and restored cleanly |
| `run` (same flags as submit, plus `--no-wait`, `--out DIR`) | runs immediately in this process; takes the lock itself (waits) |
| `compare RESULT.json` | per step and metric: A median, A spread, B median, verdict |

Builds: `--official` is the latest official release (label **O**, always first); each `--apk` is
**A**, **B**, … in order. The first build is the baseline for `compare`. With `--interleave`
(default) the order is A,B,B,A,A,B…; every build gets `--reps` measured reps, each install is
followed by a 25 s settle and one unmeasured warm-up rep.

Results: `scratch-tmp/perf-results/<timestamp>-<scenario>/result.json` (every sample, per-window
counts, HWUI summary, memory, installs, priors, restore steps) and `result.md` (tables), raw dumps
under `raw/`. Frame metrics follow the survey: framestats `FrameCompleted - IntendedVsync`, flags 0,
frames > 16.7 / 32 / 60 ms, worst, p50/p90/p99 (nearest rank), janky %, sum of frames > 32 ms.
The Control Center steps also count Flags 1, as the cc-* scripts did, because after BACK every Home
frame carries Flags 1 (its window became visible) and flags 0 alone shows an empty window.

Verdict rule: B **wins** a metric only when B's median is lower than A's median by more than A's
spread (max - min over A's samples); **loss** if higher by more than that spread; otherwise **noise**.

### atrace

`--atrace` captures `gfx view` with a 16384 KB buffer around every measured step, saved as
`raw/<build>-r<rep>-<step>.atrace.txt`. For WebView or scheduler work, widen it:

```bash
python3 -I $B submit --device 2221B01C7T01C0 --scenario screensaver-pinned:galleryWall --official \
  --apk /abs/branch.apk --reps 2 --atrace --atrace-categories gfx,view,webview,sched,freq --atrace-buffer-kb 65536
```

- `--atrace-categories`: comma- or space-separated atrace category names (lower-case letters, digits and `_`, at most 24). Anything else is refused, because the names go into an adb shell line.
- `--atrace-buffer-kb`: the per-CPU buffer, 1024–262144 KB.
- Both need `--atrace`, and both are recorded in the job and in `result.json` `options`.
- Check the header `entries-in-buffer/entries-written` of a saved trace. If fewer entries were kept than written, the ring buffer wrapped: raise the buffer or narrow the categories.

## Scenarios

| Scenario | Steps measured (per pass) | Screenshots |
|---|---|---|
| `home-tabs` | → Apps, → Tools, → Home via the tab bar (targets from `uiautomator dump`, located before the gfxinfo reset); 1 warm-up + 4 passes per rep | never |
| `cc-open` | fleet `open_control_center` (Map), 3 s; BACK to Home, 3 s | never |
| `cc-switch` | Map → Alerts via the read-only "door is open" row, 3 s; BACK, 3 s; 1 warm-up + 4 passes | never |
| `settings-open` | Settings from Home's header button, 2.5 s; BACK, 4 s | allow-listed only |
| `settings-page:<id>` | fleet `open_screen` settings page, 2.5 s; BACK, 4 s | allow-listed only |
| `screensaver-pinned:<mode>` | test source pinned at 15 s; 5 forced changes (left swipe) polled 12 s each, attributed by `photo_beat` | test-source frame only |
| `screensaver-household` | real dream (`Somnambulator`), household settings, 300 s polled window (`--poll-s`), one sample per `photo_beat` (first beat = start, skipped); warm-up dream 40 s | never |
| `rotation` | Mini emulation (1280x800 @160, auto-rotate off): fleet orientation portrait / landscape, 3.5 s each | never |
| `mini-size:<portrait\|landscape>:<scenario>` | any scenario at 800x1280 / 1280x800, density 160, auto-rotate off | as the inner scenario |
| `uiautomator-overflow[:screen,...]` | dumps home, apps, tools, control_center, settings, calls, store; flags nodes wider than the screen, past its edge, or horizontally scrollable | never |

Each scenario is a small dict in [`perflib/scenarios.py`](perflib/scenarios.py) (setup / warm-up /
inner passes / steps / teardown / its screenshot rule). Add one there; the engine needs no change.

## Safety guarantees (enforced in code, unit-tested)

- **Lock protocol** (`/tmp/portal-device-lock.md`): `mkdir /tmp/portal-lock-<serial>` + owner file
  `perf-bench <job id> worker-pid …`. If it is held, the worker waits (retry every 60 s) and runs no
  adb or fleet command. Every device command re-checks that the owner file still names this job;
  a lost lock stops the run. Only our own lock is ever removed.
- **One job at a time per Portal**, one worker per Portal; two Portals run in parallel.
- **Hard cap per job** (default 20 min, max 30, the protocol's longest hold): the worker's watchdog
  sends SIGTERM (the run restores), then SIGKILL after 5 min; if the run could not restore, the worker
  restores itself before releasing the lock.
- **Always restored**, in a `finally` (errors, SIGTERM/SIGINT/SIGHUP): official latest release
  (`gh release download`, sha256 verified on the Mac and on the device), `wm size/density reset`,
  auto-rotate back + fleet `orientation forget`, screensaver test source off, `cc_debug` cleared,
  STREAM_MUSIC back to its prior value, Home default and on top (`cmd package set-home-activity …`,
  a dream is woken, never tapped), `fleetctl dev off`, then a verification step.
- **Never**: force-stop, reboot, `pm clear/uninstall`, `kill`, `svc power`, `logcat -c`; the
  generic shell refuses `screencap`/`screenrecord`.
- **Screenshots** only via one guarded helper, only with `--screenshots`, only when the scenario
  allows it AND the resumed activity is ImmortalSettingsActivity, CallsActivity, StoreActivity, or
  PhotoFramePreviewActivity while the generated test source is active. `home-tabs`, `cc-*`,
  `screensaver-household`, `rotation` and the audit refuse always.
- BACK only on a resumed non-Home Hearthpane screen. STREAM_MUSIC is 0 during a run.
- Every session appends one line to `/tmp/portal-lock-notes-family.txt` (Mini:
  `/tmp/portal-lock-notes-mini.txt`).

## Tests

```bash
python3 -I -m unittest discover -s tools/perf    # no device: fake adb/fleet runner, real framestats captures
```
