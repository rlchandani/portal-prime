/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools › Reminders: the household's list. It's a shared screen, so it shows household and
 * non-private reminders only; private ones are a count per person ("on Maya's Home").
 */
@Composable
internal fun ToolsRemindersContent() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ReminderStore.ensureLoaded(context) } }
  val state by ReminderStore.state.collectAsState()
  val household by WhosThereStore.householdState.collectAsState()
  val people = household.people
  val shared = remember(state, people) { ReminderVisibility.shared(state, people) }
  val use24 = remember { DateFormat.is24HourFormat(context) }
  val zone = remember { TimeZone.getDefault() }
  val now = remember(state) { ReminderStore.clock() }
  var adding by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<Reminder?>(null) }

  EverydayCollectionPanes(left = {
    EverydayPanel("Household reminders") {
      when {
        shared.reminders.isEmpty() && shared.privateCounts.isEmpty() -> EverydayEmpty("check", "Nothing to remember",
            "Add one here, on someone’s Home, from the phone, or say “Hey Jarvis, remind me to…”")
        else -> EverydayCollection {
          items(shared.reminders, key = { it.id }) { r ->
            EverydayDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f).tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { editing = r },
                  verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EverydayTitle(if (r.done) "✓ ${r.title}" else r.title)
                val due = ReminderDue.tag(r.dueMs, now, zone, use24)
                ToolsText(listOfNotNull("For ${ReminderVisibility.ownerLabel(r, people)}", due,
                    r.createdBy.takeIf { it.isNotBlank() }?.let { "by $it" }).joinToString(" · "), secondary = true)
              }
              ToolsButton(if (r.done) "Undo" else "Done", primary = !r.done) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { RemindersFeature.setDone(context, r.id, !r.done) } } }
              }
            }
          }
          items(shared.privateCounts.entries.sortedBy { it.key }.toList(), key = { "private:" + it.key }) { (name, n) ->
            EverydayDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
              EverydayTitle("🔒 $n private for $name")
              ToolsText("Only on $name’s Home, while they’re alone", secondary = true)
            }
          }
        }
      }
    }
  }, right = {
    EverydayPanel("Add a reminder") {
      ToolsText("For the whole household, or for one person. A person’s reminders show on their Home when " +
          "Who’s there? recognises them; private ones only while they’re alone. Due reminders chime only for " +
          "that person, and never in quiet hours.", secondary = true)
      EverydayActions { ToolsButton("Add reminder", primary = true) { adding = true } }
    }
  })
  if (adding) ReminderEditSheet(null, null, people) { adding = false }
  editing?.let { r -> ReminderEditSheet(r, null, people) { editing = null } }
}
