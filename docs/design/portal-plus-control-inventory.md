# Portal+ approved settings/control inventory

Source read: **2026-10-05**, branch `codex/portal-plus-approved-ux`, HEAD
`dfadc3bd463340a2bb941d578dcc2b42c0163911`, including the working-tree source
present during this read. This document owns no app, mock or device changes.
Concurrent widget work is outside its implementation ownership.

This is the source half of [quality gate G0](portal-plus-quality-gates.md#g0-build-the-interaction-census-before-claiming-all).
**Every runtime result here is PENDING. No physical controls were exercised.**
Main must reconcile these definitions with the installed artifact and current UI.
The [native validation report](portal-plus-native-validation.md) contains separate,
artifact-scoped evidence; its reported passes are not inherited by this inventory.

## Denominators and reading rules

| Source denominator | Count | Meaning |
|---|---:|---|
| `ApprovedSettingsPages` | 53 | Unique approved page IDs, with exact declared titles below |
| Routes whose `activity == null` | 36 | Settings-host local bodies; includes the separately supplied screensaver lazy body |
| Routes with an explicit `activity` | 17 | Activity handoffs; multiple IDs can target the same Activity |
| Registry domains | 10 | `SettingsRegistry.domains == SettingsDomains.all` |
| Registry entries | 95 | 83 writable scalar specs, 4 navigation specs, 8 derived fields |
| Scalar breakdown | 83 | 38 Bool, 19 Int, 18 Enum, 8 String |
| Managed read-only audit fields | 7 | Subset of the 83 scalar specs; not seven extra settings |
| Supplemental native search IDs | 76 | Search candidates, not 76 proven native controls or additional persisted settings |
| Additional action-family handles | 60 | Document-only references; expand each member/collection instance into its own G0 row |
| Runtime unknown buckets | 6 | U1–U6 below; dynamic row/state/action totals remain unknown |

`L/` below means `app/src/main/java/com/immortal/launcher/`; `S/` means
`L/settings/`. File and symbol references are repository-relative source facts.
Registry IDs are exact `domain.key` values. Supplemental IDs are exact
`ApprovedNativeSettingsFields` IDs. `A01`–`A60` are **document-only action-family
handles**, not app semantics IDs; expand each member/dynamic row separately for G0.
Counts of definitions cannot be summed into a physical interaction denominator:
some fields alias another control, and collections generate multiple controls.

Each route row supplies entry, owner and body; each registry/native field row supplies
identifier, prerequisites and commit rule; the action table supplies dialogs,
destinations and return boundaries. Join them by page/ID for a G0 row. Main adds
entry variant, actual label/semantics/bounds, theme, before/after fingerprint,
artifact hash, evidence ID and PASS/FAIL/PENDING/N/A. No source predicate is a
hardware observation; N/A requires actual hardware evidence and its source.

## Shared navigation, rendering and state rules

| Source | Concrete source contract |
|---|---|
| `L/ApprovedSettingsNavigation.kt`: `rememberApprovedSettingsNavigator`, `approvedSettingsIntent` | Local navigator is used only when page has no explicit Activity (or category overview). Other pages launch their declared Activity. Null-Activity intents target `ImmortalSettingsActivity` with CLEAR_TOP/SINGLE_TOP. `standaloneActivity` identifies wrappers/NavSpecs, not an Activity handoff in the main host. |
| `L/ApprovedSettingsHistory.kt`: `navigate`, `back` | Category navigation resets history; repeated same detail replaces its control request; other detail pushes up to 32 entries. Back pops, then returns to current category overview. |
| `L/ApprovedSettingsShell.kt`: `ApprovedSettingsDetailPanes`, `ApprovedSettingsDetailBody` | At most four retained scalar panes; resource/editor bodies are not retained inactive when they have a standalone wrapper. Advanced page gate is applied in the shell. Gate off/on, >4-pane revisit and recreation need independent runtime rows. |
| `L/SettingsScaffold.kt`, `ApprovedSettingsNavigation.rememberApprovedSettingsBack` | Standalone Back callback typically finishes its Activity; local editor callbacks use the provided Settings history Back. An Activity return can differ from source flows' explicit return-to-screensaver. |
| `L/SettingsRenderer.kt`: `prepareSettingsList` | Excludes caller keys and all DerivedSpecs; requires spec `visibleWhen`, then Advanced tier. Additionally every screensaver-domain generic row except `enabled` requires `snapshot.enabled`. This renderer predicate is additional to the registry predicate table. |
| `L/SettingsRenderer.kt`: `SettingControl` | Bool commits through callback on toggle; Enum uses `PickerRow`; Int opens a draft dialog; inline String opens a draft dialog. `Entry.Nav` and NavSpec resolve the **first** matching `standaloneActivity` in the approved list. |
| `L/SettingsRenderer.kt`: `ApprovedIntSetting`, `ApprovedStringSetting` | Numeric Save validates integer/bounds, normalizes wrapping values; +/- edits only the dialog draft. String Save checks `applyWhen`; blank secret closes without overwriting. Cancel/close/dismiss drops draft. These rules do not replace bespoke-body rules. |
| `L/SettingsComponents.kt`: `PickerRow` | `ApprovedSettingsDialog` with a lazy list of options. Selecting an option closes and invokes callback immediately; dismiss closes without selection. It is not a DropdownMenu despite the older comment. |
| `S/SettingSpec.kt`, `SettingsDomain.kt`: `applyFrom`, `apply` | Apply is synchronized per domain and calls `onApplied` once if keys applied. Bool/Int reject invalid parses; non-wrapping Int checks range, not step alignment. Enum coercion is spec-specific. Secret blank means keep stored. Derived/Nav writes always return false. Visibility/tier is presentation policy, not an apply authorization guard. |
| `L/ApprovedSettingsSearch.kt`: `approvedSettingsSearchIndex`, `approvedSettingsPrerequisite` | Registry titles plus 76 supplemental names; search returns at most 12 matches. Managed controls route to `managed`. Hidden dependent specs stay searchable with a prerequisite message; native-only IDs have no registry prerequisite check. |
| `L/ApprovedSettingsShell.kt`: `openSearch`, `applyAdvanced`; `L/ApprovedSettingsRows.kt`: consent | Advanced search waits for “Turn on Advanced settings”; Cancel drops pending destination. Disabling Advanced applies the flag without clearing feature settings. Search prerequisite dialog's “Review parent settings” only dismisses the dialog; it does not navigate to the parent. |
| `L/ApprovedSourcesUi.kt`: `ApprovedSourcesWork`, `approvedSourcesReturn` | Work cancels on STOP/dispose or Advanced loss; request generation rejects stale replies. Confirmed source flows explicitly open `screensaver` (calendar flow: `calendar`) with CLEAR_TOP/SINGLE_TOP, then finish. This is not ordinary Back. |
| `L/ApprovedEditorsUi.kt`: `ApprovedEditorsNumber`, `ApprovedEditorsRemoveDialog`, `ApprovedEditorsField` | Number Save updates the containing form draft, whose Add/Save still determines persistence. Remove requires a separate dialog confirmation. Secret form fields expose Show/Hide. Editor drafts use `remember`, not saved-state credential restoration. |
| `L/DreamPolicy.kt`: `hasBattery` | Uses sticky ACTION_BATTERY_CHANGED / EXTRA_PRESENT (default false), guarded by runCatching. No device/model-specific result is recorded here. |
| `L/DisplayControl.kt`, `L/SystemSounds.kt`: `canWrite`, `requestWriteAccess` | API≥23 uses Settings.System.canWrite; grant destination is ACTION_MANAGE_WRITE_SETTINGS with package URI/NEW_TASK. Display media volume needs no special permission. Night Shift is an Immortal-drawn warm layer; its fields are local prefs, not a panel color-temperature API. |

### Category entry variants

Exact sidebar/category declarations in `ApprovedSettingsCategories`:

| Category ID / title | Declared overview entries in order |
|---|---|
| `appearance` / Appearance | `display`, `appearance`, `wallpaper` |
| `photos` / Photos & screensaver | `screensaver`, `faces`, `clock`, `sources` |
| `home` / Home & widgets | `widgets`, `weather`, `calendar`, `alarm`, `quickbar` |
| `sound` / Sound & wake | `touch`, `chime`, `welcome`, `sunrise`, `alarm`, `lamp` |
| `connections` / Connections | `ha`, `phone`, `intercom` |
| `access` / Input & accessibility | `touch`, `system`, `quickbar` |
| `device` / Device | `device`, `sleep`, `health`, `help`, `system` |
| `advanced` / Advanced | `immich`, `albums`, `smb`, `dav`, `web`, `multiroom`, `mqtt`, `boot`, `alpaca`, `options`, `speed`, `apk`, `managed`, `debug` |

Overview rows pass the chosen category explicitly to `approvedSettingsRoute`.
Direct/search/NavSpec intents derive category using `approvedSettingsCategory`,
which overrides 14 technical pages to `advanced`. Thus shared entries
(`touch`, `alarm`, `quickbar`, `system`) require each parent entry variant.
Advanced overview groups technical pages under their **declared** categories.

## All 53 approved routes

| Route ID / exact declared title | Declared → direct category | Entry variants | Navigation owner; body source | Additional source predicates | Control family / action handles |
| --- | --- | --- | --- | --- | --- |
| `touch` / Touch sounds | `sound` → `sound` | sound/access overview; display.touchSounds search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedTouchSounds` | WRITE_SETTINGS missing → permission handoff | display.touchSounds; A58 |
| `display` / Display | `appearance` → `appearance` | appearance overview/search | Settings local; `L/ImmortalSettingsActivity.kt: DisplaySection` | Snapshot loaded; canWrite for brightness/auto; nightShift.enabled for times/warmth | display.*; A01–A04 |
| `appearance` / App appearance | `appearance` → `appearance` | appearance overview/search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedNativeSettingsPage` | hideStatusBar A | immortal settingsTheme/tileSize/showMiniPlayer/hideStatusBar/clockFormat |
| `wallpaper` / Wallpaper | `appearance` → `appearance` | appearance overview/search | Settings local; `L/ImmortalSettingsActivity.kt: WallpaperSection` | None in body | wallpaper.* |
| `screensaver` / Photos & screensaver | `photos` → `photos` | photos overview; source confirmation; widget Photo memory/search | Settings local (wrapper: ScreensaverSettingsActivity); `L/ScreensaverSettingsActivity.kt: screensaverSettingsDetail` | enabled gates all except master | screensaver.* + calendar.*; A05 |
| `sources` / Photo sources | `photos` → `photos` | photos overview; screensaver.photoSource; search | Settings local (wrapper: ScreensaverSourcesActivity); `L/ScreensaverSourcesActivity.kt: ScreensaverSourcesContent` | Technical connections A; Refresh album A+usesUrl; no body enabled gate | sources.*; A09–A11 |
| `styles` / Screensaver style | `photos` → `photos` | screensaver.screensaverStyle/search | Settings local (wrapper: ScreensaverModeActivity); `L/ScreensaverModeActivity.kt: ScreensaverModeContent` | NavSpec parent enabled; no body enabled gate | screensaver.mode; A06 |
| `faces` / Photo clock faces | `photos` → `photos` | photos overview; screensaver.clockFace/search | Settings local (wrapper: FacePickerActivity); `L/FacePickerActivity.kt: FacePickerContent` | facesEnabled gates face/size; sizes nonempty; showNowPlaying generic requires screensaver.enabled | faces.* + immortal.clockFormat + screensaver.showNowPlaying; A07 |
| `clock` / Digital clock | `photos` → `photos` | photos overview/search | Settings local (wrapper: ClockSettingsActivity); `L/ClockSettingsActivity.kt: ClockSettingsContent` | digitalclock.enabled child predicates | digitalclock.*; A08 |
| `dismiss` / Open when you tap to exit | `photos` → `photos` | screensaver.dismissTarget/search | ScreensaverDismissAppActivity; `L/ScreensaverDismissAppActivity.kt: DismissAppScreen` | Installed HA for HA choice; HA selected+installed+A for path | dismiss.*; A22–A24 |
| `folder` / Choose photo folder | `photos` → `photos` | sources local tile/current source/search | FolderPickerActivity; `L/FolderPickerActivity.kt: FolderPicker` | READ_EXTERNAL_STORAGE; path selected+accessible for confirm | folder.path; A12–A14 |
| `album` / Shared album link | `photos` → `photos` | sources album tile/current source/search | AlbumUrlEntryActivity; `L/AlbumUrlEntryActivity.kt: onCreate → UrlEntryScreen` | RemoteAlbum.isSupported | album.url; A15 |
| `immich` / Connect Immich | `photos` → `advanced` | Advanced overview; sources/search | ImmichConnectActivity; `L/ImmichConnectActivity.kt: ImmichConnectScreen` | A; URL/key nonblank for test | immich.url/key; A17 |
| `albums` / Choose Immich album | `photos` → `advanced` | Advanced overview; Immich successful test/search | ImmichConnectActivity; `L/ImmichConnectActivity.kt: ImmichConnectScreen(requestAlbums=true)` | A; verified connection for selection/commit | immich.album; A18–A19 |
| `smb` / Connect SMB / NAS | `photos` → `advanced` | Advanced overview; sources/search | SmbConnectActivity; `L/SmbConnectActivity.kt: SmbConnectScreen` | A; host/share nonblank; >0 images for commit | smb.*; A20 |
| `dav` / Connect WebDAV | `photos` → `advanced` | Advanced overview; sources/search | DavConnectActivity; `L/DavConnectActivity.kt: DavConnectScreen` | A; URL nonblank; >0 images for commit | dav.*; A21 |
| `web` / Web page | `photos` → `advanced` | Advanced overview; sources/search | WebUrlEntryActivity; `L/WebUrlEntryActivity.kt: onCreate → UrlEntryScreen` | A; literal http:// or https:// prefix | web.url; A15 |
| `widgets` / Home & widgets | `home` → `home` | home overview/search; Home library entry | HomeActivity; `L/HomeActivity.kt: consumeWidgetLibraryIntent → ApprovedWidgetLibrary` | Library hides KIND_OPTIONS without A; installed provider inventory | Actual library handoff; A59–A60 |
| `cameras` / Cameras | `home` → `home` | Tools/direct/search | CameraViewerActivity; `L/CameraViewerActivity.kt: CameraViewerScreen` | RTSP predicate; matching stream ID for player branch | camera.*; A52–A53 |
| `weather` / Weather | `home` → `home` | home overview/widget/search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedNativeSettingsPage` | None in specs | immortal.weatherUnit/weatherWidget |
| `calendar` / Calendar | `home` → `home` | home overview; Today widget; URL confirm/search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedNativeSettingsPage` | hasCalendarLink/usesCalendar | calendar.*; second entry on screensaver |
| `calendar-url` / Calendar feed | `home` → `home` | calendar.url nav on calendar/screensaver/search | CalendarUrlEntryActivity; `L/CalendarUrlEntryActivity.kt: onCreate → UrlEntryScreen` | CalendarFeed.isSupported; nonblank existing link for removal | calendar.url; A15–A16 |
| `world` / World clock | `home` → `home` | widget Settings/direct wrapper/search | Settings local (wrapper: WorldClockActivity); `L/ImmortalSettingsActivity.kt: WorldClockContent` | Fixed 13-city list; first four selected shown on widget | world.city mismatch; ImmortalSettings.worldClockZones |
| `countdowns` / Countdowns | `home` → `home` | Tools shared body/direct wrapper/search | Settings local (wrapper: CountdownSettingsActivity); `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | year field iff !yearly; removal requires existing event | countdowns.*; A32 |
| `quickbar` / Quick buttons | `home` → `home` | home/access overview; Quick controls widget/search | Settings local; `L/ImmortalSettingsActivity.kt: QuickButtonsSection` | alwaysShow iff enabled; accessibility prerequisite is footer | quickbar.* |
| `alarm` / Alarm | `sound` → `sound` | home/sound overview; Tools/widget/direct/search | Settings local (wrapper: AlarmSettingsActivity); `L/AlarmSettingsActivity.kt: AlarmContent` | Body renders all fields regardless of enabled | alarm.*; A31 |
| `crypto` / Crypto | `home` → `home` | widget Settings/direct wrapper/search | Settings local (wrapper: CryptoSettingsActivity); `L/CryptoSettingsActivity.kt: CryptoSettingsContent` | Existing choices disabled; collection rows if present | crypto.*; A33 |
| `todo` / To-do | `home` → `home` | widget Settings/direct wrapper/search | Settings local (wrapper: TodoSettingsActivity); `L/TodoSettingsActivity.kt: TodoSettingsContent` | Clear completed iff any done item | todo.item; A34 |
| `news` / News | `home` → `home` | widget Settings/direct wrapper/search | Settings local (wrapper: NewsSettingsActivity); `L/NewsSettingsActivity.kt: NewsSettingsContent` | Existing choices disabled; HTTP(S)+host for add | news.*; A35 |
| `stocks` / Stocks | `home` → `home` | widget Settings/direct wrapper/search | Settings local (wrapper: StocksSettingsActivity); `L/StocksSettingsActivity.kt: StocksSettingsContent` | Alpaca nav iff A; selected popular tickers disabled | stocks.symbol; A36 |
| `alpaca` / Alpaca account | `home` → `advanced` | Advanced overview; Stocks/Options account nav/search | Settings local (wrapper: StocksSettingsActivity); `L/ApprovedEditorsAlpaca.kt: ApprovedEditorsAlpacaForm` | A; effective key+secret required | alpaca.*; A37 |
| `options` / Options scanner | `home` → `advanced` | Advanced overview; widget/direct/search | Settings local (wrapper: OptionsSettingsActivity); `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; delta 1–99 hundredths; expiry 1–365 | options.*; A38–A39 |
| `chime` / Sounds | `sound` → `sound` | sound overview/direct wrapper/search | Settings local (wrapper: ChimeSettingsActivity); `L/ChimeSettingsActivity.kt: ChimeSettingsContent` | goldenHourOn/quietHoursOn specs; bespoke sound predicates | chime.*; A25–A26 |
| `welcome` / Welcome greeting | `sound` → `sound` | sound overview/direct wrapper/search | Settings local (wrapper: WelcomeSettingsActivity); `L/WelcomeSettingsActivity.kt: WelcomeSettingsContent` | Voice A+enableTts; greetings/duration/opacity A | welcome.*; A27–A28 |
| `sunrise` / Sunrise & wake | `sound` → `sound` | sound overview/direct wrapper/search | Settings local (wrapper: SunriseSettingsActivity); `L/SunriseSettingsActivity.kt: SunriseContent` | Native children ungated despite enabled registry predicates | sunrise.*; A29 |
| `lamp` / Lamp | `sound` → `sound` | sound overview; Tools/direct/search | LampActivity; `L/LampActivity.kt: LampSettings / LampPreview` | Warmth iff !red; preview extra for fullscreen branch | lamp.*; A54–A55 |
| `multiroom` / Multi-room audio | `connections` → `advanced` | Advanced overview/direct wrapper/search | Settings local (wrapper: MultiRoomActivity); `L/ImmortalSettingsActivity.kt: MultiRoomContent` | A; multiRoomEnabled for connection form | immortal multiroom fields; A46 |
| `mqtt` / MQTT publishing | `connections` → `advanced` | Advanced overview/direct wrapper/search | Settings local (wrapper: MqttActivity); `L/ImmortalSettingsActivity.kt: MqttContent` | A; mqtt.enabled for connection form | mqtt.*; A47 |
| `ha` / Home Assistant controls | `connections` → `connections` | connections overview; widget/direct/search | Settings local (wrapper: HaSettingsActivity); `L/HaSettingsActivity.kt: HaSettingsContent → ApprovedEditorsHaContent(false)` | HTTP(S)+host and effective token; busy disables fields | ha.url/token; A40–A41 |
| `ha-entities` / Choose Home Assistant entities | `connections` → `connections` | ha save success/more options/search | HaSettingsActivity; `L/HaSettingsActivity.kt: ApprovedEditorsHaContent(true)` | HaConfig.isConfigured for discovery; nonempty discovered for search | ha.search; A42–A43 |
| `ha-dashboard` / Home Assistant | `connections` → `connections` | ha more options/direct/search | HaDashboardActivity; `L/HaDashboardActivity.kt: HaDashboardScreen` | HaConfig.isConfigured; dynamic rooms/entities | Dynamic live controls; A44–A45 |
| `phone` / Phone remote | `connections` → `connections` | connections overview; sources phone tile/search | RemotePairActivity; `L/RemotePairActivity.kt: RemotePairScreen` | LAN address for QR; resume enables remote | Pairing actions/information; A50 |
| `intercom` / Intercom | `connections` → `connections` | connections overview; Tools/direct wrapper/search | Settings local (wrapper: IntercomActivity); `L/IntercomActivity.kt: IntercomContent` | Microphone permission to broadcast; active session checks | intercom.address; A51 |
| `boot` / Start on boot | `device` → `advanced` | Advanced overview/direct wrapper/search | Settings local (wrapper: BootAppsActivity); `L/ImmortalSettingsActivity.kt: BootAppsContent` | A; installed launchable apps | BootLaunch package selections; A48 |
| `device` / Device name | `device` → `device` | device overview/search | Settings local; `L/ImmortalSettingsActivity.kt: DeviceNameSection` | Name trimmed length 1–48 | fleet.name |
| `sleep` / Sleep timer | `device` → `device` | device overview; screensaver sleep fields/search | Settings local (wrapper: SleepSettingsActivity); `L/SleepSettingsActivity.kt: SleepSettingsContent` | sleepTimerEnabled for duration/start action; A for close-app | screensaver sleep fields; A30 |
| `health` / About & device health | `device` → `device` | device overview; debug/direct wrapper/search | Settings local (wrapper: DeviceHealthActivity); `L/ImmortalSettingsActivity.kt: DeviceHealthContent` | Admin-active for two-tap deactivate; no A body gate | Read-only checks/admin action; A49 |
| `apk` / Install an APK | `device` → `advanced` | Advanced overview; Store/direct/search | ApkBrowserActivity; `L/ApkBrowserActivity.kt: ApkBrowserScreen` | A by route policy, but ApprovedAppsShell has no Settings gate; storage/installer predicates | Local APK/install flow; A56 |
| `help` / Welcome to Immortal | `device` → `device` | device overview; first launch/search | HelpActivity; `L/HelpActivity.kt: HelpTour` | Eight-page tour; link on last page | Tour actions; A57 |
| `speed` / Speed test | `connections` → `advanced` | Advanced overview/search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedNativeSettingsPage` | A | Tools tab handoff; A58 |
| `system` / Android & Portal settings | `device` → `device` | access/device overview/search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedNativeSettingsPage` | Resolvable system intents/Portal package; fallback | Nine platform destinations; A58 |
| `managed` / Automatically managed settings | `advanced` → `advanced` | Advanced overview; managed field search | Settings local; `L/ImmortalSettingsActivity.kt: ApprovedManagedSettingsPage` | A; bypasses hidden-spec predicates for audit | Seven read-only fields |
| `debug` / Diagnostics | `advanced` → `advanced` | Advanced overview/search | Settings local; `L/ImmortalSettingsActivity.kt: IosDebugRow` | A; no BuildConfig.DEBUG body predicate | debug.enabled; health nav; A58 |


The `widgets` route is an explicit `HomeActivity` handoff. Its page extra is consumed
by `HomeActivity.consumeWidgetLibraryIntent` to open the real widget library.
The `ApprovedNativeSettingsPage("widgets")` branch also exists but is not selected
by normal approved navigation; its “Customize home” and widget-settings rows cannot
be counted as the observed body for this route. Main owns physical widget QA.

## Exact registry mapping

All rows below come from `S/SettingsDomains.kt`, the named domain's `specs`.
Titles are **registry titles**, not a claim about bespoke rendered labels.
`E` = everyday tier; `A` = exact membership in
`SettingsPresentation.advancedControls`; `D` = derived/no rendered row.
Page column includes native secondary placements; `managed` means read-only audit
and does not erase the scalar's existing apply support.
Default predicate `true` is the spec default. Add generic renderer gates above.
An em dash in the title slot means no title was declared. Getter/predicate
expressions have whitespace normalized for table display.

### screensaver (33 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `screensaver.enabled` / Show the photo-frame screensaver | Bool | `{ it.enabled } → ScreensaverConfig::setEnabled` | — | screensaver | `true` | E |
| `screensaver.screensaverStyle` / Style | Nav | `{ _, s -> ScreensaverConfig.modeLabel(s.mode) }` | `ScreensaverModeActivity::class.java` | screensaver → styles | `{ _, s -> s.enabled }` | E |
| `screensaver.clockFace` / Clock face | Nav | `{ _, s -> FaceCatalog.entryFor(s.faceId).name }` | `FacePickerActivity::class.java` | screensaver → faces | `{ _, s -> s.enabled }` | E |
| `screensaver.photoSource` / Photo source | Nav | `{ _, s -> sourceLabel(s) }` | `ScreensaverSourcesActivity::class.java` | screensaver → sources | `{ _, s -> s.enabled }` | E |
| `screensaver.dismissTarget` / Open when you tap to exit | Nav | `{ c, _ -> ScreensaverDismiss.chosenLabel(c)?.let { "Opens $it" } ?: "Immortal launcher" }` | `ScreensaverDismissAppActivity::class.java` | screensaver → dismiss | `{ _, s -> s.enabled }` | E |
| `screensaver.source` / — | Derived | `{ it.source }` | — | Flat value only; no control | `false (render=false)` | D |
| `screensaver.folderPath` / — | Derived | `{ it.folderPath ?: "" }` | — | Flat value only; no control | `false (render=false)` | D |
| `screensaver.albumUrl` / — | Derived | `{ it.albumUrl ?: "" }` | — | Flat value only; no control | `false (render=false)` | D |
| `screensaver.albumRefreshMin` / Album refresh | Int | `{ it.albumRefreshMin } → ScreensaverConfig::setAlbumRefreshMin` | `15..24 * 60; step=15; wrap=false` | sources (bespoke A+usesUrl) | `{ _, _ -> false }` | A |
| `screensaver.fit` / Fit | Enum | `{ it.fit } → ScreensaverConfig::setFit` | `listOf(ScreensaverConfig.FIT_FILL to "Fill", ScreensaverConfig.FIT_FIT to "Fit"); coerce={ FleetScreensaver.coerceFit(it) }` | screensaver | `{ _, s -> s.enabled && !ScreensaverConfig.usesEngine(s.mode) }` | E |
| `screensaver.intervalSec` / Photo interval | Int | `{ it.intervalSec } → ScreensaverConfig::setInterval` | `5..600; step=5; wrap=false` | screensaver | `{ _, s -> s.enabled }` | E |
| `screensaver.shuffle` / Shuffle | Bool | `{ it.shuffle } → ScreensaverConfig::setShuffle` | — | screensaver | `true` | E |
| `screensaver.includeVideo` / Play videos | Bool | `{ it.includeVideo } → ScreensaverConfig::setIncludeVideo` | — | screensaver | `true` | E |
| `screensaver.cacheEnabled` / Store media on this device | Bool | `{ it.cacheEnabled } → ScreensaverConfig::setCacheEnabled` | — | screensaver | `{ _, s -> s.usesImmich \|\| s.usesDav }` | A |
| `screensaver.cacheBudgetGb` / Storage limit | Int | `{ it.cacheBudgetGb } → ScreensaverConfig::setCacheBudgetGb` | `ScreensaverConfig.CACHE_GB_MIN..ScreensaverConfig.CACHE_GB_MAX; step=1; wrap=false` | managed (read-only) | `{ _, _ -> false }` | A |
| `screensaver.batterySaver` / Sleep on battery when nobody's around | Bool | `{ it.batterySaver } → ScreensaverConfig::setBatterySaver` | — | screensaver | `{ c, _ -> DreamPolicy.hasBattery(c) }` | A |
| `screensaver.showNowPlaying` / Show now playing | Bool | `{ it.showNowPlaying } → ScreensaverConfig::setShowNowPlaying` | — | screensaver; faces | `true` | E |
| `screensaver.antiBurnIn` / Reduce screen burn-in | Bool | `{ it.antiBurnIn } → ScreensaverConfig::setAntiBurnIn` | — | managed (read-only) | `{ _, _ -> false }` | A |
| `screensaver.presenceMode` / Power | Enum | `{ it.presenceMode.name } → { c, v -> ScreensaverConfig.setPresenceMode(c, FrameMode.valueOf(v)) }` | `listOf( FrameMode.ALWAYS_ON.name to "Always on", FrameMode.PRESENCE.name to "Follow presence"); coerce={ FleetScreensaver.coercePresenceMode(it)?.name }` | screensaver | `true` | A |
| `screensaver.idleSleepMin` / Idle screen-off | Int | `{ it.idleSleepMin } → ScreensaverConfig::setIdleSleepMin` | `0..120; step=5; wrap=false` | screensaver | `true` | A |
| `screensaver.overnightEnabled` / Overnight screen-off | Bool | `{ it.overnightEnabled } → ScreensaverConfig::setOvernightEnabled` | — | screensaver | `true` | E |
| `screensaver.overnightStartMin` / Off from | Int | `{ it.overnightStartMin } → ScreensaverConfig::setOvernightStartMin` | `0..24 * 60 - 1; step=15; wrap=true` | screensaver | `{ _, s -> s.overnightEnabled }` | E |
| `screensaver.overnightEndMin` / Off until | Int | `{ it.overnightEndMin } → ScreensaverConfig::setOvernightEndMin` | `0..24 * 60 - 1; step=15; wrap=true` | screensaver | `{ _, s -> s.overnightEnabled }` | E |
| `screensaver.overnightNightClock` / Show a dim night clock | Bool | `{ it.overnightNightClock } → ScreensaverConfig::setOvernightNightClock` | — | screensaver | `{ _, s -> s.overnightEnabled }` | E |
| `screensaver.sleepTimerEnabled` / Sleep timer | Bool | `{ it.sleepTimerEnabled } → ScreensaverConfig::setSleepTimerEnabled` | — | screensaver; sleep (D3) | `true` | E |
| `screensaver.sleepTimerMin` / Sleep after | Int | `{ it.sleepTimerMin } → ScreensaverConfig::setSleepTimerMin` | `1..240; step=5; wrap=false` | screensaver; sleep (D3) | `{ _, s -> s.sleepTimerEnabled }` | E |
| `screensaver.pauseAudioOnSleep` / Pause audio on sleep | Bool | `{ it.pauseAudioOnSleep } → ScreensaverConfig::setPauseAudioOnSleep` | — | screensaver; sleep (D3) | `{ _, s -> s.sleepTimerEnabled }` | E |
| `screensaver.closeAppOnSleep` / Close app on sleep | Bool | `{ it.closeAppOnSleep } → ScreensaverConfig::setCloseAppOnSleep` | — | screensaver; sleep (D3) | `{ _, s -> s.sleepTimerEnabled }` | A |
| `screensaver.soundscape` / Ambient sound | Enum | `{ it.soundscape } → ScreensaverConfig::setSoundscape` | `ScreensaverConfig.SOUNDSCAPES.map { it to ScreensaverConfig.soundscapeLabel(it) }; coerce={ v -> v.takeIf { it in ScreensaverConfig.SOUNDSCAPES } }` | screensaver | `true` | E |
| `screensaver.soundscapeVolume` / Soundscape volume | Int | `{ it.soundscapeVolume } → ScreensaverConfig::setSoundscapeVolume` | `0..100; step=5; wrap=false` | screensaver | `{ _, s -> s.soundscape != ScreensaverConfig.SOUND_OFF }` | E |
| `screensaver.ambientDashboard` / Ambient dashboard | Bool | `{ it.ambientDashboard } → ScreensaverConfig::setAmbientDashboard` | — | screensaver | `true` | A |
| `screensaver.gestureWave` / Gesture wave to wake | Bool | `{ it.gestureWave } → ScreensaverConfig::setGestureWave` | — | screensaver | `true` | A |
| `screensaver.welcomeEnabled` / Welcome screen | Bool | `{ it.welcomeEnabled } → ScreensaverConfig::setWelcomeEnabled` | — | screensaver | `true` | E |

### calendar (10 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `calendar.widgetOn` / Show calendar | Bool | `{ it.calendarEnabled } → ScreensaverConfig::setCalendarEnabled` | `aliases=listOf("enabled")` | calendar; screensaver | `{ _, s -> s.hasCalendarLink }` | E |
| `calendar.url` / Calendar feed | String | `{ it.calendarUrl ?: "" } → ScreensaverConfig::setCalendarUrl` | `secret=false; applyWhen={ true }; Entry.Nav(CalendarUrlEntryActivity::class.java)` | calendar; screensaver → calendar-url | `true` | E |
| `calendar.range` / Show | Enum | `{ it.calendarRange } → ScreensaverConfig::setCalendarRange` | `FleetCalendar.RANGES.map { it to rangeLabel(it) }; coerce={ it }` | calendar; screensaver | `{ _, s -> s.usesCalendar }` | E |
| `calendar.size` / Size | Int | `{ it.calendarSize } → ScreensaverConfig::setCalendarSize` | `0..2; step=1; wrap=false` | calendar; screensaver | `{ _, s -> s.usesCalendar }` | E |
| `calendar.side` / Side | Enum | `{ it.calendarSide } → ScreensaverConfig::setCalendarSide` | `listOf( ScreensaverConfig.CAL_SIDE_LEFT to "Left", ScreensaverConfig.CAL_SIDE_RIGHT to "Right"); coerce={ it }` | calendar; screensaver | `{ _, s -> s.usesCalendar }` | E |
| `calendar.enabled` / — | Derived | `{ it.usesCalendar }` | — | Flat value only; no control | `false (render=false)` | D |
| `calendar.hasLink` / — | Derived | `{ it.hasCalendarLink }` | — | Flat value only; no control | `false (render=false)` | D |
| `calendar.provider` / — | Derived | `{ val u = it.calendarUrl.orEmpty() if (u.isBlank()) "" else CalendarFeed.providerName(u) }` | — | Flat value only; no control | `false (render=false)` | D |
| `calendar.supported` / — | Derived | `{ val u = it.calendarUrl.orEmpty() u.isNotBlank() && CalendarFeed.isSupported(u) }` | — | Flat value only; no control | `false (render=false)` | D |
| `calendar.ranges` / — | Derived | `{ JSONArray(FleetCalendar.RANGES) }` | — | Flat value only; no control | `false (render=false)` | D |

### immortal (13 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `immortal.advancedSettings` / Advanced settings | Bool | `{ it.advancedSettings } → ImmortalSettings::setAdvancedSettings` | — | Sidebar/Advanced overview | `true` | E |
| `immortal.settingsTheme` / Appearance | Enum | `{ it.settingsTheme } → ImmortalSettings::setSettingsTheme` | `listOf("light" to "Light", "dark" to "Dark"); coerce=oneOf("light", "dark")` | appearance; shell header | `true` | E |
| `immortal.weatherUnit` / Temperature unit | Enum | `{ it.weatherUnit } → ImmortalSettings::setWeatherUnit` | `listOf( ImmortalSettings.UNIT_AUTO to "Auto", ImmortalSettings.UNIT_F to "Fahrenheit", ImmortalSettings.UNIT_C to "Celsius"); coerce=oneOf(ImmortalSettings.UNIT_AUTO, ImmortalSettings.UNIT_F, ImmortalSettings.UNIT_C)` | weather | `true` | E |
| `immortal.tileSize` / App tile size | Enum | `{ it.tileSize } → ImmortalSettings::setTileSize` | `listOf( ImmortalSettings.SIZE_STANDARD to "Standard", ImmortalSettings.SIZE_LARGE to "Large", ImmortalSettings.SIZE_XL to "XL"); coerce=oneOf(ImmortalSettings.SIZE_STANDARD, ImmortalSettings.SIZE_LARGE, ImmortalSettings.SIZE_XL)` | appearance | `true` | E |
| `immortal.weatherWidget` / Weather widget | Enum | `{ it.weatherWidget } → ImmortalSettings::setWeatherWidget` | `listOf( ImmortalSettings.WIDGET_OFF to "Off", ImmortalSettings.WIDGET_HOURLY to "Hourly", ImmortalSettings.WIDGET_DAILY to "Daily"); coerce=oneOf(ImmortalSettings.WIDGET_OFF, ImmortalSettings.WIDGET_HOURLY, ImmortalSettings.WIDGET_DAILY)` | weather | `true` | E |
| `immortal.clockFormat` / Clock | Enum | `{ it.clockFormat } → ImmortalSettings::setClockFormat` | `listOf( ImmortalSettings.CLOCK_AUTO to "Auto", ImmortalSettings.CLOCK_12 to "12-hour", ImmortalSettings.CLOCK_24 to "24-hour"); coerce=oneOf(ImmortalSettings.CLOCK_AUTO, ImmortalSettings.CLOCK_12, ImmortalSettings.CLOCK_24)` | appearance; faces | `true` | E |
| `immortal.showMiniPlayer` / Now-playing mini-player | Bool | `{ it.showMiniPlayer } → ImmortalSettings::setShowMiniPlayer` | — | appearance | `true` | E |
| `immortal.hideStatusBar` / Hide status bar | Bool | `{ it.hideStatusBar } → ImmortalSettings::setHideStatusBar` | — | appearance | `true` | A |
| `immortal.multiRoomEnabled` / Multi-room audio | Bool | `{ it.multiRoomEnabled } → ImmortalSettings::setMultiRoomEnabled` | — | multiroom (A46) | `true` | A |
| `immortal.snapcastHost` / Snapcast host | String | `{ it.snapcastHost } → ImmortalSettings::setSnapcastHost` | `secret=false; applyWhen={ true }` | multiroom (A46) | `{ _, s -> s.multiRoomEnabled }` | A |
| `immortal.maPort` / Music Assistant port | Int | `{ it.maPort } → ImmortalSettings::setMaPort` | `1..65535; step=1; wrap=false; asText=true` | multiroom (A46) | `{ _, s -> s.multiRoomEnabled }` | A |
| `immortal.maUsername` / Music Assistant user | String | `{ it.maUsername } → ImmortalSettings::setMaUsername` | `secret=false; applyWhen={ true }` | multiroom (A46) | `{ _, s -> s.multiRoomEnabled }` | A |
| `immortal.maPassword` / Music Assistant password | String | `{ it.maPassword } → ImmortalSettings::setMaPassword` | `secret=true; applyWhen={ true }` | multiroom (A46) | `{ _, s -> s.multiRoomEnabled }` | A |

### mqtt (7 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `mqtt.enabled` / Publish to MQTT | Bool | `{ MqttConfig.isEnabled(it) } → MqttConfig::setEnabled` | — | mqtt (A47) | `true` | A |
| `mqtt.host` / Broker host | String | `{ MqttConfig.host(it) } → MqttConfig::setHost` | `secret=false; applyWhen={ true }` | mqtt (A47) | `{ c, _ -> MqttConfig.isEnabled(c) }` | A |
| `mqtt.port` / Port | Int | `{ MqttConfig.port(it) } → MqttConfig::setPort` | `1..65535; step=1; wrap=false; asText=true` | mqtt (A47) | `{ c, _ -> MqttConfig.isEnabled(c) }` | A |
| `mqtt.username` / Username | String | `{ MqttConfig.username(it) } → MqttConfig::setUsername` | `secret=false; applyWhen={ true }` | mqtt (A47) | `{ c, _ -> MqttConfig.isEnabled(c) }` | A |
| `mqtt.password` / Password | String | `{ MqttConfig.password(it) } → MqttConfig::setPassword` | `secret=true; applyWhen={ true }` | mqtt (A47) | `{ c, _ -> MqttConfig.isEnabled(c) }` | A |
| `mqtt.useTls` / Use TLS | Bool | `{ MqttConfig.useTls(it) } → MqttConfig::setUseTls` | — | mqtt (A47) | `{ c, _ -> MqttConfig.isEnabled(c) }` | A |
| `mqtt.validateCert` / Validate certificate | Bool | `{ MqttConfig.validateCert(it) } → MqttConfig::setValidateCert` | — | managed (read-only) | `{ _, _ -> false }` | A |

### quickbar (2 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `quickbar.enabled` / App-switcher button | Bool | `{ it.enabled } → QuickBarConfig::setEnabled` | — | quickbar | `true` | E |
| `quickbar.alwaysShow` / Always show | Bool | `{ it.alwaysShow } → QuickBarConfig::setAlwaysShow` | — | quickbar | `{ _, s -> s.enabled }` | E |

### fleet (1 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `fleet.name` / Device name | String | `{ FleetConfig.name(it) } → { c, v -> FleetConfig.setName(c, v.trim()) }` | `secret=false; applyWhen={ it.trim().length in 1..48 }` | device | `true` | E |

### chime (8 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `chime.hourlyChimeOn` / Hourly chime | Bool | `{ it.hourlyChimeOn } → ChimeConfig::setHourlyChime` | — | chime | `true` | E |
| `chime.spokenTimeOn` / Spoken time | Bool | `{ it.spokenTimeOn } → ChimeConfig::setSpokenTime` | — | chime | `true` | E |
| `chime.goldenHourOn` / Golden-hour tone | Bool | `{ it.goldenHourOn } → ChimeConfig::setGoldenHour` | — | chime | `true` | E |
| `chime.sunriseVariant` / Sunrise sound | Int | `{ it.sunriseVariant } → ChimeConfig::setSunriseVariant` | `0..1; step=1; wrap=false` | chime | `{ _, s -> s.goldenHourOn }` | E |
| `chime.volume` / Volume | Int | `{ it.volume } → ChimeConfig::setVolume` | `0..100; step=10; wrap=false` | chime | `true` | E |
| `chime.quietHoursOn` / Quiet hours | Bool | `{ it.quietHoursOn } → ChimeConfig::setQuietHours` | — | chime | `true` | E |
| `chime.quietStartMin` / Quiet from | Int | `{ it.quietStartMin } → ChimeConfig::setQuietStart` | `0..24 * 60 - 1; step=30; wrap=true` | chime | `{ _, s -> s.quietHoursOn }` | E |
| `chime.quietEndMin` / Quiet until | Int | `{ it.quietEndMin } → ChimeConfig::setQuietEnd` | `0..24 * 60 - 1; step=30; wrap=true` | chime | `{ _, s -> s.quietHoursOn }` | E |

### digitalclock (11 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `digitalclock.enabled` / Digital clock | Bool | `{ it.enabled } → DigitalClockConfig::setEnabled` | — | clock | `true` | E |
| `digitalclock.style` / Clock style | Enum | `{ it.style } → DigitalClockConfig::setStyle` | `listOf( DigitalClockConfig.STYLE_CLASSIC to "Classic", DigitalClockConfig.STYLE_FLIP to "Flip", DigitalClockConfig.STYLE_BOLD to "Bold", DigitalClockConfig.STYLE_NEON to "Neon", DigitalClockConfig.STYLE_SEGMENT to "Segment", DigitalClockConfig.STYLE_ANALOG to "Analog"); coerce=oneOf( DigitalClockConfig.STYLE_CLASSIC, DigitalClockConfig.STYLE_FLIP, DigitalClockConfig.STYLE_BOLD, DigitalClockConfig.STYLE_NEON, DigitalClockConfig.STYLE_SEGMENT, DigitalClockConfig.STYLE_ANALOG)` | managed (read-only) | `{ _, _ -> false }` | A |
| `digitalclock.face` / Clock face | Enum | `{ DigitalClockConfig.faceOf(it) } → DigitalClockConfig::setFace` | `DigitalClockConfig.FACES.map { it.id to it.label }; coerce=oneOf(*DigitalClockConfig.FACES.map { it.id }.toTypedArray())` | clock | `{ _, s -> s.enabled }` | E |
| `digitalclock.color` / Color | Enum | `{ it.color } → DigitalClockConfig::setColor` | `listOf( DigitalClockConfig.COLOR_WHITE to "White", DigitalClockConfig.COLOR_RED to "Red", DigitalClockConfig.COLOR_GREEN to "Green", DigitalClockConfig.COLOR_BLUE to "Blue", DigitalClockConfig.COLOR_YELLOW to "Yellow", DigitalClockConfig.COLOR_CYAN to "Cyan", DigitalClockConfig.COLOR_PINK to "Pink", DigitalClockConfig.COLOR_ORANGE to "Orange"); coerce=oneOf( DigitalClockConfig.COLOR_WHITE, DigitalClockConfig.COLOR_RED, DigitalClockConfig.COLOR_GREEN, DigitalClockConfig.COLOR_BLUE, DigitalClockConfig.COLOR_YELLOW, DigitalClockConfig.COLOR_CYAN, DigitalClockConfig.COLOR_PINK, DigitalClockConfig.COLOR_ORANGE)` | managed (read-only) | `{ _, _ -> false }` | A |
| `digitalclock.font` / Font | Enum | `{ it.font } → DigitalClockConfig::setFont` | `listOf( DigitalClockConfig.FONT_LIGHT to "Light", DigitalClockConfig.FONT_NORMAL to "Normal", DigitalClockConfig.FONT_BOLD to "Bold", DigitalClockConfig.FONT_MONO to "Mono", DigitalClockConfig.FONT_SERIF to "Serif", DigitalClockConfig.FONT_SEGMENT_LED to "LED", DigitalClockConfig.FONT_DIGITAL_7 to "Digital", DigitalClockConfig.FONT_TECHNOLOGY to "Tech"); coerce=oneOf( DigitalClockConfig.FONT_LIGHT, DigitalClockConfig.FONT_NORMAL, DigitalClockConfig.FONT_BOLD, DigitalClockConfig.FONT_MONO, DigitalClockConfig.FONT_SERIF, DigitalClockConfig.FONT_SEGMENT_LED, DigitalClockConfig.FONT_DIGITAL_7, DigitalClockConfig.FONT_TECHNOLOGY)` | managed (read-only) | `{ _, _ -> false }` | A |
| `digitalclock.size` / Size | Enum | `{ it.size } → DigitalClockConfig::setSize` | `listOf( DigitalClockConfig.SIZE_SMALL to "Small", DigitalClockConfig.SIZE_MEDIUM to "Medium", DigitalClockConfig.SIZE_LARGE to "Large", DigitalClockConfig.SIZE_XL to "XL"); coerce=oneOf( DigitalClockConfig.SIZE_SMALL, DigitalClockConfig.SIZE_MEDIUM, DigitalClockConfig.SIZE_LARGE, DigitalClockConfig.SIZE_XL)` | clock | `{ _, s -> s.enabled }` | E |
| `digitalclock.layout` / Position | Enum | `{ it.layout } → DigitalClockConfig::setLayout` | `listOf( DigitalClockConfig.LAYOUT_CENTER to "Center", DigitalClockConfig.LAYOUT_TOP to "Top", DigitalClockConfig.LAYOUT_BOTTOM to "Bottom", DigitalClockConfig.LAYOUT_MINIMAL to "Minimal"); coerce=oneOf( DigitalClockConfig.LAYOUT_CENTER, DigitalClockConfig.LAYOUT_TOP, DigitalClockConfig.LAYOUT_BOTTOM, DigitalClockConfig.LAYOUT_MINIMAL)` | clock | `{ _, s -> s.enabled }` | E |
| `digitalclock.background` / Background | Enum | `{ it.background } → DigitalClockConfig::setBackground` | `listOf( DigitalClockConfig.BG_BLACK to "Black", DigitalClockConfig.BG_GRADIENT to "Gradient", DigitalClockConfig.BG_RED to "Red"); coerce=oneOf( DigitalClockConfig.BG_BLACK, DigitalClockConfig.BG_GRADIENT, DigitalClockConfig.BG_RED)` | clock | `{ _, s -> s.enabled }` | E |
| `digitalclock.glow` / Glow | Enum | `{ it.glow } → DigitalClockConfig::setGlow` | `listOf( DigitalClockConfig.GLOW_NONE to "None", DigitalClockConfig.GLOW_SOFT to "Soft", DigitalClockConfig.GLOW_STRONG to "Strong"); coerce=oneOf( DigitalClockConfig.GLOW_NONE, DigitalClockConfig.GLOW_SOFT, DigitalClockConfig.GLOW_STRONG)` | managed (read-only) | `{ _, _ -> false }` | A |
| `digitalclock.showDate` / Show date | Bool | `{ it.showDate } → DigitalClockConfig::setShowDate` | — | clock | `{ _, s -> s.enabled }` | E |
| `digitalclock.showSeconds` / Show seconds | Bool | `{ it.showSeconds } → DigitalClockConfig::setShowSeconds` | — | clock | `{ _, s -> s.enabled }` | E |

### welcome (5 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `welcome.durationMs` / Display duration | Int | `{ it.durationMs } → WelcomeConfig::setDuration` | `1000..10000; step=200; wrap=false` | welcome (D4) | `true` | A |
| `welcome.showGreeting` / Show greeting | Bool | `{ it.showGreeting } → WelcomeConfig::setShowGreeting` | — | welcome (D4) | `true` | E |
| `welcome.showClock` / Show clock | Bool | `{ it.showClock } → WelcomeConfig::setShowClock` | — | welcome (D4) | `true` | E |
| `welcome.showDate` / Show date | Bool | `{ it.showDate } → WelcomeConfig::setShowDate` | — | welcome (D4) | `true` | E |
| `welcome.enableTts` / Speak greeting | Bool | `{ it.enableTts } → WelcomeConfig::setEnableTts` | — | welcome (D4) | `true` | E |

### sunrise (5 entries)

| ID / exact registry title | Spec | Getter/value → setter | Constraints / options / destination | Native entry | Registry visible predicate | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| `sunrise.enabled` / Sunrise alarm | Bool | `{ it.enabled } → SunriseConfig::setEnabled` | — | sunrise (D2) | `true` | E |
| `sunrise.hour` / Hour | Int | `{ it.hour } → SunriseConfig::setHour` | `0..23; step=1; wrap=false` | sunrise (D2) | `{ _, s -> s.enabled }` | E |
| `sunrise.minute` / Minute | Int | `{ it.minute } → SunriseConfig::setMinute` | `0..59; step=5; wrap=false` | sunrise (D2) | `{ _, s -> s.enabled }` | E |
| `sunrise.rampMinutes` / Ramp minutes | Int | `{ it.rampMinutes } → SunriseConfig::setRampMinutes` | `1..60; step=5; wrap=false` | sunrise (D2) | `{ _, s -> s.enabled }` | E |
| `sunrise.chime` / Chime at end | Bool | `{ it.chime } → SunriseConfig::setChime` | — | sunrise (D2) | `{ _, s -> s.enabled }` | E |


### Domain side effects

| Domain / source symbol | Commit side effect |
|---|---|
| `screensaver.onApplied` | `SettingsGuard.afterScreensaverApply(c, keys)` |
| `calendar` | No domain `onApplied` override; URL setter remains `ScreensaverConfig.setCalendarUrl` |
| `immortal.onApplied` | `hideStatusBar` → `SettingsGuard.applyStatusBar`; any of `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, `maPassword` → `MultiRoomService.sync` |
| `mqtt.onApplied` | If `useTls` applied, hops port 1883↔8883 only from the opposite default, then `MqttService.sync`; `explicitApply = true` |
| `quickbar.onApplied` | `SettingsGuard.reconcileBarWatch`, `QuickBar.applyConfig` |
| `fleet` | No hook override; `FleetConfig.setName` includes manifest rewrite |
| `chime.onApplied` | Reschedules for hourly/spoken/golden-hour/quiet-hours toggle and quiet start/end keys; not for `sunriseVariant` or `volume` alone |
| `sunrise.onApplied` | `SunriseScheduler.reschedule` |
| `digitalclock.onApplied` | Only `enabled` → `SettingsGuard.reaffirmScreensaver` |
| `welcome` | No hook override |

`calendar.widgetOn` accepts legacy write alias `enabled`, but `calendar.enabled`
is a separate **derived effective value**. `usesCalendar = calendarEnabled &&
hasCalendarLink`; `hasCalendarLink` means nonblank calendar URL.
`usesImmich` needs selected Immich source plus nonblank URL and key; `usesDav`
needs selected DAV plus nonblank URL; `usesUrl` needs selected shared-album source
plus nonblank link. These are `ScreensaverConfig.Settings` getters, not connectivity
tests. `CACHE_GB_MIN/MAX` are 1/32. Enum tables preserve exact option expressions;
catalog-backed counts/labels should be captured from that artifact's source/runtime.

## Supplemental native fields: candidates reconciled with bodies

Exact IDs/titles below come from `L/ApprovedSettingsSearch.kt`:
`ApprovedNativeSettingsFields`. The next column records the actual owner/state and
commit boundary, including absent or differently labeled native fields.
These IDs do not by themselves prove highlight/scroll support in a bespoke body.

| Exact supplemental ID | Exact search title (not observed label) | Search page | Native source / symbol | Actual field/state/commit reconciliation |
| --- | --- | --- | --- | --- |
| `display.brightness` | Brightness | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded+canWrite; immediate DisplayControl write; brightness 1–100 also disables auto; Auto-brightness toggle. |
| `display.auto` | Auto-brightness | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded+canWrite; immediate DisplayControl write; brightness 1–100 also disables auto; Auto-brightness toggle. |
| `display.mediaVolume` | Media volume | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded; “Media” slider 0–100, immediate setMediaVolumePercent. |
| `display.nightShift` | Night Shift | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded; immediate setNightShiftEnabled. |
| `display.from` | From | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded+nightShift.enabled; immediate setNightShiftStart; UI ±30 wraps, setter clamps (D17). |
| `display.until` | Until | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded+nightShift.enabled; immediate setNightShiftEnd; UI ±30 wraps, setter clamps (D17). |
| `display.warmth` | Warmth | `display` | `L/ImmortalSettingsActivity.kt: DisplaySection` | Loaded+nightShift.enabled; immediate setNightShiftStrength; ±10 clamps 0–100. |
| `display.touchSounds` | Touch sounds | `touch` | `L/ImmortalSettingsActivity.kt: ApprovedTouchSounds` | Label Touch sounds; if SystemSounds.canWrite then immediate system write, else requestWriteAccess; no registry spec. |
| `wallpaper.mode` | Background | `wallpaper` | `L/ImmortalSettingsActivity.kt: WallpaperSection` | Background swatches → immediate WallpaperConfig.setMode; Dusk Aurora, Dark, GRADIENTS, PHOTOS, Screensaver, Sky, Star field. |
| `wallpaper.grain` | Film grain | `wallpaper` | `L/ImmortalSettingsActivity.kt: WallpaperSection` | Film grain → immediate WallpaperConfig.setGrain. |
| `sources.selected` | Photo source | `sources` | `L/ScreensaverSourcesActivity.kt: ScreensaverSourcesContent` | Alias for source tile/current source family, not one stored scalar; A09–A11. Technical connections gated A. |
| `sources.albumRefresh` | Refresh album | `sources` | `L/ScreensaverSourcesActivity.kt: ScreensaverSourcesContent` | A+usesUrl; Refresh album PickerRow commits screensaver.albumRefreshMin; choices 15,30,45,60,120,180,240,360,720,1440. |
| `immich.url` | Server address | `immich` | `L/ImmichConnectActivity.kt: ImmichConnectScreen` | A; server/API key draft; Test & connect verifies only, no persistence until Use this album (A17–A19). |
| `immich.key` | API key | `immich` | `L/ImmichConnectActivity.kt: ImmichConnectScreen` | A; server/API key draft; Test & connect verifies only, no persistence until Use this album (A17–A19). |
| `immich.album` | Album | `albums` | `L/ImmichConnectActivity.kt: ImmichConnectScreen` | A; verified connection; Whole library/album selection draft; Use this album writes immichAlbumId/name and connection/source. |
| `smb.host` | Server | `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen` | A; Server/Share name/Folder in the share/Username/Password drafts; successful count>0 calls setSmb; Done returns (A20). |
| `smb.share` | Share name | `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen` | A; Server/Share name/Folder in the share/Username/Password drafts; successful count>0 calls setSmb; Done returns (A20). |
| `smb.path` | Folder in the share | `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen` | A; Server/Share name/Folder in the share/Username/Password drafts; successful count>0 calls setSmb; Done returns (A20). |
| `smb.username` | Username | `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen` | A; Server/Share name/Folder in the share/Username/Password drafts; successful count>0 calls setSmb; Done returns (A20). |
| `smb.password` | Password | `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen` | A; Server/Share name/Folder in the share/Username/Password drafts; successful count>0 calls setSmb; Done returns (A20). |
| `dav.url` | WebDAV folder address | `dav` | `L/DavConnectActivity.kt: DavConnectScreen` | A; WebDAV folder address/optional user/password drafts; successful count>0 calls setDav; Done returns (A21). |
| `dav.username` | Username (optional) | `dav` | `L/DavConnectActivity.kt: DavConnectScreen` | A; WebDAV folder address/optional user/password drafts; successful count>0 calls setDav; Done returns (A21). |
| `dav.password` | Password (optional) | `dav` | `L/DavConnectActivity.kt: DavConnectScreen` | A; WebDAV folder address/optional user/password drafts; successful count>0 calls setDav; Done returns (A21). |
| `web.url` | Web page address | `web` | `L/WebUrlEntryActivity.kt: onCreate → UrlEntryScreen` | A; literal http:// or https:// prefix; Use this page calls setWebUrl, returns screensaver; Cancel no write. |
| `album.url` | Shared album link | `album` | `L/AlbumUrlEntryActivity.kt: onCreate → UrlEntryScreen` | RemoteAlbum.isSupported; Use this album calls setAlbumUrl, returns screensaver; Cancel no write. |
| `folder.path` | Folder | `folder` | `L/FolderPickerActivity.kt: FolderPicker` | Folder navigation, not editable text; permission+accessible path; Use this folder rechecks then setFolder; A12–A14. |
| `screensaver.mode` | Screensaver style | `styles` | `L/ScreensaverModeActivity.kt: ScreensaverModeContent` | Screensaver style PickerRow uses MODES; option selection immediate setMode; no enabled body gate. |
| `faces.enabled` | Show clock face | `faces` | `L/FacePickerActivity.kt: FacePickerContent` | Show clock face toggle immediate ScreensaverConfig.setFacesEnabled; no enabled master predicate in body. |
| `faces.id` | Photo clock face | `faces` | `L/FacePickerActivity.kt: FacePickerContent` | facesEnabled; Photo clock face choices FaceCatalog.entries; immediate setFaceId. |
| `faces.size` | Size | `faces` | `L/FacePickerActivity.kt: FacePickerContent` | facesEnabled+entry.sizes nonempty; Size choices FaceCatalog.SIZE_LABELS indices; immediate setFaceSizeIndex. |
| `dismiss.target` | Open when you tap to exit | `dismiss` | `L/ScreensaverDismissAppActivity.kt: DismissAppScreen` | Target dialog; launcher/HA/app selection immediately writes dismiss target group; A22–A23. |
| `dismiss.haPath` | Home Assistant dashboard path | `dismiss` | `L/ScreensaverDismissAppActivity.kt: DismissAppScreen` | A+selected HA+installed HA; Dashboard path draft Save calls setDismissHaDashboard; Cancel no write. |
| `welcome.voice` | Greeting voice | `welcome` | `L/WelcomeSettingsActivity.kt: WelcomeSettingsContent` | A+enableTts; voice row selection immediate setTtsVoice; Test only auditions (A27). |
| `welcome.userName` | Your name | `welcome` | `L/WelcomeSettingsActivity.kt: EditableTextRow` | Your name inline draft; row Save calls setUserName; no explicit Cancel; navigation discards uncommitted draft on disposal. |
| `welcome.greetingNight` | Night greeting | `welcome` | `L/WelcomeSettingsActivity.kt: EditableTextRow` | A; actual label Night (22:00-04:59); inline draft Save calls WelcomeConfig.setGreetingNight; no explicit Cancel. |
| `welcome.greetingMorning` | Morning greeting | `welcome` | `L/WelcomeSettingsActivity.kt: EditableTextRow` | A; actual label Morning (05:00-11:59); inline draft Save calls WelcomeConfig.setGreetingMorning; no explicit Cancel. |
| `welcome.greetingAfternoon` | Afternoon greeting | `welcome` | `L/WelcomeSettingsActivity.kt: EditableTextRow` | A; actual label Afternoon (12:00-16:59); inline draft Save calls WelcomeConfig.setGreetingAfternoon; no explicit Cancel. |
| `welcome.greetingEvening` | Evening greeting | `welcome` | `L/WelcomeSettingsActivity.kt: EditableTextRow` | A; actual label Evening (17:00-21:59); inline draft Save calls WelcomeConfig.setGreetingEvening; no explicit Cancel. |
| `welcome.textScale` | Text size | `welcome` | `L/WelcomeSettingsActivity.kt: WelcomeSettingsContent` | Text size dialog small/medium/large constants; selection immediately setTextScale. |
| `welcome.opacity` | Background opacity | `welcome` | `L/WelcomeSettingsActivity.kt: OpacityStepper` | A; actual label Opacity; ±0.05, clampOpacity then setBackgroundOpacity; immediate. |
| `chime.voice` | Spoken-time voice | `chime` | `L/ChimeSettingsActivity.kt: VoicePicker` | spokenTimeOn; actual label Voice; offline voice DropdownMenu → immediate setSpokenVoice; A25. |
| `sunrise.days` | Repeat on | `sunrise` | `L/SunriseSettingsActivity.kt: SunriseContent.persist` | Actual heading Wake on; seven day buttons, ungated; setDays then batch registry scalars/reschedule. |
| `alarm.enabled` | Enabled | `alarm` | `L/AlarmSettingsActivity.kt: AlarmContent.persist` | No registry; all fields ungated; immediate full AlarmConfig.save+reschedule; hour modulo24, minute ±5 modulo60, seven days. |
| `alarm.hour` | Hour | `alarm` | `L/AlarmSettingsActivity.kt: AlarmContent.persist` | No registry; all fields ungated; immediate full AlarmConfig.save+reschedule; hour modulo24, minute ±5 modulo60, seven days. |
| `alarm.minute` | Minute | `alarm` | `L/AlarmSettingsActivity.kt: AlarmContent.persist` | No registry; all fields ungated; immediate full AlarmConfig.save+reschedule; hour modulo24, minute ±5 modulo60, seven days. |
| `alarm.days` | Repeat on | `alarm` | `L/AlarmSettingsActivity.kt: AlarmContent.persist` | No registry; all fields ungated; immediate full AlarmConfig.save+reschedule; hour modulo24, minute ±5 modulo60, seven days. |
| `countdowns.name` | Name | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Name draft; Add nonblank commits CountdownConfig event; Cancel resets/Back. |
| `countdowns.year` | Year | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Only !yearly; number dialog 2000–2100; Save changes draft; Add persists; yearly stores year=0. |
| `countdowns.month` | Month | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Number dialog 1–12 with wrapping arrows; Save draft only; Add persists. |
| `countdowns.day` | Day | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Number dialog 1–31 with wrapping arrows; Save draft only; Add persists; no month/day cross-validation in body. |
| `countdowns.emoji` | Icon | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Icon dialog EMOJI_PALETTE selection changes draft only; Add persists. |
| `countdowns.repeat` | Repeats every year | `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Repeats every year toggle changes form draft only; Add persists; hides Year. |
| `ha.url` | Base URL | `ha` | `L/HaSettingsActivity.kt: ApprovedEditorsHaContent(false)` | Base URL/Long-lived token draft; HTTP(S)+host/effective token; successful Save & connect commits atomic HaConfig.setConnection then ha-entities; A40. |
| `ha.token` | Long-lived token | `ha` | `L/HaSettingsActivity.kt: ApprovedEditorsHaContent(false)` | Base URL/Long-lived token draft; HTTP(S)+host/effective token; successful Save & connect commits atomic HaConfig.setConnection then ha-entities; A40. |
| `ha.search` | Search entities | `ha-entities` | `L/HaSettingsActivity.kt: ApprovedEditorsHaContent(true)` | Only discovered nonempty; actual label Search ${discovered.size} entities; query local/filter only; no persistence. |
| `world.city` | Search city | `world` | `L/ImmortalSettingsActivity.kt: WorldClockContent` | ABSENT search field (D1); 13 fixed WORLD_CLOCK_CITIES rows immediately setWorldClockZones; selection rank, >4 marked hidden. |
| `crypto.id` | CoinGecko id | `crypto` | `L/CryptoSettingsActivity.kt: CryptoSettingsContent` | CoinGecko id/optional Display symbol drafts; Add nonblank ID, fallback uppercase symbol; persisted CryptoConfig.add; A33. |
| `crypto.symbol` | Display symbol (optional) | `crypto` | `L/CryptoSettingsActivity.kt: CryptoSettingsContent` | CoinGecko id/optional Display symbol drafts; Add nonblank ID, fallback uppercase symbol; persisted CryptoConfig.add; A33. |
| `todo.item` | What needs doing? | `todo` | `L/TodoSettingsActivity.kt: TodoSettingsContent` | What needs doing? draft; Add nonblank TodoStore.add; A34. |
| `news.name` | Name (optional) | `news` | `L/NewsSettingsActivity.kt: NewsSettingsContent` | Optional Name/RSS / Atom URL drafts; Add requires HTTP(S)+host, NewsConfig.add; A35. |
| `news.url` | RSS / Atom URL | `news` | `L/NewsSettingsActivity.kt: NewsSettingsContent` | Optional Name/RSS / Atom URL drafts; Add requires HTTP(S)+host, NewsConfig.add; A35. |
| `stocks.symbol` | Symbol | `stocks` | `L/StocksSettingsActivity.kt: StocksSettingsContent` | Symbol draft; Add nonblank StocksConfig.add; A36. |
| `alpaca.key` | API Key ID | `alpaca` | `L/ApprovedEditorsAlpaca.kt: ApprovedEditorsAlpacaForm` | A; empty secret drafts; blank keeps saved value; effective pair required; Save credentials atomic; A37. |
| `alpaca.secret` | API Secret Key | `alpaca` | `L/ApprovedEditorsAlpaca.kt: ApprovedEditorsAlpacaForm` | A; empty secret drafts; blank keeps saved value; effective pair required; Save credentials atomic; A37. |
| `options.type` | Contract type | `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; Contract type dialog calls/puts selection changes draft; Save scan settings or Add persists. |
| `options.deltaMin` | Delta min | `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; 1–99 hundredths number dialog saves draft; Save scan settings/Add sorts and writes delta band. |
| `options.deltaMax` | Delta max | `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; 1–99 hundredths number dialog saves draft; Save scan settings/Add sorts and writes delta band. |
| `options.expiry` | Expiry within (days) | `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; Expiry within (days) number dialog 1–365, arrows ±5; Save draft; Save scan settings/Add persists. |
| `options.symbol` | Symbol | `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent` | A; Symbol draft; Add nonblank+valid scan parameters, saves scan then adds ticker; A38. |
| `camera.name` | Name (optional) | `cameras` | `L/CameraViewerActivity.kt: CameraViewerScreen` | Optional Name/RTSP URL drafts; Add requires startsWith(rtsp://) AND length>8; CameraConfig.add; A52. |
| `camera.url` | RTSP URL | `cameras` | `L/CameraViewerActivity.kt: CameraViewerScreen` | Optional Name/RTSP URL drafts; Add requires startsWith(rtsp://) AND length>8; CameraConfig.add; A52. |
| `lamp.brightness` | Brightness | `lamp` | `L/LampActivity.kt: LampRows / LampControlDialog` | 5–100%; slider immediate ephemeral state; dialog Save validates; preview actual window override; no prefs (A54–A55). |
| `lamp.warmth` | Warmth | `lamp` | `L/LampActivity.kt: LampRows / LampControlDialog` | !red; 0–100%; slider ephemeral, numeric Save validates; no prefs. |
| `lamp.red` | Red night light | `lamp` | `L/LampActivity.kt: LampRows` | Red night light toggle immediate ephemeral state; hides warmth; no prefs. |
| `intercom.address` | Other Portal’s address | `intercom` | `L/IntercomActivity.kt: IntercomContent` | Actual field Other Portal's address (e.g. 192.168.1.42); local draft; Listen captures trimmed host; no prefs; A51. |
| `debug.enabled` | Debug Mode | `debug` | `L/ImmortalSettingsActivity.kt: IosDebugRow` | A page; Debug Mode immediate immortal_debug/debug_mode SharedPreferences write; no registry or build guard. |


## Additional actions, dialogs and return boundaries

This table inventories action families in the native bodies, including controls
without a registry or supplemental field ID. Comma-separated members and
catalog/collection rows must each become a G0 runtime row. All are PENDING.
Source symbol names are stable references; no synthetic title is presented as UI.

| Handle / entry | Source file / symbol | Action → state → destination / commit / return |
|---|---|---|
| A01 / all Settings shells | `L/ApprovedSettingsShell.kt: ApprovedSettingsShell` | Home → `HomeActivity` HOME tab with CLEAR_TOP/SINGLE_TOP, finishes current Activity. Category buttons → overview/history reset. Header “Light appearance”/“Dark appearance” → registry theme write; failure opens “Settings” dialog with Done/close. |
| A02 / all Settings shells | `ApprovedSettingsShell.openSearch`, `ApprovedSettingsSearchField` | Type search → up to 12 results; choose result → route plus control request. Empty/no-match and hidden prerequisite states need separate rows. |
| A03 / Advanced off | `L/ApprovedSettingsRows.kt: ApprovedSettingsAdvancedConsent` | “Turn on Advanced settings” commits flag then optional pending search navigation; Cancel/close leaves flag/destination unchanged. Advanced hint/gate opens same consent. |
| A04 / requested dependent field | `L/ImmortalSettingsActivity.kt: ImmortalSettingsScreen`; `SettingsScaffold` | “Setting prerequisite” dialog; “Review parent settings”/close only dismiss. Managed message also uses this dialog. |
| A05 / `screensaver` | `L/ScreensaverSettingsActivity.kt: screensaverSettingsDetail` | Enabled → “Preview screensaver” launches `PhotoFramePreviewActivity`. Registry calendar fields are a second entry to the calendar controls. |
| A06 / `styles` | `L/ScreensaverModeActivity.kt: ScreensaverModeContent` | Style dialog choices from `ScreensaverConfig.MODES` → immediate `setMode`; “Preview” → `PhotoFramePreviewActivity`. |
| A07 / `faces` | `L/FacePickerActivity.kt: FacePickerContent` | Face dialog choices from `FaceCatalog.entries`; size dialog conditional on chosen entry's sizes. “Preview” → `PhotoFramePreviewActivity`. |
| A08 / `clock` | `L/ClockSettingsActivity.kt: ClockSettingsContent` | “Preview” → `DigitalClockPreviewActivity` NEW_TASK; available independently of `digitalclock.enabled`. |
| A09 / `sources` | `L/ScreensaverSourcesActivity.kt: ScreensaverSourcesContent` | “Immortal photos” immediately calls `useDefault`, then local Back or standalone return-to-screensaver. “My photos & videos”, “Shared album link”, “Connect your phone” → `folder`, `album`, `phone`. |
| A10 / `sources` | `ScreensaverSourcesContent`, `sourcePage` | Nondefault → Selected information row. “Change source connection” appears when Advanced OR source page is folder/album. Technical source selected with Advanced off is preserved and gets explanatory footer. |
| A11 / `sources`, Advanced | `ScreensaverSourcesContent` | Immich/SMB/NAS/WebDAV/Web page navs → connection Activities. Shared album (`usesUrl`) additionally exposes Refresh album dialog; no generic screensaver row. |
| A12 / `folder` | `L/FolderPickerActivity.kt: FolderPicker` | Missing READ_EXTERNAL_STORAGE → permission request on entry and “Allow photo access” retry. Denied state returns before folder rows. |
| A13 / `folder` | `FolderPicker`, `navigate`, `up` | Root/subfolder row enters path; “Up”/Back moves to parent, Back at root finishes. Loading/unreadable/empty/count-capped states, thumbnail samples. Cancel always finishes. |
| A14 / `folder` | `FolderPicker` | “Use this folder” requires accessible listing; rechecks accessibility asynchronously, then `setFolder` and return-to-screensaver. Empty readable folder is not rejected by photo count. |
| A15 / `album`, `calendar-url`, `web` | `L/UrlEntryScreen.kt: UrlEntryScreen` | Typing → empty/invalid/provider hint; confirm enabled only recognized nonblank draft. Save guarded against double commit/finishing Activity; Cancel/Back never saves; caller determines source/return. |
| A16 / `calendar-url` | `CalendarUrlEntryActivity`, `UrlEntryScreen` | Nonblank existing URL → “Remove calendar” opens “Remove connection?”; Remove writes empty registry URL and returns `calendar`; Cancel/close leaves link. Album/Web callers supply no remove action. |
| A17 / `immich` | `L/ImmichConnectActivity.kt: ImmichConnectScreen.connect` | “Test & connect” tests URL/key and fetches albums; busy disables form. Success changes page extra/title to `albums` without persistence. Failure stays editable. Cancel/Back cancels and finishes. |
| A18 / `albums` | `ImmichConnectScreen` | Direct albums request auto-connects only with Advanced and existing nonblank URL/key; otherwise “Connect Immich first”. Whole library/album rows alter selection draft. Empty album list permits Whole library. |
| A19 / `albums` | `ImmichConnectScreen` | “Use this album” writes album ID/name then confirmed Immich connection/source, closes work and returns screensaver. Test and album selection do not commit. |
| A20 / `smb` | `L/SmbConnectActivity.kt: SmbConnectScreen.connect` | “Test & connect” requires server/share; success requires image count >0, immediately calls `setSmb`. Connected summary “Done” only returns; subsequent Back is not rollback. |
| A21 / `dav` | `L/DavConnectActivity.kt: DavConnectScreen.connect` | “Test & connect” requires URL; count >0 immediately calls `setDav`; 0/error retains prior source. Done returns; success already committed. |
| A22 / `dismiss` | `L/ScreensaverDismissAppActivity.kt: DismissAppScreen` | Target dialog: Immortal launcher/installed HA commit immediately; “Choose an installed app” opens apps dialog. Cancel/close only closes popup. |
| A23 / `dismiss` | `DismissAppScreen`, `loadLaunchableApps` | Installed apps dialog has search and loading/empty/no-match rows; selection `setDismissApp` immediately. Excludes self/curated hidden apps, one per package. |
| A24 / `dismiss` | `DismissAppScreen` | HA selected + installed companion + Advanced → dashboard-path dialog; Save `setDismissHaDashboard`; Cancel/close drops draft. Missing companion shows fallback warning. |
| A25 / `chime` | `L/ChimeSettingsActivity.kt: VoicePicker` | spokenTimeOn → installed offline TTS voices in **DropdownMenu**; choose immediately `setSpokenVoice`; dismiss no write. Default voice remains available if enumeration empty. |
| A26 / `chime` | `ChimeSettingsContent`, `TestButton` | spoken → Test voice; hourly → Play chime; golden-hour → Test sunrise/Test sunset; any of three enabled → Play ping. Calls `ChimePlayer`, no settings commit. |
| A27 / `welcome` | `L/WelcomeSettingsActivity.kt: AndroidVoiceRow` | Advanced AND enableTts → each offline voice has selection and “Test”; selection writes `ttsVoice`, test auditions only; engine stops/shuts down on disposal. |
| A28 / `welcome` | `WelcomeSettingsContent` | “Preview welcome overlay” launches `PhotoFramePreviewActivity` with EXTRA_SHOW_WELCOME=true; no form-wide Save/Cancel. |
| A29 / `sunrise` | `L/SunriseSettingsActivity.kt: SunriseContent` | “Preview (1-min ramp)” → `WakeLightActivity`, ramp=1, current chime; days selected/unselected and next-trigger/off information require state rows. |
| A30 / `sleep` | `L/SleepSettingsActivity.kt: startCountdown`, `stopCountdown` | Start no-ops when sleep timer disabled; otherwise arms scheduler and local countdown. Stop cancels timer. Duration edit while running re-arms/restarts from new duration. |
| A31 / `alarm` | `L/AlarmSettingsActivity.kt: AlarmContent.persist` | Day/time/enabled edits each save full config preserving initial label, reschedule. No days → next occurrence once; no separate Save/Cancel or label editor. |
| A32 / `countdowns` | `L/CountdownSettingsActivity.kt: CountdownSettingsContent` | Add requires nonblank name, writes event and reloads; annual means year=0. Icon dialog changes draft only. Remove confirmation writes removal. Form Cancel resets and invokes rememberApprovedSettingsBack (provided Settings history or Activity-finish fallback); see D16 for Tools entry. |
| A33 / `crypto` | `L/CryptoSettingsActivity.kt: CryptoSettingsContent` | Add nonblank CoinGecko ID; symbol fallback/uppercase via flow policy. Popular choices commit immediately; already-selected choices disabled. Remove confirmed; Cancel clears form and Back. |
| A34 / `todo` | `L/TodoSettingsActivity.kt: TodoSettingsContent` | Add nonblank draft; Complete/Undo immediate toggle; Remove confirmed. Completed present → Clear completed confirmation; Cancel clears draft/Back. |
| A35 / `news` | `L/NewsSettingsActivity.kt: NewsSettingsContent` | Add requires HTTP(S) URL with host (`approvedEditorsHttpUrl`), optional name; popular choices immediately add, subscribed choices disabled; Remove confirmed; Cancel clears/Back. |
| A36 / `stocks` | `L/StocksSettingsActivity.kt: StocksSettingsContent` | Add requires nonblank symbol; popular choices immediately add, existing disabled; Remove confirmed; Cancel clears/Back. Advanced → Alpaca account nav. |
| A37 / `alpaca` | `L/ApprovedEditorsAlpaca.kt: ApprovedEditorsAlpacaForm` | “Save credentials” needs effective key AND secret; blank fields reuse saved values; atomic `AlpacaConfig.setCredentials`; clears secret drafts, stays with success text. Cancel clears/Back. Show/Hide only changes visibility. |
| A38 / `options` | `L/OptionsSettingsActivity.kt: OptionsSettingsContent.saveScan` | Number dialogs Save into form draft; contract-type dialog selection is draft. “Save scan settings” validates/sorts delta band and persists type/band/expiry; Add also saves scan before ticker add. |
| A39 / `options` | `OptionsSettingsContent` | Remove ticker confirmation commits independently of parameter draft. Cancel restores parameter draft and Back. Alpaca account nav uses same credential editor. |
| A40 / `ha` | `L/HaSettingsActivity.kt: ApprovedEditorsHaContent.discover` | Save & connect validates draft URL/effective token, disables form during request. Only successful discovery commits atomic connection; transfers result and launches `ha-entities`. Empty successful entity set is valid. |
| A41 / `ha` | `ApprovedEditorsHaContent` | More options: Choose entities cancels work then `ha-entities`; Home Assistant dashboard cancels work then `HaDashboardActivity`. Form Cancel cancels request, restores URL, clears token and Back. |
| A42 / `ha-entities` | `ApprovedEditorsHaContent` | Unconfigured → setup nav (`ha`). Configured → Refresh entities, busy Cancel. Pinned rows Remove confirmation; search/group expand/collapse; query forces expansion; no-query expanded groups show first 12. |
| A43 / `ha-entities` | `ApprovedEditorsHaContent`, `ApprovedEditorsHaTransfer` | Pin immediate `HaConfig.addEntity`; already pinned row inert. Discovery transfer consumed once when connection matches, otherwise fetch. STOP/dispose rejects cancelled reply. |
| A44 / `ha-dashboard` | `L/HaDashboardActivity.kt: HouseRail` | Configured → room rows select local room; scene chips directly activate `scene.evening`, `scene.night`, `scene.movie`, `scene.leave`. Static IDs are source literals, not discovered scene existence. Unconfigured body is information only. |
| A45 / `ha-dashboard` | `EntityTile`, `DimmerTile`, `ClimateTile`, `MediaTile` | Dynamic toggle → HA service; dimmer drag writes 0–100 during drag and at end; climate +/- changes target by 1; previous/play-pause/next → `NowPlayingHub`. Sensor tiles read-only. No settings Save/Cancel. |
| A46 / `multiroom` | `L/ImmortalSettingsActivity.kt: MultiRoomContent.applyConnection` | Enabled toggle immediate registry write; enabled connection draft Apply validates port and batches host/port/user/password; “Sign in” first applies then `MaControl.testLogin`. Password IME Done only clears focus. |
| A47 / `mqtt` | `ImmortalSettingsActivity.MqttContent.apply` | Enabled toggle immediate. Apply/password IME Done batch host/port/user/password/TLS. TLS switch modifies draft/default port and invokes full apply, thus can commit other unsaved fields. Invalid port retains error. Status polled 800ms while enabled. |
| A48 / `boot` | `ImmortalSettingsActivity.BootAppsContent` | Loading/empty/installed-app rows. Package toggle serialized with Mutex, rereads stored selection, saves through `BootLaunch.setPackages`, then reloads; no Apply. |
| A49 / `health` | `ImmortalSettingsActivity.DeviceHealthContent`, `L/DevicePermissions.kt: all` | Five read-only capability rows: Screen off, Now playing, Install apps, Frame relaunch, System settings; missing permission adds guidance. Admin active → “Disable screen-off admin”; first tap arms, second calls `deactivateAdmin`. **No Advanced predicate in this body.** |
| A50 / `phone` | `L/RemotePairActivity.kt: RemotePairScreen`; `L/RemotePairUi.kt: enableRemoteAndMintPin` | ON_RESUME enables remote, reconciles/rebinds watch, ensures Fleet Agent and mints PIN. No LAN → Wi-Fi hint; LAN → QR/address/PIN. Done/Back finishes, does not disable remote. No pairing secret values recorded here. |
| A51 / `intercom` | `L/IntercomActivity.kt: IntercomContent` | Broadcast asks RECORD_AUDIO if missing; start/stop broadcasting, address/listen/stop listening operate LanAudio. Starting/connecting/failure states. STOP/dispose stops session; callbacks require active page/session/lifecycle. No persisted address setting. |
| A52 / `cameras` | `L/CameraViewerActivity.kt: CameraViewerScreen` | Add requires literal rtsp:// and length >8, optional name; commits camera. Remove confirmed. View → same Activity with stream ID. Cancel clears draft/finishes. |
| A53 / `cameras` stream | `CameraViewerActivity.CameraLiveStream` | Native VideoView + MediaController. Failure dialog Retry rebuilds attempt; Done/close/Back finishes stream Activity. STOP stops playback; resume resumes; release detaches listeners/controller. MediaController's runtime control census remains U4. |
| A54 / `lamp` | `L/LampActivity.kt: LampSettings`, `LampRows`, `LampControlDialog` | Brightness/warmth slider changes ephemeral state; numeric dialog Save validates 5–100/0–100%, Cancel drops draft; red toggle hides warmth. Preview launches second LampActivity for result. No SharedPreferences commit. |
| A55 / `lamp` preview | `LampPreview`, `finishPreview`, `restoreWindow` | Back returns current ephemeral values to editor; hide/show controls and Sleep. Preview applies window brightness/keep-awake, restores originals on stop/destroy/exit. Physical light output unverified. |
| A56 / `apk` | `L/ApkBrowserActivity.kt: ApkBrowserScreen` | Allow file access permission; App permissions → package details; Refresh files/Try again rescans. Install-paused predicate disables Install. Per-APK install dialog Cancel dismisses; Install rechecks pause then `HeadlessInstaller.install`, reports installing/result. App Store/back button finishes. |
| A57 / `help` | `L/HelpActivity.kt: HelpTour`, `PageContent` | Eight help pages; Back/Next changes clamped tour index; horizontal swipe >80 changes page; Skip/Done/system Back/Escape finishes. Last page “Open our GitHub page” ACTION_VIEW. Entry marks help seen. |
| A58 / `system`, `speed`, `debug` | `ImmortalSettingsActivity.ApprovedNativeSettingsPage`, `IosDebugRow` | System: Wi-Fi, Bluetooth, Date & time, Language & region, Accessibility, Storage, App permissions, All Android Settings ACTION_*; failed action falls back ACTION_SETTINGS. Portal Settings package `com.facebook.alohaapps.settings` or fallback. Speed test → Tools tab. Debug Mode writes local pref; health nav. |
| A59 / `widgets` library | `L/HomeActivity.kt: consumeWidgetLibraryIntent`, `ApprovedWidgetLibrary`; `L/ApprovedHomeWidgetLibrary.kt: ApprovedHomeWidgetLibraryScreen` | Real full-screen Dialog: family/provider selection, Home/Back, Customize home, provider Refresh/Home & widgets; header phone/sleep/search/settings handoffs. Add built-in commits widget and dismisses; provider selection allocates/binds/configures via Activity results (cancel cleanup). Advanced filters library; provider universe needs U4. |
| A60 / widget → settings | `L/HomeActivity.kt: widget action Settings` | World clock→world, Today→calendar, Photo memory→screensaver, Quick controls→quickbar, other built-ins→kind; Timers opens local tool body; provider configure Activity or app details. Move/Size/Remove/drag widget QA belongs to main's broader G0 census; no physical coverage imported here. |

The A59 source choices are 15 literal families: Today, Home Assistant, Calendar,
Weather, Alarm, World clock, Crypto, Quick controls, Photo memory, Timers, To-do,
News, Stocks, Options and Android app widget. `KIND_OPTIONS` alone is filtered by
Advanced; the Android app widget choice opens the provider sublist rather than
adding a built-in. Existing families show “On your home” but remain selectable.

A58's exact Android destination identifiers in `ApprovedNativeSettingsPage` are:
Wi-Fi → `Settings.ACTION_WIFI_SETTINGS`; Bluetooth → `ACTION_BLUETOOTH_SETTINGS`;
Date & time → `ACTION_DATE_SETTINGS`; Language & region → `ACTION_LOCALE_SETTINGS`;
Accessibility → `ACTION_ACCESSIBILITY_SETTINGS`; Storage →
`ACTION_INTERNAL_STORAGE_SETTINGS`; App permissions → `ACTION_APPLICATION_SETTINGS`;
All Android Settings → `ACTION_SETTINGS`. All constants belong to `android.provider.Settings`.
Portal Settings uses its package launch intent as recorded in A58.

## Bespoke gaps and source discrepancies

These are reconciliation facts, not device failures or proposed code edits.

| ID | Concrete source fact / consequence |
|---|---|
| D1 | `world.city` search entry says “Search city”; `WorldClockContent` has only 13 fixed selectable cities (`WORLD_CLOCK_CITIES`), no search field. Treat search-field coverage as a source mismatch, not a tested control. |
| D2 | `SunriseContent` renders hour/minute/ramp/chime/day controls when disabled, while registry scalar predicates require enabled. Native ramp slider is 1–45 with commit on release; registry range is 1–60/step 5. |
| D3 | `SleepSettingsContent` shows pause-audio regardless of `sleepTimerEnabled`, and close-app whenever Advanced, whereas both spec predicates require sleepTimerEnabled. It also bypasses generic screensaver-enabled filtering. |
| D4 | `WelcomeSettingsContent` uses immediate bespoke integer-second duration stepper (then duration clamp), whereas registry specifies milliseconds/step 200 and generic Int draft Save. Registry welcome toggles have no parent `welcomeEnabled` predicate; this body does not supply one. |
| D5 | `MqttContent`/`MultiRoomContent` use explicit connection forms instead of generic String/Int dialogs. MQTT TLS toggle applies the whole draft. `mqtt.validateCert` is read-only under `managed`; it has no native connection-form switch. |
| D6 | `screensaver.albumRefreshMin` is `visible=false` in registry, but Sources provides its Advanced+usesUrl picker. Search routes it to Sources yet prerequisite code first rejects screensaver disabled; Sources body itself does not gate on enabled. |
| D7 | Seven managed fields bypass ordinary spec visibility in `ApprovedManagedSettingsPage`; it renders metadata/value read-only even for hidden specs. `digitalclock.style/color/font/glow` are stored raw fields; `digitalclock.face` writes a curated group and derives its value. |
| D8 | `ApprovedSettingsPrerequisite` special case is `soundVolume`, but the actual key is `soundscapeVolume`; unavailable soundscape volume therefore falls to generic prerequisite text. Native-only IDs never receive this registry check. |
| D9 | Search/control IDs and rendered labels differ in several bespoke bodies (`display.mediaVolume`→“Media”; `ha.search`→“Search ${discovered.size} entities”; `sunrise.days`→“Wake on”; voice rows, alarm/source editors). Supplemental IDs are not automatically semantics/highlight IDs. |
| D10 | Activity title/extra resolution is significant: `albums` shares Immich Activity; `ha-entities` shares HA Activity; `alpaca` shares Stocks wrapper but main host uses separate form. `widgets` actual handoff is library, not the nominal native Settings branch. |
| D11 | `DeviceHealthContent` admin action is gated by admin-active only, despite comment calling it Advanced. `IosDebugRow` comment says debug builds, but body has no `BuildConfig.DEBUG` guard: page tier is its visible gate. |
| D12 | Registry completeness test exclusions are not a native control inventory. `SettingsDomainTest` excludes rich source/face/dismiss fields, sunrise days, chime spokenVoice, welcome colors/letter spacing and immortal constrainPageWidth. Welcome body exposes no greetingColor/clockColor/dateColor/greetingLetterSpacing editor; constrainPageWidth has no spec here. Preserve as stored/unexposed, not “covered.” |
| D13 | Credential initialization differs: HA/Alpaca use empty secret drafts with keep-stored semantics; Immich/SMB/DAV and MQTT/multiroom initialize masked drafts from stored values using `remember`. None proves runtime recreation/keyboard/privacy behavior. |
| D14 | SMB/DAV commit upon successful count>0; their Done only returns. Immich defers commit until Use this album; URLs/folder defer until confirm. A generic “Cancel leaves data unchanged” assertion after **all** source-success screens would be false. |
| D15 | `apk` is an Advanced route in navigation/search, but `ApkBrowserScreen` uses `ApprovedAppsShell`, not the Settings shell's Advanced gate. Do not infer a direct-launch Advanced predicate from its category. |
| D16 | `L/ToolsActivity.kt: ToolPageOverlay` reuses CountdownSettingsContent and supplies its own overlay Back/close, but does not provide `LocalApprovedSettingsBack`. The form's Cancel instead invokes `rememberApprovedSettingsBack`, whose fallback finishes the Activity unless an enclosing host supplies that local. Treat Tools Cancel separately from Settings Cancel/overlay Back. |
| D17 | `DisplaySection` updates From/Until locally with modulo-1440 wrapping, but `DisplayControl.setNightShiftStart/End` persist `min.coerceIn(0,1439)`. At a bound, UI state and written state differ by source; the apparent wrapped value is not proof of persisted wrapping. Runtime outcome/reload remains pending. |

## Runtime unknowns and G0 completion ledger

| ID | Unknown here | Required evidence owner / gate |
|---|---|---|
| U1 | Installed artifact correspondence to this working-tree source and current branch changes | Main: APK hash, revision/build identity and source/UI reconciliation; G0 |
| U2 | Actual labels, bounds, semantics, focus order, scroll-to-control, light/dark, keyboard geometry and input validation on each entry/state | Main: per-control captures/action evidence, including end-of-list and hidden rows; G0/G4/G6/G9 |
| U3 | Window/host continuity, Back/Cancel/commit persistence, >4-pane revisit and recreation across all 53 routes and shared parent entries | Main: destination/return and data fingerprints; G0/G4/G5 |
| U4 | Applicable dynamic counts: installed apps/providers, media folders/samples, Immich albums, offline TTS voices, collection members, HA entities/rooms/tiles, local APKs, native MediaController/system/third-party screens | Main: enumerate each installed universe and actual handoff controls; G0/G5/G10 |
| U5 | Actual permissions/hardware predicates (battery, storage, WRITE_SETTINGS, admin, microphone, installed HA companion, installer paused) and both reachable states | Main: record predicate result and sourced N/A reason; G0/G6 |
| U6 | Network/audio/light/provider success/failure/cancel outcomes, late replies, frame/input latency and motion acceptance | Main: real service/device evidence and measurement cohorts; G5/G7–G10 plus performance gates |

Source completeness checks for this file: compare all 53 page IDs/titles/owners,
all 95 registry IDs/types/titles/predicates/bindings and all 76 supplemental
IDs/titles/pages against the source; verify local document links. These checks
do not pass G0. The applicable **entry × control/action × state × return**
denominator is still pending, especially dynamic rows and platform handoffs.
