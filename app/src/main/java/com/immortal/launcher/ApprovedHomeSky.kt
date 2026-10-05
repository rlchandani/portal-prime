/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.components.pollingState
import java.util.Calendar
import kotlinx.coroutines.delay

/** Keeps selected Sky's real daylight palette/body; redraws by minute, with static clouds. */
@Composable
internal fun ApprovedHomeSky() {
  val context = LocalContext.current
  val sun by pollingState(refreshMs = 30 * 60_000L, retryMs = 60_000L) { Weather.fetchSunTimes(context) }
  val nowMin by produceState(minuteOfDay(System.currentTimeMillis())) {
    while (true) {
      delay(60_000L - System.currentTimeMillis() % 60_000L)
      value = minuteOfDay(System.currentTimeMillis())
    }
  }
  val sunrise = sun?.let { minuteOfDay(it.sunriseMillis) } ?: 6 * 60
  val sunset = sun?.let { minuteOfDay(it.sunsetMillis) } ?: 20 * 60
  val (top, bottom) = remember(nowMin, sunrise, sunset) { SkyColors.gradientFor(nowMin, sunrise, sunset) }
  val isDay = nowMin in sunrise..sunset
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
    Canvas(Modifier.fillMaxSize()) {
      val fraction = if (isDay) (nowMin - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
          else {
            val night = (1440 - (sunset - sunrise)).coerceAtLeast(1)
            val since = if (nowMin > sunset) nowMin - sunset else nowMin + (1440 - sunset)
            since.toFloat() / night
          }
      val arc = (1f - 4f * (fraction - .5f) * (fraction - .5f)).coerceIn(0f, 1f)
      val body = Offset((.12f + .76f * fraction.coerceIn(0f, 1f)) * size.width,
          size.height * (.62f - .42f * arc))
      val glow = if (isDay) Color(0xFFFFE9B0) else Color(0xFFE8ECF5)
      val glowRadius = if (isDay) 260f else 150f
      drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .35f), Color.Transparent),
          center = body, radius = glowRadius), radius = glowRadius, center = body)
      drawCircle(glow.copy(alpha = .9f), if (isDay) 70f else 46f, body)
      val cloudTint = if (isDay) Color.White else Color(0xFFB9C2D8)
      fun cloud(x: Float, y: Float, width: Float, alpha: Float) {
        val center = Offset((x - .15f) * size.width, y * size.height)
        val radius = width * size.width
        drawOval(Brush.radialGradient(listOf(cloudTint.copy(alpha = alpha), Color.Transparent),
            center = center, radius = radius),
            topLeft = Offset(center.x - radius, center.y - radius * .4f),
            size = Size(radius * 2, radius * .8f))
      }
      cloud(.05f, .16f, .22f, .10f)
      cloud(.45f, .10f, .16f, .08f)
      cloud(.70f, .24f, .26f, .07f)
      drawRect(bottom.copy(alpha = .18f), size = Size(size.width, 4.dp.toPx()))
      drawRect(bottom, size = Size(size.width * (nowMin / 1440f), 4.dp.toPx()))
    }
  }
}

private fun minuteOfDay(millis: Long): Int = Calendar.getInstance().run {
  timeInMillis = millis
  get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
}
