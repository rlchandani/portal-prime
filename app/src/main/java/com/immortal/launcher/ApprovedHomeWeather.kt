/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ApprovedHomeWeather(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  var mode by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  OnResume {
    fahrenheit = ImmortalSettings.useFahrenheit(context)
    mode = ImmortalSettings.load(context).weatherWidget
  }
  val current by produceHomeState<Weather.Current?>(null, fahrenheit) {
    while (true) {
      value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
      homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
    }
  }
  val forecast by produceHomeState<Weather.Forecast?>(null, fahrenheit) {
    while (true) {
      value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
    }
  }
  Column(modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading("Weather", "weather") { context.openApprovedHomeSettings("weather") }
    Spacer(Modifier.height(22.dp))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(current?.let { "${it.temp}°" } ?: "—", color = ApprovedHomePalette.Text,
            fontSize = 94.sp, lineHeight = 100.sp, fontWeight = FontWeight.Normal,
            letterSpacing = (-2).sp, modifier = Modifier.weight(1f))
        if (current == null || current!!.code in 0..3) {
          ApprovedHomeIcon("weather", Modifier.size(72.dp), androidx.compose.ui.graphics.Color(0xFFF4DB93))
        } else Text(Weather.emoji(current!!.code), fontSize = 60.sp)
      }
      Text(current?.city?.ifBlank { "Weather" } ?: "Weather unavailable",
          color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp,
          modifier = Modifier.padding(top = 18.dp))
      forecast?.days?.firstOrNull()?.let {
        Text("H: ${it.hi}° · L: ${it.lo}°", color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 8.dp))
      }
      if (mode != ImmortalSettings.WIDGET_OFF) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (mode == ImmortalSettings.WIDGET_HOURLY) forecast?.hours?.take(5)?.forEach {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
              Text(it.label, color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp)
              Text(Weather.emoji(it.code), fontSize = 24.sp, lineHeight = 32.sp)
              Text("${it.temp}°", color = ApprovedHomePalette.Text, fontSize = 22.sp, lineHeight = 30.sp)
            }
          } else forecast?.days?.take(5)?.forEach {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
              Text(it.label, color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp)
              Text(Weather.emoji(it.code), fontSize = 24.sp, lineHeight = 32.sp)
              Text("${it.hi}°", color = ApprovedHomePalette.Text, fontSize = 22.sp, lineHeight = 30.sp)
              Text("${it.lo}°", color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp)
            }
          }
        }
      }
    }
  }
}
