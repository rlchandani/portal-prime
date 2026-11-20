/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun ImmortalWeatherWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  // Forecast mode from Settings > Appearance > Weather widget: Off / Hourly / Daily. Re-read on
  // resume so a change applies when the user returns home.
  var forecastMode by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
        fahrenheit = ImmortalSettings.useFahrenheit(context)
        forecastMode = ImmortalSettings.load(context).weatherWidget
        use24 = ImmortalSettings.use24HourClock(context)
  }
  val current by
      produceHomeState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceHomeState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sunTimes by
      produceHomeState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  val code = current?.code ?: 0
  val phase = skyPhase()
  val sky = weatherSky(code, phase)
  // The widget paints its own sky (Dusk Aurora design): a multi-stop vertical gradient that tracks
  // condition + time of day — night sky fading into a warm horizon at dusk — with a glowing
  // sun/moon orb when skies are clear. City + big temp top-left, hourly strip along the bottom.
  Box(
      modifier =
          modifier
              // 22dp matches the grid cell's LiquidGlass exactly, so the painted sky fills the
              // glass card edge-to-edge with no rim peeking through.
              .clip(RoundedCornerShape(22.dp))
              .background(Brush.verticalGradient(sky))
              .padding(18.dp),
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
              current?.city?.ifBlank { "Weather" } ?: "Weather",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              current?.let { "${it.temp}°" } ?: "—",
              color = Color.White,
              fontSize = 46.sp,
              fontWeight = FontWeight.Light,
          )
          // Always-present hero subline: condition · today's H/L · sunset — so the card reads as a
          // premium weather hero even when the forecast strip mode is Off.
          val today = forecast?.days?.firstOrNull()
          val condition = weatherConditionText(code)
          val sunsetLabel = sunTimes?.formatted(use24)?.second
          Text(
              buildString {
                append(condition)
                today?.let { append("  ·  H:${it.hi}° L:${it.lo}°") }
                sunsetLabel?.let { append("  ·  ↓$it") }
              },
              color = Color(0xE6FFFFFF),
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp),
          )
        }
        // Clear skies get a glowing sun/moon orb (the Dusk Aurora "paints its own sky" look);
        // anything with real weather keeps the condition glyph.
        if (code <= 1) SkyOrb(night = phase == SkyPhase.NIGHT) else Text(Weather.emoji(code), fontSize = 34.sp)
      }
      Spacer(Modifier.weight(1f))
      // Forecast strip honors the Weather-widget mode: Off shows just the current conditions,
      // Hourly shows the next 5 hours, Daily shows the next 5 days with high/low.
      when (forecastMode) {
        ImmortalSettings.WIDGET_HOURLY -> {
          val hours = forecast?.hours?.take(5).orEmpty()
          if (hours.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              hours.forEach { h ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(h.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(h.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${h.temp}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }
        }
        ImmortalSettings.WIDGET_DAILY -> {
          val days = forecast?.days?.take(5).orEmpty()
          if (days.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              days.forEach { d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                  Text(d.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
                  Text(Weather.emoji(d.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
                  Text("${d.hi}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                  Text("${d.lo}°", color = Color(0x99FFFFFF), fontSize = 11.sp)
                }
              }
            }
          }
        }
        else -> {} // WIDGET_OFF: current conditions only, no forecast strip.
      }
    }
  }
}

/** Time-of-day bucket for the widget's painted sky. */
private enum class SkyPhase { DAY, DUSK, NIGHT }

private fun skyPhase(): SkyPhase {
  val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
  return when (h) {
    in 8..17 -> SkyPhase.DAY
    in 5..7, in 18..20 -> SkyPhase.DUSK // dawn shares the dusk palette
    else -> SkyPhase.NIGHT
  }
}

/**
 * The widget's sky: a multi-stop vertical gradient per condition + phase (Dusk Aurora design).
 * Dusk is the signature — deep night blue melting into a warm ember horizon; weather conditions
 * mute it toward grey. Colors match mocks/ipados (weatherw / .hero .sun).
 */
private fun weatherSky(code: Int, phase: SkyPhase): List<Color> {
  val precip = code in 51..99
  val grey = code == 3 || code in 45..48
  return when (phase) {
    SkyPhase.DUSK ->
        when {
          precip -> listOf(Color(0xFF232C3D), Color(0xFF3A4457), Color(0xFF5D5350))
          grey -> listOf(Color(0xFF27303F), Color(0xFF434D5C), Color(0xFF6B6058))
          else -> listOf(Color(0xFF16294F), Color(0xFF2C4470), Color(0xFF8A5F52), Color(0xFFC98A5E))
        }
    SkyPhase.NIGHT ->
        when {
          precip -> listOf(Color(0xFF1C2330), Color(0xFF333E4E))
          grey -> listOf(Color(0xFF1D2634), Color(0xFF37424F))
          else -> listOf(Color(0xFF0A1226), Color(0xFF16294F), Color(0xFF27436E))
        }
    SkyPhase.DAY ->
        when {
          code in 71..77 -> listOf(Color(0xFF7E93A8), Color(0xFFBFCEDC)) // snow
          code in 51..67 || code in 80..82 -> listOf(Color(0xFF45607A), Color(0xFF7791A6)) // rain
          code in 95..99 -> listOf(Color(0xFF3A4A5C), Color(0xFF5E7286)) // thunder
          code == 3 -> listOf(Color(0xFF5E7E98), Color(0xFF93ABC0)) // overcast
          code in 45..48 -> listOf(Color(0xFF7E8E9C), Color(0xFFAEBBC6)) // fog
          else -> listOf(Color(0xFF2E6BB8), Color(0xFF4F97DC), Color(0xFF8FC3EE)) // clear
        }
  }
}

/** A glowing sun (or moon) orb for clear skies — radial core + soft halo, drawn not emoji. */
@Composable
private fun SkyOrb(night: Boolean) {
  androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
    val r = size.minDimension / 2f
    val c = center
    if (night) {
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0x66AFC6FF), Color(0x00AFC6FF)), center = c, radius = r),
          radius = r, center = c)
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0xFFF4F6FB), Color(0xFFC9D4E8)), center = c.copy(x = c.x - r * 0.15f), radius = r * 0.62f),
          radius = r * 0.55f, center = c)
    } else {
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0x80FFC96B), Color(0x00FFC96B)), center = c, radius = r),
          radius = r, center = c)
      drawCircle(
          Brush.radialGradient(
              listOf(Color(0xFFFFF3D6), Color(0xFFFFCF7D), Color(0xFFFF9D4D)),
              center = c.copy(x = c.x - r * 0.12f, y = c.y - r * 0.15f), radius = r * 0.62f),
          radius = r * 0.55f, center = c)
    }
  }
}
