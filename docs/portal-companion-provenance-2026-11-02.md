# Native companion provenance — 2026-11-02

**The companion source is available in local Git. Its historical working directories
are missing.** Resume from Toolkit branch `codex/toolkit-music-20261019`, commit
**`422286e1d8e2afd51c9809315418681e2f868efd`**, in a future fresh isolated checkout.
Do not start from the primary Toolkit checkout's older app.

This bounded investigation follows [product backlog 7](portal-product-operating-state.md).
The reporting worktree started at Immortal
`137608262e39d72498a9de57b6d7bdc3ff7fffe2`,
`codex/portal-hi-widget-fixes-20261102`. Main owns World Clock, device operations
and builds. Only this document was written; no checkout/ref/worktree registration
was changed, and no tests, builds, network, authentication, devices, commits,
hooks or agents were used. All Git commands used
`git -c core.hooksPath=/dev/null --no-optional-locks`.

## Concrete repository and checkout state

| Repository / reference | Local identity and availability | Resume constraint |
| --- | --- | --- |
| `/Volumes/xHome/Github/portal-toolkit` | Existing checkout on `roadmap-v1.2-1.4`, `4e1dadb8ec2ec018b6fdc250486e0c476467a7c4`; **clean** when inspected. Gradle: 1.4.0/code 5. | Preserve primary checkout. Companion development is 17 commits beyond this ancestor; clean status does not make this the companion source. |
| Toolkit `codex/companion-unified-portal-selection` | Frozen companion checkpoint **`8d18051cd822b32e3b562315789fa4041f1db3f0`**, 1.6.1-control-center/code 11. Committed tree readable. | Registered at `/private/tmp/portal-toolkit-control-center`, but directory absent and registration marked prunable. Preserve the branch and stale registration; do not force-checkout or prune it. |
| Toolkit `codex/toolkit-music-20261019` | Latest preserved companion **`422286e1d8e2afd51c9809315418681e2f868efd`**, 1.7.0-music-preview/code 12. Four commits after `8d18051`; source, tests and validation docs readable through Git. | Registered at `/private/tmp/portal-toolkit-music-20261019`, likewise absent/prunable. No current working-tree dirty state can be established there; committed recovery does not prove preservation of former uncommitted files. |
| Other Toolkit checkpoints | `codex/portal-companion-control-center` → `8db0ab691c4e5eb2aa4ff2bfbcb3529a01780297`; `codex/companion-photo-library-discovery` → `55eaf88a4c703466f038598d707c59f5b0833da9`; `codex/companion-multi-portal` → `bc5f292aceaba878dac870c3e7d7c3ea921727bd`. | All are ancestors of the music tip, useful provenance rather than independent changes to merge again. |
| Immortal Portal-side API history | `codex/portal-companion-api` → **`c8bbd4242e995109afa42bc6fba153bdcc0ad7a3`**. API introduction: `023b225c1a9dc1393dd5e51dc9b0254dcce71ad7`. Both already ancestors of reporting HEAD; the old API branch is 477 commits behind it. | Use current main-task Portal source for compatibility work. Do not roll back or re-merge the old API branch. |
| `~/.codex/worktrees/portal-companion-api/portal-prime` | Despite its directory name, this is a **Portal app** checkout on `codex/live-device-identity-215`, `a5f186890450e835d15bf25fa43f516dbc8fad96`, 222 commits behind reporting HEAD. **Dirty:** eight tracked Kotlin/test files and two untracked identity source/test files. | Existing/frozen work, not a phone checkout. No active owner was established; treat it as owned and leave it untouched. No dirty diff or private data was copied. |

Concise directory searches under `~/.codex/worktrees`, `/private/tmp`
and `/Volumes/xHome/Github` found no additional Toolkit/companion checkout.
`/private/tmp/companion-focused-sources` exists, but all seven `.kt` entries are
**broken symlinks** into the absent control-center directory. They are not usable
source backups. This was a bounded path search, not a global disk recovery scan.

## What the preserved commits actually implement

| Commit / source boundary | Source-established capability |
| --- | --- |
| `f983738df763254679bb76e648246c6a5218b4f1` | Introduces native Immortal control center and transport reliability. `data/companion/CompanionClient`, `CompanionRepository`, session/discovery/models and native Home/Remote/Photos/Apps/Settings are present in the descendant tree. |
| `bc5f292…` → `8d18051…` | Captured multi-Portal settings/photo targets and unified viewing versus destination selection. `CompanionGroup` retains target/session identity and per-Portal outcomes; native menu keeps viewing independent from write destinations. |
| `d94ec706f2759b249eafb469bbe30a8c69633e17` | Adds strict typed music protocol, repository reads/commands, `CompanionMusicController`, Queue/Speakers/Lyrics and release-only seek/volume controls. |
| `1b83013825605822991a90dbf44065e7686e71c9` | Keeps already-open music panels readable during pending server confirmation while denying unsupported writes; records scoped real room-control verification. This earlier review finding is already fixed. |
| `4dedb2f32f761cd3f9c65922739ed2147ebd74d2` | Preserves supported Pause/Volume/player-selection/group-removal and passive views during interruption; blocks starting/repositioning/joining. |
| `422286e…` | Corrects synchronous player-selection admission: publish the matching newly selected source without requiring a pending playback command or claiming playback confirmation. Two controller regressions accompany the change. |

At the music tip, all phone paths below are under
`app/src/main/java/com/portalinstaller/`:

- `data/companion/CompanionRepository.kt` implements paired settings/sources,
  photos, catalog/install-operation history, layout/snapshots, scenes/presets
  and Jarvis configuration. `CompanionClient.kt` builds `/remote/...` requests
  with captured session authorization. This is scoped management served by
  Portal's Fleet Agent, not unrestricted Fleet CLI access.
- `data/companion/CompanionMusic.kt` requires music **schemaVersion 1**;
  `viewmodel/CompanionMusicController.kt` binds source revision, device, track,
  queue and membership, coalesces confirmation reads at 250 ms for up to 12 seconds,
  and does not replay writes. Music targets the viewed Portal independently of
  multi-room settings destinations.
- `ui/controlcenter/ControlCenterScreen.kt` stops music observation when hidden.
  A music endpoint returning 404/405/501 marks it unsupported and selects the
  existing generic MediaSession `PlaybackPanel`; auth/network/parser failures
  do not establish older-Portal capability.
- ADB/USB provisioning, backups, mirror, uninstall and OTA tools remain a
  separate Device tools connection. Jarvis model/wake configuration is mediated
  by Portal; credentials stay in Jarvis.

Current reporting Portal source retains `RemoteCompanionRoutes` protocol version 1,
capability advertisement and revision checks, plus `RemoteMusicRoutes`
`GET /remote/music` and `POST /remote/music/command`. Source inspection establishes
these endpoints, not end-to-end phone compatibility with the current APK.

## Available evidence versus historical artifacts

Committed Toolkit `docs/music-preview-validation.md` is readable at `422286e`.
It supersedes the earlier 533-test summary: the latest record reports
**540 JVM tests / 72 suites**, debug/test assemblies and an installed/read-back
Pixel preview SHA-256
`c2933b966edad3f1793641bf105e1a33292543132774beeaacc79318d8fcbbd3`.
Those are **historical reported results**, not freshly executed or independently
recovered artifact verification here.

Earlier 23/23 Android UI and two paired API tests belong to their stated checkpoint.
The later physical interruption Compose harness failed before hierarchy admission
and remains **parked**; latest JVM/build/install evidence does not pass it.
Do not reopen that fixture as provenance work.

The checked historical receipt/rollback locations
`/private/tmp/toolkit-music-validation`,
`/private/tmp/portal-ma-receiver-binding-source` and
`/private/tmp/portal-music-recovery/integrated-rollout` are absent, as are the two
named final UI/live-phone logs. No private logs, photos, pairing stores,
credentials or APK data were opened/copied. No matching APK was recovered from
these locations; current phone version, signer and pairings remain unqueried.

[Music operating history](music-player-goal.md) also records that the user cancelled
the HA-hosted route and removed its services on October 19. Preserved client code
does not authorize restarting that server or unpassed audio/performance work.
Generic companion management can resume independently.

## Safe resume and verification entry points

1. **Future action, not performed here:** create a new isolated Toolkit checkout
   from the full `422286e…` commit on a new `codex/` branch and a new path. The old
   branch is associated with a stale worktree, so reuse neither its path nor its
   registration. No checkout/reset/clean/prune of existing work is needed.
2. Confirm the new branch/base and read any applicable instructions before edits.
   Keep Toolkit primary, frozen `8d18051`, music branch and dirty Portal API worktree
   unchanged. This task establishes no permission to merge into another owner's work.
3. Build declaration: `com.portalinstaller`, minSdk 26/targetSdk 35, Java/Kotlin 17;
   Gradle wrapper 8.14.5, AGP 8.13.2, Kotlin 2.0.21.
   `./gradlew --offline :app:testDebugUnitTest --no-daemon` is the JVM entry point;
   `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest` builds
   the preview/test APKs. Offline success requires cached SDK/dependencies, not
   established here.
4. Focused JVM suites:
   `com.portalinstaller.data.companion.CompanionMusicTest`,
   `CompanionMusicRepositoryTest` in the same package, and
   `com.portalinstaller.viewmodel.CompanionMusicControllerTest`.
   Native fixtures include `ui.controlcenter.MusicPanelUiTest`;
   runner is `com.portalinstaller.AwakeTestRunner`.
5. `CompanionMusicDeviceTest` gates real reads with `liveMusic=1` and a reversible
   real volume write with separate `liveMusicWrite=1`. **Do not enable either
   under this task's restrictions.** Future phone installs must verify the same
   package/signing identity and preserve pairings/data; never solve signature
   failure by uninstalling. No separate debug application ID suffix or release
   signing configuration is declared in the inspected phone build.

**Next bounded code task, after an isolated checkout with separately scoped tests:**
add focused synthetic controller regressions for older-Portal fallback:
404/405/501 enable fallback; 401, network failure and malformed schema do not;
a late unsupported response from the former device cannot switch the new device's
state or dispatch any write. The inspected controller JVM suite lacks this status
matrix; its production branch and native fallback are concrete source evidence.
This is a coverage gap, not a demonstrated runtime defect. Keep the unit within
30 minutes and preserve all existing admission/cancellation behavior.

Separately, README still calls 1.5.2 the current development build and
`docs/companion-control-center.md` says 1.6.0/code 10 at the music tip, while Gradle
declares 1.7.0/code 12. Correct those current-version statements in the future
isolated branch without rewriting dated verification receipts. No new application
behavior or public release is justified by this provenance pass.
