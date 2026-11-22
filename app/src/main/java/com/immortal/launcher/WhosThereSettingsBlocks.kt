/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.TimeZone

/** Settings › People & Recognition: the master switch, the people on this Portal, Add person. */
@Composable
internal fun WhosTherePeopleBlock() {
  val context = LocalContext.current
  remember(context) { WhosThere.install(context) }
  val settings by WhosThereStore.settingsState.collectAsState()
  val household by WhosThereStore.householdState.collectAsState()
  val ui by WhosThere.state.collectAsState()
  var consent by remember { mutableStateOf(false) }
  val unavailable = (ui.indicator as? WhosThereIndicator.Unavailable)?.message
  Card {
    ToggleRow("Recognise people on this Portal", settings.enabled, iconColor = SettingsTileColors.PURPLE.argb(),
        iconRes = approvedIconResource("face")) { on ->
      if (on) consent = true else WhosThereStore.updateSettings(context) { it.copy(enabled = false) }
    }
  }
  FooterNote(unavailable ?: if (household.enrolled.isEmpty()) "Add someone below first. Uses the camera for about a second when someone walks up."
      else "Uses the camera for about a second when someone walks up. A green dot and “Looking…” show the whole time.")
  SectionLabel("On this Portal")
  Card {
    household.people.sortedBy { it.createdMs }.forEach { p ->
      NavRow(p.name, subLabel = personLine(p, household), leading = { WhosThereAvatar(p, 46.dp) }) {
        context.startActivity(Intent(context, WhosTherePersonActivity::class.java).putExtra(WhosTherePersonActivity.EXTRA_ID, p.id))
      }
      Divider()
    }
    val full = household.people.size >= WhosThereLimits.MAX_PEOPLE
    NavRow(if (full) "This Portal has the most people it can recognise" else "Add person", enabled = !full,
        subLabel = "Name, adult / teen / kid, then a 20-second face set-up on this Portal",
        iconColor = SettingsTileColors.BLUE.argb(), iconSymbol = "+") {
      context.startActivity(Intent(context, WhosThereEnrolActivity::class.java))
    }
  }
  FooterNote("Two or more people → Family view. Someone the Portal doesn’t know sees the shared Home; their face is not kept.")
  if (consent) WhosThereEnableDialog(onDismiss = { consent = false })
}

private fun personLine(p: WhosTherePerson, h: WhosThereHousehold): String {
  val looks = h.templatesOf(p.id)?.looks?.size ?: 0
  val role = if (p.role == WhosThereRole.KID) "Kid · managed by adults" else p.role.label
  return when {
    p.neverHere -> "$role · not recognised on this Portal"
    p.pausedAt(System.currentTimeMillis()) -> "$role · paused"
    looks == 0 -> "$role · face not set up yet"
    else -> "$role · face set up"
  }
}

/** Plain-words confirmation before the adult switches recognition on for this Portal. */
@Composable
internal fun WhosThereEnableDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  ApprovedSettingsDialog("Turn on recognition here?", onDismiss) {
    Column(Modifier.padding(horizontal = 36.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      WHAT_HAPPENS.forEach { line -> Text("•  $line", color = IosLabel, fontSize = 23.sp, lineHeight = 32.sp) }
      Text("Only an adult in this home should turn this on.", color = IosSecondary, fontSize = 20.sp)
      PrimaryPillButton("Turn on") {
        WhosThereStore.updateSettings(context) { it.copy(enabled = true, consentAtMs = System.currentTimeMillis()) }
        onDismiss()
      }
      ApprovedSettingsTextButton("Not now", onDismiss)
    }
  }
}

private val WHAT_HAPPENS = listOf(
    "When someone walks up, the camera turns on for about a second, with a green dot and “Looking…” on screen.",
    "Faces are compared with the people set up on this Portal, then the frames are thrown away. Nothing is recorded.",
    "Someone it doesn’t know just sees the shared Home. Their face is not kept and nobody is told.",
    "Never at night (quiet hours), never during a call, never in the background.",
)

/** Settings › People › Privacy & Camera: pause, private items, the locked rules, audit, delete all. */
@Composable
internal fun WhosTherePrivacyBlock() {
  val context = LocalContext.current
  remember(context) { WhosThere.install(context) }
  val settings by WhosThereStore.settingsState.collectAsState()
  val audit by WhosThereStore.auditState.collectAsState()
  var confirmDelete by remember { mutableStateOf(false) }
  var notices by remember { mutableStateOf(false) }
  val now = System.currentTimeMillis()
  Card {
    val paused = settings.pausedUntilMs > now
    NavRow("Pause recognition", value = if (paused) "Paused" else "Off",
        subLabel = if (paused) "Tap to resume now" else "Tap to pause for one hour; everyone sees the shared Home") {
      WhosThereStore.updateSettings(context) { it.copy(pausedUntilMs = if (paused) 0L else now + 60 * 60_000L) }
      if (!paused) WhosThereStore.record(context, AuditKind.PAUSED)
    }
    Divider()
    ToggleRow("Hide private items when others are near", settings.hidePrivateNear) { on ->
      WhosThereStore.updateSettings(context) { it.copy(hidePrivateNear = on) }
    }
  }
  SectionLabel("Always")
  Card {
    ALWAYS.forEachIndexed { i, (title, help) ->
      if (i > 0) Divider()
      LockedRow(title, help)
    }
  }
  SectionLabel("Audit log · last 30 days")
  Card {
    val days = remember(audit) { WhosThereAudit.days(audit, TimeZone.getDefault()).take(7) }
    if (days.isEmpty()) LockedRow("Nothing yet", "Each look is counted here: the time and what happened. No names, faces or images.", locked = false)
    days.forEachIndexed { i, d ->
      if (i > 0) Divider()
      LockedRow(dayLabel(d.day), "${d.glances} looks · ${d.personal} personal Homes · ${d.corrections} “Not you?”", locked = false)
    }
  }
  FooterNote("Times and counts only, kept 30 days. Never names, scores, images or audio.")
  Spacer(Modifier.height(16.dp))
  Card {
    NavRow("Model licences", subLabel = "MediaPipe BlazeFace · OpenCV Zoo SFace · TensorFlow Lite — Apache 2.0") { notices = true }
    Divider()
    NavRow("Delete all recognition data on this Portal", subLabel = "Everyone’s face templates and the audit log. Can’t be undone.") {
      confirmDelete = true
    }
  }
  if (confirmDelete) ApprovedSettingsDialog("Delete all recognition data?", { confirmDelete = false }) {
    Column(Modifier.padding(horizontal = 36.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Every person and face template on this Portal is deleted now, and recognition turns off. Other Portals keep their own.",
          color = IosLabel, fontSize = 23.sp, lineHeight = 32.sp)
      PrimaryPillButton("Delete everything") { WhosThereStore.deleteAll(context); confirmDelete = false }
      ApprovedSettingsTextButton("Cancel") { confirmDelete = false }
    }
  }
  if (notices) ApprovedSettingsDialog("Model licences", { notices = false }) {
    val text = remember { runCatching { context.assets.open("whos_there/NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    Text(text, color = IosLabel, fontSize = 19.sp, lineHeight = 27.sp,
        modifier = Modifier.padding(horizontal = 36.dp).padding(bottom = 30.dp).verticalScroll(rememberScrollState()))
  }
}

private val ALWAYS = listOf(
    "On-device only" to "Faces are matched on this Portal. Nothing is sent anywhere, and templates are never synced.",
    "No images stored" to "Frames live in memory for about a second. Only a template of numbers is kept, encrypted.",
    "Camera-in-use indicator" to "A green dot and “Looking…” show whenever the camera is on. It can’t be turned off.",
    "Ask when unsure" to "A close match shows the shared Home, or asks “Is this you?”.",
    "Off at night and in calls" to "Never during quiet hours (Sounds › Chimes) or while a call has the camera.",
    "Corrections never retrain" to "“Not you?” only changes what’s on screen. Faces change only by setting up again.",
)

@Composable
private fun LockedRow(title: String, help: String, locked: Boolean = true) {
  Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).padding(horizontal = 28.dp, vertical = 20.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(end = 20.dp)) {
      Text(title, color = IosLabel, fontSize = Sizes.rowTitle, lineHeight = 34.sp)
      Text(help, color = IosSecondary, fontSize = Sizes.rowSub, lineHeight = 28.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (locked) SettingsLockedValue()
  }
}

private fun dayLabel(day: Long): String {
  val today = WhosThereAudit.localDay(System.currentTimeMillis(), TimeZone.getDefault())
  return when (today - day) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> java.text.SimpleDateFormat("EEEE d MMM", java.util.Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(day * 24L * 60 * 60 * 1000))
  }
}
