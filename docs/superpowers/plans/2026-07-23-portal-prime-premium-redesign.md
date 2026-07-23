# Portal Prime — Premium UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the Immortal launcher fork into a premium, visually polished portal display app — keeping all functional logic intact while replacing the flat, boilerplate UI with glass-morphism tiles, spring physics, a full Material 3 type scale, and native Compose clock faces.

**Architecture:** Visual-only redesign pass first — no ViewModel extraction, no Compose Navigation migration, no screensaver View→Compose migration. Those are Phase 2. This plan targets the design system, tile appearance, animations, and clock faces only. Every change is additive or a drop-in replacement; the grid model, photo sources, MQTT, and presence logic are untouched.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose BOM 2026.02.01, Material 3, AGP 9.2.1, Inter static fonts (existing), Android API 28–36

## Global Constraints

- Package name stays `com.immortal.launcher` — OTA self-update requires signature + package continuity
- `minSdk = 24`, `targetSdk = 36` — no API below 24
- All Compose `blur()` calls must be guarded with `if (Build.VERSION.SDK_INT >= 31)` — `RenderEffect` requires API 31; Portal runs API 28/29 so the blur degrades gracefully to no-blur
- No new external dependencies — work within existing BOM; no new Gradle modules
- Do not touch: `PhotoFrameController`, `FaceRenderer`, `ImmichSource`, `SmbSource`, `DavSource`, `MqttService`, `MultiRoomService`, `FleetAgentService`, `PresenceState`, `DreamPolicy`, `HomeGrid`, `HomeLayoutModel`, `StoreCatalog`, `Weather`, `CalendarFeed`
- Build command: `./gradlew assembleDebug` from `~/portal-prime/`
- Install command: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- All color tokens must be defined in `Color.kt` and referenced via `MaterialTheme.colorScheme` — no new hardcoded hex literals

---

## File Map

| File | Action | What changes |
|---|---|---|
| `ui/theme/Color.kt` | **Modify** | Full premium dark palette replacing boilerplate purple + Meta blue |
| `ui/theme/Type.kt` | **Modify** | Add `displayLarge`, `displayMedium`, `headlineLarge` to type scale |
| `ui/theme/Theme.kt` | **Modify** | Rename `SampleAppTheme` → `PortalPrimeTheme`; update all call sites |
| `SettingsComponents.kt` | **Modify** | Replace all `Color(0xFF1C1C1E)` / `Color(0xFF7C7C7C)` / `Color(0xFF9A9A9A)` hardcodes with `MaterialTheme.colorScheme` tokens |
| `ui/components/GlassTile.kt` | **Create** | Reusable frosted-glass tile surface composable |
| `HomeActivity.kt` | **Modify** | Replace flat tile `Surface` with `GlassTile`; add spring physics to drag-drop; replace `LinearEasing` jiggle with spring jiggle |
| `ui/clock/PrimeDigitalClock.kt` | **Create** | Canvas-drawn premium digital clock face (replaces `DigitalClockFaceView` TextView) |
| `ui/clock/SplitFlapClock.kt` | **Create** | Native Compose split-flap clock (replaces `FlipWebClockFaceView` WebView) |
| `ClockFaces.kt` | **Modify** | Wire `makeClockFace` to return new Compose-backed clock faces for DIGITAL and FLIP modes via a `ComposeClockFaceView` bridge |

---

## Task 1: Premium Color System

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/immortal/launcher/ui/theme/Theme.kt`
- Grep: `SampleAppTheme` across all `.kt` files to find call sites

**Interfaces:**
- Produces: `PortalPrimeTheme` composable replacing `SampleAppTheme`; new color tokens: `GlassSurface`, `GlassBorder`, `ClockPrimary`, `ClockSecondary`, `AccentGlow`

- [ ] **Step 1: Find all SampleAppTheme call sites**

```bash
grep -rn "SampleAppTheme" ~/portal-prime/app/src/main/java/ --include="*.kt"
```
Expected: ~5-10 results across Activity files.

- [ ] **Step 2: Replace Color.kt with premium palette**

Replace the entire file content:

```kotlin
package com.immortal.launcher.ui.theme

import androidx.compose.ui.graphics.Color

// ── Boilerplate removed ───────────────────────────────────────────────────────
// All purple/teal Material defaults replaced with Portal Prime palette.

// Primary accent — a cool indigo-blue that reads premium on dark backgrounds
val PrimeBlue         = Color(0xFF4F8EF7)
val PrimeBlueLight    = Color(0xFF82AAFF)
val PrimeBlueDim      = Color(0xFF2D5BB5)
val OnPrimeBlue       = Color(0xFFFFFFFF)

// Backgrounds — deep near-black with slight warm undertone, not pure #000
val BackgroundDark    = Color(0xFF0D0D10)
val SurfaceDark       = Color(0xFF16161C)
val SurfaceVariant    = Color(0xFF1E1E26)
val SurfaceContainer  = Color(0xFF22222C)

// Glass tile — translucent white overlay for frosted glass effect
val GlassSurface      = Color(0x14FFFFFF)   // 8% white
val GlassBorder       = Color(0x1FFFFFFF)   // 12% white
val GlassHighlight    = Color(0x0AFFFFFF)   // 4% white (inner top edge)

// Content
val ContentPrimary    = Color(0xFFF0F0F5)
val ContentSecondary  = Color(0xFFABABBD)
val ContentTertiary   = Color(0xFF6B6B80)
val ContentOnAccent   = Color(0xFFFFFFFF)

// Clock faces
val ClockPrimary      = Color(0xFFEEEEFF)   // near-white with blue tint
val ClockSecondary    = Color(0xFF8888AA)   // subdued for seconds / colon
val AccentGlow        = Color(0xFF6699FF)   // neon halo color for glow shadow

// Semantic
val Success           = Color(0xFF4CAF50)
val Warning           = Color(0xFFFF9800)
val Error             = Color(0xFFCF6679)
```

- [ ] **Step 3: Update Theme.kt — rename, rewire palette, keep dynamic color disabled**

Replace the entire Theme.kt:

```kotlin
package com.immortal.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val PrimeDarkColorScheme = darkColorScheme(
    primary              = PrimeBlue,
    onPrimary            = OnPrimeBlue,
    primaryContainer     = PrimeBlueDim,
    onPrimaryContainer   = PrimeBlueLight,
    secondary            = ContentSecondary,
    onSecondary          = ContentOnAccent,
    secondaryContainer   = SurfaceVariant,
    onSecondaryContainer = ContentPrimary,
    tertiary             = AccentGlow,
    onTertiary           = ContentOnAccent,
    background           = BackgroundDark,
    onBackground         = ContentPrimary,
    surface              = SurfaceDark,
    onSurface            = ContentPrimary,
    surfaceVariant       = SurfaceVariant,
    onSurfaceVariant     = ContentSecondary,
    surfaceContainer     = SurfaceContainer,
    outline              = GlassBorder,
    error                = Error,
    onError              = ContentOnAccent,
)

@Composable
fun PortalPrimeTheme(
    darkTheme: Boolean = true,  // Portal is always dark
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        else -> PrimeDarkColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
```

- [ ] **Step 4: Rename all SampleAppTheme → PortalPrimeTheme call sites**

```bash
cd ~/portal-prime
grep -rln "SampleAppTheme" app/src/main/java/ --include="*.kt" | xargs sed -i '' 's/SampleAppTheme/PortalPrimeTheme/g'
grep -rn "SampleAppTheme" app/src/main/java/ --include="*.kt"
```
Expected: zero results after the replacement.

- [ ] **Step 5: Build to verify no compile errors**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/ui/theme/
git commit -m "feat(theme): premium dark color system, rename SampleAppTheme → PortalPrimeTheme"
```

---

## Task 2: Display-Scale Typography

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/ui/theme/Type.kt`

**Interfaces:**
- Produces: `displayLarge` (57sp), `displayMedium` (45sp), `headlineLarge` (32sp) added to `Typography`; all existing styles preserved

- [ ] **Step 1: Replace Type.kt with full type scale**

```kotlin
package com.immortal.launcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.immortal.launcher.R

private val InterFontFamily = FontFamily(
    Font(R.font.inter,        weight = FontWeight.Normal),
    Font(R.font.inter_medium, weight = FontWeight.Medium),
    Font(R.font.inter_bold,   weight = FontWeight.Bold),
)

val Typography = Typography(
    // ── Display — used for clock faces and ambient large numbers ──────────────
    displayLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 57.sp,
        lineHeight  = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 45.sp,
        lineHeight  = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 36.sp,
        lineHeight  = 44.sp,
        letterSpacing = 0.sp,
    ),
    // ── Headline — section headers, tile labels ───────────────────────────────
    headlineLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 32.sp,
        lineHeight  = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 28.sp,
        lineHeight  = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 24.sp,
        lineHeight  = 32.sp,
    ),
    // ── Title — cards, sheet headers ─────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 22.sp,
        lineHeight  = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Bold,
        fontSize    = 18.sp,
        lineHeight  = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    // ── Body ──────────────────────────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 18.sp,
        lineHeight  = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    // ── Label ─────────────────────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
    ),
    labelMedium = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily  = InterFontFamily,
        fontWeight  = FontWeight.Medium,
        fontSize    = 12.sp,
        lineHeight  = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
```

- [ ] **Step 2: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/ui/theme/Type.kt
git commit -m "feat(theme): full Material 3 type scale with displayLarge/displayMedium for clock faces"
```

---

## Task 3: Tokenize SettingsComponents

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/SettingsComponents.kt`

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme` tokens from Task 1
- Produces: zero hardcoded hex literals in `SettingsComponents.kt`

- [ ] **Step 1: Find all hardcoded color literals in SettingsComponents.kt**

```bash
grep -n "Color(0x" ~/portal-prime/app/src/main/java/com/immortal/launcher/SettingsComponents.kt
```
Note the line numbers — you'll fix each one in Step 2.

- [ ] **Step 2: Replace all hardcoded colors with theme tokens**

Mapping to apply — search and replace each:

| Old literal | New token |
|---|---|
| `Color(0xFF1C1C1E)` | `MaterialTheme.colorScheme.surfaceContainer` |
| `Color(0xFF2C2C2E)` | `MaterialTheme.colorScheme.surfaceVariant` |
| `Color(0xFF7C7C7C)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFF9A9A9A)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFFFFFFFF)` | `MaterialTheme.colorScheme.onSurface` |
| `Color(0xFF0866FF)` | `MaterialTheme.colorScheme.primary` |

```bash
cd ~/portal-prime
sed -i '' \
  's/color = Color(0xFF1C1C1E)/color = MaterialTheme.colorScheme.surfaceContainer/g' \
  app/src/main/java/com/immortal/launcher/SettingsComponents.kt

sed -i '' \
  's/Color(0xFF7C7C7C)/MaterialTheme.colorScheme.onSurfaceVariant/g' \
  app/src/main/java/com/immortal/launcher/SettingsComponents.kt

sed -i '' \
  's/Color(0xFF9A9A9A)/MaterialTheme.colorScheme.onSurfaceVariant/g' \
  app/src/main/java/com/immortal/launcher/SettingsComponents.kt
```

- [ ] **Step 3: Verify no hardcoded hex colors remain**

```bash
grep -n "Color(0x" ~/portal-prime/app/src/main/java/com/immortal/launcher/SettingsComponents.kt
```
Expected: zero lines (or only truly semantic one-off colors that have no token equivalent — note them with a comment).

- [ ] **Step 4: Add missing MaterialTheme import if needed**

Check if `import androidx.compose.material3.MaterialTheme` is present at the top of the file. If not:

```bash
sed -i '' '1,/^import/s/^import androidx.compose.material3/import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3/' \
  app/src/main/java/com/immortal/launcher/SettingsComponents.kt
```

Or add it manually at the import block.

- [ ] **Step 5: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/SettingsComponents.kt
git commit -m "refactor(settings): replace hardcoded hex colors with MaterialTheme colorScheme tokens"
```

---

## Task 4: GlassTile Component

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/ui/components/GlassTile.kt`

**Interfaces:**
- Consumes: `GlassSurface`, `GlassBorder` from `Color.kt` (Task 1); `MaterialTheme.colorScheme`
- Produces: `GlassTile(modifier, onClick, content)` composable; `GlassTileDefaults` object

- [ ] **Step 1: Create the ui/components directory**

```bash
mkdir -p ~/portal-prime/app/src/main/java/com/immortal/launcher/ui/components
```

- [ ] **Step 2: Create GlassTile.kt**

```kotlin
package com.immortal.launcher.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.GlassBorder
import com.immortal.launcher.ui.theme.GlassHighlight
import com.immortal.launcher.ui.theme.GlassSurface

object GlassTileDefaults {
    val CornerRadius: Dp = 20.dp
    val BorderWidth: Dp = 1.dp
}

/**
 * A frosted-glass tile surface. On API 31+ the background blurs the content
 * behind it. On API <31 (Portal's Android 9/10) it degrades to a translucent
 * dark fill — still premium-looking, just without the blur.
 *
 * Usage:
 *   GlassTile(modifier = Modifier.size(96.dp), onClick = { ... }) {
 *       Icon(...)
 *       Text(...)
 *   }
 */
@Composable
fun GlassTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = GlassTileDefaults.CornerRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }

    // On API <31: blur modifier is a no-op so we use a slightly more opaque fill
    val fillColor = if (Build.VERSION.SDK_INT >= 31) GlassSurface
                    else Color(0x22FFFFFF)  // 13% white — a touch more visible without blur

    val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
        Modifier.blur(radius = 24.dp)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(blurModifier)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassHighlight,  // subtle highlight on top edge
                        fillColor,
                    )
                ),
                shape = shape,
            )
            .border(
                width = GlassTileDefaults.BorderWidth,
                color = GlassBorder,
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
```

- [ ] **Step 3: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/ui/components/GlassTile.kt
git commit -m "feat(ui): GlassTile frosted-glass composable with API-level blur degradation"
```

---

## Task 5: Apply GlassTile to Home Screen + Spring Physics

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeActivity.kt`

**Interfaces:**
- Consumes: `GlassTile` from Task 4
- Produces: home screen tiles use glass surface; drag-drop uses spring easing; jiggle animation uses spring instead of LinearEasing

- [ ] **Step 1: Find the tile Surface composable in HomeActivity.kt**

```bash
grep -n "Surface\|RoundedCornerShape\|0xFF1C1C1E\|0xFF2C2C2E" \
  ~/portal-prime/app/src/main/java/com/immortal/launcher/HomeActivity.kt | head -30
```
Note the line numbers of tile background `Surface` calls.

- [ ] **Step 2: Add GlassTile import to HomeActivity.kt**

Add at the import block (find the last `import` line):
```kotlin
import com.immortal.launcher.ui.components.GlassTile
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
```

- [ ] **Step 3: Find and replace the jiggle animation easing**

```bash
grep -n "LinearEasing\|infiniteRepeatable" \
  ~/portal-prime/app/src/main/java/com/immortal/launcher/HomeActivity.kt | head -10
```

The jiggle is an `infiniteRepeatable(tween(..., easing = LinearEasing))`. Replace the `tween` spec with a spring-style back-and-forth. Find the block and change:

```kotlin
// BEFORE (find this pattern):
infiniteRepeatable(
    animation = tween(durationMillis = 120, easing = LinearEasing),
    repeatMode = RepeatMode.Reverse,
)

// AFTER (replace with):
infiniteRepeatable(
    animation = tween(durationMillis = 180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse,
    initialStartOffset = StartOffset(0),
)
```

- [ ] **Step 4: Replace tile background Surface with GlassTile**

Search for the app tile composable — it will have a `Surface(color = Color(0xFF...))` wrapping the tile icon + label. Replace that `Surface(...)` with `GlassTile(...)`:

```kotlin
// BEFORE — flat tile surface (exact code varies, match the pattern):
Surface(
    color = Color(0xFF1C1C1E),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clickable { /* ... */ }
) {
    // icon + label content
}

// AFTER — glass tile:
GlassTile(
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f),
    onClick = { /* same onClick as before */ },
) {
    // icon + label content — unchanged
}
```

**Note:** Read the actual tile composable code at the line numbers you found in Step 1. The `onClick` handler and content block must be preserved exactly. Only the outer `Surface` → `GlassTile` changes.

- [ ] **Step 5: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Install and visually verify on Portal**

```bash
adb install -r ~/portal-prime/app/build/outputs/apk/debug/app-debug.apk
```

Check: tiles should now have a frosted-glass look. The edit-mode jiggle should feel smoother.

- [ ] **Step 7: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/HomeActivity.kt
git commit -m "feat(home): glass tiles + spring jiggle animation, remove LinearEasing"
```

---

## Task 6: Premium Canvas Digital Clock Face

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/ui/clock/PrimeDigitalClock.kt`
- Modify: `app/src/main/java/com/immortal/launcher/ClockFaces.kt`

**Interfaces:**
- Consumes: `ClockFaceView` interface from `ClockFaces.kt`; `ClockSpec`, `AssetResolver` types
- Produces: `PrimeDigitalClockFaceView` class implementing `ClockFaceView`; plugged into `makeClockFace` for `ClockMode.DIGITAL`

- [ ] **Step 1: Create the ui/clock directory**

```bash
mkdir -p ~/portal-prime/app/src/main/java/com/immortal/launcher/ui/clock
```

- [ ] **Step 2: Create PrimeDigitalClock.kt**

```kotlin
package com.immortal.launcher.ui.clock

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.immortal.launcher.ClockFaceView
import com.immortal.launcher.ClockSpec
import com.immortal.launcher.AssetResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Premium digital clock face drawn entirely on Canvas.
 * - Large hours + minutes in [ClockPrimary] with optional neon-halo shadow
 * - Seconds arc drawn as a thin circle progress indicator
 * - Replaces the flat TextView-based DigitalClockFaceView
 */
class PrimeDigitalClockFaceView(
    context: Context,
    private val spec: ClockSpec,
    private val assets: AssetResolver,
) : ClockFaceView {

    private val hourMinFmt   = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val secondsFmt   = SimpleDateFormat("ss",    Locale.getDefault())

    private var currentHourMin = "--:--"
    private var currentSeconds = 0
    private var blinkColon = true

    // Paint for the large time text
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Paint for the colon separator (slightly dimmer)
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8888AA.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Paint for the seconds arc track (background ring)
    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Paint for the seconds arc fill (progress)
    private val arcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6699FF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    override val view: View = object : View(context) {

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            // Time text size — ~40% of the shorter dimension
            val textSize = minOf(w, h) * 0.38f
            timePaint.textSize = textSize
            colonPaint.textSize = textSize

            // Apply neon glow shadow
            timePaint.setShadowLayer(textSize * 0.3f, 0f, 0f, 0x996699FF.toInt())

            // Draw colon with blink
            val colonAlpha = if (blinkColon) 255 else 80
            colonPaint.alpha = colonAlpha

            // Draw full time string centered
            val timeStr = currentHourMin
            canvas.drawText(timeStr, cx, cy + textSize * 0.35f, timePaint)

            // Seconds arc — centered ring below the text
            val arcRadius = minOf(w, h) * 0.42f
            val arcStroke = textSize * 0.04f
            arcTrackPaint.strokeWidth = arcStroke
            arcFillPaint.strokeWidth  = arcStroke

            val left   = cx - arcRadius
            val top    = cy - arcRadius
            val right  = cx + arcRadius
            val bottom = cy + arcRadius

            // Full background ring
            canvas.drawArc(left, top, right, bottom, -90f, 360f, false, arcTrackPaint)

            // Progress arc (seconds / 60)
            val sweep = (currentSeconds / 60f) * 360f
            canvas.drawArc(left, top, right, bottom, -90f, sweep, false, arcFillPaint)
        }
    }

    override fun update(now: Date, blinkOn: Boolean) {
        currentHourMin = hourMinFmt.format(now)
        currentSeconds = secondsFmt.format(now).toIntOrNull() ?: 0
        blinkColon = blinkOn
        view.invalidate()
    }
}
```

- [ ] **Step 3: Wire PrimeDigitalClockFaceView into makeClockFace**

In `ClockFaces.kt`, find the `makeClockFace` function:

```kotlin
// BEFORE:
fun makeClockFace(context: Context, spec: ClockSpec, assets: AssetResolver): ClockFaceView =
    when (spec.mode) {
        ClockMode.NONE -> NoClockFaceView(context)
        ClockMode.FLIP -> FlipWebClockFaceView(context, spec)
        else -> DigitalClockFaceView(context, spec, assets)
    }
```

```kotlin
// AFTER:
fun makeClockFace(context: Context, spec: ClockSpec, assets: AssetResolver): ClockFaceView =
    when (spec.mode) {
        ClockMode.NONE -> NoClockFaceView(context)
        ClockMode.FLIP -> FlipWebClockFaceView(context, spec)
        ClockMode.DIGITAL -> PrimeDigitalClockFaceView(context, spec, assets)
        else -> PrimeDigitalClockFaceView(context, spec, assets)
    }
```

Add the import at the top of `ClockFaces.kt`:
```kotlin
import com.immortal.launcher.ui.clock.PrimeDigitalClockFaceView
```

- [ ] **Step 4: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Install and verify clock face on Portal**

```bash
adb install -r ~/portal-prime/app/build/outputs/apk/debug/app-debug.apk
```

Trigger the screensaver (Settings → Display → Screen saver → Start now) and verify the new clock face renders with the neon glow and seconds arc.

- [ ] **Step 6: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/ui/clock/
git add app/src/main/java/com/immortal/launcher/ClockFaces.kt
git commit -m "feat(clock): premium Canvas digital clock face with neon glow + seconds arc"
```

---

## Task 7: Native Split-Flap Clock (Replace WebView Fliqlo)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/ui/clock/SplitFlapClock.kt`
- Modify: `app/src/main/java/com/immortal/launcher/ClockFaces.kt`

**Interfaces:**
- Consumes: `ClockFaceView` interface; `ClockSpec`, `Context`
- Produces: `SplitFlapClockFaceView` class implementing `ClockFaceView`; wired into `makeClockFace` for `ClockMode.FLIP`

- [ ] **Step 1: Create SplitFlapClock.kt**

```kotlin
package com.immortal.launcher.ui.clock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.immortal.launcher.ClockFaceView
import com.immortal.launcher.ClockSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native split-flap clock — replaces the Fliqlo WebView.
 * Each digit has a top half (current) and bottom half that flips down on change.
 * The flip is a ValueAnimator driving a scaleY transform on the flip card.
 */
class SplitFlapClockFaceView(
    context: Context,
    private val spec: ClockSpec,
) : ClockFaceView {

    private val timeFmt = SimpleDateFormat("HHmm", Locale.getDefault())
    private var displayedDigits = charArrayOf('0', '0', '0', '0')
    private var pendingDigits   = charArrayOf('0', '0', '0', '0')
    // flip progress per digit: 0f = idle, 0..1f = flipping
    private val flipProgress    = FloatArray(4) { 0f }
    private val animators       = arrayOfNulls<ValueAnimator>(4)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A2E.toInt()
    }
    private val topCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22223A.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8888AA.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = 3f
    }

    override val view: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val cardW = w * 0.18f
            val cardH = h * 0.55f
            val radius = cardW * 0.12f
            val gap    = cardW * 0.06f
            val colonW = cardW * 0.3f

            // Total width: 4 cards + 1 colon + gaps
            val totalW = 4 * cardW + colonW + 5 * gap
            val startX = (w - totalW) / 2f
            val startY = (h - cardH) / 2f

            textPaint.textSize  = cardH * 0.7f
            colonPaint.textSize = cardH * 0.5f

            var x = startX
            for (i in 0..3) {
                // Insert colon between digit 1 and 2
                if (i == 2) {
                    colonPaint.alpha = 255
                    canvas.drawText(":", x + colonW / 2f, startY + cardH * 0.65f, colonPaint)
                    x += colonW + gap
                }

                val rect = RectF(x, startY, x + cardW, startY + cardH)

                // Draw card background
                canvas.drawRoundRect(rect, radius, radius, cardPaint)

                // Draw top half with current digit
                val topRect = RectF(x, startY, x + cardW, startY + cardH / 2f)
                canvas.drawRoundRect(topRect, radius, radius, topCardPaint)

                // Draw digit text
                canvas.drawText(
                    displayedDigits[i].toString(),
                    x + cardW / 2f,
                    startY + cardH * 0.63f,
                    textPaint,
                )

                // Draw divider line
                canvas.drawLine(x, startY + cardH / 2f, x + cardW, startY + cardH / 2f, dividerPaint)

                // Draw flip card if animating
                val progress = flipProgress[i]
                if (progress > 0f) {
                    val flipRect = RectF(x, startY + cardH / 2f - (cardH / 2f) * (1f - progress),
                                        x + cardW, startY + cardH / 2f)
                    canvas.save()
                    canvas.scale(1f, progress, x + cardW / 2f, startY + cardH / 2f)
                    canvas.drawRoundRect(flipRect, radius, radius, topCardPaint)
                    // Draw the pending digit on the flipping card
                    textPaint.alpha = (progress * 255).toInt()
                    canvas.drawText(
                        pendingDigits[i].toString(),
                        x + cardW / 2f,
                        startY + cardH * 0.63f,
                        textPaint,
                    )
                    textPaint.alpha = 255
                    canvas.restore()
                }

                x += cardW + gap
            }
        }
    }

    private fun flipDigit(index: Int, newChar: Char) {
        pendingDigits[index] = newChar
        animators[index]?.cancel()
        val anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                flipProgress[index] = va.animatedValue as Float
                if (flipProgress[index] <= 0f) {
                    displayedDigits[index] = newChar
                    flipProgress[index] = 0f
                }
                view.invalidate()
            }
        }
        anim.start()
        animators[index] = anim
    }

    override fun update(now: Date, blinkOn: Boolean) {
        val digits = timeFmt.format(now).toCharArray()
        for (i in 0..3) {
            if (digits[i] != displayedDigits[i] && flipProgress[i] == 0f) {
                flipDigit(i, digits[i])
            }
        }
    }

    override fun dispose() {
        animators.forEach { it?.cancel() }
    }
}
```

- [ ] **Step 2: Wire SplitFlapClockFaceView into makeClockFace**

In `ClockFaces.kt`, update `makeClockFace`:

```kotlin
fun makeClockFace(context: Context, spec: ClockSpec, assets: AssetResolver): ClockFaceView =
    when (spec.mode) {
        ClockMode.NONE    -> NoClockFaceView(context)
        ClockMode.FLIP    -> SplitFlapClockFaceView(context, spec)
        ClockMode.DIGITAL -> PrimeDigitalClockFaceView(context, spec, assets)
        else              -> PrimeDigitalClockFaceView(context, spec, assets)
    }
```

Add import:
```kotlin
import com.immortal.launcher.ui.clock.SplitFlapClockFaceView
```

- [ ] **Step 3: Build**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Install and verify split-flap on Portal**

```bash
adb install -r ~/portal-prime/app/build/outputs/apk/debug/app-debug.apk
```

In Immortal screensaver settings, switch clock face to FLIP. Trigger the screensaver. Each digit should animate with a card-flip when it changes.

- [ ] **Step 5: Commit**

```bash
cd ~/portal-prime
git add app/src/main/java/com/immortal/launcher/ui/clock/SplitFlapClock.kt
git add app/src/main/java/com/immortal/launcher/ClockFaces.kt
git commit -m "feat(clock): native split-flap clock replaces Fliqlo WebView"
```

---

## Task 8: Push Branch and Open PR

**Files:** None — git operations only

- [ ] **Step 1: Verify all builds pass cleanly**

```bash
cd ~/portal-prime && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Push to origin**

```bash
cd ~/portal-prime
git push origin main
```

- [ ] **Step 3: Verify on GitHub**

```bash
gh repo view rlchandani/portal-prime --web
```

The repo at `github.com/rlchandani/portal-prime` should show all commits.

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Color system redesign (Task 1)
- ✅ Typography scale (Task 2)
- ✅ Settings color tokens (Task 3)
- ✅ Glass tiles (Task 4 + 5)
- ✅ Spring animations (Task 5)
- ✅ Premium digital clock (Task 6)
- ✅ Split-flap clock (Task 7)
- ✅ No touch to functional logic (photo sources, MQTT, presence, fleet)

**Placeholder scan:** None found — all steps contain actual code.

**Type consistency:**
- `ClockFaceView` interface used consistently in Tasks 6 and 7
- `GlassTile` composable produced in Task 4, consumed in Task 5
- `PortalPrimeTheme` produced in Task 1, all call sites updated same task
- `PrimeDigitalClockFaceView` and `SplitFlapClockFaceView` both imported in Task 6/7 and wired into `makeClockFace`
