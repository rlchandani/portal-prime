/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

@Composable
internal fun ImmortalWorldClockWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var zones by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
        zones = ImmortalSettings.worldClockZones(context)
        use24 = ImmortalSettings.use24HourClock(context)
  }
  val now by produceHomeState(initialValue = Date()) {
    while (true) {
      value = Date()
      delay(60_000L - System.currentTimeMillis() % 60_000L)
    }
  }
  val format = remember(use24) { SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault()) }
  val selected = remember(zones) { zones.take(4) }
  ImmortalWidgetShell(title = "World clock", openPage = "world", modifier = modifier,
      scrollContent = false) {
    if (selected.isEmpty()) {
      ApprovedHomeWidgetMessage("Add cities to see their local time.")
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val availableHeight = maxHeight
      val fontScale = androidx.compose.ui.platform.LocalConfiguration.current.fontScale
      // Portal firmware's SP conversion can under-budget enlarged text. Keep the
      // configured scale explicit and reserve at least its linear line height.
      val rowHeight = maxOf(with(density) { 52.sp.toDp().value }, 52f * fontScale) +
          maxOf(with(density) { 26.sp.toDp().value }, 26f * fontScale) + 2f
      val footerHeight = maxOf(with(density) { 24.sp.toDp().value }, 24f * fontScale)
      val visibleCount = remember(zones.size, availableHeight, rowHeight, footerHeight) {
        homeWorldClockVisibleCount(zones.size, availableHeight.value,
            rowHeight, footerHeight)
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.take(visibleCount).forEach { zone ->
          val display = remember(zone, now, use24) {
            format.timeZone = TimeZone.getTimeZone(zone)
            val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = format.timeZone }
            format.format(now) to "${worldClockCity(zone)} · ${weekday.format(now)}"
          }
          Column {
            Text(display.first, color = ApprovedHomePalette.Text, fontSize = 48.sp, lineHeight = 52.sp,
                fontWeight = FontWeight.Normal, letterSpacing = (-1).sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(display.second, color = ApprovedHomePalette.Secondary,
                fontSize = 20.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
          }
        }
        val remaining = zones.size - visibleCount
        if (remaining > 0 && availableHeight.value >= footerHeight) {
          Text(if (visibleCount == 0) "$remaining ${if (remaining == 1) "city" else "cities"}"
              else "$remaining more ${if (remaining == 1) "city" else "cities"}",
              color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp,
              maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }

}

/** Short city label from a tz id, e.g. "America/New_York" → "New York". */
private fun worldClockCity(zoneId: String): String =
    zoneId.substringAfterLast('/').replace('_', ' ')


/**
 * Home widget for the wake-up alarm ([AlarmConfig]). Shows the next fire time + label when armed,
 * or "No alarm" when off; a chip toggles it on/off, and tapping the body opens the alarm editor.
 * Re-reads config on resume so edits in the editor reflect here.
 */
@Composable
internal fun ImmortalAlarmWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var cfg by remember { mutableStateOf(AlarmConfig.load(context)) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
    cfg = AlarmConfig.load(context)
    use24 = ImmortalSettings.use24HourClock(context)
  }
  val active = LocalApprovedLauncherPageActive.current
  DisposableEffect(context, active) {
    val stop = if (active) AlarmConfig.observeSnapshot(context) {
      cfg = AlarmConfig.load(context)
    } else null
    if (active) cfg = AlarmConfig.load(context)
    onDispose { stop?.invoke() }
  }
  val minuteTick by produceHomeState(initialValue = 0L, cfg.enabled) {
    if (!cfg.enabled) return@produceHomeState
    while (true) {
      val now = System.currentTimeMillis()
      value = now
      delay(homeAlarmWidgetRefreshDelayMs(now))
    }
  }
  // The tick invalidates the caption; use current wall time on edits and visibility changes too.
  val remainingMinutes = remember(cfg, minuteTick, active) {
    homeAlarmRemainingMinutes(cfg, System.currentTimeMillis())
  }
  val open: () -> Unit = { context.openApprovedHomeSettings("alarm") }
  // Preserve the editable alarm time and inline enabled control within the approved card.
  ImmortalWidgetShell(title = "Alarm", openPage = "alarm", modifier = modifier.tvFocusableRow { open() }) {
    val is12 = !use24
    val h12 = if (cfg.hour % 12 == 0) 12 else cfg.hour % 12
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
          if (is12) "%d:%02d".format(h12, cfg.minute) else "%02d:%02d".format(cfg.hour, cfg.minute),
          color = if (cfg.enabled) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary,
          fontSize = 48.sp,
          lineHeight = 100.sp,
          fontWeight = FontWeight.Normal,
          letterSpacing = (-1).sp,
      )
      if (is12) {
        Text(
            if (cfg.hour < 12) " AM" else " PM",
            color = ApprovedHomePalette.Secondary,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 5.dp),
        )
      }
    }
    Text(
        when {
          cfg.enabled && remainingMinutes != null -> {
            val lead = cfg.label.ifBlank { "Weekdays" }
            "$lead · in ${remainingMinutes / 60} hr ${remainingMinutes % 60} min"
          }
          else -> "No alarm set"
        },
        color = ApprovedHomePalette.Secondary,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.height(20.dp))
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (cfg.enabled) ApprovedHomePalette.Accent else ApprovedHomePalette.Chrome)
            .clickable {
              AlarmConfig.setEnabled(context, !cfg.enabled)
              AlarmScheduler.reschedule(context)
              cfg = AlarmConfig.load(context)
            },
    ) {
      Box(
          Modifier
              .align(if (cfg.enabled) Alignment.CenterEnd else Alignment.CenterStart)
              .padding(horizontal = 4.dp)
              .size(30.dp)
              .clip(CircleShape)
              .background(ApprovedHomePalette.Text))
    }
  }
}
