# Companion emulator QA unit — 2026-11-02

**Next unit:** rendered native settings acceptance for a grouped child write when
one destination's screensaver master is disabled. Use Family Area and Living
Area's real paired API, with four checks and a hard **30-minute limit including
setup and restoration**.

**Preparation only.** Source and local APK were inspected; both running emulators
were inspected read-only. No companion was launched, installed or tested, and no
Portal API request was made. No current Pixel is reachable. This document does
not grant permission to execute the future setup, live writes or device changes.

## Current identity and evidence

Source checkout:
`~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit`,
clean at **`d2ab33982af13693a8a984912e921cbf2f41802f`**. Its preceding maintenance
commit is `610899f7074323e24832caa9f0d52e3db8a7b5fd`, based on preserved music
source `422286e1d8e2afd51c9809315418681e2f868efd`.

| Identity | Current companion |
| --- | --- |
| App label / package | **Portal Toolkit** / **`com.portalinstaller`** |
| Launcher component | **`com.portalinstaller/.MainActivity`**; exported MAIN/LAUNCHER |
| Actual app entry | `MainActivity` renders `ControlCenterScreen`, initially Home; Settings is its bottom tab. The older Device tools `MainScreen` and its `devices` start route are secondary. |
| Build declaration | **`1.7.0-music-preview` / versionCode 12**, minSdk 26, targetSdk/compileSdk 35; no separate debug application ID suffix |
| Current APK | `~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/build/outputs/apk/debug/app-debug.apk` |
| Bytes / SHA-256 | **15,369,788** / **`e319fb1486e6ad06dfa4bb3418a469acc75ee815c1688ec1bafba06c4cec0d9b`** |
| Signer certificate SHA-256 | **`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`** |

APK bytes/hash were freshly recomputed and match the
[visibility delivery receipt](portal-settings-visibility-delivery-2026-11-02.md).
Local `apksigner verify --print-certs` exited 0. These checks identify the candidate,
not an installed or accepted app. Local `aapt dump badging` also confirms the APK's
package, launcher Activity, label, version and SDK declarations in the table.

The earlier compatibility receipt's APK
`9ce56c077517e5a92ec5e10858926fe70b3c54aceb7ea46b873d40e5c1b1359e`
(14,722,589 bytes) predates `d2ab3398`. The historical Pixel music APK
`c2933b966edad3f1793641bf105e1a33292543132774beeaacc79318d8fcbbd3`
also does not identify this candidate. Identical version names/codes do not make
these artifacts interchangeable.

The latest inspected Portal delivery receipt records both rooms at local,
unpublished **1.69 / code 63**, package `com.immortal.launcher.debug`,
**25,860,686 bytes**, APK SHA-256
**`e7f6e0ceffa2685d2db40a57cdf86b2f6746dea3087a9bdca463264e0c71ccb3`**,
with the signer above. This is recorded delivery evidence, not a fresh room
readback. The coordinator's forthcoming favicon deployment may supersede it:
record that delivery's exact identity before QA; do not silently label the new
Portal artifact 1.69 or reinstall the older one.

## Running emulators: inspected, not disposable

Read-only observations on November 2 at approximately 09:16 UTC:

| Observation | `emulator-5554` | `emulator-5556` |
| --- | --- | --- |
| AVD | `HearthParity17` | `HearthSettingsIsolation17` |
| Android / API / architecture | Android 17 / **37** / arm64 emulator | Android 17 / **37** / arm64 emulator |
| Physical display size | **1440 × 3120 px** | **1440 × 3120 px** |
| Density | Physical **480 dpi** | Physical **560 dpi**, existing override **480 dpi** |
| Nominal effective display | **480 × 1040 dp**, before insets | **480 × 1040 dp**, before insets |
| Companion package, including retained package records | Absent | Absent |
| Companion entrypoint resolution | `No activity found` | `No activity found` |
| Companion user-0 data directories | `/data/user/0/com.portalinstaller` and `/data/user_de/0/com.portalinstaller` absent | Same |
| Existing unrelated app | `com.rohit.hearth` installed | `com.rohit.hearth` installed |
| Foreground at inspection | Hearth `.SettingsActivity` | Nexus launcher |
| Existing local AVD snapshot | `snapshots/default_boot` present | `snapshots/default_boot` present |

Each emulator reported one Android user. Neither has usable companion pairing
state at the inspected paths. Both have existing unrelated app/AVD state to
preserve. Hearth is non-debuggable; `run-as` refused access. Its private contents
were not inspected, so neither emulator should be described as empty or reset.
Snapshot presence alone does not prove that it captures the latest running state.

Evidence commands were serial-specific `getprop`, `wm size/density`,
`pm list packages` (also `-u`), `cmd package resolve-activity`,
filtered `dumpsys package/activity`, user-count inspection, directory existence
checks and the unsuccessful read-only Hearth `run-as` check. No ADB input,
root, launch, snapshot save/load or settings modification was used.

## Pairing, storage and the acceptance gap

The intended onboarding flow is the native Add Portal dialog: a Portal address
or pairing link and six-digit PIN, with discovery while that dialog is visible.
`CompanionClient.pair` posts to `/remote/pair`; later requests use the saved
session as an Authorization bearer header. **Do not exercise onboarding or mint
a token in this unit.** Fleet registry tokens and browser sessions are not a
substitute for the companion's saved session records.

`FileCompanionSessionStore.create` uses
`Context.noBackupFilesDir/companion`, normally:

- `/data/user/0/com.portalinstaller/no_backup/companion/companion-sessions.json`
  — versioned saved room sessions, including credentials and photo destinations.
- `/data/user/0/com.portalinstaller/no_backup/companion/companion-targets.json`
  — independently persisted selected destination IDs.

Writes are atomic with owner-only permissions. Although the manifest allows
backup, this directory is excluded from Android backup. Toolkit's ADB saved
devices/DataStore records are separate. Missing target selection defaults to all
saved rooms; an explicit empty selection persists. Select destinations
deliberately before any write.

The current production path is:
`RegistrySettingsPanel` → `ControlCenterViewModel.writeSettings` →
`CompanionRepository.writeSelectedSettings` → captured target roster →
per-room FIFO `CompanionGroup` preflight →
**`GET /remote/settings?domain=screensaver&includeInactive=1`** →
typed/range/active-only validation → `POST /remote/settings` →
per-room applied-key confirmation. Ordinary display reads still use
`/remote/settings` without `includeInactive=1`.

The corrected code and nine added tests have a recorded **564 JVM tests /
74 suites plus debug assembly** pass. They have no current rendered native,
real-API acceptance. The older live multi-room test changes `immortal/weatherUnit`;
it does not exercise a hidden `screensaver` child. Portal/PWA disabled-parent
acceptance likewise does not pass this native path. **That specific gap is the
purpose of this slot.**

## Safe setup and readiness gate

All steps below are proposed future QA operations, not operations performed
during this preparation.

1. Reserve the slot with the coordinator after favicon deployment and room
   restoration. Avoid concurrent room configuration writes. Keep the two
   running Hearth emulators untouched; use a separately named disposable clone
   from owner-approved AVD state. Preserve current state before any later
   snapshot workflow; never overwrite `default_boot` or assume it is current.
   Use an isolated read-only overlay with snapshot saving disabled, or a
   quiescent owner-approved snapshot clone; do not copy live locked disk images.
2. Reuse an owner-approved, existing companion no-backup store or a complete
   companion-bearing emulator snapshot. Transfer it privately into the clone
   with restricted permissions, without terminal output, source control or
   credential-bearing logs. Preserve the original donor byte-for-byte. Prefer
   a full clone/snapshot so target selection and app preferences remain coherent.
   Standard Android backup alone does not restore these sessions.
3. Verify the candidate's exact hash/signer, then install only into the
   authorized clone and verify installed bytes/hash/version/component there.
   Never solve a signature mismatch by uninstalling an existing owner app.
   Do not automatically install the older instrumentation APK: its receipt
   predates the current preflight change.
4. Confirm existing Family/Living sessions authenticate from the clone. A missing
   or expired session is a readiness failure, not permission to pair again.
   Privately capture each room's complete screensaver/source baseline and the
   clone's viewing/target/font/display state; report equality checks, not values.
   Establish an enabled viewed Family and disabled destination Living using
   single-room master changes only where necessary.

**Present readiness:** neither inspected emulator supplies companion sessions.
The historical receipt locations `/private/tmp/toolkit-music-validation`,
`/private/tmp/portal-ma-receiver-binding-source` and
`/private/tmp/portal-music-recovery/integrated-rollout` are absent.
`/private/tmp/portal-companion-compat-20261102` exists as the current local
validation receipt directory; its existence does not establish a pairing backup.
No usable donor was established. With no reachable phone, the live slot cannot
start honestly until existing authorized session state is available.
If setup/authentication is not ready by minute 5, stop and record **blocked before
live acceptance**; do not extend the slot or generate credentials. Account-free
UI fixtures can be a separate fallback, but cannot close this acceptance gap.

## Four checks in one fresh 30-minute slot

| Time | UI action and precise acceptance |
| --- | --- |
| **0–5 min** | Complete the setup/readiness gate above. No write without an individual room baseline and a restoration route. |
| **5–9 min — 1. Disabled display stays filtered** | View Living → Settings → screensaver category. Ordinary and phone Advanced views expose only the generic master `enabled`; `shuffle` and `intervalSec` remain hidden. Calendar's independently owned controls remain accessible. Switch back to enabled Family and refresh: supported saved children return. Judge registry rows, not the separately owned photo-source forms. Do not enable Living merely to make group preflight pass. |
| **9–17 min — 2. Real disabled-parent group preflight** | View enabled Family; choose both Family and Living under **Send changes to**. From Family's visible **Shuffle** switch submit the opposite of Living's privately saved original `shuffle`, ensuring the disabled destination actually changes. Require a ledger row for each captured room, both **Confirmed by Portal**, and authenticated readback of the requested key from both rooms' inactive metadata. Living's `enabled` remains false; Family's remains true. No unsupported-setting rejection for Living and no unrelated source/setting changes. The native switch/ledger plus live readback are required; an API-only probe or JVM pass is insufficient. |
| **17–20 min — 3. Viewing/destinations and input admission** | Reopen the top menu, change **View Portal** independently of destination checkboxes and confirm the passive Settings summary matches the selected destinations. Clear selection: ordinary group controls become disabled; restore both destinations deliberately. On enabled Family's Photo interval field, type **4**, then **601**: Save stays disabled and no operation appears. Restore its original draft without saving. The server range is 5–600; active-Portal-only controls have a separate policy and are not expected to follow the group-selection guard. |
| **20–24 min — 4. Current dropdown/overlap regression smoke** | In the clone only, use a narrow **280 dp** window with **2×** text. Open the top Portal menu, scroll explicitly to View Portal / Send changes to and the last available action, select/deselect Living, dismiss with Back, and reopen the Settings category dropdown. Verify readable bounded popups, reachable final rows, selected indicators, dismissal and correct callbacks. Header, destination summary, label/help, field/Save and bottom navigation must not overlap or obscure touch targets. Keep targets at least 48 dp. Restore display/font configuration afterward. Two real rooms cannot prove the historical 60-choice stress case. |
| **24–30 min** | Restore and verify all room and clone state; record the scoped verdict. |

The latest write finishes before layout stress. Do not inject outages, revoke
pairings, change scene/layout lists, upload photos, alter playback, start HA
services or add another feature area. Live writes are limited to the master
setup and one reversible `shuffle` change. Any menu/range checks must not send
extra settings changes.

## Restoration and result boundary

Restore each room's own `shuffle` while its current master state remains as
tested, using a single-target write from an enabled viewed schema or the existing
validated inactive API. Then restore that room's original `enabled`. Never
restore one shared guessed value to both rooms. Verify complete screensaver and
source baselines match per room, and return any navigation state changed by the
QA workflow to its baseline. Preserve baseline material privately until equality
is confirmed.

Wait for posted work to settle before restoration. Do not retry a successful
target or an ambiguous write blindly; read back first. Begin cleanup immediately
on failure and reserve the final six minutes. A restoration failure is an
explicit incomplete result, even if the functional check passed.

Restore clone target/view/font/display state or discard the owned clone after
room restoration. Keep donor sessions and the two original AVDs unchanged.
Do not erase an original pairing or rely on application export to restore the
no-backup session store.

The receipt should contain exact source/APK/Portal identities, emulator/window
configuration, four pass/fail/not-run results, per-room applied-key confirmation
and baseline equality. Store any restricted captures outside the repository;
capture Settings/menu regions only, excluding credentials and household photos.

**A pass closes emulator-native UI → real Family/Living API acceptance for this
specific preflight change.** It does not establish current Pixel installation,
folded/unfolded layout, physical touch/IME/accessibility, lifecycle/network
behavior, audible music, latency or rendering performance. Repeat the scoped
native checks on the exact candidate when the Pixel becomes available, preserving
its existing data. The parked physical interruption harness remains parked.

Prior dropdown width, long-menu clipping, help alignment and narrow-header
overlap defects were already fixed in the October 8 receipts. The October 9
unified menu also passed 17 emulator UI checks; that receipt's Pixel was locked
and did not supply visible menu acceptance. This slot checks current regressions;
it does not relist those defects as unresolved or borrow historical Pixel
performance figures.

## Inspected evidence

- Current Toolkit `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity`,
  `ControlCenterScreen`, `RegistrySettingsPanel`, `ChoiceControls`,
  `CompactPortalHeader`, `ControlCenterViewModel`, `CompanionClient`,
  `CompanionRepository`, `CompanionGroup` and `CompanionSessionStore`.
- Current `CompanionGroupTest`, `CompanionClientTest` test entrypoints and
  `CompanionInactiveSettingsTest` location; existing opt-in
  `CompanionMultiPortalDeviceTest` implementation. No tests were executed here.
- Toolkit `docs/companion-compatibility-validation-2026-11-02.md`,
  `companion-premium-controls-validation-2026-10-08.md`,
  `companion-compact-remote-validation-2026-10-08.md`,
  `companion-multi-portal-device-validation.md` and
  `companion-unified-portal-menu-validation-2026-10-09.md`.
- This worktree's `portal-product-operating-state.md`,
  `portal-companion-provenance-2026-11-02.md`,
  `portal-settings-visibility-delivery-2026-11-02.md`,
  `portal-pwa-connection-status-delivery-2026-11-02.md`, and current
  `SettingsDomains.kt` interval/shuffle definitions.
- Read-only emulator/package/display observations and host AVD snapshot
  existence; current candidate manifest, SHA-256/size and signer verification.

Only this plan was written. No source edits, builds, installations, ADB input,
phone/Portal changes, network requests, token generation, GitHub actions or
commits were performed.
