/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject
import java.util.Calendar

/** Set the sunrise alarm: time, which days, ramp length, and the optional chime. */
class SunriseSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { SunriseScreen() } }
  }
}

private val DAY_LABELS =
    listOf(
        Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S")

@Composable
private fun SunriseScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Sunrise Alarm",
      subtitle = "The screen brightens gradually to wake you, with an optional gentle chime.",
      onBack = { activity?.finish() },
  ) { SunriseContent() }
}

/** Inlinable body of the Sunrise settings screen — rendered both by [SunriseScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun SunriseContent() {
  val context = LocalContext.current
  val initial = remember { SunriseConfig.load(context) }
  var enabled by remember { mutableStateOf(initial.enabled) }
  var hour by remember { mutableIntStateOf(initial.hour) }
  var minute by remember { mutableIntStateOf(initial.minute) }
  var ramp by remember { mutableIntStateOf(initial.rampMinutes) }
  var chime by remember { mutableStateOf(initial.chime) }
  var days by remember { mutableStateOf(initial.days) }

  fun persist() {
    // Set days first (bespoke — the registry models scalars, not sets), then route the scalar
    // fields through the domain so its onApplied (SunriseScheduler.reschedule) fires once with
    // the new days already in prefs.
    SunriseConfig.setDays(context, days)
    SettingsDomains.sunrise.apply(
        context,
        JSONObject()
            .put("enabled", enabled)
            .put("hour", hour)
            .put("minute", minute)
            .put("rampMinutes", ramp)
            .put("chime", chime))
  }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Text("Enabled", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
          Switch(checked = enabled, onCheckedChange = { enabled = it; persist() })
        }
      }

      // Time pickers (stepper rows).
      Card {
        Stepper("Hour", hour, 0, 23) { hour = it; persist() }
        Stepper("Minute", minute, 0, 59, step = 5) { minute = it; persist() }
      }

      SectionLabel("Wake on")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DAY_LABELS.forEach { (d, label) ->
          val on = d in days
          Surface(color = if (on) IosBlue else IosDivider, shape = CircleShape,
              modifier = Modifier.tvFocusable(CircleShape, focusScale = 1.08f) {
                days = if (on) days - d else days + d; persist()
              }) {
            Text(label, color = if (on) Color.White else IosLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(14.dp))
          }
        }
      }

      Text("Ramp: $ramp min", color = IosLabel, fontSize = Sizes.rowTitle)
      Slider(value = ramp.toFloat(), onValueChange = { ramp = it.toInt() },
          valueRange = 1f..45f, onValueChangeFinished = { persist() })

      Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Text("Chime at the end", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
          Switch(checked = chime, onCheckedChange = { chime = it; persist() })
        }
      }

      val next = remember(enabled, hour, minute, days) {
        SunriseConfig.nextTrigger(SunriseConfig.Config(enabled, hour, minute, ramp, chime, days))
      }
      Text(
          if (next != null)
              "Next: " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
                  .format(java.util.Date(next))
          else "Alarm is off.",
          color = IosBlue, fontSize = Sizes.rowValue)

      Surface(color = Color(0xFFEF6C00), shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp)) {
            context.startActivity(
                Intent(context, WakeLightActivity::class.java)
                    .putExtra(WakeLightActivity.EXTRA_RAMP_MIN, 1)
                    .putExtra(WakeLightActivity.EXTRA_CHIME, chime))
          }) {
        Text("Preview (1-min ramp)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
      }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(label, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    StepBtn("–") { onChange(((value - step).coerceAtLeast(min))) }
    Text("%02d".format(value), color = IosLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    StepBtn("+") { onChange(((value + step).coerceAtMost(max))) }
  }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
  Surface(color = IosDivider, shape = CircleShape,
      modifier = Modifier.tvFocusable(CircleShape, focusScale = 1.1f) { onClick() }) {
    Text(label, color = IosBlue, fontSize = 24.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
  }
}
