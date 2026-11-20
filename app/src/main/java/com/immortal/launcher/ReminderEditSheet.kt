/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOME_LABEL = "Home (everyone)"

/**
 * Add or edit a reminder on the Portal: what, who it's for (an enrolled person or the household),
 * an optional day and time, and Private (on by default for adults and teens; never for kids or
 * the household). [existing] null adds; [forPerson] preselects the owner.
 */
@Composable
internal fun ReminderEditSheet(existing: Reminder?, forPerson: WhosTherePerson?, people: List<WhosTherePerson>,
                               onDismiss: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val zone = remember { TimeZone.getDefault() }
  val now = remember { ReminderStore.clock() }
  val use24 = remember { DateFormat.is24HourFormat(context) }
  val days = remember(now) { ReminderDue.dayOptions(now, zone) }
  val initialOwner = existing?.let { ReminderVisibility.ownerOf(it, people) } ?: forPerson.takeIf { existing == null }
  var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
  var ownerId by rememberSaveable { mutableStateOf(initialOwner?.id) }
  var dayOffset by rememberSaveable { mutableStateOf(ReminderDue.dayOffsetOf(existing?.dueMs, now, zone)) }
  var time by rememberSaveable { mutableStateOf(existing?.dueMs?.let { ReminderDue.clock(it, zone, use24) }.orEmpty()) }
  var private by rememberSaveable { mutableStateOf(existing?.private ?: ReminderVisibility.defaultPrivate(initialOwner)) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val owner = people.firstOrNull { it.id == ownerId }
  // An owner that lives only on another Portal keeps its id and name when edited here.
  val keepRemoteOwner = existing != null && existing.person != null && initialOwner == null

  fun save() {
    val clock = time.takeIf { it.isNotBlank() }?.let { ReminderTimeText.parse(it) ?: return run { error = "Type a time like 18:30 or 6:30 pm" } }
    val due = ReminderDue.dueFrom(dayOffset, clock, ReminderStore.clock(), zone)
    if (dayOffset != null && due == null) { error = "That time has passed. Pick a later time or another day."; return }
    val draft = when {
      keepRemoteOwner && ownerId == existing?.person -> ReminderDraft(title, existing?.person, existing?.personName.orEmpty(), due, private)
      else -> ReminderDraft(title, owner?.id, owner?.name.orEmpty(), due, private && ReminderVisibility.canBePrivate(owner))
    }
    if (ReminderOps.validate(draft, ReminderStore.clock(), existing?.dueMs) == null) { error = "Say what to remember"; return }
    busy = true
    error = null
    scope.launch {
      val r = withContext(Dispatchers.IO) {
        runCatching {
          if (existing == null) RemindersFeature.add(context, draft, RemindersFeature.creator(context, forPerson))
          else RemindersFeature.edit(context, existing.id, draft)
        }.getOrElse { ReminderResult.Refused("failed") }
      }
      busy = false
      if (r is ReminderResult.Ok) onDismiss() else error = "Couldn’t save the reminder. Try again."
    }
  }

  ToolsSheet(if (existing == null) "New reminder" else "Edit reminder", { if (!busy) onDismiss() }, actions = {
    if (existing != null) ToolsButton("Delete", destructive = true, enabled = !busy) {
      busy = true
      scope.launch {
        withContext(Dispatchers.IO) { runCatching { RemindersFeature.delete(context, existing.id) } }
        busy = false
        onDismiss()
      }
    }
    ToolsButton("Cancel", enabled = !busy) { onDismiss() }
    ToolsButton(if (existing == null) "Add reminder" else "Save", primary = true, enabled = !busy) { save() }
  }) {
    ToolsField("What to remember", title, { title = it.take(ReminderLimits.TITLE_MAX * 2) }, enabled = !busy)
    val names = listOf(HOME_LABEL) + people.map { it.name } +
        listOfNotNull(existing?.personName?.takeIf { keepRemoteOwner && it.isNotBlank() })
    val current = owner?.name ?: if (keepRemoteOwner && ownerId == existing?.person) existing?.personName.orEmpty() else HOME_LABEL
    ToolsChoice("For", current, names) { picked ->
      val next = people.firstOrNull { it.name == picked }
      ownerId = when {
        next != null -> next.id
        keepRemoteOwner && picked == existing?.personName -> existing.person
        else -> null
      }
      private = if (next != null) ReminderVisibility.defaultPrivate(next) else keepRemoteOwner && picked == existing?.personName && private
    }
    ToolsChoice("When", days.firstOrNull { it.second == dayOffset }?.first ?: "No time", days.map { it.first }) { picked ->
      dayOffset = days.firstOrNull { it.first == picked }?.second
    }
    if (dayOffset != null) ToolsField("Time (blank for 9 am)", time, { time = it.take(12) }, enabled = !busy)
    val privateAllowed = ReminderVisibility.canBePrivate(owner) || (keepRemoteOwner && ownerId == existing?.person)
    if (privateAllowed) PrivateRow(private, owner?.name ?: existing?.personName.orEmpty()) { private = it }
    else if (owner != null) ToolsText("${owner.name}’s reminders are never private. Parents can see everything.", secondary = true)
    EverydayError(error)
  }
}

@Composable
private fun PrivateRow(checked: Boolean, name: String, onChange: (Boolean) -> Unit) {
  val p = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Checkbox(checked = checked, onCheckedChange = null,
        colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = if (p.dark) p.background else Color.White,
            uncheckedColor = p.secondary), modifier = Modifier.size(68.dp))
    ToolsText("Private: only on $name’s Home, while they’re alone")
  }
}
