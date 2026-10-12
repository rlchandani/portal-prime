# Sounds settings: worker-backed saves and reliable readback

Sounds scalar rows previously applied the Chime registry synchronously in the touch callback.
Quiet-hours and audible-cue edits also ran ChimeScheduler.reschedule on that same thread,
including Android alarm operations. The editor now submits to the existing LatestSystemWrites
worker mechanism through one process-owned Chime writer. SettingsDomains.chime still validates,
persists and invokes its side effects; storage and remote settings APIs are unchanged.

Accepted saves finish even if the pane leaves. Pane-owned readbacks await the writer and reload
the real stored snapshot off the UI thread. Cancelled reads do not clear busy state. Resume
retains the submitted edit's failure baseline, and an obsolete completion cannot clear a newer
edit. Save feedback remains above the scrolling list. Numeric rows and their Save action now
honor editsEnabled, matching the busy behavior already used by registry toggle rows.

## Verification

- Full local gate: 1867 tests across 218 suites;
  zero failures, errors or skipped tests. Debug and optimized builds passed.
- Six worker tests cover actual Chime volume validation/persistence, worker execution,
  registry-hook ordering, accepted saves surviving pane cancellation, latest-pending event
  ordering and non-replayed failures. The side-effect thread test uses a controlled registry
  hook; it does not measure Android AlarmManager duration.
- Five save-state tests cover cancelled refreshes remaining busy, failure surviving resume,
  successful retry, obsolete completions and recovery from read errors.
- 88 offline native-runner tests passed before the Family trial, including the original 69.
- Seven native checks passed: Settings entry, Sound & wake overview, original Sounds value,
  changed Volume with completed UI/store readback, Back, Forward with retained value, and
  original Volume restored. Dialogs use their own Android window; the same Settings window
  returns after Save and remains through Back/Forward. No new Settings Activity is opened.
- Exactly two configuration edits: one Volume step and restoration. No audio feature was
  enabled, no test sound played, and no voice was changed. Other config/source snapshots were
  compared in RAM and are not exported in proof files.
- Exact APK and SDK/bundled profiles verified. Candidate retained; Home restored; collector
  inactive; configuration and source unchanged. No Living Area or phone operations.

## Limits

These checks establish correct saves and remove synchronous registry/alarm work from the input
callback. They do not establish an FPS percentage, per-tap speedup or universal frame-rate target.
Spoken-voice enumeration and audio playback actions were not changed or retested in this pass.
Superseded pre-review build artifacts are preserved locally and were never installed.
The first native trial stopped at the install/Home-return guard and is retained in the original
proof root. Installing the running launcher can expose the suspended browser underneath it.
The recovery runner admits one Home return only inside that installation scope, after a strict
pre-install Home and fresh exact installed-APK verification. Normal navigation admission remains
strict; Meta/other foreign apps, changed browser windows, uncertain receipts and reused slots
are rejected. Read-only UI observation tolerates a transient missing Activity window for up to
three seconds, returning the fresh stable window ID only when before/after reads agree. A
regression reproduced the stale outer-window comparison before this correction. Foreign owners,
cover/pin hazards, persistent missing windows and uncertain input remain fatal. Earlier failed
reports remain immutable in sibling install-return and owner-observation proof roots.
No product navigation behavior was changed for these driver corrections.

The deployment recovery issue began at 02:11:38 UTC on 2026-10-12. Successful Family proof was
confirmed at 02:36:15 UTC: 24 minutes 37 seconds, inside its 30-minute limit.

Candidate APK SHA-256: `8bea72ec84d7974a810045401a5648e2ca00afa0dee000d08df6d59db7938a27`.
Source SHA-256: `dc26f3ce1f926998b899e3d7a1112a3986fbfcc39dd957505a68820a19dc5770`.
Postflight SHA-256: `defc3a0678c498175f027d9a5ea86f40474ad6001c1415330aac443e08c0de0f`.
Proof root: `~/portal-prime/build/fleet/diagnostics/settings-chime-writes-stable-owner-2026-10-11`.
