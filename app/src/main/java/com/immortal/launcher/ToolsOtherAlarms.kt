/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.jarvis.AlarmDays
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SAVE_ERROR = "Couldn’t save the alarm. Try again."

/** "On weekdays · Gym", "Once": the repeat, then the label when it isn't the default. Pure. */
internal fun otherAlarmSubtitle(c: AlarmConfig.Config): String {
  val repeat = AlarmDays.spoken(c.days).ifEmpty { "once" }.replaceFirstChar { it.uppercase() }
  return if (c.label.isBlank() || c.label == "Alarm") repeat else "$repeat · ${c.label}"
}

/**
 * Tools › Alarm's "Other alarms": the alarms beyond the main one ([MoreAlarms], added by Jarvis), each with its time,
 * repeat and label, Turn on/off and Remove (asks first). Laid out like Tools › Timers' running list; it stacks under
 * Repeat in a narrow pane, so it fits the Portal+ and the Mini either way up. Saved edits re-arm only these alarms.
 */
@Composable
internal fun ToolsOtherAlarmsPanel() {
  val context = LocalContext.current.applicationContext
  val operations = rememberEverydayOperations()
  var revision by remember { mutableStateOf(0) }
  DisposableEffect(context) {
    val stop = MoreAlarms.observe(context) { revision++ }
    onDispose { stop() }
  }
  val alarms by produceState<List<MoreAlarms.Alarm>?>(null, revision) {
    value = withContext(Dispatchers.IO) { runCatching { MoreAlarms.load(context) }.getOrDefault(emptyList()) }
  }
  var removeId by rememberSaveable { mutableStateOf<Int?>(null) }

  fun change(edit: (List<MoreAlarms.Alarm>) -> List<MoreAlarms.Alarm>) {
    operations.run(SAVE_ERROR, task = { MoreAlarms.edit(context, edit); AlarmScheduler.rescheduleMore(context) }) {
      revision++
    }
  }

  EverydayPanel("Other alarms") {
    val list = alarms.orEmpty()
    if (list.isEmpty()) {
      ToolsText("Ask Jarvis for another, e.g. “also wake me at 9 on Saturdays”.", secondary = true)
    }
    list.forEachIndexed { index, alarm ->
      if (index > 0) EverydayDivider()
      OtherAlarmRow(alarm, enabled = !operations.busy,
          onToggle = { change { all -> all.map { if (it.id == alarm.id) it.copy(config = it.config.copy(enabled = !it.config.enabled)) else it } } },
          onRemove = { removeId = alarm.id })
    }
    EverydayError(operations.error.takeUnless { removeId != null })
  }

  removeId?.let { id ->
    val selected = alarms?.firstOrNull { it.id == id }
    ToolsConfirm(
        "Remove this alarm?",
        selected?.let { "Remove the ${clockText(it.config)} alarm? Your other alarms stay on." } ?: "This alarm is gone.",
        confirmLabel = "Remove alarm",
        onDismiss = { removeId = null },
        onConfirm = { removeId = null; if (selected != null) change { all -> all.filterNot { it.id == id } } },
    )
  }
}

private fun clockText(c: AlarmConfig.Config) = String.format(Locale.ROOT, "%02d:%02d", c.hour, c.minute)

@Composable
private fun OtherAlarmRow(alarm: MoreAlarms.Alarm, enabled: Boolean, onToggle: () -> Unit, onRemove: () -> Unit) {
  val c = alarm.config
  Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(Modifier.weight(1f)) {
        EverydayTitle(otherAlarmSubtitle(c))
        ToolsStatus(if (c.enabled) "Alarm enabled" else "Alarm off", active = c.enabled)
      }
      EverydayValue(clockText(c), size = 56)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ToolsButton(if (c.enabled) "Turn off" else "Turn on", enabled = enabled, onClick = onToggle)
      ToolsButton("Remove", destructive = true, enabled = enabled, onClick = onRemove)
    }
  }
}
