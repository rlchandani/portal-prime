/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.Calendar

/** Settings › People › a person: re-capture, pause, their calendar feed, delete their face data. */
class WhosTherePersonActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val id = intent.getStringExtra(EXTRA_ID).orEmpty()
    WhosThere.install(this)
    setContent { PortalPrimeTheme(darkTheme = false) { PersonScreen(id) { finish() } } }
  }

  companion object {
    const val EXTRA_ID = "person"
  }
}

@Composable
private fun PersonScreen(id: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val household by WhosThereStore.householdState.collectAsState()
  val loaded by WhosThereStore.loaded.collectAsState()
  val p = household.person(id)
  LaunchedEffect(loaded, p) { if (loaded && p == null) onBack() }
  if (p == null) return
  var confirmDelete by remember { mutableStateOf(false) }
  var calendar by remember(p.id) { mutableStateOf(p.calendarUrl.orEmpty()) }
  var calendarError by remember { mutableStateOf<String?>(null) }
  val looks = household.templatesOf(p.id)?.looks?.size ?: 0
  val now = System.currentTimeMillis()
  SettingsScaffold(title = p.name, onBack = onBack) {
    Row(Modifier.padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
      WhosThereAvatar(p, 84.dp)
      Column(Modifier.padding(start = 22.dp)) {
        Text(p.name, color = IosLabel, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Text("${p.role.label}${if (p.role.parentManaged) " · managed by adults" else ""} · set up on this Portal only",
            color = IosSecondary, fontSize = 21.sp)
      }
    }
    Card {
      NavRow("Face", value = "Set up again",
          subLabel = if (looks == 0) "Not set up yet" else "$looks looks · about ${looks * 512 / 1024 + 1} KB of numbers, encrypted") {
        context.startActivity(Intent(context, WhosThereEnrolActivity::class.java).putExtra(WhosThereEnrolActivity.EXTRA_ID, p.id))
      }
      Divider()
      val paused = p.pausedAt(now)
      NavRow("Pause recognition", value = if (paused) "Paused" else "Off",
          subLabel = if (paused) "Tap to resume" else "Tap to pause until tomorrow morning; shows the shared Home for ${p.name}") {
        val until = if (paused) 0L else tomorrowMorning()
        WhosThereStore.updateHousehold(context, { h -> h.upsert(p.copy(pausedUntilMs = until)) })
        if (!paused) WhosThereStore.record(context, AuditKind.PAUSED)
      }
      Divider()
      ToggleRow("Never recognise ${if (p.role.parentManaged) p.name else "me"} on this Portal", p.neverHere) { on ->
        WhosThereStore.updateHousehold(context, { h -> h.upsert(p.copy(neverHere = on)) })
      }
    }
    SectionLabel("Home map")
    Card {
      ToggleRow("Show ${if (p.role.parentManaged) "${p.name}’s" else "my"} room on the home map", p.onMap && !p.neverHere,
          enabled = !p.neverHere) { on ->
        WhosThereStore.updateHousehold(context, { h -> h.upsert(p.copy(onMap = on)) })
      }
    }
    FooterNote("When this Portal recognises ${p.name}, Control Center and the phone’s House tab can show “${p.initial}” in this " +
        "room for 5 minutes — only if the household setting Control Center › Plan style › Show who’s where is set to Names. " +
        "Other Portals get the name, colour and room in a sealed household message; never a photo or face data. Pausing stops it at once.")
    if (!p.role.parentManaged) {
      SectionLabel("${p.name}’s calendar")
      Card {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          IosTextField(calendar, { calendar = it; calendarError = null }, "Optional: a private ICS / webcal link")
          calendarError?.let { Text(it, color = IosRed, fontSize = 20.sp) }
          PrimaryPillButton("Save calendar") {
            val url = calendar.trim()
            when {
              url.isEmpty() -> WhosThereStore.updateHousehold(context, { h -> h.upsert(p.copy(calendarUrl = null)) })
              url.startsWith("https://") || url.startsWith("webcal://") ->
                WhosThereStore.updateHousehold(context, { h -> h.upsert(p.copy(calendarUrl = url)) })
              else -> calendarError = "Use an https:// or webcal:// link"
            }
          }
        }
      }
      FooterNote("Without one, ${p.name}’s Home shows family-calendar events named “${p.name}: …” or tagged #${p.name.lowercase()}, " +
          "plus family events. Start a title with “Private” to hide it when others are near.")
    }
    Card {
      NavRow("Delete ${p.name}’s face data", subLabel = "Removes ${p.name} and every face template of theirs from this Portal") {
        confirmDelete = true
      }
    }
  }
  if (confirmDelete) ApprovedSettingsDialog("Delete ${p.name}?", { confirmDelete = false }) {
    Column(Modifier.padding(horizontal = 36.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("${p.name}’s face templates are deleted from this Portal now. Chores, routines and the household list aren’t touched.",
          color = IosLabel, fontSize = 23.sp, lineHeight = 32.sp)
      PrimaryPillButton("Delete") {
        WhosThereStore.updateHousehold(context, { h -> h.without(p.id) })
        WhosThereStore.record(context, AuditKind.DELETED)
        confirmDelete = false
        onBack()
      }
      ApprovedSettingsTextButton("Cancel") { confirmDelete = false }
    }
  }
}

private fun tomorrowMorning(): Long = Calendar.getInstance().apply {
  add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
}.timeInMillis
