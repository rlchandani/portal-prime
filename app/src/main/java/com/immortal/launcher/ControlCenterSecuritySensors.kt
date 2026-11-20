/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SENSORS card (`.setRow`s), as the mock reads it: four summary rows — Doors (with locks),
 * Windows, Garage, Motion — each with its count, a line ("Office window", "Open since 7:25 pm") and
 * a status tag; tapping a row opens it to list exactly which ones, with friendly names, room, state
 * and since when, plus an inline Close for an open garage door and Lock for an unlocked lock
 * (through `CcUi.act`, so the garage still shows its camera first and dry run still holds). The
 * alarm panel, smoke & CO and tamper sit behind "More sensors" unless they need attention, when
 * they lead the card already open. Everything starts closed otherwise, like the mock.
 */
@Composable
internal fun CcSecuritySensors(sections: List<CcSecSection>, canControl: Boolean, reason: String?, onAction: (CcSecItem) -> Unit) {
  val card = remember(sections) { CcSecuritySummary.card(sections) }
  if (card.isEmpty) return
  val c = LocalCc.current
  val now by rememberCamNow(MINUTE_MS)
  var open by remember { mutableStateOf<Set<CcSecGroup>?>(null) }
  var moreOpen by remember { mutableStateOf(false) }
  val expanded = open ?: CcSecuritySummary.startOpen(card)
  val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
  val time: (Long) -> String = remember { { ccTime(it) } }
  val dayOf: (Long) -> String = remember(day) { { day.format(Date(it)) } }
  val toggle: (CcSecGroup) -> Unit = { g -> open = if (g in expanded) expanded - g else expanded + g }
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
    CcSectionLabel("Sensors", Modifier.padding(bottom = 6.dp))
    card.primary.forEachIndexed { i, sec ->
      key(sec.group) {
        SectionRows(sec, sec.group in expanded, canControl, now, time, dayOf, onAction, toggle)
        if (i < card.primary.lastIndex || card.more.isNotEmpty()) SecDivider()
      }
    }
    if (card.more.isNotEmpty()) {
      MoreRow(card, moreOpen) { moreOpen = !moreOpen }
      if (moreOpen) card.more.forEach { sec ->
        key(sec.group) {
          SecDivider()
          SectionRows(sec, sec.group in expanded, canControl, now, time, dayOf, onAction, toggle)
        }
      }
    }
    if (!canControl && sections.any { s -> s.items.any { it.action != null } }) reason?.let { Text(it, color = c.faint, fontSize = 17.sp) }
  }
}

@Composable
private fun SectionRows(sec: CcSecSection, isOpen: Boolean, canControl: Boolean, now: Long, time: (Long) -> String, day: (Long) -> String,
                        onAction: (CcSecItem) -> Unit, toggle: (CcSecGroup) -> Unit) {
  GroupRow(sec, CcSecuritySummary.line(sec, now, time, day), isOpen) { toggle(sec.group) }
  if (isOpen) sec.items.forEach { item -> key(item.id) { ItemRow(item, canControl, now, time, day, onAction) } }
}

@Composable
private fun SecDivider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(LocalCc.current.div))
}

/** Icon, "Doors · 15", its line, the tag, and a chevron; the whole row opens the group. */
@Composable
private fun GroupRow(sec: CcSecSection, line: String, open: Boolean, onToggle: () -> Unit) {
  val c = LocalCc.current
  val count = sec.items.size
  val tag = sec.tag.takeIf { CcSecuritySummary.showsTag(sec) }
  Row(Modifier.fillMaxWidth().clip(CcStyle.Pill).clickable(role = Role.Button, onClick = onToggle)
      .ccSpeak("${sec.group.title}, $count, $line${tag?.let { ", $it" } ?: ""}, ${if (open) "open" else "closed"}").padding(vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    CcIcon(sec.group.icon, c.text, 30.dp)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sec.group.title, color = c.text, fontSize = 22.sp, maxLines = 1)
        Text("$count", color = c.faint, fontSize = 19.sp, maxLines = 1)
      }
      Text(line, color = c.sec, fontSize = 18.sp, maxLines = if (open) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
    tag?.let { CcTag(it, if (sec.ok == true) CcTone.GREEN else if (sec.ok == false) CcTone.AMBER else CcTone.NEUTRAL) }
    CcIcon("chev", c.sec, 24.dp, Modifier.rotate(if (open) QUARTER_TURN else 0f))
  }
}

/** "More sensors · 9" — the alarm panel, smoke & CO and tamper while they're calm. */
@Composable
private fun MoreRow(card: CcSecCard, open: Boolean, onToggle: () -> Unit) {
  val c = LocalCc.current
  val line = CcSecuritySummary.moreLine(card)
  Row(Modifier.fillMaxWidth().clip(CcStyle.Pill).clickable(role = Role.Button, onClick = onToggle)
      .ccSpeak("More sensors, ${card.moreCount}, $line, ${if (open) "open" else "closed"}").padding(vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    CcIcon("shield", c.text, 30.dp)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("More sensors", color = c.text, fontSize = 22.sp, maxLines = 1)
        Text("${card.moreCount}", color = c.faint, fontSize = 19.sp, maxLines = 1)
      }
      Text(line, color = c.sec, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    CcTag("OK", CcTone.GREEN)
    CcIcon("chev", c.sec, 24.dp, Modifier.rotate(if (open) QUARTER_TURN else 0f))
  }
}

/** One entity: friendly name, "Garage · since 7:25 pm", its state, and Close / Lock when that's what it needs. */
@Composable
private fun ItemRow(item: CcSecItem, canControl: Boolean, now: Long, time: (Long) -> String, day: (Long) -> String, onAction: (CcSecItem) -> Unit) {
  val c = LocalCc.current
  val sub = CcSecNames.subline(item.name, item.area, CcSecurityList.sinceLine(item.since, now, time, day))
  Row(Modifier.fillMaxWidth().alpha(if (item.unavailable) UNAVAILABLE_ALPHA else 1f).clip(CcStyle.Pill)
      .then(if (item.attention) Modifier.background(c.sel) else Modifier).padding(start = 48.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Column(Modifier.weight(1f)) {
      Text(item.name, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (sub.isNotEmpty()) Text(sub, color = c.sec, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val action = item.action
    if (action != null) CcButton(action.label, onClick = { onAction(item) }, icon = if (action == CcSecAction.LOCK) "lock" else "garage",
        style = CcButtonStyle.PRIMARY, enabled = canControl, height = CcStyle.MinTarget.value.toInt())
    else CcTag(item.state, if (item.attention) CcTone.AMBER else CcTone.NEUTRAL)
  }
}

private const val QUARTER_TURN = 90f
private const val MINUTE_MS = 60_000L
private const val UNAVAILABLE_ALPHA = 0.55f
