/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.immortal.launcher.ui.components.OnResume
import com.immortal.launcher.ui.theme.ApprovedDarkPalette
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home-screen wallpaper. The launcher background can be a flat dark fill, one of a few gradient
 * presets (optionally with film grain), a bundled photo, or "sync with the screensaver" — which
 * mirrors the screensaver's photo source as a blurred backdrop. Photo modes are always shown
 * blurred + dimmed so app icons stay legible.
 */
object WallpaperConfig {

  private const val PREFS = "immortal_wallpaper"
  private const val KEY_MODE = "mode"
  private const val KEY_GRAIN = "grain"
  private const val KEY_MODE_EXPLICIT_SELECTION = "mode_explicitly_selected"
  private const val KEY_LEGACY_AURORA_MIGRATED = "aurora_migrated"

  const val DARK = "dark"
  const val SCREENSAVER = "screensaver"
  const val PHOTO_PREFIX = "photo:"
  // Live, self-computed backgrounds (no photo source): a sky gradient that drifts through the day,
  // and the real night sky projected for the device's location. Formerly ForkHome-only.
  const val SKY = "sky"
  const val STARFIELD = "starfield"
  const val SPACE = "g_space"
  // The Dusk Aurora look (the approved mock): night sky melting into a warm ember horizon with
  // drifting teal/violet aurora ribbons and stars. The new default.
  const val DUSK_AURORA = "dusk_aurora"

  // Default = Dusk Aurora, the approved home design. Users can switch in Settings > Wallpaper.
  data class Config(val mode: String = DUSK_AURORA, val grain: Boolean = false)

  /** Gradient presets: id → top-to-bottom colour stops (ARGB longs). */
  val GRADIENTS: List<Pair<String, List<Long>>> =
      listOf(
          "g_space" to listOf(0xFF1A3A6B, 0xFF0D1F42, 0xFF080F20, 0xFF020408),
          "g_midnight" to listOf(0xFF0F2027, 0xFF203A43, 0xFF2C5364),
          "g_dusk" to listOf(0xFF41295A, 0xFF2F0743),
          "g_ocean" to listOf(0xFF1A2980, 0xFF26D0CE),
          "g_ember" to listOf(0xFF6A2C70, 0xFFB83B5E, 0xFFF08A5D),
          "g_aurora" to listOf(0xFF0B486B, 0xFF3B8686, 0xFF79BD9A),
      )

  /** Bundled photo presets (mode id → label). Files live in assets/photoframe_fallback. */
  val PHOTOS: List<Pair<String, String>> =
      listOf(
          PHOTO_PREFIX + "01_wilderness.jpg" to "Wilderness",
          PHOTO_PREFIX + "02_denali.jpg" to "Denali",
          PHOTO_PREFIX + "03_yosemite.jpg" to "Yosemite",
      )

  fun gradientColors(mode: String): List<Long>? = GRADIENTS.firstOrNull { it.first == mode }?.second

  fun isPhoto(mode: String): Boolean = mode == SCREENSAVER || mode.startsWith(PHOTO_PREFIX)

  // Resolve the historical Sky default on read, retaining its original stored value.
  // Both selection markers protect deliberate Sky choices, including ones made after
  // the previous migration but before explicit-selection metadata was introduced.
  fun load(context: Context): Config {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val stored = p.getString(KEY_MODE, DUSK_AURORA) ?: DUSK_AURORA
    val explicit = p.getBoolean(KEY_MODE_EXPLICIT_SELECTION, false) ||
        p.getBoolean(KEY_LEGACY_AURORA_MIGRATED, false)
    val mode = if (stored == SKY && !explicit) DUSK_AURORA else stored
    return Config(mode = mode, grain = p.getBoolean(KEY_GRAIN, false))
  }

  fun setMode(context: Context, mode: String) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
          .putString(KEY_MODE, mode)
          .putBoolean(KEY_MODE_EXPLICIT_SELECTION, true)
          .apply()

  fun setGrain(context: Context, on: Boolean) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GRAIN, on).apply()

  /**
   * Loads + blurs the photo for a photo/screensaver wallpaper: a bundled asset for a `photo:` mode,
   * or the screensaver's current source for [SCREENSAVER]. The blur is a real StackBlur on a
   * moderate-resolution downscale (smooth "frosted glass", not the blocky look of a tiny upscale),
   * and works on the Portal's Android 9/10 (no RenderScript / Modifier.blur needed).
   */
  fun loadPhoto(context: Context, mode: String): Bitmap? {
    val src =
        when {
          mode.startsWith(PHOTO_PREFIX) -> bundled(context, mode.removePrefix(PHOTO_PREFIX))
          else -> screensaverPhoto(context)
        } ?: return null
    // Downscale so the longest edge is ~moderate (keeps detail for a smooth blur, but cheap), then
    // blur. Crop-upscaling a properly-blurred ~360px image reads as a clean frosted backdrop.
    val maxDim = maxOf(src.width, src.height).coerceAtLeast(1)
    val scale = (360f / maxDim).coerceAtMost(1f)
    val tw = (src.width * scale).toInt().coerceAtLeast(1)
    val th = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = runCatching { Bitmap.createScaledBitmap(src, tw, th, true) }.getOrNull() ?: src
    return runCatching { stackBlur(scaled, 24) }.getOrDefault(scaled)
  }

  private fun bundled(context: Context, name: String): Bitmap? =
      runCatching {
            context.assets.open("photoframe_fallback/$name").use { BitmapFactory.decodeStream(it) }
          }
          .getOrNull()

  private fun anyBundled(context: Context): Bitmap? =
      runCatching {
            val names =
                context.assets.list("photoframe_fallback")
                    ?.filter { it.endsWith(".jpg", ignoreCase = true) }
                    .orEmpty()
            names.randomOrNull()?.let { bundled(context, it) }
          }
          .getOrNull()

  private fun screensaverPhoto(context: Context): Bitmap? {
    val cfg = ScreensaverConfig.load(context)
    val folder = cfg.folderPath
    if (cfg.usesFolder && folder != null && LocalMedia.isAccessible(folder)) {
      val pick =
          LocalMedia.enumerate(folder, includeVideo = false, max = 80)
              .filterNot { it.isVideo }
              .randomOrNull()
      if (pick != null) {
        runCatching { SlideshowDecoder.file(pick.path, maxEdge = 360) }.getOrNull()?.let { return it }
      }
    }
    return anyBundled(context)
  }
}

/** The launcher's wallpaper layer, placed behind the home content. Re-reads its config on resume. */
@Composable
fun HomeBackground(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var cfg by remember { mutableStateOf(WallpaperConfig.load(context)) }
  OnResume { cfg = WallpaperConfig.load(context) }

  val mode = cfg.mode
  val gradient = WallpaperConfig.gradientColors(mode)
  androidx.compose.foundation.layout.Box(modifier) {
    when {
      mode == WallpaperConfig.DUSK_AURORA -> DuskAuroraBackground()
      mode == WallpaperConfig.SKY -> {
        SkyBackground()
        DayProgressBar()
      }
      mode == WallpaperConfig.STARFIELD -> StarFieldBackground()
      gradient != null -> {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(gradient.map { Color(it) })))
      }
      WallpaperConfig.isPhoto(mode) -> {
        val bmp by
            produceState<android.graphics.Bitmap?>(initialValue = null, mode) {
              value = withContext(Dispatchers.IO) { WallpaperConfig.loadPhoto(context, mode) }
            }
        val b = bmp
        if (b != null) {
          Image(
              bitmap = b.asImageBitmap(),
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
          )
          // Dim for icon legibility over the blurred photo.
          androidx.compose.foundation.layout.Box(
              Modifier.fillMaxSize().background(Color(0x73000000)))
        } else {
          androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(ApprovedDarkPalette.background))
        }
      }
      else -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(ApprovedDarkPalette.background))
    }
    if (cfg.grain) GrainOverlay()
    NightShiftOverlay()
  }
}

/**
 * Night Shift warm overlay (from [DisplayControl]): a translucent amber layer over the launcher
 * during the user's nightly window, so the home screen reads warmer at night. Re-checks every
 * minute; alpha 0 (nothing drawn) when disabled or outside the window.
 */
@Composable
private fun NightShiftOverlay() {
  val context = LocalContext.current
  var alpha by remember { mutableStateOf(DisplayControl.currentWarmAlpha(context)) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      alpha = DisplayControl.currentWarmAlpha(context)
      kotlinx.coroutines.delay(60_000L)
    }
  }
  if (alpha > 0f) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Color(0xFFFF8A2A).copy(alpha = alpha)))
  }
}

/** A subtle film-grain overlay (tiled random-noise bitmap at low alpha). */
@Composable
private fun GrainOverlay() {
  val noise = remember { makeNoise(128) }
  Canvas(Modifier.fillMaxSize()) {
    drawIntoCanvas { c ->
      val paint =
          android.graphics.Paint().apply {
            shader =
                android.graphics.BitmapShader(
                    noise,
                    android.graphics.Shader.TileMode.REPEAT,
                    android.graphics.Shader.TileMode.REPEAT,
                )
            alpha = 20
          }
      c.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
  }
}

private fun makeNoise(n: Int): Bitmap {
  val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
  val rnd = java.util.Random(7)
  val px = IntArray(n * n)
  for (i in px.indices) {
    val v = rnd.nextInt(256)
    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
  }
  bmp.setPixels(px, 0, n, 0, 0, n, n)
  return bmp
}


/**
 * StackBlur (Mario Klingemann) — a fast Gaussian-like blur that runs on any API level (no
 * RenderScript). Operates on a copy and returns the blurred bitmap.
 */
private fun stackBlur(sent: Bitmap, radius: Int): Bitmap {
  val bitmap = sent.copy(sent.config ?: Bitmap.Config.ARGB_8888, true)
  if (radius < 1) return bitmap
  val w = bitmap.width
  val h = bitmap.height
  val pix = IntArray(w * h)
  bitmap.getPixels(pix, 0, w, 0, 0, w, h)
  val wm = w - 1
  val hm = h - 1
  val div = radius + radius + 1
  val r = IntArray(w * h)
  val g = IntArray(w * h)
  val b = IntArray(w * h)
  val vmin = IntArray(maxOf(w, h))
  var divsum = (div + 1) shr 1
  divsum *= divsum
  val dv = IntArray(256 * divsum)
  for (i in 0 until 256 * divsum) dv[i] = i / divsum
  val stack = Array(div) { IntArray(3) }
  val r1 = radius + 1

  var yw = 0
  var yi = 0
  for (y in 0 until h) {
    var rinsum = 0
    var ginsum = 0
    var binsum = 0
    var routsum = 0
    var goutsum = 0
    var boutsum = 0
    var rsum = 0
    var gsum = 0
    var bsum = 0
    for (i in -radius..radius) {
      val p = pix[yi + minOf(wm, maxOf(i, 0))]
      val sir = stack[i + radius]
      sir[0] = (p and 0xff0000) shr 16
      sir[1] = (p and 0x00ff00) shr 8
      sir[2] = (p and 0x0000ff)
      val rbs = r1 - kotlin.math.abs(i)
      rsum += sir[0] * rbs
      gsum += sir[1] * rbs
      bsum += sir[2] * rbs
      if (i > 0) {
        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
      } else {
        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
      }
    }
    var stackpointer = radius
    for (x in 0 until w) {
      r[yi] = dv[rsum]
      g[yi] = dv[gsum]
      b[yi] = dv[bsum]
      rsum -= routsum; gsum -= goutsum; bsum -= boutsum
      var stackstart = stackpointer - radius + div
      var sir = stack[stackstart % div]
      routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
      if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
      val p = pix[yw + vmin[x]]
      sir[0] = (p and 0xff0000) shr 16
      sir[1] = (p and 0x00ff00) shr 8
      sir[2] = (p and 0x0000ff)
      rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
      rsum += rinsum; gsum += ginsum; bsum += binsum
      stackpointer = (stackpointer + 1) % div
      sir = stack[stackpointer % div]
      routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
      rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
      yi++
    }
    yw += w
  }
  for (x in 0 until w) {
    var rinsum = 0
    var ginsum = 0
    var binsum = 0
    var routsum = 0
    var goutsum = 0
    var boutsum = 0
    var rsum = 0
    var gsum = 0
    var bsum = 0
    var yp = -radius * w
    for (i in -radius..radius) {
      yi = maxOf(0, yp) + x
      val sir = stack[i + radius]
      sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
      val rbs = r1 - kotlin.math.abs(i)
      rsum += r[yi] * rbs
      gsum += g[yi] * rbs
      bsum += b[yi] * rbs
      if (i > 0) {
        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
      } else {
        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
      }
      if (i < hm) yp += w
    }
    yi = x
    var stackpointer = radius
    for (y in 0 until h) {
      pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
      rsum -= routsum; gsum -= goutsum; bsum -= boutsum
      var stackstart = stackpointer - radius + div
      var sir = stack[stackstart % div]
      routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
      if (x == 0) vmin[y] = minOf(y + r1, hm) * w
      val p = x + vmin[y]
      sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
      rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
      rsum += rinsum; gsum += ginsum; bsum += binsum
      stackpointer = (stackpointer + 1) % div
      sir = stack[stackpointer]
      routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
      rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
      yi += w
    }
  }
  bitmap.setPixels(pix, 0, w, 0, 0, w, h)
  return bitmap
}

/**
 * The Dusk Aurora wallpaper (the approved mock, ported 1:1): a dusk sky — near-black up top
 * melting into a warm ember horizon — with three soft aurora ribbons (teal, violet-teal, and a
 * warm glow) and a scattering of stars. Ribbons drift slowly; everything is drawn, no assets.
 * Colors match mocks/ipados/aurora.css exactly.
 */
@Composable
fun DuskAuroraBackground(modifier: Modifier = Modifier) {
  // 26s ease-in-out drift, mirrored — the same animation the mock runs.
  val t by rememberInfiniteTransition(label = "aurora")
      .animateFloat(
          initialValue = -1f,
          targetValue = 1f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(26_000, easing = EaseInOutSine),
                  repeatMode = RepeatMode.Reverse),
          label = "drift")
  // Fixed star positions (fraction of w/h, radius px, alpha) — from the mock's #stars layer.
  val stars = remember {
    listOf(
        Triple(0.12f, 0.18f, 0.9f), Triple(0.28f, 0.08f, 0.7f), Triple(0.44f, 0.24f, 0.8f),
        Triple(0.58f, 0.12f, 0.6f), Triple(0.71f, 0.20f, 0.85f), Triple(0.83f, 0.07f, 0.7f),
        Triple(0.92f, 0.27f, 0.75f), Triple(0.36f, 0.38f, 0.5f), Triple(0.66f, 0.42f, 0.55f),
        Triple(0.08f, 0.46f, 0.45f))
  }
  Canvas(modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    // Sky: the mock's vertical gradient — deep night for the top two-thirds, the warm shift
    // held tight to the bottom edge so the ember reads as a horizon, not a pink wash.
    drawRect(
        Brush.verticalGradient(
            0.00f to Color(0xFF050810), 0.40f to Color(0xFF0A1226), 0.70f to Color(0xFF12234A),
            0.88f to Color(0xFF27436E), 0.96f to Color(0xFF3E4E66), 1.00f to Color(0xFF5A5560)))
    // Aurora ribbons: soft radial blobs stand in for the mock's blurred linear ribbons — the
    // radial falloff IS the blur, so no expensive blur pass is needed on the Portal's GPU.
    fun ribbon(cx: Float, cy: Float, r: Float, colors: List<Color>) {
      drawCircle(
          Brush.radialGradient(colors, center = androidx.compose.ui.geometry.Offset(cx, cy), radius = r),
          radius = r,
          center = androidx.compose.ui.geometry.Offset(cx, cy))
    }
    val drift = t * 0.02f * w
    // a1 — teal band, upper left, drifting.
    ribbon(w * 0.30f + drift, h * 0.13f, w * 0.42f,
        listOf(Color(0x511DE9B6), Color(0x3326C6DA), Color(0x0026C6DA)))
    // a2 — violet-teal, upper right, counter-phase.
    ribbon(w * 0.78f - drift, h * 0.22f, w * 0.36f,
        listOf(Color(0x2E7C4DFF), Color(0x211DE9B6), Color(0x001DE9B6)))
    // a3 — warm ember glow hugging the bottom edge (static in the mock; kept small so the
    // warmth stays at the horizon).
    ribbon(w * 0.52f, h * 1.04f, w * 0.24f,
        listOf(Color(0x52FF9D5C), Color(0x24FF783C), Color(0x00FF783C)))
    // Stars — small white dots, upper half only.
    stars.forEach { (fx, fy, a) ->
      drawCircle(
          Color.White.copy(alpha = a * 0.8f),
          radius = 1.6f,
          center = androidx.compose.ui.geometry.Offset(fx * w, fy * h))
    }
  }
}
