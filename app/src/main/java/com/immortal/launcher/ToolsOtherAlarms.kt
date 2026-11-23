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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
private const val FULL = "You have ${MoreAlarms.MAX} other alarms. Remove one to add another."
private const val MAX_LABEL = 40

/** The Add alarm sheet's draft: what was typed and tapped, kept across recomposition until Save or Cancel. */
internal data class OtherAlarmDraft(val hour: String = "7", val minute: String = "0", val days: Set<Int> = emptySet(),
    val label: String = "") {
  /** The alarm to add, or null while the hour or minute isn't a valid number. Pure. */
  fun config(): AlarmConfig.Config? {
    val h = ApprovedToolsEverydayValidation.number(hour, 0, 23) ?: return null
    val m = ApprovedToolsEverydayValidation.number(minute, 0, 59) ?: return null
    return AlarmConfig.Config(true, h, m, label.trim().take(MAX_LABEL).ifEmpty { "Alarm" }, days)
  }

  fun toggled(day: Int) = copy(days = if (day in days) days - day else days + day)
}

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
  var adding by rememberSaveable { mutableStateOf(false) }
  var draftHour by rememberSaveable { mutableStateOf("7") }
  var draftMinute by rememberSaveable { mutableStateOf("0") }
  var draftDays by rememberSaveable { mutableStateOf(emptyList<Int>()) }
  var draftLabel by rememberSaveable { mutableStateOf("") }
  val draft = OtherAlarmDraft(draftHour, draftMinute, draftDays.toSet(), draftLabel)

  fun change(edit: (List<MoreAlarms.Alarm>) -> List<MoreAlarms.Alarm>) {
    operations.run(SAVE_ERROR, task = { MoreAlarms.edit(context, edit); AlarmScheduler.rescheduleMore(context) }) {
      revision++
    }
  }

  EverydayPanel("Other alarms") {
    val list = alarms.orEmpty()
    if (list.isEmpty()) {
      ToolsText("Add one here, or ask Jarvis, e.g. “also wake me at 9 on Saturdays”.", secondary = true)
    }
    list.forEachIndexed { index, alarm ->
      if (index > 0) EverydayDivider()
      OtherAlarmRow(alarm, enabled = !operations.busy,
          onToggle = { change { all -> all.map { if (it.id == alarm.id) it.copy(config = it.config.copy(enabled = !it.config.enabled)) else it } } },
          onRemove = { removeId = alarm.id })
    }
    val full = alarms != null && list.size >= MoreAlarms.MAX
    EverydayActions {
      ToolsButton("Add alarm", primary = true, enabled = alarms != null && !full && !operations.busy) {
        operations.error = null
        adding = true
      }
    }
    if (full) ToolsText(FULL, secondary = true)
    EverydayError(operations.error.takeUnless { removeId != null || adding })
  }

  if (adding) {
    val config = draft.config()
    fun close() {
      adding = false
      draftHour = "7"; draftMinute = "0"; draftDays = emptyList(); draftLabel = ""
    }
    EverydaySheet("Add alarm", onDismiss = { if (!operations.busy) adding = false }, actions = {
      ToolsButton("Cancel", enabled = !operations.busy) { close() }
      ToolsButton("Save", primary = true, enabled = !operations.busy && config != null) {
        if (config != null) {
          operations.run(SAVE_ERROR, task = {
            var added = false
            MoreAlarms.edit(context) { all -> MoreAlarms.add(all, config)?.also { added = true } ?: all }
            check(added) { "no free alarm slot" }
            AlarmScheduler.rescheduleMore(context)
          }) { revision++; close() }
        }
      }
    }) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
          EverydayNumberField("Hour", draftHour, 0, 23, 7, wrap = true, enabled = !operations.busy) { draftHour = it }
        }
        Column(Modifier.weight(1f)) {
          EverydayNumberField("Minute", draftMinute, 0, 59, 0, wrap = true, enabled = !operations.busy) { draftMinute = it }
        }
      }
      Spacer(Modifier.height(24.dp))
      ToolsText("Repeat: choose days, or leave all off for a one-time alarm.", secondary = true)
      Spacer(Modifier.height(12.dp))
      EverydayDayChips(draft.days, enabled = !operations.busy) { day -> draftDays = draft.toggled(day).days.sorted() }
      Spacer(Modifier.height(24.dp))
      ToolsField("Label (optional)", draftLabel, { draftLabel = it.take(MAX_LABEL) }, enabled = !operations.busy)
      EverydayError(operations.error)
    }
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
