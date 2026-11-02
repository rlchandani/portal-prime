# Portal product operating state

Snapshot: **2026-11-02 UTC**. Original inventory inspected
`codex/portal-hi-widget-fixes-20261102` at base
`137608262e39d72498a9de57b6d7bdc3ff7fffe2`; subsequent deliveries and maintenance
are recorded below, with the native editor unit committed at `2682e3a9`.
Resume from this inventory and the
linked receipts; update implementation, checks, deployment and acceptance separately.
The updated whole-product objective is
`~/.codex/attachments/4832b00e-0492-44a6-a521-a970bf2ea968/goal-objective.md`.

## Current delivery and boundaries

- The active goal covers the whole existing product: Portal, native Android
  companion, browser remote, fleet/provisioning, integrations, UX, reliability,
  performance and delivery automation. Research and backlog items are not
  implementation claims or blanket approval for proposed features. The goal
  does not require creating every platform mentioned in its discovery checklist.
  Preserve the user's no-HA-music-deployment and no-GitHub-authentication/publication
  constraints, unapproved mocks, and 30-minute issue limits.
- **Initial two implemented fixes:** Photo Memory edit opens `sources` with idle
  slideshow disabled; the alarm caption observes active-page configuration and
  refreshes at minute boundaries, rounding positive partial minutes up.
  [Independent source review](portal-hi-first-units-review-2026-11-02.md) found no
  concrete defects. **Coordinator update:** 3,779 JVM tests and both APK builds passed;
  Family routing/countdown/return checks passed, and both original
  1.65 installed readbacks passed; current 1.70 preserves those fixes. Read the
  [delivery receipt](portal-widget-fixes-release-2026-11-02.md) for native limits,
  artifact hashes, restored configuration and deployment evidence.
- **World Clock clipping is now delivered:** complete-row fit and remaining-city
  counts passed Family's normal two/three/four-city and 150% live/cold checks.
  The final APK is installed with exact readback on both rooms. Read the
  [1.66 receipt](portal-world-clock-delivery-2026-11-02.md) for restored settings,
  the superseded candidate, automated checks and hardware limits.
- **Home timer owner fix is delivered:** the 1.66 failure was reproduced natively. Home Open and Customize → Settings now control the shared widget countdown; Tools preserves kitchen timers. Family controls, custom 0:10, exact-alarm ring/Stop and dismissal passed. Original nine-widget layout, Home page and all screensaver fields were restored. Both rooms have exact 1.67 installed APK readbacks. See the [timer receipt](portal-home-timer-delivery-2026-11-02.md) for limits.
- **Screensaver parent visibility is delivered on Portal/PWA:** installed 1.67's
  disabled ordinary schema exposed 16 controls and the mobile PWA exposed 11
  ordinary rows; installed 1.68 exposes only the master on both, including
  Advanced. Family native hide/re-enable, validated inactive writes and complete
  profile checks passed; all 36 saved fields and Home page 1 of 2 were restored.
  Both rooms have exact 1.68 byte/hash/version readbacks. Companion group preflight
  is corrected on the isolated branch, commit `d2ab3398`: 564 JVM tests / 74 suites
  and debug assembly passed. That phone APK is uninstalled; physical phone
  acceptance remains open. See the [delivery receipt](portal-settings-visibility-delivery-2026-11-02.md).
- **Browser connection-status ownership is delivered:** the actual Family 1.68
  cancellation reproduced false Offline. The 1.69 controller removes the unowned
  continuation and promptly replaces missing status after navigation/resume.
  Six installed Family trials passed (119–175ms browser click-to-metadata), six
  connected tab switches added zero status GETs, controlled transport/401 handling
  and original pairing worked. Exact 1.69 APK readbacks and unchanged complete
  profile/screensaver/layout baselines passed on both rooms. Family’s original
  photo Dream was observed again after cleanup. [Receipt](portal-pwa-connection-status-delivery-2026-11-02.md).
- **Browser tab branding is delivered:** one explicit favicon link reuses the existing public 64 × 64 PNG. Actual Family paired/unpaired browser sessions naturally requested the public icon without authorization, with zero default favicon requests; management authentication, manifest/touch icons and original pairing were preserved. Both rooms have exact 1.70 APK readbacks, unchanged full profile/screensaver/layout, and Family's original photo Dream was restored. [Receipt](portal-pwa-tab-branding-delivery-2026-11-02.md).
- Gradle and `version.json` both currently declare **1.70 / versionCode 64**.
  These identify the verified local device iteration, not a published release.
- **Completed parallel maintenance units:** native settings editor classification/projection
  coverage and native phone music-compatibility coverage. Nine focused Portal
  tests passed; a deliberate hidden-text mutation caused four assertion failures
  and was restored. Full Portal gates passed: 3,796 JVM tests / 398 suites, both
  APKs, R8, performance vital lint and version/signing checks. The isolated companion branch
  passed 555 JVM tests / 73 suites, including fifteen new compatibility tests;
  debug and instrumentation APK builds passed. These are regression
  protection increments, not new visible features. Native settings maintenance
  is included in delivered 1.70; scoped Slideshow acceptance does not prove every
  editor. Companion maintenance remains uninstalled. Latest Portal gates passed
  **3,800 JVM tests / 399 suites**, 676 bundled JS, 161 Python and optimized assembly. Earlier debug APK gates retain their original scope.
  Native editor work is committed as `2682e3a9`; read its
  [receipt](portal-settings-editor-contract-2026-11-02.md). Companion maintenance
  is committed as `610899f7074323e24832caa9f0d52e3db8a7b5fd`; its receipt is
  `docs/companion-compatibility-validation-2026-11-02.md` in the isolated Toolkit
  checkout. Original 540 companion tests were unchanged and passed against the
  candidate; no separate unmodified-base run or physical phone check is claimed.
- The [current local roadmap](http://127.0.0.1:8891/roadmap/) presents nineteen
  tracked items with acceptance/evidence/limits and actual cropped clock captures.
  It is a saved priority-queue snapshot, not live telemetry or an exhaustive
  product acceptance report. Browser interactions passed at desktop/mobile
  sizes and the simulated Portal viewport; no native device acceptance is
  implied by this artifact. Source lives under
  `mocks/portal-widget-gallery-20261102/roadmap/`.
- The [widget gallery](portal-widget-gallery-checkpoint-2026-11-02.md) remains an
  **unapproved browser review artifact**. Its Android/iOS columns are design
  interpretations. The broader widget redesign is not authorized by these two fixes.
- The initial inventory sidecar inspected local files only; coordinator delivery
  and later source provenance are recorded separately in their receipts. Current
  authorized local checks and device delivery continue. All Git use requires
  `git -c core.hooksPath=/dev/null --no-optional-locks`; GitHub authentication,
  push and publication remain forbidden.

## Product and platform inventory

| Component | Purpose, dependencies and inspected evidence | Verification/release procedure and current limits |
| --- | --- | --- |
| **Portal Android** | Single Kotlin/Compose `:app`, `com.immortal.launcher`; minSdk 24, targetSdk 36; Portal API 28/29, no Google services/root. Home/apps/widgets, photo and clock Dreams, tools, store/updater, MQTT/HA, music, accessibility, boot/sleep. Inspected [README](../README.md), build configuration, Home and settings source, current review and existing audits. | `./gradlew :app:testDebugUnitTest --no-daemon`, `:app:assembleDebug`; optimized variant and native acceptance are separate. Debug has `.debug` suffix. Preserve signing identity and configuration for in-place updates. Current 1.70 optimized gates and Family browser-branding acceptance passed; both rooms have the exact artifact. Earlier 1.69 browser-status proof remains separately scoped. Earlier 1.68 Slideshow acceptance remains separate. Earlier timer acceptance remains separately scoped. Earlier World Clock acceptance remains recorded separately. Whole-product hardware acceptance remains incomplete. |
| **Native Android phone companion** | Paired management uses Portal protocol version 1: revision-guarded layout/scenes, settings/sources, photos, operations and Jarvis; music client supports capability fallback. Recovered source `422286e…` now has committed maintenance `610899f…` on isolated `codex/companion-compat-20261102`, still 1.7.0-music-preview/code 12. See [provenance](portal-companion-provenance-2026-11-02.md). | Earlier 555 JVM tests/73 suites and debug/test APKs passed. Latest group-preflight correction passed 564 tests/74 suites and debug assembly. Neither phone candidate was installed/run. Current phone identity, rendered UI, live networking/playback and parity remain unverified; historical Pixel receipts are separate. Primary/frozen/stale checkouts preserved. No shipped native iOS app identified. |
| **Related Portal Toolkit checkout** | **Accessible and inspected:** `/Volumes/xHome/Github/portal-toolkit`. Compose/Dadb phone utility for USB/Wi-Fi ADB, APKs, photos, backups, files, logs, capture/mirror and OTA controls; Google Photos auth belongs on the phone. Build declares `com.portalinstaller`, minSdk 26/targetSdk 35, **1.4.0 / code 5**; README says release 1.1.0 and only Portal Mini tested. | Gradle `:app:testDebugUnitTest`, `:app:assembleDebug`; 34 JVM test source files found, none executed. No `.github` directory present; release signing/publication process not established. This checkout is not proof of the paired companion branch above. August audit checkboxes are stale: synchronous connection guards, error handling, shell quoting and capture cleanup already exist in inspected source. Revalidate before creating defects. |
| **Browser remote / PWA** | APK-bundled `RemoteHtml` plus `remote/{core.mjs,control-center.js,control-center.css}`, served by Fleet on LAN. PIN/session pairing, room roster, input/media, photos, apps, registry settings, layout/scenes/snapshots, Jarvis. Inspected [control-center contract](features/browser-control-center.md), [remote docs](features/remote.md), routes and offline JS fixtures. Paired management requires session auth; do not equate it with unrestricted Fleet auth. | No separate web build/deploy: assets ship with the Portal APK. Offline Node tests cover controller behavior; rendered browser/mobile and live Portal checks are additional gates. Existing receipts establish selected cases only. Current browser/device sessions, PWA installation and native companion parity were not inspected. |
| **Fleet / provisioning** | Opt-in `FleetAgentService`/HTTP routes, std-only Rust `provisioning/fleet.rs`, Bash/PowerShell kit, backup/restore; uses existing installer/catalog/settings. Inspected [Fleet docs](features/fleet.md), provisioning README and CI. Gen-1 installer/overlay/daemon differences must remain explicit; Windows scripts must remain ASCII. | `bash -n provisioning/provision.sh`; PowerShell 7 and Windows 5.1 parse/behavior checks; `cd provisioning && rustc -O fleet.rs -o fleetctl`. Deploy only with preserved signing/configuration and post-install identity/readback. Tokens/registries were not opened. Fleet reachability, actual CLI binary and provisioning hardware compatibility are unverified here. |
| **Jarvis external integration** | External `com.portal.assistant`; Immortal foreground wake handoff, durable bridge worker and finite `content://com.portal.assistant.control` IPC. Inspected `JarvisPreparedLaunch`, `RemoteCompanionJarvis` and [bridge](design/portal-jarvis-bridge-write-validation.md)/[handoff](design/portal-plus-jarvis-handoff.md) receipts. Model/wake configuration validates advertised choices and readback; microphone credentials stay in Jarvis. | Known local `portal-assistant` paths checked are absent; external provider/cloud source and current model availability are **unavailable/unqueried**. Older voice and bridge checks do not establish current voice/cloud performance. Provider capability tests, foreground/microphone behavior and external APK release require that project's source and separate acceptance. No model migration inferred from old research. |

Shared settings use `SettingSpec` validation, immutable snapshots where available,
domain `onApplied`, and Activity-backed rich editors. The
[registry audit](design/portal-settings-registry-audit-2026-11-02.md) records
**12 domains / 108 specs / 97 writable scalars**, with deliberate collection,
credential and bespoke-editor exceptions; coverage tripwires do not cover every
preference/store. The [navigation audit](design/portal-settings-navigation-audit-2026-11-02.md)
records **8 categories / 54 pages**, not 54 device-verified screens.

## Build, CI and release state

| Gate | Inspected configuration / evidence | Status |
| --- | --- | --- |
| Portal local checks | Latest [1.70 receipt](portal-pwa-tab-branding-delivery-2026-11-02.md); earlier editor/clock/timer/visibility receipts retained separately. | Latest 3,800 JVM/399 suites, 40/676 JS, 161 Python, optimized APK/R8/vital lint and version/signature checks passed. Exact installed readbacks both rooms; Family browser-status acceptance passed. Earlier native Slideshow and World Clock proof retains its original scope; every-editor and physical phone acceptance remain separate. |
| CI | `tests.yml`: JVM, existing slideshow JS, all 29 bundled-JS regression modules, and seven Portal Python regression files. Catalog: schema/network validation. Provisioning: Bash/PowerShell behavior, parse and ASCII. Docs: strict MkDocs plus main-push Pages deploy. Release guard: version/URL/notes consistency. | Newly added JS/Python gates passed locally (652/140); YAML parsed, shell prerequisite syntax checked. Hosted Ubuntu/Node 22/Python 3.12 execution remains unobserved. Native device checks and Rust compilation are not configured there. |
| Public release | [Release procedure](releasing.md) and `cut-release.sh`: authenticate/fetch, version bump, signed build, published-key comparison, kit archive, commit/tag/push, draft/assets/publication and URL verification. | **Forbidden in this session. Do not run the script.** It has no local-only mode and does not run the unit gate. Source metadata agreement alone is not artifact/signature/release verification. |
| Device delivery | Current [1.70 receipt](portal-pwa-tab-branding-delivery-2026-11-02.md), installed APK `6157a890…`; earlier timer/clock receipts retained. | Family installed-browser acceptance and exact APK readback passed. Living exact artifact/version/room/config readback passed after one dispatch; no duplicate physical interaction run. |

## Prioritized backlog

The original eight items, native clock finding and two browser findings are tracked below.
Estimates are planning estimates: **S** fits one bounded
local unit; **M** requires decomposition before implementation. Preserve each
existing issue's 30-minute cap, including cleanup; this document grants no
additional time or approval.

| Rank / owner | Evidence and value | Acceptance criteria | Platforms; effort/risk; verification |
| --- | --- | --- | --- |
| **1 — Main/QA: current two-fix delivery — delivered** | Routing/countdown fixes committed as `d9fce1fb`; required automated checks, Family acceptance and both exact APK readbacks passed. | Minute-caption and source-entry acceptance passed. Native 24-hour/time-edit cases and equivalent-scenario performance comparison remain explicitly unverified, as recorded in the receipt. | Portal; completed bounded correctness units. No whole-product, background-instrumentation or universal performance claim. |
| **2 — CI: shipped JS/Python regression gates — implemented** | All 29 bundled-JS modules and seven Portal Python modules are now included in `tests.yml`. Protects room targeting, stale responses, operations, photos, install observation and validation contracts. | Local suites passed **652 / 140**; syntax and ordinary failure propagation checked. Existing slideshow/JVM gates retained. No live endpoints or credentials needed. | Browser/PWA + Portal CI; local checks passed. Hosted Ubuntu/Node 22/Python 3.12 execution remains unobserved; [assessment](portal-python-ci-gate-2026-11-02.md) records prerequisites and timing limits. |
| **3 — Settings QA: editor classification/projection guards — locally verified, included in 1.67** | Obsolete inline-string prohibition removed. A pure editor policy now selects the existing renderer branches. [Receipt](portal-settings-editor-contract-2026-11-02.md) records nine focused passing tests and a four-failure hidden-text mutation. | Actual registry coverage includes inline and Activity-backed strings, fleet name and dashboard URL; validation, secret handling and persistence tests retained. Source review found no concrete production regression. | Portal/shared registry; **S**, low risk. 3,796 JVM tests / 398 suites and both APK builds passed. Classification tests do not prove composed controls or native interactions; included in 1.67 without fresh settings UI acceptance. |
| **4 — Settings architecture: disabled-parent visibility — delivered on Portal/PWA** | Live 1.67 ordinary schema exposed 16 controls and mobile PWA 11 rows while off. Twenty-four master predicates are corrected; separate calendar/bespoke owners remain. | 532-case registered matrix, full 3,800 JVM / 654 JS tests, Family native/API/mobile hide and re-enable checks passed. All 36 saved fields and Home page 1 of 2 restored; inactive validation/profile retain 33 controls. | Exact 1.68 in both rooms. [Receipt](portal-settings-visibility-delivery-2026-11-02.md). Companion group-preflight correction has separate local/deployment status; no physical phone or performance claim. |
| **5 — UX/native: truthful Quick controls setup routing** | Heading opens Display; edit opens Quick buttons, although tiles derive from app discovery/dashboard URL. [Ownership map](design/portal-widget-settings-map-2026-11-02.md) proves neither edits those tiles. | After destination approval, heading/edit reach the same real owner; explain dashboard precedence and app availability; Back works; duplicate widgets share owners. No new discovery prefs or duplicated credentials. | Portal; **S after approval**, low/medium navigation risk. Route contracts and native configured/unconfigured/Advanced checks. Broader mock remains unapproved. |
| **6 — Home timer editor ownership — delivered** | Native 1.66 red reproduced: editor 5m created one kitchen timer while Home stayed Ready. Home editor now uses the shared countdown; Tools keeps its collection. | Family start/pause/Back/widget observation/reopen/resume/reset, custom 0:10, exact-alarm ring/Stop and both entry points passed. Original layout/settings/timer state restored. | Portal Home; full 3,796 JVM tests/398 suites and both APKs passed. Exact 1.67 in both rooms. [Receipt](portal-home-timer-delivery-2026-11-02.md) records native limits; no migration, new schema or performance claim. |
| **7 — Companion lead: compatibility regression guards — locally verified** | Isolated branch `codex/companion-compat-20261102`, commit `610899f…`, adds fifteen tests and a behavior-preserving media fallback predicate extraction to recovered 1.7.0/code 12 source. | 404/405/501 fallback, auth/network/schema distinction, delayed former-room/session rejection and write-admission/cancellation guards passed. No new fallback behavior, HA service setup or phone rewrite. | Native phone + Portal contracts; **S**. Fresh 555 tests/73 suites and debug/test APK gates passed. No rendered Compose, physical phone, live playback, installation or deployment proof. Primary/frozen/stale/dirty checkouts preserved. |
| **8 — Release engineering: local readiness — implemented and verified** | A separate standard-library checker now runs local gates and verifies the resulting package/version/signers against a mandatory baseline. It does not invoke the publication script. | Contract guards reject stale APKs, unsigned/different signers, missing/failed/reused reports, unsafe output locations, source changes and timed-out process groups. Prior reports are archived; selected SDK reaches Gradle. | Portal/release tooling; real run exited 0 in 152,626ms: 40/654 JS, 161 Python, 3,800 JVM/399suites, optimized APK/lint and actual SDK signatures passed. Exact existing1.68 APK; no reinstall. [Receipt](portal-local-readiness-delivery-2026-11-02.md). HostedCI/publication/hardware acceptance remain separate. |
| **9 — Native UX: World Clock clipping — delivered** | Complete-row fit and truthful remaining-city counts replace clipped previews. Native enlarged-text finding was corrected within the same slot. | Normal two/three/four-city and 150% live/cold checks passed; original city order, font scale, Home page and screensaver configuration restored. | Portal; 3,788 JVM tests and both builds passed. Original exact 1.66 delivery passed; current 1.70 preserves the fix. [Receipt](portal-world-clock-delivery-2026-11-02.md) retains taller-card/locale/24-hour limits; no general performance claim. |
| **10 — Browser UX: connection status after tab navigation — delivered** | Actual delayed-startup cancellation showed false Offline. The duplicate unowned status writer is removed; missing metadata is replaced promptly. | 22 ownership guards (10 red before), 36 focused / 676 full JS, actual 6/6 Family trials, zero extra status GETs over six connected tab changes, controlled transport/401 handling and existing pairing passed. | Exact 1.69 APK in both rooms, complete config/profile/layout unchanged. [Receipt](portal-pwa-connection-status-delivery-2026-11-02.md). Browser 119–175ms samples are not optical/universal-network performance. |
| **11 — Browser UX: tab branding — delivered** | Installed 1.69 omitted the favicon link, causing a protected default request. One explicit existing public PNG link is now delivered in exact 1.70 both rooms. | Fresh unpaired and existing paired Family browser sessions naturally fetched the 64 × 64 PNG without Authorization/default favicon requests. Authentication, manifest/touch icons, pairing and complete room configuration were preserved. | PWA; bounded slot. Local 3,800 JVM / 716 JS / 161 Python plus optimized gates passed in 227,704 ms. [Receipt](portal-pwa-tab-branding-delivery-2026-11-02.md); no general performance or universal-browser claim. |
| **12 — Companion QA: current rendered settings acceptance — queued** | Current preflight fix is locally verified, but the exact candidate has no rendered/native acceptance. A disjoint read-only plan identifies four settings/menu/group checks and preserves existing Hearth emulators. | Start with an isolated emulator; distinguish actual UI from JVM fixtures. Live grouped disabled-parent writes need valid current sessions/baselines. Restore per-room state; physical Pixel acceptance remains separate. | Companion + Portal API; one fresh 30-minute unit including setup/cleanup. [Plan](portal-companion-emulator-qa-plan-2026-11-02.md). No companion install or live native acceptance yet. |

**Completed next unit:** CI protection was implemented in a separate write scope.
All 29 existing `app/src/test/js/*.test.mjs` modules passed locally:
**652 tests, zero failures/skips** on Node 24. Python guards passed **140 tests**;
the Ubuntu runner must prepare the profile tool's intentional `/private/tmp`
workspace. Hosted Node 22/Python 3.12 execution remains unobserved.
No app/UI change or live service was needed for the CI unit. World Clock (item 9)
has since passed its bounded delivery gates. Registry editor contract coverage
(item 3) and companion fallback tests (item 7) are committed and locally verified.
Timer widget/editor ownership (item 6) passed native acceptance and both-room exact delivery.
Disabled-parent presentation (item 4) now passed scoped Portal/PWA acceptance and
both-room exact 1.68 delivery. Companion preflight is corrected, committed and
locally verified (564 tests / 74 suites); physical installation/acceptance remains
open. Local-only release readiness (item 8) is now implemented and verified. Browser
connection-status ownership (item 10) is delivered on both rooms. Public tab branding (item 11) is now delivered in exact 1.70 on both rooms. Current companion rendered UI acceptance (item12) passed33 tests in an isolated emulator; live paired native writes are next and physical Pixel proof remains separate. Previous exhausted units remain parked.
Do not reopen exhausted old issues through these units.

## Latest companion rendered checkpoint — 2 November 2026

This checkpoint supersedes earlier “uninstalled/no rendered acceptance” statements
for the emulator only. Companion **1.7.1-music-preview / code13**, local commit
`3ae098044a0eaf4f42dadf2fff8184f3aa5a5646`, passed **564 JVM /74 suites** and
**33/33 actual Android UI tests in40.425s**. Exact installed APK readback matched
in owned API34 emulator-5558. The disconnected category placeholder is corrected.
Strict two-room fixtures exercise the real UI/VM/repository pipeline; live room
writes and physical Pixel acceptance remain open. Owned read-only overlay cleanup
completed at09:50:04 UTC with source AVD and original connections preserved.
Both Portals remain exact1.70/code64. The saved roadmap now has17 tasks; the next
bounded unit is live paired native companion acceptance. No new unit has started.
See [delivery and limitations](portal-companion-rendered-settings-delivery-2026-11-02.md).

## Latest native live settings checkpoint — 2 November 2026

The09:53:31–10:23:31UTC unit adds **five passed scoped check families** on real
Family/Living APIs: inactive metadata, one native grouped Shuffle action,
basic/Advanced parent visibility, invalid API601 rejection and independent
view/destination behavior. Both whole registry profiles and screensaver configs
were exactly restored. Owned emulator/relay cleanup completed at10:16:56UTC.
Source AVD files were unchanged; original Family transports remain present.

**Cold native reconnect did not pass; the native numeric601 editor check was
not run.** Direct emulator networking and a later ADB relay produced failures,
including an actual pre-header EOF. Host sessions stayed valid. A product root
cause was not established, so no speculative production patch was retained.
Existing sessions were reused; fresh PIN pairing, physical Pixel and performance
remain open. See [scoped acceptance](portal-companion-live-settings-acceptance-2026-11-02.md).
The18-task roadmap preserves these limits and queues independent local
fleet/provisioning checks next. No new unit has started.

## Latest fleet transport checkpoint — 2 November 2026

The10:22:40–10:52:40UTC unit implemented a local workstation CLI correction:
incomplete HTTP responses now fail before file replacement, while the existing
zero-length procfs convention requires clean EOF. Whole-input JSON validation
preserves numeric-leading diagnostic text. The old binary produced five failed
TCP and five failed compiled-CLI cases; review/device findings added reproduced
procfs and JSON regressions before correction. Final **23 Rust /17 compiled-CLI
/33 room-helper tests** passed;38 Calls-access checks, three Bash parses, three
ASCII file checks and actionlint passed. The optimized CLI is675,912bytes.

Actual CLI identity reads passed against both real rooms at1.70/code64; complete
registry profiles and screensaver configurations were unchanged. Family procfs
pull/raw each returned305,019bytes. No APK or provisioning/configuration change
was made. HostedCI, Linux/Python3.12, PowerShell7/Windows5.1 and Windows runtime
remain unverified. See [delivery and limits](portal-fleet-response-integrity-delivery-2026-11-02.md).

The19-task saved roadmap queues **interrupted upload integrity** next. Source
opens a destination before the request body completes; interruption remains a
hypothesis until safely reproduced against a named public scratch file. This
requires its own30-minute unit and cleanup; no new unit has started and the old
native cold reconnect/screensaver/performance slots are not reopened.

## Latest fleet upload checkpoint — 2 November 2026

The10:37:50–11:07:50UTC unit reproduced actual interrupted-upload corruption on
Family1.70 using public disposable bytes. The corrected request/writer boundary
preserves the original until a completed upload is closed and renamed, reports
HTTP400 for incomplete bodies, and removes staging files on failure. The14/21
red baseline became21 passing focused cases within the fresh **3,821 JVM /401
suites** run. **716 JS /161 Python**, optimized APK/R8/vital lint, version,
signature and independent review gates passed.

**Current exact1.71/code65 is installed on both rooms.** Family's13 controlled
API checks passed; actual installed APK bytes/hash/signature match. Living's
helper returned an invalid install result, but subsequent own-process installed
APK readback independently matched the exact accepted artifact. No second
install was attempted. Both complete settings profiles and screensaver configs
are unchanged. Owned Family scratch cleanup is verified, and its slideshow
activity is resumed. Three8MB upload samples have essentially unchanged medians:
157.54ms before /157.01ms after. This does not establish general app performance.
See [delivery and limits](portal-fleet-upload-integrity-delivery-2026-11-02.md).

The20-task saved roadmap records the delivered upload correction and queues a
separate request-body integrity investigation: `bodyText` currently stops at EOF
and can return a complete JSON prefix from an incomplete declared body. This is
source evidence; no settings mutation or runtime failure is claimed. Use pure
request-boundary tests before considering a fix. The deployment helper's false
negative is also recorded for a future independent tooling slot. Exhausted
native cold reconnect/screensaver/performance units remain parked.

## Latest JSON request checkpoint — 2 November 2026

The 10:56:13–11:26:13 UTC unit reproduced accepted incomplete/clipped JSON and
overflowing-length normalization with nonmutating Family 1.71 requests. The
reader now requires the complete declared body, rejects size clipping and
accounts consumed bytes immediately. Explicit invalid/overflowing lengths are
rejected; shared request-error mapping preserves direct companion and guarded
diagnostic response contracts.

Initial focused checks had 12 failures in 34 tests. The first full gate caught
one direct companion contract regression and was not promoted. Review findings
and that regression were corrected after an 11-test/3-failure baseline. Final
**3,840 JVM / 403 suites, 716 JS / 161 Python**, optimized APK/R8/vital lint,
version/signer/source and independent review checks passed. All 43 focused
reader/server/stream/companion boundary cases pass.

**Current exact 1.72/code66 is installed in both rooms.** Family's 22 JSON/header
checks passed; complete 16,003-byte binary upload/readback and interrupted
overwrite preservation passed. Both complete profiles/screensaver configs are
unchanged. Owned scratch cleanup and Family slideshow foreground restoration
are verified. Three no-op LAN round-trip medians were 9.50 → 13.24 ms: an observed
3.74 ms increase, with no general performance claim. See
[delivery and limits](portal-json-body-integrity-delivery-2026-11-02.md).

The 21-task saved roadmap queues the observed **deploy-helper false negative**.
The actual 1.72 self-update trace contains `incomplete HTTP headers` and a
transport-error marker; the helper's qualified-loss branch accepts older
`read failed:` wording but excludes this newer message. Independent exact
installed-APK readback proved delivery. A fresh tooling unit should replay this
actual transcript and retain strict changed-path/hash verification, without
manufacturing another Portal release or reinstalling an already verified APK.
The earlier native cold reconnect, physical phone and exhausted native issues
retain their existing scope and status.

## Preserve parked work and scoped acceptance

- [Black handoff](portal-black-handoff-second-slot-2026-11-02.md) remains exhausted:
  the zero-black-frame candidate delayed complete Home to 1,169 ms versus 418 ms;
  no candidate retained. Reopened legacy night motion also remains parked:
  three rejected candidates, final 2 native failures and only 5/11 strict cases.
  **Do not restart either through this backlog.**
- Cold Shuffle startup, rejected Home/Apps/settings performance experiments,
  the timed-out typography candidate and incomplete playing-gesture cases retain
  their existing parked status and receipts. Accepted later photo/controller/SMB
  fixes must not be re-created from older findings.
- The latest completion audit supersedes earlier “physical fades unavailable”
  wording: the user accepted both natural fade directions on `872b6af7…`.
  This does not close optical panel FPS, legacy motion or whole-product performance.
- Live music providers/auth/audio/groups, receiver Calls/Jarvis handoff and final
  Living acceptance retain the [music ledger's](music-player-goal.md) qualifications.
  Server deployment and native companion rewrites remain deferred; no new service
  setup is implied. Browser fixtures, JVM counts and historical native samples
  never establish current end-to-end playback or universal performance.

Unavailable/uninspected: live integration behavior, cloud APIs, hosted CI/releases,
fresh companion device behavior, and external Jarvis source. Fresh companion
local build/test identity is now recorded separately. Current Portal
artifact identity and bounded Family checks are now inspected, as recorded above.
This inventory is a resumption aid, not whole-product acceptance.
