# Settings registry audit — 2026-11-02

Status: source inspected on `codex/widget-gallery-20261102`; native changes below are proposals
requiring user design approval. Scope is registry contracts and coverage, not Settings shell
navigation or the widget inventory.

**Verification:** the initial focused command failed before executing tests because its
environment omitted the existing SDK location. Re-running with
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` passed **98 tests across 9 suites**,
including all **26 `SettingsDomainTest` methods** and the existing `ApprovedSettings*Test`
suites. Zero failures, errors or skips. No SDK installation or build-configuration changes
were needed. The other named suites below were inspected, not executed in this pass.
This is JVM contract evidence, not native device or remote-renderer verification.

## Exact declaration counts

[SettingsDomains.all](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L1338)
registers **12 domains, 108 spec instances**. Counting constructor occurrences alone gives
106: `calendarPacks` expands one `BoolSpec` constructor over the three
[CalendarPacks.AVAILABLE](../../app/src/main/java/com/immortal/launcher/CalendarPacks.kt#L32)
entries (`romanian`, `irish`, `prayer`). These are source-derived instance counts, not a
measurement of controls currently visible on a Portal.

| Domain ID | Bool | Int | Enum | String | Derived | Nav | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `screensaver` | 18 | 10 | 5 | 0 | 3 | 3 | 39 |
| `calendar` | 1 | 1 | 2 | 1 | 5 | 0 | 10 |
| `immortal` | 4 | 1 | 5 | 5 | 0 | 0 | 15 |
| `mqtt` | 3 | 1 | 0 | 3 | 0 | 0 | 7 |
| `quickbar` | 2 | 0 | 0 | 0 | 0 | 0 | 2 |
| `fleet` | 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| `chime` | 4 | 4 | 0 | 1 | 0 | 0 | 9 |
| `digitalclock` | 3 | 0 | 8 | 0 | 0 | 0 | 11 |
| `welcome` | 4 | 1 | 0 | 0 | 0 | 0 | 5 |
| `sunrise` | 2 | 3 | 0 | 0 | 0 | 0 | 5 |
| `home_dashboard` | 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| `calendarpacks` | 3 | 0 | 0 | 0 | 0 | 0 | 3 |
| **Total** | **44** | **21** | **20** | **12** | **8** | **3** | **108** |

[SettingSpec.kt](../../app/src/main/java/com/immortal/launcher/settings/SettingSpec.kt)
defines these six concrete types; there is no `FloatSpec` or `GroupSpec`.
The first four types account for **97 writable scalars**. All eight current derived specs are
flat-payload-only (`render = false`): screensaver `source`, `folderPath`, `albumUrl`; calendar
`enabled`, `hasLink`, `provider`, `supported`, `ranges`. The three `NavSpec`s own no value,
write nothing and return null metadata, so they are absent from remote schemas even when
visible natively. `calendar.url` is different: a writable `StringSpec` with `Entry.Nav`, edited
via an Activity natively and a string field remotely.

## Visibility groups and their boundaries

There are **46 explicit parent/source/style-gated specs**, **one hardware capability gate**
and **11 always-false scalar predicates**. These counts include the three gated native NavSpecs,
exclude the eight non-rendering derived fields, and do not count the separate Advanced policy.

| Group | Actual predicate / affected keys | Source |
| --- | --- | --- |
| Calendar link and enablement | `hasCalendarLink` → `widgetOn`; `usesCalendar` → `range`, `size`, `side`. `url` remains available. | [calendar](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L80) |
| Screensaver master and style | `enabled` → `mode`, `intervalSec`, `clockFace`, `photoSource`, `dismissTarget`; motion also requires `supportsPhotoMotion(mode)`; people framing requires Smart/Portraits; `fit` additionally requires Classic. | [screensaver styles](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L207) |
| Photo-source capability | `usesImmich || usesDav` → `cacheEnabled`; no additional `enabled` predicate on this spec. | [cache](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L318) |
| Overnight, darkness, sleep | `overnightEnabled` → start/end/night-clock; `darkRoomClock` → lux threshold; darkness OR enabled overnight night-clock → brightness/tone; `sleepTimerEnabled` → duration/pause-audio/close-app. | [night/sleep](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L396) |
| Ambient audio | `soundscape != SOUND_OFF` → volume. | [soundscape](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L493) |
| Multi-room connection | `multiRoomEnabled` → `snapcastHost`, `maPort`, `maApiToken`, `maPlayerId`. Username/password are always hidden instead. | [immortal audio](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L652) |
| MQTT connection | `MqttConfig.isEnabled(c)` → host/port/username/password/TLS. These are live Context reads, not immutable snapshot predicates. | [mqtt](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L746) |
| Quick buttons | `enabled` → `alwaysShow`. | [quickbar](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L833) |
| Chime | `goldenHourOn` → sunrise variant; `quietHoursOn` → quiet start/end. Volume has no predicate, despite the nearby conditional-visibility comment. | [chime](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L920) |
| Sunrise | `enabled` → hour/minute/ramp/chime. | [sunrise](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L1017) |
| Digital clock | `enabled` → face/size/layout/background/date/seconds. | [digitalclock](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L1113) |
| Hardware | `DreamPolicy.hasBattery(c)` → screensaver `batterySaver`. `darkRoomClock` only describes its light-sensor requirement; it has no capability predicate. `DarkRoomObserver` declines activation without a sensor. | [battery](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L345), [dark clock](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt#L432), [observer](../../app/src/main/java/com/immortal/launcher/DarkRoomObserver.kt#L27) |

**Advanced is a separate presentation layer.**
[SettingsPresentation](../../app/src/main/java/com/immortal/launcher/settings/SettingsPresentation.kt#L13)
declares 64 control identifiers; **34 match registered specs**. The other identifiers address
bespoke forms/pages and should not all be treated as orphan registry keys. The registered groups are:

- `screensaver` (11): `cacheEnabled`, `batterySaver`, `presenceMode`, `idleSleepMin`,
  `closeAppOnSleep`, `ambientDashboard`, `gestureWave`, `albumRefreshMin`, `cacheBudgetGb`,
  `antiBurnIn`, `darkRoomLux`.
- `immortal` (8): `hideStatusBar`, `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`,
  `maPassword`, `maApiToken`, `maPlayerId`.
- `mqtt` (7): all its specs.
- `digitalclock` (4): `style`, `color`, `font`, `glow`.
- `welcome` (1): `durationMs`; `calendarpacks` (3): all its specs.

The server tags these controls/options with `tier`; it does not remove them on the basis of the
Advanced flag. Native
[prepareSettingsVisibility](../../app/src/main/java/com/immortal/launcher/SettingsVisibilityPreparation.kt#L24)
and remote
[visibleControls](../../app/src/main/assets/remote/core.mjs#L594)
apply their own Advanced filtering. Both preserve a selected legacy slideshow style when Advanced
is off: native `visibleEnumOptions` and remote `renderSetting` use that exception.

**Native adds a master gate that the schema lacks.** For a disabled screensaver,
`prepareSettingsVisibility` permits only `enabled`; the real screen also conditionally prepares
its child list. `SettingsDomain.schemaJson` applies only each spec's `visibleWhen`.
Consequently scalar specs with default visibility, such as `shuffle`, `includeVideo`,
`showNowPlaying`, `overnightEnabled` and `darkRoomClock`, can remain in the ordinary remote
schema while native child rows disappear. The slideshow parity test covers four selected specs,
not this complete domain difference. This is source-proven behavior, not a reproduced UI defect.

Visibility does not restrict writes:
[SettingsDomain.applyValues](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomain.kt#L105)
iterates all specs regardless of visibility or tier. Hidden values remain writable through
validated API requests. This permits retaining/configuring dormant settings; presentation and
apply eligibility must not be conflated in a corrective change.

## Explicit exclusions

**Always hidden from ordinary generic schemas and rows (11 writable specs):**
`screensaver.{albumRefreshMin,cacheBudgetGb,antiBurnIn}`,
`immortal.{maUsername,maPassword}`, `mqtt.validateCert`, `chime.spokenVoice`,
`digitalclock.{style,color,font,glow}`. Advanced does not override `visible = false`.
Native bespoke editors may still own presentation, e.g. the spoken-voice chooser calls the
chime domain's writer. Comments saying that the phone remote “can still tune” some of these
do not establish generic UI reachability: normal GET omits them. Explicit
`includeInactive=1` schema requests expose their metadata for validation; the route requires
a domain for that mode. See
[schemaJson](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomain.kt#L49) and
[RemoteRoutes.settings](../../app/src/main/java/com/immortal/launcher/RemoteRoutes.kt#L466).

**Snapshot field exclusions in `SettingsDomainTest` (37 fields):**

| Test owner | Exact `managedElsewhere` entries | Source |
| --- | --- | --- |
| Screensaver (23) | `immichUrl`, `immichKey`, `immichAlbumId`, `immichAlbumName`; `smbHost`, `smbShare`, `smbPath`, `smbUser`, `smbPass`; `davUrl`, `davUser`, `davPass`, `webUrl`; `facesEnabled`, `faceId`, `faceSizeIndex`; `dismissAppComponent`, `dismissHaDashboard`; `calendarUrl`, `calendarEnabled`, `calendarRange`, `calendarSize`, `calendarSide`. | [screensaver tripwire](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt#L328) |
| Immortal (1) | `constrainPageWidth`: retained dead storage field. | [immortal tripwire](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt#L359) |
| Welcome (12) | `greetingNight`, `greetingMorning`, `greetingAfternoon`, `greetingEvening`, `userName`, `greetingColor`, `clockColor`, `dateColor`, `backgroundOpacity`, `textScale`, `greetingLetterSpacing`, `ttsVoice`. | [welcome tripwire](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt#L439) |
| Sunrise (1) | `days`: bespoke Set-of-days picker. | [sunrise tripwire](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt#L480) |

Screensaver wire keys `photo_motion` / `keep_people_in_frame` are mapped to snapshot fields;
calendar's differently named fields are excluded rather than checked via a binding map.
Digital clock additionally subtracts synthetic spec `face` before field equality.

**Native exclusion sets mirrored by the inline-string test:** screensaver `{enabled}`;
immortal `{multiRoomEnabled,snapcastHost,maPort,maUsername,maPassword,maApiToken,maPlayerId}`;
chime `{spokenVoice}`; calendar/digitalclock/welcome/sunrise have empty sets.
This is a hand-maintained test list, not a discovery of all native consumers.

**Whole-config exceptions (11):** `CameraConfig`, `TimerConfig`, `CountdownConfig`, `NotesConfig`,
`AlarmConfig`, `CryptoConfig`, `NewsConfig`, `StocksConfig`, `AlpacaConfig`, `OptionsScanConfig`,
`HaConfig`. The global
[config tripwire](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt#L535)
classifies 20 direct `*Config.kt` files: nine registered + eleven excepted. It does not inspect
arbitrary preference setters, stores or subdirectories. For example,
`ImmortalSettings.setWorldClockZones` writes a preference outside its reflected `Settings`
snapshot. `TodoStore`, `TimerStore` and `HomeWidgetStore` are outside the filename gate.
Thus “all persisted settings are covered” is stronger than these tests establish.

## Tests: established contracts and gaps

| Inspected tests | What their assertions cover | What they do not establish |
| --- | --- | --- |
| [`SettingsDomainTest`](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt) (26 methods) | Generic partial apply, aliases, strict types/ranges, wrapping exception, enum coerce/skip, string guard, secret masking/blank preservation, hook once per batch; schema sections/defaults; real slideshow setters/styles; field/config tripwires. | Execution in this audit; actual Android effects, every real domain's hook, complete render parity, editor reachability. Most generic cases use recording setters. |
| `slideshowControls_gateIdenticallyForDeviceAndRemote_withoutClearingStoredChoices` | Remote schema vs direct `visibleWhen` for selected motion/framing/fit controls across styles. | It does not invoke native `prepareSettingsVisibility`, compose the screen or compare the full screensaver domain. |
| `onDeviceRenderedDomains_haveNoUnrenderableInlineStrings` | Seven hardcoded domain/exclusion lists contain no remaining inline strings. | Its premise is stale: current `SettingControl` sends inline strings to `ApprovedStringSetting`. It omits fleet/home-dashboard/quickbar/calendarpacks and does not validate the actual consumer lists. |
| [`SettingsVisibilityPreparationTest`](../../app/src/test/java/com/immortal/launcher/SettingsVisibilityPreparationTest.kt) | Native projection rules, Advanced hints, exclusions, disabled screensaver handling and predicate call counts using fixtures. | Comparison with actual registered domains' remote schemas. Existing tests deliberately lock the native master gate. |
| `SettingsPresentationTest`, `SlideshowSettingsPresentationTest`, `ClockTimeDomainsTest`, `NightClockSettingsTest` | Tier/legacy selection, time metadata and selected conditional controls. | Capability availability and complete parent combinations across all twelve domains. |
| [`SettingsSchemaRequestTest`](../../app/src/test/java/com/immortal/launcher/settings/SettingsSchemaRequestTest.kt), [`InactiveSettingsSchemaTest`](../../app/src/test/java/com/immortal/launcher/settings/InactiveSettingsSchemaTest.kt) | Request-scoped shared snapshots, fresh reads, hidden validation metadata, masking, readonly/default/section handling. | MQTT's live Context getters becoming a coherent immutable snapshot; real HTTP/Compose end-to-end behavior. |
| [`HomeDashboardDomainTest`](../../app/src/test/java/com/immortal/launcher/settings/HomeDashboardDomainTest.kt), [`CalendarPackDomainTest`](../../app/src/test/java/com/immortal/launcher/settings/CalendarPackDomainTest.kt), [`FleetSettingsTest`](../../app/src/test/java/com/immortal/launcher/settings/FleetSettingsTest.kt) | New-domain membership, fields, validation and bindings; pack expansion/tiers. | Native presentation reachability or a single generic parity contract. These suites were inspected, not included in the attempted focused run. |
| JS [`remote-settings-search-context.test.mjs`](../../app/src/test/js/remote-settings-search-context.test.mjs) and time-of-day tests | Remote filtering/time contracts against fixtures. | They are separate from Kotlin registry declarations; they do not compare a real schema with native projection. Not run here. |

## Bounded architecture corrections

1. **Specify the disabled-parent parity contract first.** Choose which settings should remain
   editable while a feature is off. Then share a pure eligibility policy between schema generation
   and native preparation, with explicit exceptions for independently useful shared settings.
   Add a matrix test using registered specs, the native projection and remote tier filtering:
   enabled/disabled × Advanced on/off × source/style. Preserve stored inactive choices.

2. **Represent consumer exposure separately from validity.** Replace ambiguous always-false
   visibility/comments with explicit generic-native / generic-remote / bespoke-editor exposure
   where needed. Start with the eleven listed controls and document the intended owner; leave
   persistence/setters intact. `NavSpec` is intentionally native-only, so remote parity needs an
   explicit existing owner link rather than pretending `metaJson` emits a remote control.

3. **Modernize the coverage tests narrowly.** Replace the obsolete inline-string prohibition
   with a supported-editor contract that exercises current `SettingControl` capabilities.
   Check unique domain IDs/spec keys, and map each snapshot field to a writable spec, derived
   representation or named editor exemption. In particular, verify calendar wire-field mappings
   instead of merely subtracting them. Keep whole-config exceptions with reasons; add coverage
   for user-facing preferences outside aggregate snapshots when those owners change.

4. **Make capability requirements inspectable.** Add shared capability metadata or predicates
   for light-sensor-dependent settings, using a cheap supplied capability snapshot. Either hide
   unsupported sensor controls or show an explanatory unavailable state after design approval;
   preserve scheduled night-clock appearance controls on sensorless hardware. Test battery and
   sensor combinations without probing hardware separately for every row.

5. **Give MQTT an immutable snapshot before generic native rendering.** Reuse a snapshot in
   schema generation too, so enabled/values come from one read rather than live Context getters.
   Keep the current bespoke connection screen until that contract exists. Preserve explicit
   batching and the existing `onApplied` TLS/port/service synchronization.

6. **Keep validation semantics explicit and repair stale comments.** `IntSpec.step` is a UI hint,
   not a divisibility constraint; wrapping specs bypass bounds rejection and defer normalization.
   Calendar enums deliberately normalize through setters, while other enums reject unknown
   values through `coerce`. Test/document those policies rather than imposing a blanket rule.
   `SettingsDomains` still describes a 16-key screensaver slice and future apply migration,
   although it now has 39 specs, `onApplied`, and `FleetScreensaver.apply` calls `applyValues`.
   Update those comments in a separately authorized code change.

Evidence with source hashes, expanded counts, hidden/Advanced keys and both the initial failed attempt and passing JVM rerun:
[settings-registry-audit.json](../../mocks/portal-widget-gallery-20261102/evidence/settings-registry-audit.json).
No production, test or build configuration files were edited by this audit.
