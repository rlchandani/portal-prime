/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Live, self-computed home backgrounds — formerly ForkHome-only, now selectable wallpaper modes
 * ([WallpaperConfig.SKY] / [WallpaperConfig.STARFIELD]). Rendered behind the grid by
 * [HomeBackground]. The sky gradient drifts through the day; the star field is the real night sky
 * projected for the device's location, fading in through twilight. Logic lives in [SkyColors] /
 * [StarField]; this is only rendering.
 */

/**
 * A full-screen ambient sky driven by the real sunrise/sunset for the device's location: a
 * time-of-day gradient with a soft sun/moon glow that arcs across the sky as the day progresses,
 * plus a few slowly-drifting cloud blobs. All cheap (gradients + blurred circles), no bitmaps —
 * safe for the Portal's GPU. Selected via the "Sky" wallpaper mode; also the default.
 */
@Composable
internal fun SkyBackground() {
  val context = LocalContext.current
  val sun by
      produceState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  var nowMin by remember { mutableStateOf(ambientMinuteOfDay()) }
  LaunchedEffect(Unit) {
    while (true) {
      nowMin = ambientMinuteOfDay()
      delay(5L * 60 * 1000)
    }
  }
  // Slow cloud drift: a phase 0..1 that advances over ~3 minutes, mapped to horizontal offset.
  val drift = rememberInfiniteTransition(label = "cloudDrift")
  val driftX by drift.animateFloat(
      initialValue = 0f, targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(180_000, easing = LinearEasing), RepeatMode.Restart),
      label = "driftX",
  )
  val sr = sun?.let { ambientMinuteOfDay(it.sunriseMillis) } ?: 6 * 60
  val ss = sun?.let { ambientMinuteOfDay(it.sunsetMillis) } ?: 20 * 60
  val (top, bottom) = SkyColors.gradientFor(nowMin, sr, ss)
  val isDay = nowMin in sr..ss

  Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      // Celestial body: sun by day, moon by night. Arcs left→right, dawn/dusk near the horizon,
      // midday high. Position = fraction of the daylight (or night) span.
      val (frac, isSun) =
          if (isDay) ((nowMin - sr).toFloat() / (ss - sr).coerceAtLeast(1)) to true
          else {
            // night: from sunset (0) through midnight to sunrise (1), wrapping
            val nightLen = (24 * 60 - (ss - sr)).coerceAtLeast(1)
            val since = if (nowMin > ss) nowMin - ss else nowMin + (24 * 60 - ss)
            (since.toFloat() / nightLen) to false
          }
      val cx = (0.12f + 0.76f * frac.coerceIn(0f, 1f)) * w
      // parabola: high at midday (frac .5), low at the horizons
      val arc = 1f - 4f * (frac - 0.5f) * (frac - 0.5f)
      val cy = h * (0.62f - 0.42f * arc.coerceIn(0f, 1f))
      val glowColor = if (isSun) Color(0xFFFFE9B0) else Color(0xFFE8ECF5)
      val coreR = if (isSun) 70f else 46f
      val glowR = if (isSun) 260f else 150f
      // outer glow
      drawCircle(
          brush = Brush.radialGradient(
              colors = listOf(glowColor.copy(alpha = 0.35f), Color.Transparent),
              center = Offset(cx, cy), radius = glowR),
          radius = glowR, center = Offset(cx, cy))
      // core
      drawCircle(color = glowColor.copy(alpha = 0.9f), radius = coreR, center = Offset(cx, cy))

      // Drifting cloud blobs — three soft translucent ellipses at different depths/speeds.
      val cloudTint = if (isDay) Color.White else Color(0xFFB9C2D8)
      fun cloud(baseX: Float, y: Float, rw: Float, alpha: Float, speed: Float) {
        val x = ((baseX + driftX * speed) % 1.3f - 0.15f) * w
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(cloudTint.copy(alpha = alpha), Color.Transparent),
                center = Offset(x, y), radius = rw),
            topLeft = Offset(x - rw, y - rw * 0.4f),
            size = androidx.compose.ui.geometry.Size(rw * 2, rw * 0.8f))
      }
      cloud(0.05f, h * 0.16f, w * 0.22f, 0.10f, 1.0f)
      cloud(0.45f, h * 0.10f, w * 0.16f, 0.08f, 0.7f)
      cloud(0.70f, h * 0.24f, w * 0.26f, 0.07f, 1.3f)
    }
  }
}

/**
 * A thin day-progress line at the very top of the screen (midnight → midnight), tinted with the
 * current sky colour so it matches the [SkyBackground].
 */
@Composable
internal fun DayProgressBar(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val sun by
      produceState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  var nowMin by remember { mutableStateOf(ambientMinuteOfDay()) }
  LaunchedEffect(Unit) {
    while (true) {
      nowMin = ambientMinuteOfDay()
      delay(60L * 1000)
    }
  }
  val sr = sun?.let { ambientMinuteOfDay(it.sunriseMillis) } ?: 6 * 60
  val ss = sun?.let { ambientMinuteOfDay(it.sunsetMillis) } ?: 20 * 60
  val fill = SkyColors.gradientFor(nowMin, sr, ss).second
  val progress = (nowMin / 1440f).coerceIn(0f, 1f)
  Box(modifier = modifier.fillMaxWidth().height(4.dp).background(fill.copy(alpha = 0.18f))) {
    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(fill))
  }
}

/**
 * The real night sky projected to the device's horizon for the current time + location, with a few
 * asterism lines. By day it's a deep dark panel; stars fade in through twilight.
 */
@Composable
internal fun StarFieldBackground() {
  val context = LocalContext.current
  val coords by
      produceState<Pair<Double, Double>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.coordinates(context) }
      }
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(30L * 1000)
    }
  }

  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(
                  Brush.verticalGradient(listOf(Color(0xFF05060F), Color(0xFF0C1430))))) {
    val loc = coords ?: return@Box
    val (lat, lon) = loc
    val lst = StarField.localSiderealTime(now, lon)
    val sunAlt = StarField.sunAltitude(now, lat, lon)
    val nightFactor = ((-sunAlt) / 12.0).coerceIn(0.0, 1.0).toFloat()
    if (nightFactor <= 0.01f) return@Box

    val projected =
        remember(now, lat, lon) { StarField.STARS.map { StarField.project(it, lat, lst) } }

    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      fun screenX(az: Double) = (az / 360.0 * w).toFloat()
      fun screenY(alt: Double) = (h * (1.0 - alt / 90.0)).toFloat()

      StarField.LINES.forEach { (a, b) ->
        val pa = projected[a]
        val pb = projected[b]
        if (pa.alt > 0 && pb.alt > 0) {
          val xa = screenX(pa.az)
          val xb = screenX(pb.az)
          if (kotlin.math.abs(xa - xb) < w / 2f) {
            drawLine(
                color = Color(0xFF6E8BD8).copy(alpha = 0.35f * nightFactor),
                start = Offset(xa, screenY(pa.alt)),
                end = Offset(xb, screenY(pb.alt)),
                strokeWidth = 2f,
            )
          }
        }
      }

      StarField.STARS.forEachIndexed { i, star ->
        val p = projected[i]
        if (p.alt <= 0) return@forEachIndexed
        val radius = (2.6f - star.mag.toFloat() * 0.55f).coerceIn(0.8f, 4.5f)
        val alpha = ((1.8f - star.mag.toFloat() * 0.32f).coerceIn(0.35f, 1f)) * nightFactor
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = radius,
            center = Offset(screenX(p.az), screenY(p.alt)),
        )
      }
    }
  }
}

private fun ambientMinuteOfDay(): Int {
  val c = Calendar.getInstance()
  return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

private fun ambientMinuteOfDay(millis: Long): Int {
  val c = Calendar.getInstance().apply { timeInMillis = millis }
  return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}
