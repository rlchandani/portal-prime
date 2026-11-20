/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TICK_SETTLE_MS = 420L
private const val TAG_REFRESH_MS = 60_000L

/**
 * The personal Home's Reminders card (the mock's "Reminders · 🔒 Just you"): the person's own
 * reminders, then the household's. Private rows become soft bars while someone else is near, with
 * the "N private — someone's here · Show" pill. Tap the box to tick one off; tap the text to edit.
 */
@Composable
internal fun PersonalRemindersCard(ui: WhosThereUi, p: WhosTherePerson, modifier: Modifier) {
  val context = LocalContext.current
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ReminderStore.ensureLoaded(context) } }
  val state by ReminderStore.state.collectAsState()
  val card = remember(state, p, ui.people, ui.hidePrivate) { ReminderVisibility.homeCard(state, p, ui.people, ui.hidePrivate) }
  var editing by remember { mutableStateOf<Reminder?>(null) }
  var adding by remember { mutableStateOf(false) }
  var now by remember { mutableLongStateOf(ReminderStore.clock()) }
  LaunchedEffect(Unit) { while (true) { delay(TAG_REFRESH_MS); now = ReminderStore.clock() } }
  val use24 = remember { DateFormat.is24HourFormat(context) }
  val zone = remember { TimeZone.getDefault() }
  PersonalCardFrame(modifier, "Reminders", {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      AddChip { adding = true }
      if (card.hasPrivate) OwnerChip(null, "Just you", lock = true) else OwnerChip(p)
    }
  }) {
    if (card.rows.isEmpty()) {
      ApprovedHomeWidgetMessage("Nothing to remember. Tap + or say “Hey Jarvis, remind me to…”")
      return@PersonalCardFrame
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      card.rows.forEach { row ->
        androidx.compose.runtime.key(row.reminder.id) {
          ReminderRow(row, ReminderDue.tag(row.reminder.dueMs, now, zone, use24), onEdit = { editing = row.reminder })
        }
      }
    }
    if (ui.hidePrivate && card.privateHidden > 0) {
      Spacer(Modifier.weight(1f))
      PrivatePill(card.privateHidden, ui.canReveal)
    }
  }
  if (adding) ReminderEditSheet(null, p, ui.people) { adding = false }
  editing?.let { r -> ReminderEditSheet(r, p, ui.people) { editing = null } }
}

@Composable
private fun ReminderRow(row: ReminderVisibility.Row, tag: String?, onEdit: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var ticked by remember { mutableStateOf(false) }
  val fade by animateFloatAsState(if (ticked) 0.45f else 1f, tween(TICK_SETTLE_MS.toInt()), label = "tick")
  val r = row.reminder
  val bar = ApprovedHomePalette.Chrome
  Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).graphicsLayer { alpha = fade }, verticalAlignment = Alignment.CenterVertically) {
    val box = RoundedCornerShape(8.dp)
    Box(Modifier.size(52.dp).tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f, ringColor = ApprovedHomePalette.Accent,
        enabled = !row.redacted && !ticked) {
      ticked = true
      scope.launch {
        delay(TICK_SETTLE_MS)
        withContext(Dispatchers.IO) { runCatching { RemindersFeature.setDone(context, r.id, true) } }
      }
    }.semantics { contentDescription = if (row.redacted) "Private reminder" else "Mark ${r.title} done" },
        contentAlignment = Alignment.Center) {
      Box(Modifier.size(26.dp).clip(box).background(if (ticked) ApprovedHomePalette.Accent else androidx.compose.ui.graphics.Color.Transparent)
          .approvedOutline(box), contentAlignment = Alignment.Center) {
        if (ticked) WhosThereGlyph(WtGlyph.CHECK, ApprovedHomePalette.Card, 20.dp)
      }
    }
    Text(r.title, color = ApprovedHomePalette.Text, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp).weight(1f).redacted(row.redacted, bar)
            .tvFocusable(RoundedCornerShape(10.dp), focusScale = 1f, ringColor = ApprovedHomePalette.Accent,
                enabled = !row.redacted, onClick = onEdit))
    val label = if (!row.mine) "Home" else tag
    if (label != null) Tag(label, home = !row.mine, hidden = row.redacted)
  }
}

@Composable
private fun Tag(text: String, home: Boolean, hidden: Boolean) {
  Row(Modifier.padding(start = 10.dp).height(30.dp).clip(RoundedCornerShape(9.dp)).background(ApprovedHomePalette.Chrome)
      .padding(horizontal = 10.dp).redacted(hidden, ApprovedHomePalette.Chrome),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (home) ApprovedHomeIcon("home", Modifier.size(18.dp))
    Text(text, color = ApprovedHomePalette.Secondary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

@Composable
private fun AddChip(onClick: () -> Unit) {
  val shape = RoundedCornerShape(18.dp)
  Box(Modifier.size(36.dp).clip(shape).background(ApprovedHomePalette.Chrome)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { contentDescription = "Add a reminder" }, contentAlignment = Alignment.Center) {
    Text("+", color = ApprovedHomePalette.Secondary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
  }
}

/** The mock's lock pill: "3 private — someone’s here · Show" (Show only for the owner). */
@Composable
private fun PrivatePill(count: Int, canReveal: Boolean) {
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ApprovedHomePalette.Chrome)
      .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    WhosThereGlyph(WtGlyph.LOCK, ApprovedHomePalette.Secondary, 24.dp)
    Text("$count private — someone’s here", color = ApprovedHomePalette.Secondary, fontSize = 19.sp,
        modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
    if (canReveal) ApprovedHomeTextButton("Show") { WhosThere.reveal() }
  }
}
