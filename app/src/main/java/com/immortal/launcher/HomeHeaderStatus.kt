/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val RSSI_UNAVAILABLE = -127

@Composable
private fun wifiLevel(): Int {
  val context = androidx.compose.ui.platform.LocalContext.current
  return produceState(initialValue = -1) {
    while (true) {
      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      value = if (wifiManager != null) {
        val rssi = wifiManager.connectionInfo?.rssi ?: RSSI_UNAVAILABLE
        if (rssi == RSSI_UNAVAILABLE) {
          -1
        } else {
          @Suppress("DEPRECATION")
          WifiManager.calculateSignalLevel(rssi, 5)
        }
      } else {
        -1
      }
      delay(10_000L)
    }
  }.value
}

@Composable
private fun WifiBars(level: Int) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.Bottom,
  ) {
    for (i in 0..3) {
      val height = (4 + i * 3).dp
      val color = if (level != -1 && i < level) Color.White else Color.White.copy(alpha = 0.25f)
      Box(
          modifier = Modifier
              .width(3.dp)
              .height(height)
              .background(color, RoundedCornerShape(1.dp))
      )
    }
  }
}

internal data class BatteryReading(val present: Boolean, val percent: Int, val charging: Boolean)

/** Reads the device battery, updating live. Returns present=false on devices
 * without a battery (Portal+, Portal TV, Portal Mini), so callers can hide it. */
@Composable
internal fun batteryState(): BatteryReading {
  val context = androidx.compose.ui.platform.LocalContext.current
  var reading by remember { mutableStateOf(BatteryReading(false, 0, false)) }
  DisposableEffect(Unit) {
    fun parse(i: Intent?): BatteryReading {
      if (i == null) return BatteryReading(false, 0, false)
      val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      val charging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
      return BatteryReading(present && pct >= 0, pct.coerceIn(0, 100), charging)
    }
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reading = parse(i)
          }
        }
    // Registering for the sticky ACTION_BATTERY_CHANGED returns the current value.
    val sticky =
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    reading = parse(sticky)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return reading
}

/** Minimal drawn battery glyph + percent, green while charging, red when low. */
@Composable
internal fun BatteryIndicator(percent: Int, charging: Boolean) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 18.dp)) {
      val cap = 3.dp.toPx()
      val bodyW = size.width - cap
      val stroke = 2.dp.toPx()
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(bodyW - stroke, size.height - stroke),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = stroke),
      )
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(bodyW, size.height * 0.3f),
          size = Size(cap, size.height * 0.4f),
          cornerRadius = CornerRadius(2f, 2f),
      )
      val inset = stroke + 2.dp.toPx()
      val fillColor =
          when {
            charging -> Color(0xFF4CAF50)
            percent <= 15 -> Color(0xFFE53935)
            else -> Color.White
          }
      drawRoundRect(
          color = fillColor,
          topLeft = Offset(inset, inset),
          size =
              Size(
                  ((bodyW - inset * 2) * (percent / 100f)).coerceAtLeast(0f),
                  size.height - inset * 2,
              ),
          cornerRadius = CornerRadius(2f, 2f),
      )
    }
    Text("$percent%", color = Color.White, fontSize = 22.sp)
    if (charging) Text("⚡", color = Color(0xFF4CAF50), fontSize = 16.sp)
  }
}
