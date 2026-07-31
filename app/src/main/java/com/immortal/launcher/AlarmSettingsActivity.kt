/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.Calendar

/** Set the wake-up alarm: time, which days, and a label. Opened from Tools. */
class AlarmSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { AlarmScreen() } }
  }
}

private val ALARM_DAY_LABELS =
    listOf(
        Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S")

@Composable
private fun AlarmScreen() {
  val activity = LocalContext.current as? Activity
  PopupScaffold(
      title = "Alarm",
      subtitle = "Wake to a gentle chime at a set time. Pick the days it should repeat.",
      onBack = { activity?.finish() },
  ) {
    AlarmContent()
  }
}

/** The alarm editor body, shared by the standalone [AlarmScreen] popup and the in-page Tools overlay. */
@Composable
internal fun AlarmContent() {
  val context = LocalContext.current
  val initial = remember { AlarmConfig.load(context) }
  var enabled by remember { mutableStateOf(initial.enabled) }
  var hour by remember { mutableIntStateOf(initial.hour) }
  var minute by remember { mutableIntStateOf(initial.minute) }
  var days by remember { mutableStateOf(initial.days) }

  fun persist() {
    AlarmConfig.save(
        context, AlarmConfig.Config(enabled = enabled, hour = hour, minute = minute,
            label = initial.label, days = days))
    AlarmScheduler.reschedule(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Text("Enabled", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
          Switch(checked = enabled, onCheckedChange = { enabled = it; persist() }, colors = iosSwitchColors())
        }
      }

      Card {
        Stepper(
            label = "Hour",
            valueText = "%02d".format(hour),
            onMinus = { hour = (hour + 23) % 24; persist() },
            onPlus = { hour = (hour + 1) % 24; persist() },
        )
        Divider()
        Stepper(
            label = "Minute",
            valueText = "%02d".format(minute),
            onMinus = { minute = (minute + 55) % 60; persist() },
            onPlus = { minute = (minute + 5) % 60; persist() },
        )
      }

      SectionLabel("Repeat on")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALARM_DAY_LABELS.forEach { (d, label) ->
          val on = d in days
          Surface(color = if (on) IosBlue else IosDivider, shape = CircleShape,
              modifier = Modifier.tvFocusable(CircleShape, focusScale = 1.08f) {
                days = if (on) days - d else days + d; persist()
              }) {
            Text(label, color = if (on) Color.White else IosLabel, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(14.dp))
          }
        }
      }
      FooterNote("No days selected rings once at the next occurrence of that time.")

      val next = remember(enabled, hour, minute, days) {
        AlarmConfig.nextTrigger(AlarmConfig.Config(enabled, hour, minute, initial.label, days))
      }
      Text(
          if (next != null)
              "Next: " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
                  .format(java.util.Date(next))
          else "Alarm is off.",
          color = IosBlue, fontSize = Sizes.rowValue)
    }
}
