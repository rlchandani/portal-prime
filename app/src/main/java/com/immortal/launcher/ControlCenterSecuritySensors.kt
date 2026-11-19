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
 * The SENSORS card (`.setRow`s): one summary row per group with its tag ("4 open"); tapping a row
 * opens it to list exactly which ones — name, room, state and since when — with an inline Close for
 * an open garage door and Lock for an unlocked lock (through `CcUi.act`, so the garage still shows
 * its camera first and dry run still holds). Groups needing attention start open.
 */
@Composable
internal fun CcSecuritySensors(sections: List<CcSecSection>, canControl: Boolean, reason: String?, onAction: (CcSecItem) -> Unit) {
  if (sections.isEmpty()) return
  val c = LocalCc.current
  val now by rememberCamNow(MINUTE_MS)
  val total = sections.sumOf { it.items.size }
  var open by remember { mutableStateOf<Set<CcSecGroup>?>(null) }
  val expanded = open ?: sections.filter { it.ok == false }.map { it.group }.toSet()
  val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
    CcSectionLabel("Sensors · $total", Modifier.padding(bottom = 6.dp))
    sections.forEachIndexed { i, sec ->
      key(sec.group) {
        val isOpen = sec.group in expanded
        GroupRow(sec, isOpen) { open = if (isOpen) expanded - sec.group else expanded + sec.group }
        if (isOpen) sec.items.forEach { item ->
          key(item.id) { ItemRow(item, canControl, now, { ccTime(it) }, { day.format(Date(it)) }, onAction) }
        }
        if (i < sections.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.div))
      }
    }
    if (!canControl && sections.any { s -> s.items.any { it.action != null } }) reason?.let { Text(it, color = c.faint, fontSize = 17.sp) }
  }
}

/** Icon, group title + who's open, the tag, and a chevron; the whole row opens the group. */
@Composable
private fun GroupRow(sec: CcSecSection, open: Boolean, onToggle: () -> Unit) {
  val c = LocalCc.current
  val title = "${sec.group.title} · ${sec.items.size}"
  Row(Modifier.fillMaxWidth().clip(CcStyle.Pill).clickable(role = Role.Button, onClick = onToggle)
      .ccSpeak("$title, ${sec.tag}, ${if (open) "open" else "closed"}").padding(vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    CcIcon(sec.group.icon, c.text, 30.dp)
    Column(Modifier.weight(1f)) {
      Text(title, color = c.text, fontSize = 22.sp)
      Text(sec.summary, color = c.sec, fontSize = 18.sp, maxLines = if (open) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
    CcTag(sec.tag, if (sec.ok == true) CcTone.GREEN else if (sec.ok == false) CcTone.AMBER else CcTone.NEUTRAL)
    CcIcon("chev", c.sec, 24.dp, Modifier.rotate(if (open) QUARTER_TURN else 0f))
  }
}

/** One entity: name, "Garage · since 7:25 pm", its state, and Close / Lock when that's what it needs. */
@Composable
private fun ItemRow(item: CcSecItem, canControl: Boolean, now: Long, time: (Long) -> String, day: (Long) -> String, onAction: (CcSecItem) -> Unit) {
  val c = LocalCc.current
  val since = CcSecurityList.sinceLine(item.since, now, time, day)
  val sub = listOfNotNull(item.area, since.ifEmpty { null }).joinToString(" · ")
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
