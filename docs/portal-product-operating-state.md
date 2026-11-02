# Portal product operating state

Snapshot: **2026-11-02 UTC**. Local source inspection on
`codex/portal-hi-widget-fixes-20261102`, HEAD `6c018dc80524db2dad58e4542174326b9c673cd5`
plus the coordinator's uncommitted changes. Resume from this inventory and the
linked receipts; update implementation, checks, deployment and acceptance separately.
The updated whole-product objective is
`~/.codex/attachments/4832b00e-0492-44a6-a521-a970bf2ea968/goal-objective.md`.

## Current delivery and boundaries

- **Main owns two implemented fixes:** Photo Memory edit opens `sources` with idle
  slideshow disabled; the alarm caption observes active-page configuration and
  refreshes at minute boundaries, rounding positive partial minutes up.
  [Independent source review](portal-hi-first-units-review-2026-11-02.md) found no
  concrete defects. **Coordinator update:** 3,779 JVM tests and both APK builds passed;
  Family routing/countdown/return checks passed, and both rooms have the exact
  1.65 candidate installed. Read the
  [delivery receipt](portal-widget-fixes-release-2026-11-02.md) for native limits,
  artifact hashes, restored configuration and deployment evidence.
- Gradle and `version.json` both currently declare **1.65 / versionCode 59**.
  These identify the verified local device iteration, not a published release.
- The [widget gallery](portal-widget-gallery-checkpoint-2026-11-02.md) remains an
  **unapproved browser review artifact**. Its Android/iOS columns are design
  interpretations. The broader widget redesign is not authorized by these two fixes.
- This sidecar inspected local files only and wrote only this document. No tests,
  builds, device/service access, secrets, authentication, hooks, push, publication
  or commits. All Git use requires `git -c core.hooksPath=/dev/null --no-optional-locks`.
  These boundaries override release scripts and broader autonomous-goal language.

## Product and platform inventory

| Component | Purpose, dependencies and inspected evidence | Verification/release procedure and current limits |
| --- | --- | --- |
| **Portal Android** | Single Kotlin/Compose `:app`, `com.immortal.launcher`; minSdk 24, targetSdk 36; Portal API 28/29, no Google services/root. Home/apps/widgets, photo and clock Dreams, tools, store/updater, MQTT/HA, music, accessibility, boot/sleep. Inspected [README](../README.md), build configuration, Home and settings source, current review and existing audits. | `./gradlew :app:testDebugUnitTest --no-daemon`, `:app:assembleDebug`; optimized variant and native acceptance are separate. Debug has `.debug` suffix. Preserve signing identity and configuration for in-place updates. Current fixes await coordinator results and device acceptance. No physical hardware inspected here. |
| **Native Android phone companion** | Historical paired companion uses Portal `RemoteCompanionRoutes`: protocol version 1, capability advertisement, revision-guarded layout/snapshots/scenes, photos, operations and Jarvis. Inspected Portal-side contracts and [launch receipt](companion-app-launch-validation-2026-10-08.md). [Music ledger](music-player-goal.md) records separate `codex/toolkit-music-20261019` work, including 533 JVM/23 Android UI checks and two paired checks after its pending-panel correction. | Phone source at the documented `/private/tmp/portal-toolkit-music-20261019` is **absent**. Results are historical, not current verification. Build/test/install must occur in the matching companion checkout, independently of Portal. Preserve frozen work; no companion merge, release or current installed version established here. No shipped native iOS app identified. |
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
| Portal local checks | Current two-fix review and [delivery receipt](portal-widget-fixes-release-2026-11-02.md). | 3,779 JVM tests; debug/optimized builds and performance vital lint passed. Family native checks passed within recorded scope. |
| CI | `tests.yml`: JVM, existing slideshow JS, all 29 bundled-JS regression modules, and seven Portal Python regression files. Catalog: schema/network validation. Provisioning: Bash/PowerShell behavior, parse and ASCII. Docs: strict MkDocs plus main-push Pages deploy. Release guard: version/URL/notes consistency. | Newly added JS/Python gates passed locally (652/140); YAML parsed, shell prerequisite syntax checked. Hosted Ubuntu/Node 22/Python 3.12 execution remains unobserved. Native device checks and Rust compilation are not configured there. |
| Public release | [Release procedure](releasing.md) and `cut-release.sh`: authenticate/fetch, version bump, signed build, published-key comparison, kit archive, commit/tag/push, draft/assets/publication and URL verification. | **Forbidden in this session. Do not run the script.** It has no local-only mode and does not run the unit gate. Source metadata agreement alone is not artifact/signature/release verification. |
| Device delivery | Current [two-fix receipt](portal-widget-fixes-release-2026-11-02.md), installed APK `640054a1…`. | Family functional verification and exact artifact readback passed. Living exact artifact/version/room readback passed; no repeated physical interaction run. |

## Prioritized backlog

The original eight items and one new native visual finding are tracked below.
Estimates are planning estimates: **S** fits one bounded
local unit; **M** requires decomposition before implementation. Preserve each
existing issue's 30-minute cap, including cleanup; this document grants no
additional time or approval.

| Rank / owner | Evidence and value | Acceptance criteria | Platforms; effort/risk; verification |
| --- | --- | --- | --- |
| **1 — Main/QA: current two-fix delivery — delivered** | Routing/countdown fixes committed as `d9fce1fb`; required automated checks, Family acceptance and both exact APK readbacks passed. | Minute-caption and source-entry acceptance passed. Native 24-hour/time-edit cases and equivalent-scenario performance comparison remain explicitly unverified, as recorded in the receipt. | Portal; completed bounded correctness units. No whole-product, background-instrumentation or universal performance claim. |
| **2 — CI: shipped JS/Python regression gates — implemented** | All 29 bundled-JS modules and seven Portal Python modules are now included in `tests.yml`. Protects room targeting, stale responses, operations, photos, install observation and validation contracts. | Local suites passed **652 / 140**; syntax and ordinary failure propagation checked. Existing slideshow/JVM gates retained. No live endpoints or credentials needed. | Browser/PWA + Portal CI; local checks passed. Hosted Ubuntu/Node 22/Python 3.12 execution remains unobserved; [assessment](portal-python-ci-gate-2026-11-02.md) records prerequisites and timing limits. |
| **3 — Settings QA: replace the obsolete inline-string coverage premise** | `SettingsDomainTest.onDeviceRenderedDomains_haveNoUnrenderableInlineStrings` assumes blank rows, while `SettingsRenderer` calls `ApprovedStringSetting`. [Registry audit](design/portal-settings-registry-audit-2026-11-02.md) identifies missing consumer coverage. | A focused supported-editor contract covers actual generic consumers and named exemptions, including inline and Activity-backed strings; retains validation, secret handling and persistence coverage without changing UI. | Portal/shared registry; **S**, low risk. Focused JVM contract plus required unit gate; meaningful unsupported-editor negative case, not a duplicated source assertion. |
| **4 — Settings architecture: agree disabled-parent exposure** | Native `prepareSettingsVisibility` hides all disabled screensaver children; `SettingsDomain.schemaJson` uses spec predicates, leaving some ordinary remote children exposed. Source discrepancy, not reproduced runtime failure. | Document intended shared-setting exceptions, then align presentation eligibility and test enabled/disabled × Advanced × source/style. Saved inactive values and validated API writes remain available as intended. | Portal + PWA/schema consumers; **M**, medium compatibility risk, policy decision first. Real registered-domain projection/schema matrix, offline browser filtering and later native/browser checks. No blanket apply restriction. |
| **5 — UX/native: truthful Quick controls setup routing** | Heading opens Display; edit opens Quick buttons, although tiles derive from app discovery/dashboard URL. [Ownership map](design/portal-widget-settings-map-2026-11-02.md) proves neither edits those tiles. | After destination approval, heading/edit reach the same real owner; explain dashboard precedence and app availability; Back works; duplicate widgets share owners. No new discovery prefs or duplicated credentials. | Portal; **S after approval**, low/medium navigation risk. Route contracts and native configured/unconfigured/Advanced checks. Broader mock remains unapproved. |
| **6 — Product/architecture: resolve timer ownership** | `ImmortalTimersWidget` uses `TimerStore`; `ApprovedHomeTimersPage` hosts `TimersOverlay` writing `TimerConfig`. Same destination masks different data owners. | Choose and record widget/editor semantics; demonstrate the selected editor changes the timer shown by the widget, preserving pause/resume/ringing and existing timers. Migration requires a separate decision. | Portal Home + Tools; **M**, medium data/lifecycle risk. Owner-level state tests and native start/pause/resume/cancel/Back checks. First reproduce; do not label source mismatch a verified device failure. |
| **7 — Companion lead: reconcile source and artifact provenance** | Accessible Toolkit is 1.4.0 in Gradle/1.1.0 in README; documented paired/music checkout absent. Historical API/UI passes cannot establish current cross-platform parity. | Locate preserved companion source; record branch/base, package/version, API capabilities, artifact identity and tests separately from ADB Toolkit. Map old-Portal fallback and phone/PWA gaps before any merge. | Native phone + Portal/PWA contracts; **S discovery**, low risk; subsequent implementation separately scoped. Local source/report comparison first; later companion JVM/UI and authorized paired checks. Preserve frozen branches and already-fixed audit findings. |
| **8 — Release engineering: local readiness before publication** | Current release script couples useful verification to forbidden auth/network/Git publication and omits unit tests. Existing workflows do not prove current artifact readiness. | Specify a local-only readiness gate producing candidate version, source/artifact identity, signing comparison against a known local baseline and check receipts; publication is a separate explicit operation. Missing evidence fails honestly; no credentials printed or hooks run. | Portal + provisioning/release tooling; **M**, low/medium script risk. Review paths with stub commands and isolated fixtures; run builds/tests only in an authorized unit. Never invoke the current publication script under these constraints. |
| **9 — Native UX: World clock's second city is clipped** | Family's current medium widget shows the second time but clips its city/day label. Private 2160 × 1440 screenshot confirms this; `ImmortalWorldClockWidget` uses 100sp line height for 48sp time text and renders up to four stacked zones. | In a fresh bounded slot, correct excessive vertical spacing using the existing approved card; verify configured cities or an explicit overflow indication fit supported widget sizes. Preserve timezone formatting and minute refresh; no unapproved gallery redesign. | Portal; **S initially**, low layout risk. Inspect actual available bounds, build, and native two/four-city visual checks; park if size handling exceeds the cap. |

**Completed next unit:** CI protection was implemented in a separate write scope.
All 29 existing `app/src/test/js/*.test.mjs` modules passed locally:
**652 tests, zero failures/skips** on Node 24. Python guards passed **140 tests**;
the Ubuntu runner must prepare the profile tool's intentional `/private/tmp`
workspace. Hosted Node 22/Python 3.12 execution remains unobserved.
No app/UI change or live service was needed. After CI integration, prioritize the
newly observed World clock clipping (item 9) before lower-impact contract cleanup.

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
preserved companion branch source, and external Jarvis source. Current Portal
artifact identity and bounded Family checks are now inspected, as recorded above.
This inventory is a resumption aid, not whole-product acceptance.
