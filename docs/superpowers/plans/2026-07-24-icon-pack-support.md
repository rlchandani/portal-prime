# Android Icon-Pack Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Pure parsing logic (Task 2) is TDD-first.

**Goal:** Let the user install any standard Android icon-pack APK (the Nova / Apex / ADW / GO ecosystem format) and have the launcher apply its icons to home-screen apps. This is the legal way to get "premium Samsung/iOS-style icons": the user installs a pack **they own** (sideloaded via the existing APK flow — F-Droid packs like *Arcticons*, or any pack APK), and we ship **zero copyrighted art**. When no pack is selected, behaviour is exactly as today.

**Architecture (current state, cited):**
- `IconRenderer.renderAppIcon(context, drawable, sizePx = 144): Bitmap` composites adaptive-icon layers and applies a 22%-radius squircle mask via `DST_IN` — `IconRenderer.kt:41`. This is the single normalization point we reuse so pack icons match the house style.
- `loadApps(context)` (`HomeActivity.kt:3466`) queries `ACTION_MAIN`/`CATEGORY_LAUNCHER`, filters via `Curation`, and per app resolves a drawable then calls `IconRenderer.renderAppIcon` → `AppEntry.icon` (an `ImageBitmap`). The icon-override hook is already wired at `HomeActivity.kt:3479` (`Curation.iconOverride(pkg): Int?`).
- `AppEntry` (`HomeActivity.kt:179-185`): `label`, `component: ComponentName`, `icon: ImageBitmap`, `folder`, `uninstallable`. The launch `ComponentName` is `ComponentName(ai.packageName, ai.name)` (`HomeActivity.kt:3490`) — this is our appfilter lookup key.
- `loadApps` already runs on `Dispatchers.IO` (`HomeActivity.kt:581`), so pack parsing/decoding stays off the main thread with no new threading.
- Persistence pattern to mirror: `DockStore` (`DockStore.kt`) — an `object` with `PREFS`/`KEY` consts, tolerant `load`/`save`, and `internal` pure helpers for unit tests.
- Settings pattern: declarative `SettingSpec` registry (`settings/SettingSpec.kt`); the `immortal` domain is `SettingsDomains.kt:484`. `NavSpec` (`SettingSpec.kt:291`) is an on-device-only row that shows a current-value label and launches a bespoke picker Activity — the exact shape we want (the pack list is dynamic and device-specific, so it does not fit an `EnumSpec` with static options).
- Picker Activity precedent: `FacePickerActivity` (`FacePickerActivity.kt:52`) — a `ComponentActivity` + Compose, registered `exported="false"` with `Theme.SampleApp` (`AndroidManifest.xml:181-184`, sub-screen block `:192-197`).

**Tech stack:** Kotlin, Jetpack Compose, `PackageManager`, `Resources` / `getResourcesForApplication`, `ResourcesCompat`, `XmlPullParser`, existing `SharedPreferences` store pattern, JUnit + Mockito (JVM unit tests, no Robolectric — same as the rest of `app/src/test`).

## Global Constraints

- Copyright header on every NEW file, verbatim:
  ```
  /*
   * Copyright (c) 2026 Starbright Lab.
   *
   * This source code is licensed under the MIT license found in the
   * LICENSE file in the root directory of this source tree.
   */
  ```
  Never the Meta Platforms template.
- `minSdk 24` / `targetSdk 36`; device Portal Android 9/10 (API 28/29), arm64, **no Google Play Services**. Icon packs are plain APKs with resources + an `appfilter.xml`; no Play dependency.
- Match existing style; edit over rewrite; keep changes focused. Many small files.
- Immutable data: parser returns new maps; stores return new lists; never mutate in place.
- Build: `./gradlew :app:assembleDebug --offline`. Compile: `./gradlew :app:compileDebugKotlin --offline`. Unit tests (first run needs network for deps): `./gradlew :app:testDebugUnitTest`.
- Glyph safety: picker UI uses plain characters / stroke shapes only (Portal Android 10 font coverage).
- On-device Compose verified manually; pure logic is unit-tested (repo convention).

---

## Background: the icon-pack format (what we parse)

An icon pack is an ordinary APK that:
1. Declares one or more theme intent-filters so launchers can discover it.
2. Bundles an **`appfilter.xml`** mapping launcher components to drawable names, plus the drawables in `res/drawable*`.

`appfilter.xml` entries look like:
```xml
<resources>
  <iconback img1="iconback" />
  <iconmask img1="iconmask" />
  <iconupon img1="iconupon" />
  <scale factor="0.75" />
  <item component="ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" drawable="chrome" />
  <item component="ComponentInfo{org.chromium.chrome/...}" drawable="chrome" />
  <item component=":LEGACY_ICON" drawable="..." />   <!-- ignore non-ComponentInfo entries -->
</resources>
```
- `component="ComponentInfo{<pkg>/<activity>}"` → look up drawable `<name>` via `resources.getIdentifier(name, "drawable", packPkg)`.
- `<iconback>`, `<iconmask>`, `<iconupon>`, `<scale>` describe how to **theme apps NOT in the map** (draw the app's own icon scaled onto the pack's back layer, masked, with an overlay). This is **Phase 2 (optional)** — Phase 1 only applies explicit `<item>` mappings and falls back to today's behaviour otherwise.

`appfilter.xml` lives either in **`assets/appfilter.xml`** or as an **XML resource** (`res/xml/appfilter.xml`, found via `resources.getIdentifier("appfilter", "xml", packPkg)`). We must handle both.

---

## File structure

- **Create** `IconPackRepository.kt` — discovery (`PackageManager` query) + resource-loading side of parsing. Impure (touches `PackageManager`/`Resources`); thin.
- **Create** `IconPackParser.kt` — **pure** `appfilter.xml` parsing (`Reader`/`InputStream` → `Map<ComponentName, String>` + optional `IconMask` metadata). No Android framework deps beyond `ComponentName` parsing (extract the `ComponentInfo{…}` split as a pure string fn so it is trivially testable). Unit-tested.
- **Create** `IconPackStore.kt` — persist the selected pack package (mirrors `DockStore`). `""`/absent = System default.
- **Create** `IconPackPickerActivity.kt` — Compose picker listing discovered packs + "System default"; writes `IconPackStore`; finishes.
- **Modify** `Curation.kt` — no schema change required, but document that pack icons take precedence over the (currently empty) `iconOverride` map; decide order in Task 3.
- **Modify** `HomeActivity.kt` — in `loadApps`, resolve the active pack once, and per app prefer the pack drawable; on any miss fall back to the existing `iconOverride`-then-`loadIcon` path. Still funnel through `IconRenderer`.
- **Modify** `settings/SettingsDomains.kt` — add a `NavSpec("iconPack", …, activity = IconPackPickerActivity::class.java)` to the `immortal` domain (`:484`), with a `value` lambda showing the current pack label or "System default".
- **Modify** `AndroidManifest.xml` — register `IconPackPickerActivity` in the sub-screen block (`:192-197`), `exported="false"`, `Theme.SampleApp`.
- **Tests:** `IconPackParserTest.kt` (the bulk — sample XML variants), `IconPackStoreTest.kt` (serialize/round-trip/tolerant defaults).

---

### Task 1: Icon-pack discovery + resource access (`IconPackRepository`)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/IconPackRepository.kt`

**Interfaces:**
```kotlin
data class IconPackInfo(val packageName: String, val label: String)  // 'preview' = the pack's own app icon, loadable on demand; keep model tiny

object IconPackRepository {
  // Intent actions every mainstream pack declares (any one match = a pack).
  private val PACK_ACTIONS = listOf(
    "org.adw.launcher.THEMES",
    "com.novalauncher.THEME",
    "com.gau.go.launcherex.theme",
    "com.anddoes.launcher.THEME",
  )

  /** Discover installed packs. queryIntentActivities per action, dedupe by package, sort by label. */
  fun discover(context: Context): List<IconPackInfo>

  /** Load the pack's Resources, then read appfilter (assets first, then res/xml). Returns null if unavailable. */
  fun loadAppfilter(context: Context, packPkg: String): IconPack?   // IconPack = parsed map + mask meta + a Resources handle
}
```

**Steps:**
- [ ] `discover`: for each action, `pm.queryIntentActivities(Intent(action), 0)`, collect `activityInfo.packageName` + `ri.loadLabel(pm)`, dedupe by package, sort by label. (Some packs declare the filter on `<activity>`, some on `<activity-alias>`; the query covers both.)
- [ ] `loadAppfilter`: `val res = pm.getResourcesForApplication(packPkg)`. Try `res.assets.open("appfilter.xml")` first; on `IOException`/not-found, try `res.getIdentifier("appfilter", "xml", packPkg)` → `res.getXml(id)`. Hand the stream/parser to `IconPackParser` (Task 2). Wrap in `runCatching`; return null on any failure (pack removed mid-parse, no appfilter, locked resources).
- [ ] Keep this file thin: no drawable decoding here (that is Task 3, per-app, so it stays lazy).

---

### Task 2: Pure appfilter parser (`IconPackParser`) — TDD

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/IconPackParser.kt`
- Test: `app/src/test/java/com/immortal/launcher/IconPackParserTest.kt`

**Interfaces:**
```kotlin
data class IconMask(          // Phase-2 metadata; parsed now, applied later
  val scale: Float? = null,
  val iconBack: List<String> = emptyList(),   // img1,img2… (packs may rotate multiple backs)
  val iconMask: String? = null,
  val iconUpon: String? = null,
)
data class ParsedAppfilter(
  val map: Map<ComponentName, String>,   // launch component → drawable name
  val mask: IconMask,
)

object IconPackParser {
  /** Parse from any XmlPullParser (works for both res/xml getXml() and assets via XmlPullParserFactory). */
  fun parse(parser: XmlPullParser): ParsedAppfilter

  /** Pure: "ComponentInfo{pkg/activity}" -> ComponentName, or null if malformed / not a ComponentInfo. */
  internal fun parseComponent(raw: String): ComponentName?
}
```

**TDD test cases (write first, RED):**
- [ ] Well-formed `<item component="ComponentInfo{a.b/a.b.Main}" drawable="foo"/>` → map has that component → "foo".
- [ ] Multiple items, later duplicate component **wins last** (packs list variants; last is conventional).
- [ ] Non-`ComponentInfo` components (`:LEGACY_ICON`, empty, `com.foo` without braces) are skipped, not crashed.
- [ ] Missing `drawable` attr → entry skipped.
- [ ] `<scale factor="0.8"/>`, `<iconback img1="ib"/>`, `<iconmask img1="im"/>`, `<iconupon img1="iu"/>` → populate `IconMask`; multiple `iconback` imgs collected.
- [ ] Garbage / truncated XML → returns empty map + empty mask, never throws (feed a `runCatching` at the boundary too).
- [ ] `parseComponent` unit tests: valid, missing brace, missing slash, whitespace, trailing junk.

**Implementation (GREEN):** iterate `XmlPullParser` events; on `START_TAG` switch on tag name (`item`, `scale`, `iconback`, `iconmask`, `iconupon`); build immutable maps via `buildMap`. `parseComponent`: strip `ComponentInfo{`…`}`, `split('/')`, validate two non-blank halves → `ComponentName(pkg, activity)`. This class has **no `PackageManager`/`Resources` dependency**, so it runs on the JVM under plain JUnit.

---

### Task 3: Apply the pack in `loadApps`

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeActivity.kt` (`loadApps`, `:3466-3505`)

**Steps:**
- [ ] At the top of `loadApps` (already on `Dispatchers.IO`), read `IconPackStore.selected(context)`. If non-empty, `IconPackRepository.loadAppfilter(context, pkg)` **once** → an `IconPack` (parsed map + a cached `Resources` handle). Parse cost is paid once per `loadApps`, not per app.
- [ ] Per app, resolve the drawable in this precedence order:
  1. **Icon pack** — look up `ComponentName(ai.packageName, ai.name)` in the pack map. If present, `val id = res.getIdentifier(drawableName, "drawable", packPkg)`; if `id != 0`, `ResourcesCompat.getDrawable(res, id, null)`.
  2. **Component-mismatch fallback (packs map by a different activity than the app launches):** if the exact component misses, try any map entry whose **package** matches `ai.packageName` (build a `Map<String, String>` package→drawable from the parsed map, first-wins, alongside the component map). Many packs key the main/aliased activity, not the resolved launcher activity.
  3. Existing `Curation.iconOverride(pkg)` bundled hero drawable.
  4. `ri.loadIcon(pm)` (today's default).
- [ ] Whatever drawable wins, still call `IconRenderer.renderAppIcon(context, drawable, 144)` (`IconRenderer.kt:41`) so the squircle/normalization is consistent. Pack drawables are often already square/adaptive; the mask is idempotent enough for the house look. (Phase 2 may skip the squircle for packs that supply their own `iconmask`.)
- [ ] Keep the whole per-app body inside the existing `runCatching { … }.getOrNull()` (`:3475`) so a single bad drawable never drops the app.

**Note on Curation ordering:** pack takes precedence over `iconOverride` (user explicitly chose a pack). Document this in a comment near `Curation.iconOverride`.

---

### Task 4: Persistence store (`IconPackStore`)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/IconPackStore.kt`
- Test: `app/src/test/java/com/immortal/launcher/IconPackStoreTest.kt`

**Interface (mirror `DockStore.kt`):**
```kotlin
object IconPackStore {
  private const val PREFS = "immortal_iconpack"
  private const val KEY = "selected_pack"
  const val SYSTEM_DEFAULT = ""   // sentinel: use app's own icons

  fun selected(context: Context): String       // "" if unset
  fun setSelected(context: Context, pkg: String)  // "" clears back to default
}
```
- [ ] Tests: default is `""`; set/get round-trips; setting `""` clears. (Trivial but keeps the store honest, matching `DockStoreTest`.)

---

### Task 5: Settings integration — `NavSpec` + picker Activity

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt` (add to `immortal` domain, `:484`; add `"iconPack"` to its `sections` map under an "Appearance"/"Home" section header near `tileSize`).
- Create: `app/src/main/java/com/immortal/launcher/IconPackPickerActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (sub-screen block `:192-197`).

**Why `NavSpec`, not `EnumSpec`:** the option list is **dynamic** (discovered from installed APKs, device-specific) and needs previews. `EnumSpec` options are static and appear on the phone-remote schema; a bespoke picker is the established pattern for dynamic/device lists (`FacePickerActivity`, `ScreensaverDismissAppActivity`). `NavSpec.metaJson` is null, so it is on-device-only — acceptable: pack selection is inherently local to the Portal that has the APK installed.

**Steps:**
- [ ] Add:
  ```kotlin
  NavSpec(
    key = "iconPack",
    title = "Icon pack",
    value = { c, _ ->
      val p = IconPackStore.selected(c)
      if (p.isEmpty()) "System default"
      else IconPackRepository.discover(c).firstOrNull { it.packageName == p }?.label ?: "System default"
    },
    activity = IconPackPickerActivity::class.java,
    help = "Use icons from an installed icon-pack app. Install a pack APK first (Store / sideload).",
  )
  ```
- [ ] `IconPackPickerActivity` (Compose, `Theme.SampleApp`, like `FacePickerActivity.kt:52`): load `IconPackRepository.discover(this)` on `Dispatchers.IO`; render a list = **"System default"** row + one row per pack (label + small preview = the pack's launcher icon). Tapping a row calls `IconPackStore.setSelected` and `finish()`. The current selection shows a checkmark/selected state. Empty-state text when no packs found: "No icon packs installed. Install a pack APK from the Store or sideload one."
- [ ] Register in manifest: `<activity android:name=".IconPackPickerActivity" android:exported="false" android:theme="@style/Theme.SampleApp" />`.
- [ ] Home refresh: `loadApps` re-runs when `HomeActivity` resumes (it is the `withContext(Dispatchers.IO)` producer at `:581`); confirm the picker returning triggers a reload. If the home does not already reload on resume, add a `LaunchedEffect`/lifecycle-resume trigger keyed to `IconPackStore.selected` — verify manually on device.

---

### Task 6: Caching & performance

- [ ] `loadAppfilter` is called **once per `loadApps`** (Task 3), not per app — parsing ~a few thousand `<item>` entries once is cheap and already off-main-thread.
- [ ] Add a tiny in-memory cache in `IconPackRepository`: `@Volatile private var cache: Pair<String, IconPack>?` keyed by pack package, so repeated `loadApps` runs (resume, tile-size change) skip re-parsing. Invalidate when the selected package changes or the pack version code changes (compare `PackageInfo.getLongVersionCode`). Keep it a simple single-entry cache — only one pack is active at a time.
- [ ] Do **not** cache decoded `Bitmap`s here; `AppEntry.icon` already holds the rendered `ImageBitmap` for the session, and `loadApps` reruns are infrequent.

---

### Task 7: Edge cases (verify each)

- [ ] **Pack uninstalled after selection:** `getResourcesForApplication` throws `NameNotFoundException` → `loadAppfilter` returns null → every app falls back to its own icon. (Do **not** auto-clear the stored pref — the user may reinstall; the picker shows the missing pack as unavailable / falls to "System default" label.)
- [ ] **Pack with no `appfilter.xml`:** both asset and xml-resource lookups miss → null → default icons. No crash.
- [ ] **Component mismatch:** handled by the package-level fallback (Task 3 step 2). Note some packs use `component=":LEGACY_ICON"` or wildcards — skipped by `parseComponent` (not a valid `ComponentInfo`), which is correct.
- [ ] **Drawable name present but resource missing** (`getIdentifier` returns 0): fall through to default for that app.
- [ ] **No Play Store (Meta Portal):** packs arrive via the existing sideload/APK flow (`ApkInstallActivity` / `StoreActivity`); nothing special needed — discovery is purely `PackageManager`-based. F-Droid packs (Arcticons, etc.) work as-is.
- [ ] **Malformed/hostile XML:** parser is defensive (Task 2) and wrapped in `runCatching`.

---

## Phase 2 (optional, out of scope for first landing)

Fallback theming for apps **not** in `appfilter.xml`: draw the app's own icon scaled by `<scale>` onto the pack's `<iconback>` layer, apply `<iconmask>` (DST_IN) and `<iconupon>` overlay. Requires extending `IconRenderer` with a `renderThemedIcon(base, mask: IconMask, packRes)` path. The `IconMask` metadata is already parsed in Task 2, so this is additive. Mark clearly as Phase 2; Phase 1 ships explicit mappings + graceful fallback only.

---

## Verification

- [ ] `./gradlew :app:testDebugUnitTest` — parser + store tests green (this is the coverage that matters; the rest is framework-bound).
- [ ] `./gradlew :app:assembleDebug --offline` — clean build.
- [ ] Manual on device: install an F-Droid pack (e.g. Arcticons) → Settings ▸ Immortal ▸ Icon pack ▸ select → home icons change; select "System default" → icons revert; uninstall the pack while selected → icons revert without crash.
