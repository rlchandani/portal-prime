# Immortal / Portal Prime — complete agent handover

Prepared **6 November 2026, America/Los_Angeles**. This is a continuation document
for the next agent, requested by the human after the two sequential November 5
slices finished. It records the state of the ongoing improvement effort, not
whole-product completion.

**Read this first.** Repository state was checked while preparing this handover.
Device, build, test and visual results below are the **last recorded results on
November 5 or the explicitly stated earlier date**. Preparing this document did
not repeat hardware checks, build a new app, change settings or publish anything.
All issue-clock times are UTC unless a Pacific conversion is explicitly given.

The human's initial takeover reference was
`codex://threads/01a10766-0fbc-73c1-89e4-5d83b5efe23a`.
Use the repository handover and receipts for continuation; that chat reference
is historical context, not a request to message another chat.

## 1. Immediate continuation instructions

Use this checkout:

```text
~/.codex/worktrees/portal-music-player/portal-prime
branch: codex/product-audit-20261103-144114
implementation HEAD at handover preparation: c0389c2d
```

The handover itself is committed after that implementation HEAD. Resolve the
current Git HEAD before changing source; do not reset to `c0389c2d`. The user-facing
project directory `~/portal-prime` is the owner checkout and contains
the private device registry. Continue implementation in the existing worktree.

The short current-state document is
[portal-product-operating-state.md](portal-product-operating-state.md).
This longer document supplies the context, exact boundaries and operating
instructions the short document deliberately omits.

Paste this into the new agent:

> Continue the Immortal improvement effort from
> `~/.codex/worktrees/portal-music-player/portal-prime`, branch
> `codex/product-audit-20261103-144114`. Read
> `docs/portal-complete-handover-2026-11-06.md`, `AGENTS.md`, then the selected
> roadmap task and its dated receipt. Prioritize my reported screensaver and
> visible UX problems. Work in small, independently useful slices: five-minute
> discovery cap, 10–15-minute delivery target, original 30-minute hard limit per
> issue. Both November 5 slices are finished; do not repeat them or reset held
> issue clocks. Preserve household settings, accounts, sources and existing
> work. No Google phone/Pixel access, GitHub authentication/push/publication or
> HA music deployment is authorized. Do useful authorized work, test it, verify
> any permitted delivery, then move on. Report shipped behavior and remaining
> acceptance gaps plainly. This handover is context, not approval to reopen
> parked work.

Before the next implementation, take these bounded steps:

1. Check the existing branch and `git status --short`. Preserve the pre-existing
   untracked `scripts/__pycache__/`; use Python `-B`.
2. Read this document and the repository instructions. Use the
   [roadmap index](handover/portal-roadmap-index-2026-11-06.md) to avoid repeating
   completed work. Read only the selected task's receipt and relevant history.
3. State one concrete problem, reachable caller, useful scope and frozen
   acceptance criteria. Start discovery and the hard clock together.
4. Proceed with a distinct authorized issue. A parked task needs the explicit
   decision recorded below; a new agent or new task name does not renew it.
5. Use read-only device checks only when the selected issue needs them. This
   document is not a reason to inventory devices, reinstall the current app,
   re-run all historical suites or take another unchanged clock screenshot.

There is no unfinished candidate or device write to finish from the latest two
slices. The next feature has not been selected by this handover.

## 2. What the human wants

The broader objective is autonomous, accountable improvement of the entire
product: reliable engineering, useful features, polished UX, meaningful tests,
small releases and verified outcomes across the actual available components.
The full original instruction is preserved in
[portal-product-objective-2026-11-06.md](handover/portal-product-objective-2026-11-06.md).
Its source was the human's attached objective, not an agent-generated plan.

The immediate complaints and priorities are more specific:

- Photos looked too small inside a conspicuous grid; visible cell borders and
  empty outlines made the screensaver look poor.
- Other layouts left large plain dark areas above and below photos. The human
  asked for quiet, lightweight doodles or graffiti, comparable in spirit to
  WhatsApp's background, and a nice frame around the actual image.
- Screensaver problems and visible UX are the priority. The human asked directly
  what had been fixed and why delivery was slow.
- The human explicitly rejected spending hours on an interesting technical
  rabbit hole: scope tightly, timebox, choose low-hanging fruit, break larger
  work into small pieces, test and release frequently.
- Group photo import was separately approved for **local browser UI/controller
  implementation**: one or both Portals, independent destination folders,
  per-file/per-room results and retry only definite failures.
- “Do both one after another” authorized the two latest bounded slices:
  optimized-photo verification first, SMB warm caching second. Both finished.

Interpret the requested “bottle around the image” in that visual conversation
as a border/frame, consistent with the concrete reviewed photo designs and
subsequent implementation. Do not introduce a decorative bottle motif.

Fast progress means useful behavior delivered with relevant evidence. Task
counts, documentation churn, generic refactors, repeated negative discoveries
or the same screenshot are not substitutes for progress.

## 3. Authorization and non-negotiable boundaries

These human preferences persist into the next agent. A handover does not revoke
them or expand permission.

| Area | Existing scope / constraint |
| --- | --- |
| Local work | Focused reversible implementation, source review and relevant host tests are authorized within the admitted product scope. |
| Normal Portal delivery | Existing routine development delivery may proceed within an authorized slice, using the same signing key, explicit targets, functional checks and complete installed-APK verification. This is separate from public release. |
| Household state | Preserve all settings, accounts, credentials, media, selected photo sources, pairings and cache choices. Do not connect to a provider, change an album/source or enable caching to create an experiment. |
| Generated native fixtures | Use owned generated content and isolated fixture contexts. Retire packages, processes and external directories; restore the original runtime and state. Native work still needs a useful, bounded scope. |
| Google phone / Pixel | **No inspection, control or installation since November 3 until direct human reauthorization.** Earlier approval and receipts are historical. |
| GitHub | **No authentication, push or publication.** Read-only public information is separate. No upstream issue closure/comment was performed for the latest fixes. |
| HA music | **No HA music deployment.** The human cancelled that route and had its services removed. |
| Group photo transfers | Local group-import implementation was approved. Actual household photo transfer and public publication retain their separate existing gates. |
| Parked issues | Original clocks and holds remain. Renewed work requires the explicit scope/decision stated in the task, not a renamed issue or automatic new slot. |
| Device targeting | Exact authorized Portal target only. No unscoped ADB, `adb devices` inventory, broad LAN scan or phone discovery. |
| Living ADB | A targeted read was unavailable in the earlier visual observation. Do not enable TCP/IP, reprovision or reconnect broadly as a workaround without a new authorized reason. |
| Secrets | Never print or commit Fleet tokens, session cookies/keys, owner preference XML, keystores or per-device registry files. The repository is public. |
| Existing work | Preserve owner/frozen/dirty checkouts, historical branches and the existing untracked Python cache. Do not prune unrelated worktrees. |

The whole-product objective encourages beneficial independent specialist work.
Delegate only under the current session's applicable authorization, with
disjoint write scopes. Root owns shared integration, signing, builds and device
operations. Do not start a large team simply because historical agent names exist.

If a permission question is genuinely required, first produce the concrete,
reviewable work that can safely be done. Explain the exact boundary requiring
the decision. Do not repeatedly ask for permission already given.

## 4. Current repository, release and device state

### Repository checkpoint

| Item | State checked for this handover |
| --- | --- |
| Active worktree | `~/.codex/worktrees/portal-music-player/portal-prime` |
| Branch | `codex/product-audit-20261103-144114` |
| Latest implementation | `c0389c2d` — `feat: cache SMB photos for repeat playback` |
| Previous completed slice | `681de4f5` — `test: verify optimized static photo controller scenes` |
| Previous observation | `705bcf22` — `docs: record current Portal frame observation` |
| Pre-existing untracked item | `scripts/__pycache__/` only at preparation start; preserve it |
| Public repository | `starbrightlab/immortal` |
| Public update metadata | `version.json` remains **1.83 / code77** |
| New source candidate | None pending from the two latest slices |

The many intermediate local version bumps are development deliveries. They do
not mean 125 public releases happened between 1.83 and 1.208. The public manifest,
installed development APK, source build version and historical task version must
always be distinguished.

### Last verified room matrix — November 5

| Item | Family Area | Living Area |
| --- | --- | --- |
| Stable serial | `FAMILY_PORTAL_SERIAL` | `LIVING_PORTAL_SERIAL` |
| Known targeted connection | `192.168.0.70:5555` ADB; API29 | Fleet selector `Living Area`; no fresh native visual connection established |
| Installed package | `com.immortal.launcher.debug` | `com.immortal.launcher.debug` |
| Exact optimized version | **1.208 / code202** | **1.208 / code202** |
| Complete installed APK | **25,904,007 bytes**, hash below | Same complete bytes/hash |
| Install dispatches in this slice | One; ADB acknowledged success | One; acknowledgment uncertain, resolved with nine read-only observations |
| Fleet service | Ready | Ready |
| Original preference files | **43**, byte-exact | **30**, byte-exact |
| Saved configuration maps | All four exact, including revisions | All four exact, including revisions |
| Normal frame request | `ok=true`, `legacy_frame_requested` | Same |
| Captured logcat | 1,610 lines, zero fatal/ANR markers | 1,749 lines, zero fatal/ANR markers |
| Additional runtime preservation | Font/accessibility exact; original Chromium activity retained; frame resumed; awake | No corresponding fresh native visual/foreground proof |

The four saved maps are `/screensaver`, `/remote/settings/profile`,
`/remote/sources` and `/remote/layout`. Counts cover the captured preference set,
not every possible Android/app store. Zero captured crash markers are a bounded
observation, not a universal health or stability guarantee.

Latest optimized APK SHA256:

```text
cd12ddb0d874f24dc7c8903abe79a639a3d840308e5c4de3bf3556093f92a886
```

Signer certificate SHA256:

```text
4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6
```

The debug 1.208 APK was also built and inspected, but was **not** the room
delivery: 46,763,030 bytes, SHA256
`bde888711d5deb525c8af2210ec6cbd8b6756b16d56c9db57e9027c16750ad6b`.

The delivery/restoration checkpoint finished at
**2026-11-05 15:43:57.911268 UTC**, approximately **07:43:58 PST**.
This is not a statement that the devices were freshly checked on November 6.

### Roadmap metadata caveat

The actual roadmap contains **216 tasks**:
**154 delivered, 50 verified, 9 parked, 2 approval, 1 queued**.
“Verified” means the task's stated scope was verified; it does not imply delivery
or full hardware acceptance.

Its top-level `updatedAt`, label and a boundary string still refer to the earlier
options-token / **1.207** checkpoint. Its last two task objects and the latest
delivery receipt record the later work and **1.208**. `defaultTask` remains
`weather-native-layout`, a parked review selection. Treat the latest dated
delivery receipt as authoritative for room identity; neither that stale header
nor the default selection grants permission to resume Weather.

The roadmap is a manually maintained snapshot, not a live monitor and not an
exhaustive product inventory. This handover preserves it rather than silently
rewriting its history.

## 5. Latest completed slice: optimized static photo verification

Receipt:
[portal-optimized-photo-controller-2026-11-05.md](portal-optimized-photo-controller-2026-11-05.md).
Task ID: `optimized-photo-controller-acceptance`. Commit: `681de4f5`.

The earlier optimized fixture preflight found that R8 removed/inlined the
frozen Stage presentation and stop entrypoints. That preflight was correctly
parked; an unchanged fixture retry could not preserve its actual-call contract.
The human later approved a **distinct** controller fixture slice.

This slice normally constructs the **actual optimized 1.207 controller** in
photos-only mode, injects generated views, the actual Stage and its budget,
and calls actual controller presentation and **normal controller stop**.
It preserves the frozen visual and lifetime checks instead of substituting a
debug build, weakening assertions or adding production keep rules.

| Acceptance | Recorded result |
| --- | --- |
| Device / APK | Family Android10/API29; exact optimized **1.207/code201** |
| Scenes | Portrait whole fit, panorama whole fit, Gallery Spread |
| Viewport / input | Isolated **800×480**; generated images only |
| Render paths | Hardware Window PixelCopy and software Canvas; six captures |
| Capture synchronization | Two committed frames before every hardware capture |
| Geometry and pixels | Whole-image geometry, bitmap identity/admission, 16 literal corners and 16 thin edges per path, quiet doodle matte and opacity predicates |
| Retirement | Normal controller stop returns budget to zero while inputs are live; detach plus a committed frame precede input recycling |
| Isolation | Preference/resolver/service access rejected; counters zero; no fixture INTERNET permission |
| Cleanup | All three scenes retire; fixture package/process/external directory absent |
| Room preservation | Both rooms still 1.207 at that checkpoint; 43/30 files and all four maps exact |
| Independent review | Source/ABI review and replay of all six generated captures accepted |

Two setup attempts reached **zero scenes** and are excluded from product
acceptance. Android called value-only `Context.isRestricted()` and
`canLoadUnsafeResources()` on the strict null wrapper. Returning false for those
queries corrected the fixture without relaxing service/pref/containment guards.
Keep the failed attempts and their cleanup evidence; do not describe them as
renderer defects or behavioral RED.

Exact 1.207 input artifact: **25,887,623 bytes**, SHA256
`6dec140dcebe9ffc9bed73b60dca80a0b52e283a1513844f838b1e8798b95288`.
Mapping SHA256:
`1e314bcbb72bf195c867d4721eff81f394543496a35a79ef61cfcfb49c4f3d61`.
The mapping has 83,387,754 bytes.

For future **exact-APK** adaptation only, the 1.207 optimized ABI was:

- Controller presentation `T(List, boolean)`; normal stop `x0()`.
- Stage field `p`; budget `s`; settings `B0`; front/back photos `m` / `n`;
  viewer controls `Z`; photos-only flag `f`.
- The removed `used()` synchronized wrapper is replaced in this fixture by
  observing the actual surviving total `f()` under the original budget lock `b`.
  Do not rename the total method “used” or read it without the lock.
- Actual optimized viewer callback instances were retained; the fixture does
  not call full `start()` or `getView()`, which would widen source/service scope.

Obfuscated names are specific to **1.207 and its matching mapping**. Reinspect
the actual future APK; do not copy these names into a new build unverified.

Clock: **15:25:00–15:55:00 UTC November 5**; discovery cutoff 15:30,
target 15:40; selected 15:29:45. Native/restoration checks finished by 15:34:31;
aggregate receipt closed 15:35:16. The slice took approximately 10½ minutes.

Committed fixture and evidence:
`mocks/portal-optimized-photo-controller-20261105/`
contains `AttachedPhotoActivity.java`, `AndroidManifest.xml`, preparation,
native report, retirement, independent review, verification and generated PNGs.
Final Java source SHA256:
`21bc839af31a690a10521065116ebfaaea67e78b066e0fccf58fb734583d09d6`.

**Still unverified by this slice:** full controller startup, system Dream,
selected-source loading, production clock/Shuffle coupling, motion, resize,
performance, API28, Living visual appearance and the currently selected household
style. These captures bind **1.207**, not the later **1.208** APK.

## 6. Latest completed slice: SMB warm-photo caching

Receipt:
[portal-smb-photo-cache-2026-11-05.md](portal-smb-photo-cache-2026-11-05.md).
Task ID: `smb-photo-warm-cache`. Commit: `c0389c2d`.

Before this slice, SMB's bitmap-returning transport bypassed the existing encoded
image cache. The useful first increment reuses **MediaCache** for SMB image bytes
without adding a second cache, changing a source account or redesigning startup.

### Changed source and contracts

| File | What changed and why |
| --- | --- |
| `app/src/main/java/com/immortal/launcher/SmbPhotoCache.kt` | New small helper for qualified keys, warm/miss behavior, bounded reads and lifecycle checks. |
| `SmbSource.kt` | Exposes an internal source-qualified photo key using actual source identity. Existing connect/list/close behavior remains. |
| `PhotoFrameController.kt` | Initializes the existing opt-in cache on the SMB worker after successful connect/list; binds custom SMB fetch to bounded tracked streams and existing bitmap decoders. |
| `settings/SettingsDomains.kt` | Existing cache switch is visible when an enabled Immich, WebDAV **or SMB** source can use it. Existing persistence/default/budget and registry application remain. |
| `SmbPhotoCacheTest.kt` | Nine actual-helper JVM methods cover the useful behavior and cleanup cases below. |
| `settings/SettingsDomainTest.kt` | One actual-registry visibility check for SMB on device and remote without changing saved values. |
| `app/build.gradle.kts` | Development version **1.207/code201 → 1.208/code202**. |
| `docs/features/screensaver.md` | Documents SMB cache availability and online startup/caption limits. |

Key construction length-prefixes host, share, username, domain and share-relative
path. Host normalization is locale-independent; path separators are normalized.
Passwords are excluded. Existing MediaCache derives its stored key. Different
servers/shares/accounts/domains/paths do not share an ambiguous photo entry.

Warm hits decode the saved encoded image without opening that image transport.
A miss or corrupt cached decode reads bounded original bytes, attempts cache
publication, then decodes the in-memory bytes even if cache writing fails or
immediate eviction removes the copy. Cache failure must not break the slideshow.

The SMB stream is registered under the controller's transport lock after checking
the captured generation. Existing cancellation can close an admitted held read.
All paths unregister and close it. Epoch checks reject stale reads/presentation;
a bitmap decoded after retirement is discarded. The existing encoded-image
limit is **32 MiB**, with interrupt/current-generation checks during reading.

The custom SMB `remoteFetch` remains non-null. This preserves the SMB transport
and the native Filmstrip contract's bitmap-source fallback; SMB is not accidentally
treated as an HTTP URL. Existing decoder/EXIF and stream-close machinery is reused.

### Verification and limits

The nine helper methods cover repeated warm hits without image transport,
cache-off direct behavior, qualified identity, corrupt-entry recovery, cache
write/self-eviction tolerance, cancellation after read and after decode, oversized
or failed reads with cleanup, and an owned held stream closed/unregistered.
One registry method verifies visibility without a saved-value change.

All **4,754 JVM tests across 496 suites** passed with zero failures/errors/skips.
Debug assembly, optimized assembly, R8, performance vital lint, both JavaScript
gates and exact APK/signer/source-asset inspection passed. Each APK contains the
same verified **23 source assets**. Independent source review accepted the change.

This is generated actual-helper/registry coverage, not a historical baseline RED,
live NAS/native playback verdict or actual controller-stop race acceptance.
The source implementation and successful delivery are distinct facts.

**Remaining work:**

- Startup still connects to and lists the NAS. Warm image reuse does **not**
  make offline album startup work.
- Caption metadata may open its own SMB EXIF read. A warm image hit does not
  imply zero NAS I/O for the whole screen.
- Reconnect behavior, real NAS interoperability, actual native decoder and
  full controller cancellation timing need separately scoped acceptance.
- Cancellation and cache publication are not atomic. The contract rejects late
  presentation; do not claim every possible cancellation prevents cache writes.
- The cache remains **opt-in**. No household cache switch or source was changed.

One exact optimized app install per room and complete readbacks are recorded
in section 4. Living's uncertain ACK was reconciled by observation, never by
replaying the install. This does not close the whole upstream SMB issue.

Clock: **15:35:26–16:05:26 UTC November 5**; discovery cutoff 15:40:26,
target 15:50:26; selected 15:39:06. Delivery/restoration ended 15:43:57.911268;
documentation/commit finished in approximately 12 minutes from start.
All earlier issue clocks remain.

## 7. Status of the originally reported screensaver appearance

The complaints were addressed through several small changes rather than a single
whole-renderer rewrite. These receipts are implemented/delivered evidence at their
stated versions. The latest APK inspection proves packaged asset identity;
it does not repeat every historical native scene on 1.208.

| Reported area / style | Implemented change and relevant receipt |
| --- | --- |
| Small photos / visible empty grid outlines | Paper direction uses whole, larger photos, slim photo-sized frames and quiet doodle margins. [Review](portal-photo-layout-review-2026-11-03.md), [implementation](portal-photo-layout-paper-2026-11-03.md), [native geometry](portal-photo-paper-native-2026-11-03.md). |
| Clock/date/caption contrast on Paper | Adaptive localized backing around measured native widgets, preserving configured foreground and opacity. [Contrast receipt](portal-paper-clock-contrast-2026-11-03.md). |
| Thin frame across photo shapes | [Slideshow slim frame](portal-slideshow-slim-frame-2026-11-02.md). |
| Ken Burns / Push margins | Quiet doodles and slim photo-sized frames. [Doodles](portal-cinematic-doodles-2026-11-04.md), [frames](portal-cinematic-frames-2026-11-04.md). |
| Hero + Sidebar | Doodles in outer and per-photo gaps; useful one/two/three-photo handling. [Receipt](portal-sidebar-doodles-2026-11-04.md). |
| Photo Pair / Memories / Panorama | Quiet matte gaps. [Receipt](portal-matte-doodles-2026-11-04.md). |
| Cinema Letterbox | Quiet margins, slim frame and later retained viewport resize fit. [Matte/frame](portal-cinema-matte-2026-11-04.md), [resize](portal-cinema-viewport-2026-11-05.md). |
| Polaroid | Quiet background and complete prints clear of the clock. [Doodles](portal-polaroid-doodles-2026-11-04.md), [clock space](portal-polaroid-clock-space-2026-11-04.md). |
| Gallery Wall | Quiet empty margins and complete tiles clear of clock bounds. [Doodles](portal-gallery-doodles-2026-11-04.md), [clock space](portal-gallery-clock-space-2026-11-04.md). |
| Native photo styles | Native doodle matte and thin photo edges. [Matte](portal-native-photo-matte-2026-11-04.md), [border](portal-native-photo-border-2026-11-04.md). |
| Static native rendering | Earlier debug hardware/software checks and latest actual optimized controller checks. [Debug hardware](portal-native-photo-hardware-2026-11-04.md), [optimized 1.207](portal-optimized-photo-controller-2026-11-05.md). |
| Dissolve pixels | Bounded generated hardware dissolve checks. [Receipt](portal-native-dissolve-pixels-2026-11-04.md). |

Other completed fixes include panorama/Memories retained viewport handling,
caption ownership/null-place/date/timezone correctness, local/SMB Home image
fallbacks and browser preview decoding feedback. Find exact IDs and evidence in
the complete roadmap index before touching those paths again.

The approved visual direction is sparse and quiet. Doodles sit behind and around
whole photos; borders follow image bounds. Avoid recreating thick outlined grid
cells, shrinking photos to decorate margins, or adding expensive animated
backgrounds. Preserve motion/clock/source/lifetime behavior.

### What is still not established

Whole-device screensaver acceptance remains incomplete: actual full startup,
Dream and source/clock/Shuffle integration, all styles, API28, Living physical
visuals, motion/performance and the active household selection are separate
from generated fixture acceptance. Do not say “all screensaver issues are fixed.”

At **07:56 UTC November 5**, a read-only Family observation showed the owned
PhotoFramePreviewActivity at 2160×1440 with a warm clock/date on black and
**no photos**. Living targeted ADB was unavailable. Saved overnight enable and
night-clock switches were false, Shuffle fit remained, and all room captures
were preserved. This is not photo-layout evidence or proof that night policy
caused the black background. Ambient-light behavior was not established.
See [current-frame observation](portal-current-frame-observation-2026-11-05.md).

A photo layout existing in source and the room currently displaying that layout
are different facts. Any useful next visual slice should address a distinct,
reproducible gap without changing the household selection merely for proof.
Do not retake the unchanged clock screen and label it a completed feature.

## 8. Browser group photo import: approved and locally verified

Task: `browser-group-photo-import`.
[Implementation receipt](portal-browser-group-photo-import-2026-11-03.md),
[design scope](design/portal-browser-group-photo-import-review-2026-11-03.md),
[contract](design/portal-browser-group-photo-import-contract-2026-11-03.md).

The approved implementation lets the browser view one Portal's library while
capturing import recipients independently. The human can choose one/both rooms,
pick a folder for each and review a result for every room/file. Lists use eight
rows per page; the action names file and destination counts.

`PhotoImportBatch` owns at most two room lanes, sequential files within each
lane. Every write binds stable device identity, captured destination and current
valid pairing/capability. A positive exact path/byte acknowledgment confirms
a copy. Parsed rejection is definite failure; missing, malformed or lost reply
is **unconfirmed**. Merely listing a matching filename cannot upgrade uncertainty.

Retry selects only definite failed pairs, preserves original folder/device
identity and explicitly captures current pairing at the same endpoint.
Collision rename retries that copy; existing server files are not overwritten.
Stop cancels queued work and lets an in-flight response settle. Pairing/page
retirement preserves uncertainty; stale sheet inspection cannot revive a
closed/newer batch or change folders after Start.

Historical local gate: **743 JavaScript**, **4,358 JVM / 453 suites**, debug
APK asset equality, 16 Chromium leaf cases and 12 responsive layouts at
320/390/768 px, light/dark and 100%/130% root-font emulation. Independent review
found five ownership issues, which were corrected and covered. Generated
intercepted replies and text payloads labelled image/png were used.

The original receipt says no install in **that slice**. Subsequent room APKs
include the current bundled remote assets; latest 1.208 inspection verifies their
source bytes. The task remains **verified**, because real group transfers were
not accepted. Do not reinterpret later APK delivery as live group-upload proof.

Open gates: actual room transfer/storage/no-overwrite, LAN/CORS/session behavior,
real decoding, state preservation, physical phone and hardware performance.
No page-close background continuity, persisted folder preferences, merged
library browsing, bulk delete or group slideshow activation is promised.

Original mock clock **November 3 03:02:13–03:32:13 UTC** remains.
Approved implementation clock **November 4 00:42:19–01:12:19 UTC** is separate.
The direct human approval was “Approve implementation”; it approved the local
browser UI/controller scope, not household transfer or publication.

## 9. All remaining tracked tasks and their holds

This table includes every task whose current status is parked, approval or queued.
The [roadmap appendix](handover/portal-roadmap-index-2026-11-06.md) preserves their
complete current task records and all 216 task IDs/titles/statuses.

| Task ID / status | Reason, original boundary and next permissible action |
| --- | --- |
| `optimized-photo-fixture-abi` — parked | Exact optimized 1.205 lacks the frozen Stage show/stop ABI. **Nov 5 06:44:25–07:14:25 UTC**, discovery cap 06:49:25. The separately approved 1.207 controller fixture closes a narrow static acceptance gap; retain this historical preflight rather than retrying the old fixture or claiming broad startup acceptance. |
| `web-page-zoom-native-rejection` — parked | Candidate 1.182 rendered Automatic/75%/125% identically at scale 1.8000000715. Withdrawn; higher-version 1.183 restored baseline behavior. **Nov 4 23:09:07–23:39:07 UTC**; feature work stopped 23:35:53, recovery closure 23:45:28.941546 honestly exceeded cap. New implementation requires explicit human choice; native rendering gate must precede promotion. [Receipt](portal-web-page-zoom-2026-11-04.md). |
| `toolkit-apk-read` — parked | Actual ViewModel candidate fixed three baseline failures and passed 200 host tests, but the **Nov 3 17:40:01–18:10:01 UTC** slot expired before integration. Withdrawn, source restored to clean Toolkit `44d75050`; candidate private. Do not commit/deploy it or restart automatically. [Receipt](portal-toolkit-apk-read-2026-11-03.md). |
| `platform-video-redirects` — parked | Direct MediaPlayer streams bypass the delivered photo helper. AOSP Android9/10's internal redirect switch omits effective-port checks. **Nov 3 01:52:39–02:22:39 UTC** retained. No real credential exposure observed. Protected cache-first or ranged MediaDataSource work requires separately authorized scope plus hardware/range/seek/lifecycle proof. [Research](portal-platform-video-redirect-research-2026-11-03.md). |
| `weather-native-layout` — parked | Weather-only adaptive design approved; candidate 1.78 on `codex/weather-native-refinement-20261102` withheld. Medium daily lows clip; large placement and 150% text/native unavailable acceptance incomplete. **Nov 2 15:05:14–15:35:14 UTC** geometry slot closed; bookkeeping 6.51s late recorded. Fresh bounded follow-up decision needed. [Candidate](portal-weather-native-refinement-2026-11-02.md), [settings return](portal-weather-settings-return-2026-11-03.md). |
| `settings-native-source-drafts` — parked | Masking/unsaved Cancel passed at observed scope. SMB keyboard covers actions under `immersive.status=*`; manifest-only adjustResize candidate failed and was withdrawn. **Nov 2 17:35:44–18:05:44 UTC**. A fresh explicit fullscreen-compatible scope is needed; do not repeat the manifest patch. [Receipt](portal-settings-native-source-drafts-2026-11-02.md). |
| `black-handoff` — parked | Zero-black-frame tradeoff made complete Home **1,169 ms vs 418 ms**; candidate rejected, no retained fix. Second explicit slot **Nov 2 01:01:54–01:31:54 UTC** closed. No automatic reopening; later accepted fades do not pass this issue. [Receipt](portal-black-handoff-second-slot-2026-11-02.md). |
| `legacy-night` — parked | Seven old styles remain exhausted; three reopened motion candidates rejected, final two native failures and only **5/11 strict cases**. Preserve the historical per-style clocks. The aggregate task has no single clock object; use history/receipts, never invent a fresh start. Explicit new scope required. |
| `performance-experiments` — parked | Cold Shuffle, rejected Home/Apps/settings, timed-out typography and incomplete playing-gesture work retain their original clocks. No accepted broad performance candidate. Any new work needs explicit, equivalent-scenario bounded scope; passing host tests or static pixels cannot close it. |
| `quick-controls` — approval | Functional setup/destination mock has **43 interaction checks / 12 layouts**. Native implementation awaits design sign-off. Reuse dashboard and Apps owners; no new shortcut preference model. [Concrete review](design/portal-quick-controls-setup-review-2026-11-02.md). |
| `widget-gallery` — approval | Whole gallery and Portal/Android/iOS interpretations remain unapproved. Weather-only approval does not approve the rest or manual location. [Checkpoint](portal-widget-gallery-checkpoint-2026-11-02.md). |
| `music-browser-playback` — queued | Normal human Google sign-in required before bounded real playback/control acceptance. Signed-out page and silent Web Audio readiness are not Premium playback. No login automation or cookie extraction. [Readiness](music-standalone-readiness-2026-11-02.md). |

Do not promote old rejected/private candidates. A “verified candidate” that was
withdrawn at cutoff is not currently integrated production source.

## 10. Closed discovery and failures that should not be repeated

The following findings are especially easy to rediscover. Preserve them and move
to a distinct useful issue unless new evidence changes the premise.

- **Optimized Stage ABI:** old entrypoints were actually removed/inlined. The
  host inventory was not proof of a renderer bug. The distinct controller fixture
  is already done; neither debug substitution nor production keep rules are needed.
- **Fixture Context failures:** the two null-wrapper value-query failures were
  setup failures before scenes. Existing final overrides and isolation guards
  are part of the accepted fixture; do not relitigate them.
- **Caption line-limit lead:** selected maxLines/ellipsis/panel guards did not
  establish a distinct reachable defect. Pixel bounds remain unverified, but
  that is not evidence of a bug. Discovery cap **Nov 5 07:27:24–07:32:24 UTC**
  closed without an app candidate.
- **Notification image cleanup hypothesis:** a missing finally is source-supported,
  but its experimental URL-constructor mock intercepted Gradle resource loading.
  Execution failed before assertions, so no product RED was obtained. Test removed;
  logs retained. Original held scope **Nov 3 13:09:28–13:39:28 UTC** must be
  qualified before another attempt. Do not repeat that constructor mock unchanged.
- **Browser navigation/registry/profile/source/snapshot scan:** **Nov 5
  06:01:12–06:06:12 UTC** closed without a distinct candidate; no fresh native
  acceptance or release came from it.
- **Crop editor/preview lead:** **Nov 5 06:33:13–06:36:43 UTC** closed before
  the 06:38:13 cap. Current local Photo wall already showed the larger images,
  slim frames and pale doodles. Save/Cancel/ownership source review found no new
  reachable defect; do not turn that negative scan into another unit.
- **Public issue scan:** **Nov 5 07:35:47–07:40:47 UTC**, later source qualification
  at 07:43:48 under the same clock. A live public GET found 24 issues plus 20 PRs;
  PRs were excluded. Connector access was unauthorized; cached search was not
  current evidence. Do not disable TLS verification to force Python retrieval.
- **Issue252 grid helper:** no active production caller. Old candidate was
  discarded and tests restored; zoom rejection has its own retained clock.
- **Issue264:** report concerned a 1.74 shutdown race. Current stopped guards exist,
  but fresh Calls/Dream runtime acceptance does not. Source review neither closes
  the upstream issue nor grants broad runtime health.
- **Issue182:** current centered Apps cluster already has its own visibility
  setting; an older fixed-offset Recents report is not a new current bug.
- **Issue246 volume heartbeat:** the reported premise does not match this checkout;
  MQTT publisher has no such volume entity/heartbeat/mic-privacy state. Adding it
  would be a feature, not an easy correction to an existing path.
- **Issue257 SMB caching:** image-cache bypass was real and the first warm-cache
  increment is now delivered. Offline startup/reconnect and live NAS proof remain.
  Reference fork `lukasdcl/immortal-contrib`, `pr/3-smb-photo-cache`,
  `4bf327d7c727df0ef428fcb6cbc34fc4dd37e2c8` was source reference only; no broad
  upstream adoption or issue closure was made.
- **Private capture overwrite:** an earlier XML read was overwritten and cannot
  be recovered by later workflow checks. Current rules require exclusive new
  directories before requests and retention of the first failure. This is workflow
  maintenance, not an app feature or a recovered old baseline.
- **SMB keyboard:** fullscreen policy defeated adjustResize in actual native
  acceptance. Build/schema passes did not save the candidate.
- **Web zoom:** source tests passed but actual rendering did not. Do not promote
  future WebView behavior before the decisive native rendering check.

The private discovery paths and dated receipts are in section 15 and the
historical snapshot. No unchanged exhausted hypothesis gets a new clock just
because a new agent is reading it.

## 11. Product architecture the next agent must preserve

Immortal is an independent community launcher/screensaver/app store/fleet layer
for discontinued Meta Portal devices. It is not affiliated with Meta.
One Android module, Kotlin/Jetpack Compose:

- Namespace `com.immortal.launcher`; production application ID same.
- Debug and optimized development delivery application ID
  `com.immortal.launcher.debug`; Activity class names keep the base namespace.
- minSdk24, targetSdk36; real Portal hardware Android9/API28 and Android10/API29,
  arm64; no Google Play Services.
- Bootloader cannot be unlocked, no root; unreliable USB-C thumb-drive mounting
  is firmware behavior. Do not turn these hardware facts into “fixes.”
- Performance variant uses R8 and the development package with production-like
  flags. Debug results are not optimized acceptance.

### Existing ownership map

| Area | Existing source owners / paths |
| --- | --- |
| Home / apps | `HomeActivity`, `UserLayout`, `QuickBar*`, `AppSwitcherActivity` |
| Screensaver lifecycle | `PhotoDreamService`, `PhotoFrameController`, `Screensaver*`, `DreamPolicy`, `PresenceState`, `AntiBurnIn` |
| Native photo renderer | `NativePhotoStage*`, photo bitmap budget/decoder and controller ownership; search actual symbols before editing |
| Web photo renderer | `app/src/main/assets/screensaver/`: `modes.js`, `mode-impls.js`, scheduler, photos, styles, doodle SVGs |
| Faces / overlays | `Face*`, `ClockFaces`, `FlipWebClockFaceView`, `DigitalClock*`, `Welcome*` |
| Sources | `LocalMedia`, `ImmichSource`, `SmbSource`, `DavSource`, `RemoteAlbum`, `MediaCache`, bounded decoders |
| Calendar/weather | `Weather`, `CalendarFeed`, `CalendarPacks`, associated configs/Activities |
| Tools | `ToolsActivity`, `HomeToolOverlays`, camera/lamp/story/intercom/countdown and keyless helpers |
| Ambient | `Chime*`, `Sunrise*`, `Sleep*`, `ScreenControl` |
| Wallpaper | `WallpaperConfig`, `AmbientBackground`, `HomeBackground`, `SkyColors`, `StarField` |
| Input | `BackHelper`, accessibility back-gesture services, `SystemSounds`, `Remote*`, `TvFocus` |
| Store/install | `StoreActivity`, `StoreCatalog`, `UpdateManager`, `InstallDaemon`, `HeadlessInstaller`, install Activities/services |
| Fleet | `FleetAgentService`, `FleetHttpServer`, `FleetRoutes`, `FleetConfig`, `FleetFs`, `FleetDiag`, `FleetCalendar`, `FleetScreensaver` |
| Paired browser/API | `RemoteHtml`, `RemoteCompanionRoutes`, bundled `remote/core.mjs`, `control-center.js`, `control-center.css` |
| Audio | `MultiRoom*`, `NowPlaying*`, `Snapcast*`, `Ma*`, media session/listener owners |
| MQTT | `Mqtt*`; reuse established publication and settings owners |
| Boot | `ImmortalApp`, `BootReceiver`, `BootLaunch` |

Kotlin package layout is mostly flat. Reach for `rg` by symbol, not a guessed
feature directory. Reuse established transport/cache/lifecycle infrastructure;
do not build parallel preference, navigation or error-handling systems.

### Settings rules

Read the full settings section of `AGENTS.md` before touching a setting.
`settings/SettingSpec`, `SettingsDomain`, `SettingsDomains` and `SettingsRegistry`
define one source of truth for persisted values, on-device UI and remote schema.

1. Add/change a `SettingSpec` bound to existing config accessors. Never write/read
   raw prefs directly from UI or routes.
2. Constraints and validation belong on the spec. Domain application rejects
   invalid values; UI should not silently compensate with separate clamps.
3. Side effects belong in domain `onApplied`, shared by on-device and remote paths.
4. Visibility is declarative and shared. SMB cache visibility follows this model.
5. Complex pickers/connectors/editors use an Activity reached through `NavSpec`,
   not an in-file `showX` navigation toggle.
6. Use immutable settings snapshots for recomposition. Bespoke MQTT/audio live
   screens retain registry spec coverage and pin tests.
7. Credential groups are atomic multi-field connect Activities/Fleet façades.
   Planned GroupSpec infrastructure is not permission to refactor them now.
8. Completeness tests fail if persisted fields lack specs or explicit exclusions.

The historical registry audit counted 12 domains / 108 specs / 97 writable
scalars; navigation audit counted 8 categories / 54 pages. Those are dated
inventory counts, not today's exact totals or 54 device-accepted screens.

Native Filmstrip has prior scoped Family acceptance; native Home and background
WebView behavior retain their existing opt-in/default-off boundaries. Inspect
current flags before work and preserve them unless a new scope requires change.

### Repository conventions

Match style and make focused edits. New source headers use **2026 Starbright Lab**
and MIT, never the Meta Platforms template. Windows-executed `.ps1` and `.bat`
must stay ASCII. Catalog edits need schema/network validation and consideration
of the bundled fallback. Gen-1 Portal+ installation uses the shell-privileged
daemon because the system installer dialog is broken; do not bypass that route.

## 12. Other components: what exists and what is actually accepted

### Browser/PWA

There is no independent web deployment for the paired remote: source assets
ship inside the Portal APK and are served on LAN by Fleet. Pairing/session auth
is scoped management, distinct from full Fleet access. Host Node tests,
intercepted Chromium tests, a live paired Portal browser and a physical phone
are different gates.

Historical local review URLs:
`http://127.0.0.1:8891/group-photo-import-review.html`,
`http://127.0.0.1:8891/roadmap/`,
`http://127.0.0.1:8892/renderer.html`.
These were local review servers/tabs. Their existence in earlier ambient UI is
not a current health check or permission to operate a particular browser.
Reuse committed mock files if a server is absent; do not spend a feature slot
reconstructing old review tabs.

### Native Android companion versus related Toolkit

Both live in the broader Toolkit Git history but are not interchangeable
checkouts. Initial accessible owner repository was
`/Volumes/xHome/Github/portal-toolkit`, clean at `4e1dadb8…` on
`roadmap-v1.2-1.4`, declaring `com.portalinstaller`, minSdk26/targetSdk35,
1.4.0/code5. Historical paired-companion source was recovered from
`codex/toolkit-music-20261019`, `422286e1d8e2afd51c9809315418681e2f868efd`;
old temporary worktree directories were missing/prunable. Preserve original,
frozen and dirty checkouts. See
[provenance](portal-companion-provenance-2026-11-02.md).

Latest recorded phone delivery is **1.7.8/code20**, package `com.portalinstaller`,
on branch `codex/photo-activation-readiness-20261103`,
source commit `d5b1a4de1069ceb02d172bfb10832a60c232bf53`.
It guards slideshow activation against failed/incomplete listings.
[Receipt](portal-companion-photo-activation-2026-11-03.md):
651 JVM / 81 suites, 16 actual native component executions, full APK readback
on owned emulator and Pixel, all three saved companion files unchanged.
APK 14,738,977 bytes, SHA256
`9e6d1fdab7468963b9a6d4f53526452ba78aa41f3629128a9eb4ab9d6421702c`.

That phone result is historical. Physical whole-app UI, real source writes and
full parity remain open; **all current Pixel/Google phone access is prohibited**.
Do not refresh phone identity, inspect ADB inventory or use an emulator result
as physical phone proof.

Separate Toolkit maintenance receipts record monitoring/cancellation and local
CI work (`0c9a37ca`, `44d75050`) and later uptime validity coverage.
Initial August checklist items were stale: connection guards, errors, quoting
and cleanup already existed. Revalidate actual source before opening a bug.
The APK-read candidate is withdrawn at its expired clock, not merged.

No shipped native iOS app was identified. Gallery iOS columns were design
interpretations; do not claim implementation or platform parity.

### Jarvis

External app `com.portal.assistant`; finite control IPC at
`content://com.portal.assistant.control`. Immortal owns wake/foreground handoff
and bridge validation; microphone/provider credentials stay in Jarvis.
Known external source paths were absent in the earlier inventory.
Current external provider/cloud/model availability and end-to-end voice
performance are unestablished. Read
[bridge scope](design/portal-jarvis-bridge-write-validation.md) and
[handoff](design/portal-plus-jarvis-handoff.md) before work. Do not infer a new
model migration or current voice health from old research.

### Music / HA

[music-player-goal.md](music-player-goal.md) is the authoritative historical
ledger and open acceptance matrix. The HA-hosted route was cancelled October19;
Music Assistant and its YouTube helper were removed, Family's old connection
disabled. Existing client/receiver branches are preserved, not a running server.

Standalone readiness November2 verified Chrome138, a signed-out official page
and silent Web Audio on Family. Normal human Google sign-in and actual Premium
playback remain pending. No cookies are to be extracted or login automated.

Live provider/audio, real queue/groups, reconnect during playback, Calls/Jarvis
interruption, user-paused behavior, native quality/performance and final Living
music acceptance remain open. Earlier connected-idle/native protocol/browser
checks do not pass those gates. Historical latency target ≤500ms was missed;
polling experiment reverted. Historical frame-quality target was missed and
that experiment parked. Natural fade directions were accepted later by the
human, but optical FPS, legacy motion and whole-product performance remain open.

Do not provision a replacement service or resume HA deployment from this handover.

## 13. Build and verification runbook

### Local dependencies last located

These paths existed when this document was prepared. Temporary runtimes/artifacts
can disappear; confirm the specific required path before use, not a global scan.

| Dependency | Path |
| --- | --- |
| JDK21 | `~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` |
| Build tools | SDK `build-tools/36.0.0` |
| APK analyzer | SDK `cmdline-tools/latest/bin/apkanalyzer` |
| Existing MkDocs runtime | `/private/tmp/portal-docs-runtime-20261104/bin/mkdocs` |
| Node packages | `~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules` |
| Installed Chrome | `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` |

### Relevant gates, not ritual reruns

For a production Portal source change and optimized room delivery:

```sh
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:assemblePerformance :app:lintVitalPerformance --no-daemon
```

Run the appropriate bundled JavaScript gates for web changes or APK delivery.
The existing readiness helper selects the actual tests; use it rather than
reconstructing globs:

```sh
python3 -B scripts/portal_release_readiness.py --help
```

Its modes are full local gates, `--javascript-only`, or `--inspect-apk`.
Full/inspection modes require the baseline APK; choose `--variant performance`
and `--local-preview` for existing optimized development delivery. Supply the
SDK and a **new exclusive output directory**.

`--inspect-apk` validates a supplied APK's identity, signing/source assets and
baseline relationship. It does not run fresh builds/unit tests.
`portal_delivery_summary.py` summarizes saved receipts offline; it does not
contact devices or prove current health.

For a focused small change, run meaningful relevant checks and required
repository gates; broaden only when a failure/change/real concern warrants it.
Do not re-run 4,754 tests or rebuild Android merely because the next agent read
this handover. Documentation changes use strict MkDocs and link/consistency checks.

Other task-dependent gates:

```sh
python3 -B scripts/validate_catalog.py --network
bash -n provisioning/provision.sh
```

Fleet source changes require its optimized Rust build and relevant tests.
Windows scripts require actual parse/ASCII checks; Bash-only acceptance is
insufficient. CI files configure host/JVM/JS/Python, pairing browser, provisioning,
catalog, docs and release metadata checks. **Hosted execution has not been
observed just because local gates pass.**

The release guard can reject intentional local-preview version divergence.
Use the documented helper's local-preview semantics; do not bump public
`version.json` merely to make development numbers match.

### Native generated-fixture tools

Reuse `scripts/portal_native_fixture.py` (11 offline unit cases in its retained
receipt). It packages owned generated Java with `android.jar`, compiles all
nested classes with d8, embeds the exact source APK and outputs an unsigned
aligned fixture under a deadline/exclusive destination. Signing/install/run
are separate root-owned actions.

Bind APK, matching mapping, fixture source, manifest, expected pixels and
cleanup to recorded hashes. Freeze meaningful assertions before the candidate.
Retain baseline and failed setup attempts. Do not relax visual/lifetime guards
to obtain a pass. A Context counter proves its stated access containment;
it is not global instrumentation of every Android call.

Seal the exact APK and matching R8 mapping **before another build overwrites
standard build outputs**. Hash the full installed app, not only a manifest or
package name.

## 14. Portal capture, delivery, restoration and recovery runbook

Read `skills/immortal-fleet/SKILL.md` before deploying/managing devices.
Use `provisioning/fleetctl` and existing room helpers, not a new parallel
Fleet client. Device registry files live privately in the owner checkout.
Never put their values in tool output or public receipts.

### Before device requests

1. Establish the explicit authorized room/serial and the selected issue's clock.
2. Create a new private directory exclusively, mode0700, **before any request**.
   Reject an existing destination. Raw files are mode0600.
3. Capture the complete relevant baseline: all preference files; the four maps
   with revision/content; name/dev mode; current app identity.
4. For Family native work, also capture font scale, enabled accessibility,
   original Activity/task and awake/frame state using the explicit Portal target.
5. Hash owner registry bytes without printing contents. Use temporary private
   copies only when existing helpers need them; remove copied records afterward.

The latest private `capture.py` in the SMB evidence directory is an example of
the accepted exclusive-directory behavior. It uses exact saved room records,
bounded HTTP reads and existing paired authorization. Inspect it before reuse;
do not copy secret-containing raw output into Git.

For preference backup, reuse `provisioning/fleet-backup.sh` with its unique private
snapshot publication. A successful later comparison cannot erase the first
failed comparison. Use a new numbered `after-2` directory and retain the first
raw read. Compare file sets and full byte contents, not selected friendly values.

### Candidate admission and one write

Complete relevant gates, independent scoped review where needed, artifact/signer
inspection and any decisive generated native behavior gate before promotion.
Seal candidate and recovery inputs. The latest delivery used root review and
an exclusive one-install claim so uncertainty could not create duplicate writes.

Target Family explicitly:

```text
adb -s 192.168.0.70:5555 ...
```

Revalidate that known endpoint's stable Portal identity before a new operation.
Its IP is a last-known route, not immutable identity. Never use unscoped ADB
or inspect other attached devices.

Living used the existing Fleet `dev update` path with `--no-pause` to preserve
its update policy. If acknowledgment is missing/disconnected/uncertain,
**observe first**: complete installed APK, version, service readiness and state.
The last update settled after nine read-only observations; no second install.
ACK uncertainty alone is not a reason to retry a mutation.

### After delivery or native fixture

- Pull/read the **entire installed APK**, compare exact bytes/hash/signer/version.
- Verify the relevant functional outcome; version/readiness alone cannot prove
  a rendered feature, actual transfer or playback.
- Capture into a new private destination and compare all baseline files/maps.
  Explicitly qualify any observed internal cache difference; do not hide it.
- Request the existing normal screensaver/frame path using the valid paired
  `/remote/key` action when that is the recorded restoration path.
- Preserve original Chromium/task, font/accessibility and awake/runtime state.
  A legacy frame request is not proof of natural system Dream recovery.
- Retire generated fixture package, process, external directory, temporary
  forwards/records and any owned scratch. Verify absence.
- Keep failed/first observations. Record completion, remaining limits and
  original clock honestly, then commit the useful result locally.

For visual capture, first establish that an owned PhotoFramePreviewActivity is
resumed or the existing `/remote/pin` response reports its owned cover. Do not
capture/publish personal photos or unrelated apps. Fleet does not provide an
invented unrestricted shell/screenshot endpoint; use documented existing paths.

### Recovery

The prior sealed 1.207 APK is retained as a recovery input. It is **not** a
promise that a downgrade install is accepted: Android rejected a prior optimized
downgrade. The zoom recovery restored baseline behavior in a **higher-version**
1.183 APK. Do not blindly run `adb install -d`, clear app data, reset settings or
replay an unknown write.

If a candidate fails after delivery, stop feature work, establish actual state,
perform necessary bounded recovery, preserve data and record any deadline
overrun. Safety/restoration is not permission to continue feature investigation.
Public release remains separate: do not run `scripts/cut-release.sh`, whose
workflow authenticates/pushes/publishes and has no local-only mode.

## 15. Evidence locations and portability

### Durable tracked evidence

| Evidence | Purpose |
| --- | --- |
| [Current operating state](portal-product-operating-state.md) | Short current delivery/priorities/holds |
| [Full objective](handover/portal-product-objective-2026-11-06.md) | Exact portable copy of the original human objective |
| [Roadmap index and remaining records](handover/portal-roadmap-index-2026-11-06.md) | All 216 tasks plus complete open/held task records |
| `mocks/portal-widget-gallery-20261102/roadmap/roadmap.json` | Authoritative current task objects, acceptance, clocks and evidence pointers |
| [Preserved history](portal-product-history-through-2026-11-05.md) | Complete prior handoff, inventory, approvals, releases and closed investigations |
| `mocks/portal-optimized-photo-controller-20261105/` | Latest actual optimized fixture source, receipts and generated images |
| `mocks/portal-smb-photo-cache-20261105/` | Latest artifact/source/verification bindings |
| `mocks/portal-native-photo-hardware-20261104/` | Frozen predecessor fixture and scoped debug hardware proof |
| `mocks/portal-photo-layout-20261103/` | Photo review, implementation and earlier native evidence |
| Dated `docs/portal-*.md` receipts | Scope-specific outcomes; use the selected roadmap entry |
| [Music ledger](music-player-goal.md) | Historical implementation and still-open acceptance matrix |

The historical handoff was preserved byte-for-byte:
**277,802 bytes**, SHA256
`2236d2efb2d017e57d937f84e2a6bf4e9d0a91a6803f0ca585ffa00af01640e5`.
Its old “current” wording remains historical.

Original objective: **22,569 bytes**, SHA256
`5ec85df05eb8dfb0d011f320c9ed025774f328f454fc216918b4bcdb8d517706`.
Original attachment:
`~/.codex/attachments/e1ac08b5-a8c8-4f3e-9d81-6508e323ce04/pasted-text-1.txt`.
The portable copy keeps the exact source bytes; later human priorities and
restrictions in this handover take precedence over broader original wording.

### Private evidence — never publish

These are useful exact locations, not files to commit. The two latest directories
still existed when this handover was prepared. Temporary retention is not durable
backup; if a path disappears, use tracked sanitized evidence and report the
missing raw artifact instead of reconstructing a claim.

| Private location | Contents / relevance |
| --- | --- |
| `/private/tmp/portal-smb-photo-cache-20261105-073526/` | Sealed `candidate-performance-1.208.apk`, `candidate-debug-1.208.apk`, `rollback-1.207.apk`; gates, inspection, source review, capture/promotion helpers, before/after and completion records |
| `/private/tmp/portal-optimized-photo-controller-20261105-072500/` | Exact 1.207 APK/mapping, preparation and three separate attempts, native/run/cleanup and preservation evidence |
| `/private/tmp/portal-product-discovery-20261105-064425/` | Earlier optimized ABI preflight; no device run |
| `/private/tmp/portal-product-discovery-20261105-072724/` | Caption negative scan and failed notification mock; not product RED |
| `/private/tmp/portal-product-discovery-20261105-073547/` | Public issue inventory and same-clock source qualification |
| `/private/tmp/portal-product-discovery-20261105-063313/` | Closed editor/preview negative discovery |
| `/private/tmp/portal-current-frame-20261105-075606/` | Earlier clock-only private observation and preserved state |
| `/private/tmp/portal-settings-native-source-drafts-20261102/` | Historical SMB keyboard rejection and restoration |
| `/private/tmp/portal-companion-photo-activation-20261103/` | Historical companion artifacts/receipts; no permission to inspect phone |

The latest SMB directory is mode0700. Its retained debug, performance and rollback
APKs were mode0600 when this handover was prepared; their exact bytes remain
bound to the delivery records. Do not claim filesystem read-only sealing for
those files. Use read-only artifact sealing where the delivery procedure calls
for it, and rehash before reuse. Raw captures are private; preference/session/
registry files must stay private even when their hashes appear in a public receipt.

## 16. Delivery discipline for the new agent

Start the clock with discovery, not after a lengthy setup phase. Five-minute
discovery cap; 10–15-minute target for a useful completed slice; hard 30 minutes
per issue including implementation, checks, delivery, restoration and recording.
Retain the first start across turns, compactions, aliases and negative follow-ups.

Split a large improvement into independently useful increments. Select a
reachable production caller and a meaningful acceptance predicate; do not
manufacture bugs from inaccessible providers or inactive helpers. If blocked,
park the slice with honest evidence and choose unrelated authorized work.
Necessary recovery can run past the cap, but record it and stop feature work.

Use the existing infrastructure. A new fixture should prove a product question,
not grow into a framework project. New test helpers must earn their maintenance
cost. Do not write tests that merely mirror a reversible cosmetic implementation.

Report status precisely:

| Word | Required meaning |
| --- | --- |
| Inspected | Specific source/configuration read; no runtime claim |
| Implemented | Source retained in the working branch |
| Verified | Named checks passed against a stated artifact/scenario |
| Delivered | Exact accepted artifact installed and completely verified on stated targets |
| Accepted | Explicit scope met, including required native/user evidence |
| Published | Public asset/manifest/release actually published and verified; none in this effort's latest slices |
| Parked | Original bounded scope stopped; no automatic continuation |

Keep current state short, completed detail in dated receipts and exact task
records, and broad history preserved. Do not create another enormous running
handoff on every release. This thorough document is a transfer snapshot.

Use brief progress updates describing what was learned and what remains.
Avoid long silent runs or narrating unchanged polls. When completing a slice,
lead with useful user behavior, then the relevant evidence and material limits.

The historical specialist names included `calendar_native_fixture`,
`catalog_fallback_review`, `startup_source_review`, `app_switcher_audit`,
`group_photo_review`, `media_source_review`, `photo_layout_review` and
`toolkit_source_review`. These are prior contributors, not a guaranteed live
queue. Latest independent reviews completed; don't wait for old agents or assume
their context exists in the new chat.

## 17. Transfer completion checklist

- Both requested sequential slices are recorded as finished.
- Latest development delivery is 1.208; public metadata remains 1.83.
- 1.207 optimized visual proof is kept separate from 1.208 delivery.
- Reported visual improvements and remaining whole-screensaver gaps are explicit.
- Group import approval and actual-transfer limits are preserved.
- All 12 remaining parked/approval/queued tasks and all 216 task IDs are carried.
- Original objective and complete historical snapshot remain available.
- Phone, publication, HA, provider/source and secret boundaries are explicit.
- Original clocks, rejected candidates, failed fixtures and negative discoveries
  remain; no new issue scope is opened by this transfer.
- Worktree, commands, tools, artifact identities, preservation and recovery
  procedures are supplied.

Handover validation on November 6 passed strict MkDocs and Git whitespace checks.
All 300 local links resolved; all 216 index entries and all 12 complete remaining
task records matched the source roadmap. The objective copy and historical
snapshot were byte-exact. All seven latest source bindings and both retained
1.208 APK byte/hash identities matched the delivery receipt. These are fresh
host/document/artifact checks, with no new Android build, test execution or
device request. Private validation record:
`/private/tmp/portal-handover-validation-20261106-p80an51k/verification.json`.

The ongoing product objective is unfinished. The immediate user request is this
handover; the next agent should continue from the evidence above without
repeating completed work or treating historical results as fresh checks.
