# Portal+ approved native UX: independent performance verification

Date: 2026-10-04. Branch: `codex/portal-plus-approved-ux`.
Repository HEAD when reviewed: `caf8f350cabd00ab1b4aa2951e512bf6e351a702`.

The acceptance requirement is an optimized native experience with no perceptible
lag across warmed Home, Settings, Tools, Store and AppSwitcher navigation and
scrolling. This sidecar supplies a reusable evidence probe and source review.
It does not change Android code. Other workers own implementation; the main
worker exclusively owns Portal/ADB access, APK installation and device runs.

This document preserves the initial independent source audit and probe contract.
The sidecar did not access the device. The main worker subsequently installed and
measured the native implementation on the connected Portal+. Current results and
limitations are maintained in `docs/design/portal-plus-native-validation.md`;
historical source findings below describe the code at audit time.

## Probe contract

Run `scripts/portal_ux_probe.py` with Python 3.9+ on macOS/Linux and an installed
ADB executable. Device modes require `--serial`; there is no device discovery or
implicit default device. The package defaults to `com.immortal.launcher.debug`.
`--adb /absolute/path/to/adb` selects an installed executable.

- `--collect-only` reads existing `gfxinfo ... framestats` once, memory PSS,
  allowlisted package metadata, SDK and foreground destination. It does not
  launch, reset counters, wait for a sample window, or inject input.
- `--exercise` warms each selected destination, then collects separate
  navigation and interaction windows for each cycle. Each measurement resets
  package gfxinfo immediately before the corresponding window. It returns Home
  between destinations to bound the activity stack.
- Private activities use `run-as PACKAGE am start --user 0 -W
  --activity-clear-top -n PACKAGE/com.immortal.launcher.ACTIVITY`. User 0 is
  explicit; the class package stays `com.immortal.launcher` even for `.debug`.
  This launch path was verified by the main worker, not this sidecar.
- Only HomeActivity, ImmortalSettingsActivity, ScreensaverSettingsActivity,
  ToolsActivity, StoreActivity and AppSwitcherActivity can be launched. No arbitrary component or extra is
  accepted. Non-Home exercise requires a debuggable package and working run-as.
- Vertical scrolls alternate up/down, last 220 ms, and stay in the central
  content band. Each gesture is preceded by a foreground-window check. The
  script injects no taps, long presses, BACK, text, preference edits, install,
  uninstall, force-stop, data clearing, network configuration or service changes.
  Focus failure stops exercise rather than sending input into another app.
- Foreground checks read full `dumpsys window`, without the `windows` selector.
  The main worker confirmed that this Portal's selector omits mCurrentFocus and
  mFocusedApp, while the full dump includes them. Window text remains transient.
  Package-only window titles require a matching allowlisted mFocusedApp in
  user 0; missing/null focus or a foreign overlay still stops injection. Failed
  checks retain only expected/allowlisted activity names and field-presence
  booleans, plus the last sanitized AM start result.
- Home receives **no injected gestures**: hosted widgets and household controls
  can interpret drags as actions. Its automatic evidence covers launch and idle.
  The main worker must supplement safe manual Home/widget paging and swipes with
  `--collect-only`. AppSwitcher uses the explicit All apps extra. The optional `screensaver`
  destination exercises a longer scalar settings list. Home paging still needs
  safe manual coverage; its duplicate embedded apps grid has been removed.
- Every ADB subprocess has a configurable timeout of at most 30 seconds. Ctrl-C
  and SIGTERM stop the sequence, kill the current host ADB process group, and
  emit partial JSON with status `cancelled` and exit 130. Child cleanup has a
  separate bounded one-second reap. Errors use fixed diagnostic codes and exit 1.
  Remote gestures already dispatched may finish their 220 ms motion.

Navigation necessarily executes the app's existing lifecycle hooks. For example,
Home reaffirms screensaver settings and clears its stock-home bridge; Home/Store
may fetch configured photo/calendar/weather sources or catalog metadata. The
probe does not invoke setting controls or alter those implementations. Run on
an already initialized launcher after onboarding/migrations, during an idle
daytime session with no install, permission dialog, call, pairing operation,
screensaver, tool overlay or another worker using the device. An exact guarantee
of zero app-originated persistence during lifecycle execution is not possible;
use `--collect-only` when even navigation side effects must be excluded.

## Main-worker runbook

Use the same Portal+, orientation, theme, widgets, installed apps, photo/calendar
sources, network conditions and quiet foreground workload for both artifacts.
Do not change household configuration to make a run look faster. Record relevant
conditions separately without names, addresses, credentials or calendar contents.
Give the device time to cool and source loading time to settle. Run at least three
paired trials; use fresh output names because existing files are never overwritten.

The main worker installs each APK through its approved workflow before running
the corresponding command. `--apk` only hashes the operator-supplied local
artifact; it does not prove the installed artifact matches and never installs it.
Set `PORTAL_SERIAL` to the main worker's already selected serial. These commands
are instructions for that worker and have not been executed here.

```bash
python3 scripts/portal_ux_probe.py --self-test
mkdir -m 700 /private/tmp/portal-ux-evidence

# After the main worker installs the old baseline APK:
python3 scripts/portal_ux_probe.py --exercise --serial "$PORTAL_SERIAL" \
  --label baseline-old --cycles 5 --swipes 12 --settle-seconds 3 \
  --observe-seconds 3 --adb-timeout 30 \
  --output /private/tmp/portal-ux-evidence/baseline-old-1.json

# After the main worker installs the candidate native APK:
python3 scripts/portal_ux_probe.py --exercise --serial "$PORTAL_SERIAL" \
  --label approved-native --cycles 5 --swipes 12 --settle-seconds 3 \
  --observe-seconds 3 --adb-timeout 30 \
  --apk ~/portal-prime/app/build/outputs/apk/debug/app-debug.apk \
  --output /private/tmp/portal-ux-evidence/approved-native-1.json
```

Allow roughly several minutes per full run; cancellation is available throughout.
The script prints the same sanitized JSON to stdout. The JSON file is created
with mode 0600. Device identity is a short SHA-256 fingerprint of the supplied
serial, not the serial or an IP address. Package version and probe hash identify
the evidence. No raw dumpsys output, UI hierarchy, window titles, launch output,
token, photo source, calendar text, logcat, app inventory or preference contents
are persisted in the JSON.

For safe manual coverage, the main worker opens the relevant destination,
resets gfxinfo, performs only navigation/scrolling, then collects **before**
navigating elsewhere. Keep the gesture interval short: gfxinfo has a finite ring
buffer and a long run can lose its earliest frames.

```bash
adb -s "$PORTAL_SERIAL" shell dumpsys gfxinfo com.immortal.launcher.debug reset
# Main worker performs approved, safe Home/embedded-apps or Settings-subpage gestures.
python3 scripts/portal_ux_probe.py --collect-only --serial "$PORTAL_SERIAL" \
  --label approved-native-home-manual \
  --output /private/tmp/portal-ux-evidence/approved-native-home-manual-1.json
```

Screenshots are opt-in with
`--screenshot-dir /private/tmp/portal-ux-evidence/native-private-shots`.
The directory is created 0700 or must already be private; PNGs are created 0600.
They capture personal screen contents and belong only in private local evidence.
JSON contains relative screenshot filenames, never image contents. Screenshot
capture happens after measurement to keep its cost outside the measured window.

## Metrics and interpretation

`Flags`, `IntendedVsync` and `FrameCompleted` are matched by CSV header name,
including reordered headers and all `---PROFILEDATA---` chunks. Nonzero flags,
empty/zero/sentinel/nonpositive durations, malformed rows and duplicate records
within the same window are excluded and counted. Frames at the same vsync in
distinct windows are retained; raw window identifiers never leave the parser.

`elapsed_ms` is `(FrameCompleted - IntendedVsync) / 1e6` over valid drawn frames.
It reports sample count, p50/p90/p95/p99 and maximum using linear interpolation.
At the default assumed 60 Hz the budget is exactly `1000 / 60` ms (about
16.67 ms); `over_budget_frames/percent` compare against that budget. This is a
frame-elapsed proxy, not a claim about the hardware's actual refresh rate.
`--refresh-hz` changes the assumption for a known display rate.

`FrameDeadline`, when supplied and valid, yields separate actual reported
deadline-miss counts and percentages; its denominator includes only frames
with a usable deadline. `FrameInterval` is reported independently when present.
Android 9/10 often omit both fields, so missing deadline evidence is `null`,
not zero misses. Even reported deadlines and FrameCompleted are rendering
pipeline counters, not a measurement of compositor presentation or photon time.

CPU evidence is explicitly separate:

- `cpu_stage_elapsed_ms` sums `SyncQueued - HandleInputStart` and
  `SwapBuffers - SyncStart` when the legacy timestamps form a valid sequence.
  It excludes the sync-queue gap.
- `cpu_completion_elapsed_ms` reports `CpuCompleted - IntendedVsync` on newer
  headers when available.
- Both are wall-clock interval proxies and can include scheduling/driver waits.
  Gfxinfo does not provide actual CPU execution time/utilization. A CPU-vs-GPU
  cause needs a system trace after a regression is observed.

Launch evidence retains only AM status, LaunchState and ThisTime/TotalTime/WaitTime
in milliseconds. `initial_start` is separate from the intentionally warmed
samples. LaunchState cold/warm/hot is retained when supplied; Android versions
that omit it report `unknown`. A warmed Activity creation is not the same as a
HOT task resume. No cold start is manufactured by force-stopping the launcher:
that would disturb running services/bindings. If the main worker naturally
starts a fresh process, initial COLD timing is captured if AM reports it.
AM's launch timing is not input-to-photon latency or completion of async content.

Memory is total process PSS from `dumpsys meminfo`, in KiB. It includes Home,
the current activity, caches and launcher services; it is not per-screen
attribution. Compare like-for-like runs, especially later cycles, and account
for GC and cache warmup.

## Acceptance checklist

These are screening targets for the explicit no-lag requirement, not an automatic
pass emitted by the script:

1. Finish all destinations and manual gaps with JSON status `collected`; no focus
   failures, cancellation, blank content, unexpected navigation or unsafe action.
2. For comparable warmed scrolling windows with at least 120 drawn frames, target
   p95 <= 16.67 ms, p99 <= 33.33 ms, <= 1% over-budget/deadline misses, and no
   frame > 100 ms. Compare baseline vs candidate per destination and per phase.
   Any repeated visible hitch or regression blocks acceptance even if a
   percentile happens to meet its target. Investigate outliers separately.
3. AM warmed start times must not reproducibly regress by more than both 10%
   and 20 ms; actual foreground responsiveness remains the deciding observation.
   Retain cold timing if available, otherwise mark unavailable.
4. Compare stable-cycle PSS: investigate a sustained increase exceeding both
   10% and 20 MiB, or monotonic growth across five cycles after warmup. Explain
   expected image caches with evidence rather than assuming a leak.
5. Confirm manual Home paging/swipes, embedded apps, a sufficiently long scalar
   Settings list, and visible data/image arrival. Static/non-scrollable pages
   may yield few or zero frames. Such samples are insufficient for a tail-latency
   conclusion; idle silence is not a no-lag pass.
6. Repeat with the final APK after native workers finish. Debug builds add runtime
   and Compose overhead. Debug A/B evidence detects regressions; shipping
   acceptance should also include a release/profileable measurement or the
   supported collect-only path on release plus manual observation. Run-as will
   not open private activities in a normal release build.

Do not average per-window percentiles to invent pooled p99. Review each trial's
sample counts, distribution, excluded rows and phase separately. Navigation
captures are package-wide and may include outgoing Home and incoming windows.
The interaction capture starts after settling and resets to remove warmup;
source arrival after that reset remains part of the real workload. Short
windows may miss network-delayed arrivals; the main worker must also observe
those safely without changing bindings or credentials.

The following table records the initial sidecar audit, before the main worker's
device runs. It is historical, not the current acceptance status. Subsequent
baseline, three-trial scrolling, safe widget gesture and continuous-window
measurements are recorded in `docs/design/portal-plus-native-validation.md`.
Performance acceptance remains open.

| Initial audit evidence | Baseline old APK | Native APK at audit time |
|---|---|---|
| Automated destination runs | Initial attempt failed focus check; retry pending | Pending main worker |
| Safe manual coverage | Pending main worker | Pending main worker |
| Drawn-frame tails / deadlines | Unmeasured | Unmeasured |
| AM starts / stable PSS | Unmeasured | Unmeasured |
| Perceptible lag verdict | Pending | Pending |

The main worker's initial
`/private/tmp/portal-plus-approved-ux/baseline-performance.json` was inspected
offline. Its error was `focus: destination_not_foreground; exercise_stopped`,
with zero samples. SDK 29, debuggable package metadata and viewport 2160x1440
were successfully collected. The script had reached its initial launch/focus
gate; this was not a viewport parsing or USB authorization failure. The
main-worker diagnosis confirmed the unsupported narrow focus-dump selector.
The updated probe uses the full dump in both exercise and collect-only.
Preserve that failed JSON and use a fresh filename for the retry:

```bash
python3 scripts/portal_ux_probe.py --exercise --serial "$PORTAL_SERIAL" \
  --label baseline-old --cycles 1 --swipes 4 --settle-seconds 2 \
  --observe-seconds 1 --adb-timeout 30 \
  --output /private/tmp/portal-plus-approved-ux/baseline-performance-rerun.json
```

These retry parameters match the initial failed attempt. Expand to the paired
runbook above after firmware compatibility is confirmed.

## Read-only source audit: actionable gaps

Paths below are relative to `app/src/main/java/com/immortal/launcher/`.
Line anchors were read while workers were actively editing; verify the symbol
and current diff before assigning a fix. Priority describes verification/fix
urgency, not a demonstrated device fault.

| Priority | Path:line / concrete concern | Action for the owning native worker |
|---|---|---|
| P1 | `HomeActivity.kt:324` / `SettingsGuard.kt:57`: Home resume calls screensaver reaffirm on the UI thread. Secure-setting reads/writes cross the settings provider; `SettingsGuard.kt:82` writes enabled=1 even when already enabled. This is repeated on every return from a measured destination. | Measure return-to-Home spikes; guard redundant writes and, where lifecycle ordering permits, move blocking provider work off the UI thread. Preserve sleep/screensaver semantics. |
| P1 | `ApprovedHomeCalendar.kt:47`: grouping/sorting the fetched event list runs inside `remember` on the composition thread; `CalendarWidget.kt:71` sorts and allocates Calendar/day-group objects for the entire feed. `ApprovedHomeCalendar.kt:51` and `:60` eagerly compose all events in a scrollable Column. Three days bounds dates, not the number of rows. | Bound/prepare visible events off the UI thread and use lazy event items with stable identity. Verify a busy existing calendar without editing its contents. |
| P1 | `HomeActivity.kt:691`, `:710`, `:796`, `:4183`: every resume invalidates the app list and widget-provider list. Icon rendering in `loadApps` at `:4200` creates new 144px bitmaps for every launchable app, including apps not currently visible. Work is on IO, but repeated allocations, state publication and texture uploads can hitch the return transition. | Reuse icon/provider snapshots with package-change invalidation; measure allocation/PSS and first visible draw. Keep package add/remove correctness. Do not duplicate the AppSwitcher worker's existing cache implementation. |
| P2 | `StoreActivity.kt:255` / `StoreIcons.kt:28`, `:44`: a fixed pool limits active loads but its queue is unbounded. Disposing a card only suppresses its callback. A pending cache miss can enqueue again when scrolling back, and concurrent loads use the same disk filename. `StoreIcons.kt:55` also copies the response without a byte cap. | Coalesce pending package/icon requests, cancel stale interests or bound the queue, and use bounded atomic disk writes. Compare fast down/up scrolling, late icon arrival and stable PSS; existing 128px decode is already helpful. |
| P2 | `StoreActivity.kt:81`, `:85` / `StoreCatalog.kt:58`, `:65`, `:160`: repeated entries schedule catalog refresh/update scans on one executor. Generation guards discard callbacks, but do not cancel queued work. Earlier remote refreshes/update lookups can delay the next bundled-catalog result. | Verify rapid Store re-entry during existing slow source conditions; reuse a snapshot, coalesce refreshes and discard obsolete work before it consumes the queue. Preserve asset-first fallback. |
| P2 | `AppSwitcherActivity.kt:103`, `:340`, `:346`, `:359`: both all-app and recent lists are built on entry; recent limiting occurs after package/label/icon work. Newly added bitmap caching avoids repeat conversion, but `pm.getApplicationIcon(ai)` / `ri.loadIcon(pm)` are evaluated before the cached-icon helper is called. | Check cache before obtaining the drawable and limit successful recent entries during iteration. Measure warmed entry with many existing installed apps; retain curated hiding/order behavior. |
| P2 | `HomeActivity.kt:729`, `:740`, `:2624`, `:2636` and `ui/theme/Theme.kt:59`: preference snapshots/JSON deserialization and Today countdown sorting/to-do counting remain synchronous in composition/resume. Already-loaded SharedPreferences reads are usually cheap, but first XML loading can block and larger JSON arrays add UI CPU work. | Profile snapshot publication before changing it; preload/prepare expensive immutable snapshots on IO and keep registry persistence/side effects authoritative. Theme already uses the shared presentation observer, so there is no separate direct-prefs fix to duplicate. |
| P2 | `ApprovedHomePhoto.kt:58`, `:66`, `:144`, `:158`: decode is correctly on IO and cached, but longest-side sampling still allows a roughly 9.8 MiB ARGB bitmap at 1600x1600. The bounded 16 MiB transfer also creates an extra byte-array copy before decode. First texture upload, not `asImageBitmap` wrapping, can dominate a frame. | Check image-arrival and return tails/PSS; size decoded images to the actual hero viewport and reuse cached bitmap identity. Do not claim image upload is free because decode is off-thread. |
| P2 | `SettingsRenderer.kt:98`, `:102`: the list is lazy per section, but a section's Card composes every scalar control eagerly. A long first section still creates offscreen rows and effects at once. | Verify long scalar subpages manually; flatten row items with stable domain/spec identities if that section is the measured bottleneck. Overview-only probe evidence does not cover this. |

Positive checks and refresh scope:

- Normal Home widgets are page-bounded; custom/edit grids and the apps/Tools/Store
  grids are lazy. Home app/provider loading, Home photo decode, calendar fetch,
  AppSwitcher loading and Store installed checks already dispatch blocking work
  off the UI thread. Avoid reimplementing those improvements.
- `HomeActivity.kt:4610` (`ApprovedHomeGreeting`) and `:2928`
  (`ImmortalWorldClockWidget`) now tick by minute within their own composables.
  The legacy `HeaderBar` still has a one-second loop, but the current file has no
  caller for it. It is not evidence of a one-second whole-dashboard refresh.
  Watch live timer/now-playing widgets separately; they can intentionally update
  faster and must not invalidate unrelated grids or reload images.
- Theme has no disk-image decoding or blur/render-effect loop. ColorScheme
  objects are reconstructed when the theme recomposes; caching static palettes
  is a lower-priority allocation improvement if a trace justifies it, not an
  established cause of navigation lag.

Selected source content fingerprints at review (first 16 SHA-256 hex characters):

| File | Fingerprint |
|---|---|
| HomeActivity.kt | `33c329621a0d5728` |
| ApprovedHomeCalendar.kt | `83547638f47bdc0f` |
| ApprovedHomePhoto.kt | `7a6343844a216c2f` |
| AppSwitcherActivity.kt | `d96144d1631366e1` |
| StoreActivity.kt | `7d66b3a2481460c5` |
| StoreIcons.kt | `11da80ebec1d596a` |
| StoreCatalog.kt | `eca7570cede0aad8` |
| SettingsRenderer.kt | `a879d41c174aea21` |
| ui/theme/Theme.kt | `75047bd3920fdc7d` |

## Local verification

The script includes offline fixtures for Android 9-style and extended framestats,
multiple/reordered CSV chunks, flag/sentinel/malformed/duplicate filtering,
window identity, hand-calculated percentiles, deadline availability, CPU proxies,
launch/PSS/metadata redaction, private-activity command construction, focus
rejection, timeout/cancellation child cleanup, viewport/swipe behavior, CLI
validation and collect-only's one-buffer/no-input contract.

Run `python3 scripts/portal_ux_probe.py --self-test` and
`python3 scripts/portal_ux_probe.py --help` locally without ADB. Android build/unit
gates belong to the main/native workers because this change touches only the
Python probe and this audit. Offline tests do not verify firmware command
compatibility, coordinate behavior or actual responsiveness. The main worker's
later device evidence is recorded separately in the native validation report;
offline checks alone do not establish responsiveness.

Latest local verification: 14 offline tests passed, including a simulated
five-destination exercise and the full-window focus contract. CLI help, Python
syntax compilation in memory, and both owned files' whitespace checks passed.
No bytecode/build files were written by those checks.
