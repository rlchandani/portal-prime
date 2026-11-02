# Settings visibility: independent PWA review

Reviewed locally on 2026-11-02 UTC in
`~/.codex/worktrees/portal-music-player/portal-prime`.
HEAD and the supplied base both resolved to
`1ca6a54bae86ca4f593445835bf52ac364c1cc56`.
Independent write scope: this receipt and
`app/src/test/js/remote-settings-disabled-parent.test.mjs` only.

## Source findings and consumer assumptions

The base has a concrete native/PWA visibility mismatch. The native screensaver
page gates its child lists on `settings.enabled` in
`ScreensaverSettingsActivity.kt`; the registered screensaver domain still
advertises children such as `shuffle`, `includeVideo`, `showNowPlaying`,
`presenceMode`, `idleSleepMin`, `overnightEnabled`, `darkRoomClock`,
`sleepTimerEnabled`, `soundscape`, `ambientDashboard`, `gestureWave`, and
`welcomeEnabled` without that master predicate. Conditional children also need
the master predicate alongside their existing source, hardware, or feature
condition. For example, the base cache control tests only
`s.usesImmich || s.usesDav`, and the overnight time controls test only
`s.overnightEnabled`. Main owns the Kotlin correction and registered-domain
parity matrix; this worker did not edit those files.

The PWA trusts ordinary schema membership. `core.mjs::visibleControls` filters
the supplied controls by Advanced tier and search; it does not infer parent
relationships from keys. `control-center.js::settingsPage` and `renderDomain`
render that result, and the row-retention cache prunes unseen entries after
each settings render. Fixing the registered visibility predicates is therefore
necessary: the consumer cannot distinguish a mistakenly advertised child from
an intentionally available control. No consumer defect was found in the
tested master-off/readback/reenable sequence.

Display visibility and write availability must remain separate.
`applyDomain` fetches
`/remote/settings?domain=screensaver&includeInactive=1` immediately before POST
inside the existing per-room queue. `RemoteRoutes.settings` exposes inactive
metadata only for an explicitly named domain, while ordinary GET and POST
readback call the visibility-filtered schema. `SettingsDomain.applyValues`
iterates the specs without a visibility gate. Adding visibility predicates
must preserve this path so profiles can enable a parent and set previously
hidden children in one validated batch. Hidden values are not reset by schema
filtering.

After POST, the controller replaces the displayed domain with the response's
`domain`; it also updates the room schema cache. A successful active-domain
response retires preceding registry reads. The new regression verifies
replacement and an explicit subsequent GET, but does not introduce a
concurrent delayed-GET test.

Calendar remains a separate scope consideration. The native screensaver page
also bulk-hides its embedded calendar list when screensaver is off. The PWA
has a separate Calendar category, whose feed is shared with Home and whose
widget controls use `hasCalendarLink`/`usesCalendar`. The new test covers only
the screensaver domain. It does not establish cross-category parity or justify
adding a master gate to the shared Home calendar feed.

## Existing coverage and added guard

Existing controller tests cover components of this behavior, but do not cover
the complete manual master-toggle sequence:

- `remote-settings-row-retention.test.mjs`: “narrowing keeps common rows, prunes
  hidden rows and resets only visible defaults” checks search-driven pruning
  and reset payload; “identical fresh schema retains rows and one changed
  control replaces only its row” checks fresh-schema metadata/value updates;
  “mixed-room and advanced presentation update while writes preserve current
  targets” checks enum option visibility and multi-room writes.
- `remote-settings-reset-scope.test.mjs`: “reset review follows Advanced
  visibility while omitting secrets read-only info and missing defaults”
  checks Advanced-aware review and reset payload.
- `remote-browser-workflows.test.mjs`: “profile preflight enables a disabled
  parent and its hidden children in one batch per room”, “profile preflight
  rejects an out-of-range hidden child without partially enabling its parent”,
  and “legacy peers that omit hidden child metadata fail safely with no partial
  profile write” cover the inactive-schema write contract.

The new module runs the actual shipped `control-center.js` via
`remote-browser-harness.mjs`, using the controller's renderer, master switch,
Advanced checkbox, refresh-button handler, schema preflight, and POST result
handling. The route callback is an explicitly synthetic server fixture.

Both new tests start enabled, with Advanced initially off or on. They assert:

- Exact everyday/advanced rows and enum options, with nondefault saved values.
- Exactly `{enabled:false}` is posted and the successful fixture readback
  contains only `enabled`; every child row/control disappears.
- Previously connected child rows become detached and their stale switch
  handler cannot dispatch a write.
- Switching Advanced in either direction while disabled cannot reveal
  server-inactive controls or dispatch a write.
- The actual refresh handler fetches ordinary off-state schema and keeps only
  the master visible.
- Reenable posts exactly `{enabled:true}` after fetching the full inactive
  schema while master is still false, and restores saved enum, integer and
  boolean values.
- Advanced controls/options are shown or hidden through the real checkbox
  without changing settings.

This is consumer regression coverage. It cannot prove actual Kotlin
predicates, SharedPreferences preservation, validation/side effects, live
HTTP, Android rendering, browser layout, device acceptance, or APK delivery.
The harness models DOM/event behavior without a real layout engine.

## Verification

Runtime: Node `v24.13.0`; CI declares Node 22, which was not executed here.

Focused command:

```sh
node --test app/src/test/js/remote-settings-disabled-parent.test.mjs app/src/test/js/remote-settings-row-retention.test.mjs app/src/test/js/remote-settings-reset-scope.test.mjs app/src/test/js/remote-settings-search-context.test.mjs app/src/test/js/remote-settings-time-of-day.test.mjs
```

Final focused terminal result: **33 tests, 33 pass, 0 fail, 0 cancelled,
0 skipped, 0 todo**, exit 0, duration `930.89775 ms`.
An initial test-authoring run counted the category chooser as a setting row
(31 pass / 2 fail); the new test's row helper now excludes that chooser.

Full bundled-JS suite:

```sh
node --test app/src/test/js/*.test.mjs
```

The first full run passed **654 tests, 654 pass, 0 fail, 0 cancelled,
0 skipped, 0 todo**, exit 0, duration `16542.712125 ms`.
Final rerun after adding disabled-state Advanced assertions used
`node --test --test-reporter=spec app/src/test/js/*.test.mjs` and passed
**654 tests, 654 pass, 0 fail, 0 cancelled, 0 skipped, 0 todo**, exit 0,
duration `28657.650583 ms`.
These are Node terminal test counts; the legacy screensaver module also prints
its own internal assertions and counts as one Node test file.

A deliberate mutation was applied only in a separate process's
`readFileSync` result before the harness evaluated the actual controller.
Both domain replacements in `applyDomain` retained `d.controls` instead of
using the POST readback's controls. **2 tests failed / 0 passed**, exit 1, at the
exact assertion requiring only the master row after disable; the Advanced-on
case also exposed the stale advanced row. No production source was written.
This establishes sensitivity to stale-domain consumption, not sensitivity to
the Kotlin predicate bug.

No Gradle, device, network, GitHub, commit, or production-JS operation was run.
All Git reads used `git -c core.hooksPath=/dev/null --no-optional-locks`.

## Main integration checkpoint

At the initial source review checkpoint, a scoped Git diff showed no changes to
`SettingsDomains.kt` or `SettingsDomainTest.kt`. No generated real-schema
fixture had been supplied. The following independent follow-up supersedes that
pending source-review status. The JS guard remains a synthetic consumer fixture.

### Follow-up: actual Kotlin patch and parity tests

Final source/hash checkpoint: **2026-11-02 07:58:57 UTC**.
Reviewed the uncommitted patch against the same
`1ca6a54bae86ca4f593445835bf52ac364c1cc56` base and read the complete new
`ScreensaverVisibilityParityTest.kt`, its `ScreensaverTestPrefs` implementation,
native visibility preparation, config load/setters, spec/schema/apply code,
presentation policy and relevant routes. **No concrete implementation defect
was found in the reviewed change.** This follow-up changed only this document;
no extra tests, fixes, builds, device commands or API calls were performed.

The actual diff changes **24 control predicates**, rather than the 22 described
in the handoff: `shuffle`, `includeVideo`, `cacheEnabled`, `batterySaver`,
`showNowPlaying`, `presenceMode`, `idleSleepMin`, `overnightEnabled`,
`overnightStartMin`, `overnightEndMin`, `overnightNightClock`, `darkRoomClock`,
`darkRoomLux`, `nightClockBrightness`, `nightClockTone`, `sleepTimerEnabled`,
`sleepTimerMin`, `pauseAudioOnSleep`, `closeAppOnSleep`, `soundscape`,
`soundscapeVolume`, `ambientDashboard`, `gestureWave`, and `welcomeEnabled`.
The scoped production diff is 38 insertions / 24 deletions in
`SettingsDomains.kt`; the other inspected production files have no diff.

Every renderable screensaver child now either requires `s.enabled` or retains
an unconditional false visibility predicate. The master remains unconditionally
available. Derived fields still have no remote metadata; the three navigation
rows retain their previous master predicates and native-only treatment.

The cache gate correctly reads `s.enabled && (s.usesImmich || s.usesDav)`.
Both night-clock gates correctly group their full OR expression underneath
`s.enabled`. Battery detection, overnight/sleep timer children, dark-room
threshold and soundscape volume retain their original additional guards.
Preexisting style guards for `fit`, photo motion and people framing are
unchanged. Always-hidden album refresh, cache budget and anti-burn-in controls
remain hidden. With master enabled the old visibility behavior is preserved;
with master disabled the new predicates also stop the native Advanced hint
from treating hidden children as eligible.

Storage keys, getters, setters, constraints, enum coercion, defaults,
`onApplied`, flat legacy payloads, ordinary/POST schema routes, inactive
preflight and complete-profile export are unchanged. Visibility still does not
gate `applyFrom` or `applyValues`. The native preparation's own
`screensaverDisabled` row gate and both screensaver page master gates remain
unchanged. The separate Calendar domain is untouched, preserving its shared
Home feed and existing calendar-child conditions.

The four current test methods use real registered specs and real config
load/setters over a test-owned in-memory `SharedPreferences` implementation
with a mocked Context. This is meaningful JVM coverage, not Android-backed
storage or rendered Compose acceptance:

- The disabled test pins the remote schema and native projection to exactly
  `enabled`, checks the Advanced hint is absent, and checks no preference writes
  across seven configured sources and all nineteen styles.
- The matrix compares native row keys, excluding three native-only navigation
  rows, with tier-filtered schema keys across 532 master/source/style/tier
  combinations. The native preparation has its own master gate, so this
  comparison can expose the original missing remote master predicates.
- The inactive-write test checks three successful hidden writes, preservation
  of the disabled master, rejection of brightness 99 against max 15, and the
  saved values after enabling. It deliberately calls `applyValues`, so it does
  not exercise domain side effects or the HTTP transport.
- The calendar test pins the feed and widget toggle's availability while the
  photo frame is disabled.

Coverage limits: enabled-state source/style equality uses the same registered
predicates in both consumers, so it is not an independent oracle for every
conditional restriction. Nested feature switches are all seeded on, and the
mocked Context returns no battery Intent; neither independent off-branch
combinations nor the battery-present branch are exercised by this matrix.
The disabled test remains sensitive to missing master gates with those nested
features enabled. Source review, rather than additional execution here,
establishes that the old source/style/hardware expressions were preserved.

The export helper is now private and called at the start of the first test,
**before its assertions**. File presence therefore does not imply a passing
suite; export timestamps alone do not prove green provenance. The earlier XML
observed at the 07:58:57 checkpoint had timestamp
`2026-11-02T07:56:24.320Z`: **5 tests, 4 assertion failures, 0 errors**.
It was the red run, not execution of the current four-method source.
Its disabled JSON advertised 23 controls with master false; its enabled JSON
advertised 27. This historical artifact observation is superseded by the fresh
report/schema inspection below.

Main reports that the installed 1.67 baseline accepted Fleet bearer on
`GET /remote/settings?domain=screensaver` and returned 16 controls with master
false, followed by exact configuration restoration. This worker did not
independently observe that device/API operation or its restoration. Main owns
fresh JVM/full gates, provenance of corrected exports, APK/device acceptance,
deployment and restoration.

SHA-256 identities at the 07:58:57 follow-up checkpoint:

| File | SHA-256 |
| --- | --- |
| `app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt` | `4d110cd39ae80e5cc92180283edf5e5e19c62c3235f2a1ed4daccf619b40a045` |
| `app/src/test/java/com/immortal/launcher/settings/ScreensaverVisibilityParityTest.kt` | `43379c2eaac5d7aa444f79b4558283ed9a4565c1e78786714a133a471bd47514` |
| `app/src/test/java/com/immortal/launcher/ScreensaverTestPrefs.kt` | `fb9e3c546226ef72da6034eea38cfda76c4ee4c1b2d6874379add9f746363320` |
| `app/src/main/java/com/immortal/launcher/SettingsVisibilityPreparation.kt` | `75ab835407ebb97bb771005b5e41eb9f73dd9bf151c6ef9e07a1a85bd9950bdc` |
| `app/src/main/java/com/immortal/launcher/SettingsRenderer.kt` | `63afa6f10117b4f96b78a9c7395cb730f978d469f1397a854bd6fa532f74156e` |
| `app/src/main/java/com/immortal/launcher/settings/SettingSpec.kt` | `756ab777e09c9ae44362f0ec2484027eb5159a42cbc287549b876113265b1c85` |
| `app/src/main/java/com/immortal/launcher/ScreensaverConfig.kt` | `6738cb1378b5f160c0361608734c66d18bfbfde2a6a69ff6973ed4b0f7c96796` |
| `app/src/main/java/com/immortal/launcher/DreamPolicy.kt` | `8346c055940f4ce4a76e9f0885822e794f6d2ca0714ead258ddc51707094d42e` |
| Earlier red `TEST-com.immortal.launcher.settings.ScreensaverVisibilityParityTest.xml` | `91603dc00a8f6684f7bba6b5d415b541fef53cf27aeebbdc3287b4151c2c65d2` |
| Earlier red `screensaver-disabled.json` | `3b18966d84f93396b6baafe0784951a9c9f880181c16c09676bf02d4c3052628` |
| Earlier red `screensaver-enabled.json` | `622e2d9bd3fd2f22f71c413129ecb91ecdbcb5aea6ed438f9ea2cd614c0d9833` |

### Fresh report inspection and device-gate recommendations

At **2026-11-02 08:03:27 UTC**, independently read the newly written report and
exported JSON from the actual worktree's `app/build/` directory. The helper's
relative `build/reports/settings-visibility` path resolves under the app test
working directory. No Gradle or device operation was run by this worker.

The current XML has timestamp `2026-11-02T08:00:11.110Z` and records the four
reviewed test methods: **4 tests, 0 failures, 0 errors, 0 skipped**, suite time
`1.405 s`. The disabled export has modification time
`2026-11-02T08:00:12.490472Z` and contains exactly one control,
`enabled=false`. The enabled export has modification time
`2026-11-02T08:00:12.496013Z`, contains 27 controls and is byte-identical to
the earlier enabled export. The source and test hashes remain those reviewed
above. This is observation of coordinator-generated JVM artifacts; it is not
an independent JVM rerun or live HTTP verification. The JS regression still
uses its explicit synthetic server schema.

Main additionally reports that the authenticated mobile PWA at 390 × 844
showed eleven ordinary non-category rows while the installed 1.67 master was
off, confirming the visible baseline failure. Main reports UI reenable and
an exact comparison/restoration of all 36 API fields, using one existing
paired session without pairing or reconnecting. Main also reports full JVM
and debug-stage success, with optimized R8 still running at the handoff.
These device/build statements are attributed to main; this worker did not
observe those operations or verify the installed candidate.

**No source-review blocker was found for the coordinator's candidate device
gate.** Concrete acceptance recommendations:

1. On the installed candidate, check ordinary
   `GET /remote/settings?domain=screensaver` with master off returns only the
   master. In the existing mobile PWA session, check the screensaver category
   has one registry row with Advanced both off and on; exclude the category
   chooser and browser filter checkbox from the row count. Toggle off from
   an enabled view as well, so cached child rows must disappear after confirmed
   POST readback.
2. Reenable in that same session and check the user's saved values return,
   with Advanced filtering still applying. Compare the full 36-field
   configuration and restore it exactly after acceptance, as main did for the
   baseline. Continue using the existing paired session to cover refresh and
   readback behavior without introducing a separate pairing change.
3. In the native screensaver page, confirm disabled master leaves only its
   master/status presentation, with no child controls, embedded calendar list
   or Advanced hint; enabled rendering should preserve existing source/style
   eligibility. Check the separate PWA Calendar category remains available
   because its feed also supplies Home.
4. Retain separate results for
   `?domain=screensaver&includeInactive=1` and complete-profile capture, whose
   hidden metadata must remain available for validated writes. A reduced
   ordinary display schema must not be reported as removal of stored fields
   or write support. Main owns live API/profile and side-effect verification.

For future test hardening, independent expected-key assertions for source/style
restrictions and explicit battery-present/absent and nested-feature-off cases
would protect against both consumers sharing the same incorrect predicate.
This is a coverage recommendation, not a demonstrated defect or a condition
for accepting this reviewed master-gating patch. No additional tests or fixes
were added.

Fresh generated artifact SHA-256:

| File | SHA-256 |
| --- | --- |
| `app/build/test-results/testDebugUnitTest/TEST-com.immortal.launcher.settings.ScreensaverVisibilityParityTest.xml` | `3f7042f8d785f022fef04dd6d15a34b48f2955a36c5af72a16d78a3bd82b70dc` |
| `app/build/reports/settings-visibility/screensaver-disabled.json` | `a11e6db1a00a0f6620936ece54cd909c5185466c3c914b56f113804e4b75b817` |
| `app/build/reports/settings-visibility/screensaver-enabled.json` | `622e2d9bd3fd2f22f71c413129ecb91ecdbcb5aea6ed438f9ea2cd614c0d9833` |

## Inspected file hashes

SHA-256 at the initial independent review checkpoint (the later Kotlin patch
identity is recorded above):

| File | SHA-256 |
| --- | --- |
| `app/src/main/assets/remote/control-center.js` | `338adf028c4b43e5be07c46db02e14543b4c32584c9cf50e6f9513d565581ca8` |
| `app/src/main/assets/remote/core.mjs` | `d285bcac7c2595400d11726453e50a10a9840d715fc761512b3125a8d68487dc` |
| `app/src/test/js/remote-browser-harness.mjs` | `6b7a8b47143a6fcb5aa1a9130799329df01fb80f13ec91c3e13a0d2effaa6e7e` |
| `app/src/test/js/remote-settings-disabled-parent.test.mjs` | `4128dd1bf3b77ec8dfa887790b0ff6ce145ecef12dbfbd26e6b39c7114fd43f8` |
| `app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt` | `de366051ad676a3b8f555f828dda45fba334f72ba7364d344a62dc8d2d4f13a6` |
| `app/src/main/java/com/immortal/launcher/settings/SettingsDomain.kt` | `0893fa845f5849a71a3619f23b58cc1f5275777b36402cb737e0e71a41313fec` |
| `app/src/main/java/com/immortal/launcher/settings/SettingsPresentation.kt` | `6545c544dfce3a9263ebbeccfd3585889faf28a0c5a1b17b4504bbc4956473ef` |
| `app/src/main/java/com/immortal/launcher/RemoteRoutes.kt` | `912dcfc81b8c20f5f2d2e5c5730fbe9c70138c6dc4feca7256f16527ad2d1faa` |
| `app/src/main/java/com/immortal/launcher/ScreensaverSettingsActivity.kt` | `42a83209f5d2644d5f2513f1fc5a255086ca5bc22abb2219ddd5fbd008829875` |

## Recovered Android phone consumer compatibility

Source inspection began **2026-11-02 08:06:16 UTC** in
`~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit`,
HEAD `610899f7074323e24832caa9f0d52e3db8a7b5fd`.
Scoped Git status showed no changes to the inspected phone files. This
sidecar appended only this review document; no phone edits, tests, Gradle,
device or network commands were run. Prior review and verification results
above are preserved.

**Concrete compatibility issue: group child writes use display schema as their
validation schema.** In
`app/src/main/java/com/portalinstaller/data/companion/CompanionGroup.kt:253`,
each captured target fetches `client.get(session, "settings")` before writing.
`CompanionGroupSettings.validate`, at lines 62–65, requires each requested key
to be present in that ordinary response. The new server correctly omits
inactive screensaver children, so an edit of `shuffle`, for example, from an
enabled active Portal to a selected disabled Portal fails with
`Unsupported setting: screensaver/shuffle. Update this Portal and retry.`
before that target's POST. Enabled targets can still succeed, yielding a
partial group result. This affects the ordinary phone UI because
`ControlCenterViewModel.writeSettings` calls
`CompanionRepository.writeSelectedSettings`, which routes non-active-only
controls through this group preflight even for one selected target.

The same boundary rejects a combined `{enabled:true, shuffle:...}` patch for
a disabled target before POST: ordinary preflight sees only `enabled`, so the
client does not reach the server's supported inactive-write path. A queued
child edit after master disable, or a retry whose target has since disabled
its master, can similarly fail. These are source-demonstrated rejection paths;
they were not reproduced on a phone or executed in a test here.

**Expected working behavior:**

- Master enable/disable patches remain valid because `enabled` is always in
  the ordinary schema. Enabling selected rooms first, then editing children
  after refresh, remains supported.
- `RegistrySettingsPanel.kt` renders only controls in the supplied schema,
  using Compose keys and tier/search filters; `ControlCenterScreen.kt` keeps
  the screensaver category selectable because its everyday master remains.
  `CompanionRepository.merge("settings")` replaces the entire settings list
  and raw schema after GET. Group completion refreshes the captured active
  room and clears that target's cache. Child rows therefore disappear after
  successful refresh and return from the server after reenable; physical
  Compose rendering is unverified.
- The separate active-only `CompanionRepository.writeSettings` path directly
  posts, checks the `applied` keys, then refreshes settings. It does not require
  visible metadata, so this server change does not reject hidden writes at
  that direct boundary. Ordinary screensaver controls use the group path,
  however, so the direct path is not a general UI workaround.
- The group path reports each target's result and rejects missing metadata
  before sending; no silent child-value reset or data loss follows from the
  identified preflight issue. The phone relies on `applied` plus a subsequent
  GET rather than the PWA's direct domain-readback replacement.

**Complete profiles and snapshots:** an inspection of phone
`app/src/main/java` found no `settings/profile`, `profileComplete`,
`includeInactive`, or settings-profile capture/apply implementation.
`AppProfileStore.kt` stores app package names, and its associated app-profile
workflow is unrelated to the settings schema. Do not claim the recovered
phone supports the PWA's complete settings-profile feature.
Phone settings/layout export and restore instead call the separate `snapshot`
route in `CompanionRepository.kt`. On Portal,
`RemoteCompanionRoutes.kt::state` calls `RemoteSnapshotPolicy.values`, which
iterates safe registered specs without `visibleWhen`; validation and
`applyVerified` also operate on the registered specs. The predicate-only patch
does not narrow those snapshot values or remove inactive scalar restore
support. Snapshot credentials/private fields remain deliberately excluded.
No live export/restore acceptance is claimed.

**Recommendation:** record this group-preflight limitation in the companion
compatibility scope before describing the server change as fully compatible.
A phone follow-up should fetch the explicit per-domain
`includeInactive=1` schema inside each target's existing FIFO preflight,
retaining ordinary schema for display and preserving active-only/type/range
validation. `CompanionClient.kt:179` currently rejects query characters in its
generic route argument, so this requires a dedicated query-aware settings
reader rather than passing a query string to `get`. Until that consumer change,
enable all intended targets first, refresh, then edit their child controls.
This worker made no consumer fix and does not claim physical phone acceptance.

SHA-256 for the inspected files (phone paths relative to the phone checkout;
Portal paths relative to the Portal worktree):

| Checkout/file | SHA-256 |
| --- | --- |
| Phone `app/src/main/java/com/portalinstaller/data/companion/CompanionClient.kt` | `6c8fbe255e0db063d6db93391ef0b07524e499de142d2bc46f89a714732ed6cf` |
| Phone `app/src/main/java/com/portalinstaller/data/companion/CompanionGroup.kt` | `f1129bc9ec58e4ccaaa63729040149360883db5c6e66cea88371bc25a3d8ad20` |
| Phone `app/src/main/java/com/portalinstaller/data/companion/CompanionModels.kt` | `56c786484b9b532783315b4af995b5b2a0b1c4d60a444b46930ea7058d0f1f63` |
| Phone `app/src/main/java/com/portalinstaller/data/companion/CompanionRepository.kt` | `9b358b35b15703f595c5353e0116c822ec4205b578ede762eb8f9ef3d04251b7` |
| Phone `app/src/main/java/com/portalinstaller/ui/controlcenter/RegistrySettingsPanel.kt` | `554f0b4202254e5dec4d4d3e066bf4346b0368304f52a4fa222216b67132a242` |
| Phone `app/src/main/java/com/portalinstaller/ui/controlcenter/SettingsPageContent.kt` | `897cea52795b91c759259cf5603e0aabc4634df5383c6644a2ea074fd9d839d6` |
| Phone `app/src/main/java/com/portalinstaller/ui/controlcenter/ControlCenterScreen.kt` | `cd179c82f70a5c983ca3c2b3158a1cb03a9e6647f04b45a8e9d0f0e12ab2b422` |
| Phone `app/src/main/java/com/portalinstaller/viewmodel/ControlCenterViewModel.kt` | `1bcf04724e9ffbe0ad78e05f3b3cc2e8326cac49f745e4c2c51695cbb98c0956` |
| Phone `app/src/main/java/com/portalinstaller/data/store/AppProfileStore.kt` | `5f2d7e9c575bb6c10cc41551828041fc7c5d590e099886846c159c99449bf746` |
| Portal `app/src/main/java/com/immortal/launcher/RemoteCompanionRoutes.kt` | `3900b71f2e6af1dd41c71e368abe082baac404b1559cfa15783e94f2ef185618` |
| Portal `app/src/main/java/com/immortal/launcher/RemoteSnapshotPolicy.kt` | `d8d219e0100cf73c094bb40dce0dc3078d8b45d089c739a05112052f0209002f` |

## Authorized phone preflight fix — 08:12 UTC follow-up

The source-only finding above was subsequently fixed in the isolated phone
checkout, preserving the prior review as historical evidence. Commit
`d2ab33982af13693a8a984912e921cbf2f41802f` builds on
`610899f7074323e24832caa9f0d52e3db8a7b5fd`. Git hooks were disabled; no push
was performed. Coding and the focused gate completed within approximately
three minutes of the 08:09 UTC authorization; all gates and commit completed
by 08:13 UTC.

`CompanionClient.settingsForValidation` uses the existing safe URL builder
and authenticated JSON/error boundary to GET
`/remote/settings?domain=<encoded domain>&includeInactive=1`.
`CompanionGroupController` now calls it within each target's existing FIFO
preflight. Ordinary GETs used for display remain unchanged. Existing group
key/type/range/active-only validation and applied-key verification remain in
place. An older peer that omits requested inactive metadata fails preflight
without partially enabling the parent.

The new `CompanionInactiveSettingsTest.kt` exercises the production client
and group controller against an explicit fixture transport. Its seven tests
cover a disabled child write, a combined enable/hidden-child patch sent as
one POST, invalid hidden range without partial enable, unsupported keys,
active-only metadata rejection, preflight ordering inside the per-room FIFO,
and a peer omitting inactive metadata. Two client tests additionally verify
encoded query values, session authentication/error handling, and rejection
of a blank domain before transport. The existing group ledger fixture now
matches the parsed settings path to accommodate the query parameters.

Commands ran in the phone checkout with this common prefix:

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew \
  --offline --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

- Fail-first: `:app:testDebugUnitTest --tests com.portalinstaller.data.companion.CompanionInactiveSettingsTest --console=plain`.
  Session `60905`, exit 1, `BUILD FAILED in 42s`: 7 tests, 3 assertion failures,
  0 errors/skips. Disabled-child success, combined-patch success, and the
  active-only diagnostic failed against the original ordinary preflight.
  An earlier invocation failed during SDK discovery and supplied no test
  evidence; the explicit local SDK path corrected that invocation.
- Focused: `:app:testDebugUnitTest --tests com.portalinstaller.data.companion.CompanionInactiveSettingsTest --tests com.portalinstaller.data.companion.CompanionGroupTest --tests com.portalinstaller.data.companion.CompanionClientTest --console=plain`.
  Session `37176`, exit 0, `BUILD SUCCESSFUL in 45s`: respectively 7, 20,
  and 6 tests; total **33**, 0 failures/errors/skips. Fresh XML timestamps
  begin `2026-11-02T08:12:03`.
- Full: `:app:testDebugUnitTest :app:assembleDebug --console=plain`.
  Session `19953`, exit 0, `BUILD SUCCESSFUL in 14s`: **564 tests across
  74 suites**, 0 failures/errors/skips; debug APK built successfully.
  `git diff --check` also passed. Existing coroutine opt-in warnings came
  from the unchanged music repository/controller tests.

Terminal logs and preserved red/focused XML are under phone
`app/build/reports/settings-inactive/`. The full XML manifest is
`full-jvm-sha256.txt`; it records each report identity before later runs can
overwrite test results.

| Phone file/evidence | SHA-256 |
| --- | --- |
| `app/src/main/java/com/portalinstaller/data/companion/CompanionClient.kt` | `f7705e61156e53ac1d4c2004402dc4b6117e76ae7860be56106be42584472073` |
| `app/src/main/java/com/portalinstaller/data/companion/CompanionGroup.kt` | `208bb6813c6f26179d059e2775d9025d63b0a06a9ab7c3810988742809d8b22c` |
| `app/src/test/java/com/portalinstaller/data/companion/CompanionClientTest.kt` | `5af30ebcfbefed078f07b61d650e194b59b283fb2593c8c7ff4fafa974320856` |
| `app/src/test/java/com/portalinstaller/data/companion/CompanionGroupTest.kt` | `dc88bd5daf7bcf50cfcf9b6233e1ee7357d18d5a438f5a002d309cb35f0d85fc` |
| `app/src/test/java/com/portalinstaller/data/companion/CompanionInactiveSettingsTest.kt` | `6fdbda97e5560a8096fd3a5e58e1adba6ebadfa11660bdbf77404f3f703a563d` |
| `app/build/reports/settings-inactive/red.xml` | `a78e63abddbe8ebf3aca45b9b2a8b361053d7990a8ca3967a8936781fcfad0d0` |
| `app/build/reports/settings-inactive/focused.log` | `99fc7428904a6fc1c6eecd847d2ab86bc3aa74a44f93604b4330859471509c15` |
| `app/build/reports/settings-inactive/full-debug.log` | `bb5ad9fa89fad2b69306ed767ed74ebb9165a8718bfdac7927d7646509f0c39b` |
| `app/build/reports/settings-inactive/full-jvm-sha256.txt` | `8711e013208bf1c931b7102d5ad25facdb9db18be0bc8660da40f92b070a997b` |
| `app/build/outputs/apk/debug/app-debug.apk` | `e319fb1486e6ad06dfa4bb3418a469acc75ee815c1688ec1bafba06c4cec0d9b` |

Limits: these consumer gates use fixture responses and do not prove live
HTTP, real Portal acceptance, physical phone rendering, or phone deployment.
No device/network/deployment, version, or signing configuration changes
were made. Coordinator-reported Family native/API/browser success and
restoration are separate evidence, including ordinary-off membership of
one control, inactive metadata/complete profile of 33 controls, valid hidden
shuffle apply, and invalid brightness rejection; this worker did not
independently execute those checks.
