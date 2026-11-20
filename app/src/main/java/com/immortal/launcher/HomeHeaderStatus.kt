/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext

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
