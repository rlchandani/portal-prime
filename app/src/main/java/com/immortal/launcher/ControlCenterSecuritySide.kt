/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `.secSide`: WHO'S HOME, the SENSORS checklist and the "N things open" note. */
@Composable
internal fun CcSecuritySide(side: CcSecuritySideSlice, modifier: Modifier, ui: CcUi) {
  val c = LocalCc.current
  val checks = side.checks
  val open = side.open
  Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(Modifier.fillMaxWidth().ccCard().padding(24.dp)) {
      CcSectionLabel("Who's home", Modifier.padding(bottom = 14.dp))
      if (side.people.isEmpty()) Text("No people in Home Assistant yet. Add a person with a phone or presence sensor to see who's home.",
          color = c.sec, fontSize = 19.sp, lineHeight = 26.sp)
      side.people.chunked(2).forEachIndexed { row, pair ->
        Row(Modifier.fillMaxWidth().padding(top = if (row == 0) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          pair.forEach { p -> key(p.id) { PersonCard(p, Modifier.weight(1f)) } }
          if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
    if (side.sections.isNotEmpty()) CcSecuritySensors(side.sections, side.canControl, side.reason) { item -> securityAction(ui, item) }
    else if (checks.isNotEmpty()) Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
      CcSectionLabel("Sensors", Modifier.padding(bottom = 6.dp))
      checks.forEachIndexed { i, ck -> key(ck.title) { CheckRow(ck, divider = i < checks.lastIndex) } }
    }
    Row(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      val ready = side.readiness
      when (ready.tone) {
        CcRingTone.OFF -> CcIcon(if (open > 0 || ready.line != null) "warn" else "check", if (open > 0 || ready.line != null) c.text else c.green, 26.dp)
        CcRingTone.ARMED -> CcIcon("shield", c.green, 26.dp)
        CcRingTone.ARMING -> CcIcon("shield", c.amber, 26.dp)
        CcRingTone.ALERT -> CcIcon("warn", c.red, 26.dp)
      }
      Column(Modifier.weight(1f)) {
        Text(ready.title, color = c.text, fontSize = 20.sp, fontWeight = if (ready.line != null) FontWeight.Bold else null)
        ready.line?.let { Text(it, color = c.sec, fontSize = 18.sp) }
      }
    }
  }
}

/** An inline Close / Lock: the current entity, through the normal policy (garage asks with its camera). */
private fun securityAction(ui: CcUi, item: CcSecItem) {
  val e = ControlCenter.state(ui.context).value.entity(item.id) ?: return
  val call = when (item.action) {
    CcSecAction.CLOSE -> CcCalls.coverClose(e)
    CcSecAction.LOCK -> CcCalls.lock(e)
    null -> return
  }
  ui.act(e, call)
}

/** `.whoC`: avatar (greyed when away), name, where + how HA knows. */
@Composable
private fun PersonCard(p: CcPersonItem, modifier: Modifier) {
  val c = LocalCc.current
  val home = p.home
  Row(modifier.clip(RoundedCornerShape(22.dp)).background(c.surface2).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    val tint = CcStyle.Avatars[p.index % CcStyle.Avatars.size]
    Box(Modifier.size(60.dp).alpha(if (home) 1f else AWAY_ALPHA).clip(CircleShape).background(if (home) tint else grey(tint)),
        contentAlignment = Alignment.Center) {
      Text(p.name.take(1).uppercase(), color = Color(0xFF14161B), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
    Column(Modifier.weight(1f)) {
      Text(p.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(p.line, color = c.sec, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** The mock's `filter: grayscale(1)` for people who are away. */
private fun grey(color: Color): Color {
  val l = LUMA_R * color.red + LUMA_G * color.green + LUMA_B * color.blue
  return Color(l, l, l, color.alpha)
}

/** `.setRow` with an icon and an OK / amber tag. */
@Composable
private fun CheckRow(ck: CcCheck, divider: Boolean) {
  val c = LocalCc.current
  Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    CcIcon(ck.icon, c.text, 30.dp)
    Column(Modifier.weight(1f)) {
      Text(ck.title, color = c.text, fontSize = 22.sp)
      Text(ck.detail, color = c.sec, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    ck.tag?.let { CcTag(it, if (ck.ok == true) CcTone.GREEN else if (ck.ok == false) CcTone.AMBER else CcTone.NEUTRAL) }
  }
  if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.div))
}

private const val AWAY_ALPHA = 0.5f
private const val LUMA_R = 0.299f
private const val LUMA_G = 0.587f
private const val LUMA_B = 0.114f
