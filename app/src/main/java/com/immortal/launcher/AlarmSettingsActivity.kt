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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

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
  SettingsScaffold(
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
  val application = context.applicationContext
  val owner = LocalLifecycleOwner.current
  val pageActive = LocalApprovedLauncherPageActive.current
  val editor = remember(application) { alarmSettingsEditor(application) }
  var config by remember(editor) { mutableStateOf(editor.snapshot) }
  DisposableEffect(application, owner, editor, pageActive) {
    var active = false
    var stopObserving: (() -> Unit)? = null
    fun refresh() {
      if (active) config = editor.refresh()
    }
    fun stop() {
      active = false
      stopObserving?.invoke()
      stopObserving = null
    }
    fun start() {
      if (active || !pageActive) return
      active = true
      // Subscribe before readback, including a one-shot that fired while this page was paused.
      stopObserving = AlarmConfig.observeSnapshot(application, ::refresh)
      refresh()
    }
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> start()
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
        else -> Unit
      }
    }
    owner.lifecycle.addObserver(observer)
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    onDispose {
      owner.lifecycle.removeObserver(observer)
      stop()
    }
  }
  val enabled = config.enabled
  val hour = config.hour
  val minute = config.minute
  val days = config.days
  val locale = Locale.getDefault()
  val weekdayNames = remember(locale) { DateFormatSymbols(locale).weekdays }
  val repeatNote = remember(days, weekdayNames) {
    when {
      days.isEmpty() -> "No repeat days selected. Rings once when enabled."
      ALARM_DAY_LABELS.all { it.first in days } -> "Repeats every day."
      days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
          Calendar.THURSDAY, Calendar.FRIDAY) -> "Repeats on weekdays."
      days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "Repeats on weekends."
      else -> "Repeats on " + ALARM_DAY_LABELS.filter { it.first in days }
          .joinToString(", ") { weekdayNames[it.first] } + "."
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
      // One card, as in the approved mock: the switch, its time and its days belong together.
      ApprovedSettingsSectionLabel("Alarm")
      Card {
        ToggleRow("Alarm", enabled) { config = editor.setEnabled(it) }
        Divider()
        ApprovedClockTimeSetting(
            title = "Alarm time",
            value = hour * 60 + minute,
            valueLabel = String.format(Locale.ROOT, "%02d:%02d", hour, minute),
            help = "Choose the alarm time in 24-hour format.",
            enabled = true,
            onApply = { config = editor.setTime(it / 60, it % 60) },
        )
        Divider()
        SettingsDayChipsRow("Repeat", ALARM_DAY_LABELS, days, weekdayNames) { config = editor.toggleDay(it) }
      }
      FooterNote(repeatNote)

      val next = remember(config, ClockChangeAlarms.revision) {
        AlarmConfig.nextTrigger(config)
      }
      Text(
          if (next != null)
              "Next: " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
                  .format(java.util.Date(next))
          else "Alarm is off.",
          color = IosBlue, fontSize = Sizes.rowValue)
    }
}
