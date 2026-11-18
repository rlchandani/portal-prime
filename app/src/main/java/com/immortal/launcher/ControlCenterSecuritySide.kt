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
import androidx.compose.runtime.remember
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
internal fun CcSecuritySide(s: CcUiState, modifier: Modifier) {
  val c = LocalCc.current
  val checks = remember(s.entities) { CcSecurity.checklist(s.entities.values, ::ccTime) }
  val open = remember(s.entities) { CcSecurity.openCount(s.entities.values) }
  Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(Modifier.fillMaxWidth().ccCard().padding(24.dp)) {
      CcSectionLabel("Who's home", Modifier.padding(bottom = 14.dp))
      if (s.people.isEmpty()) Text("No people in Home Assistant yet. Add a person with a phone or presence sensor to see who's home.",
          color = c.sec, fontSize = 19.sp, lineHeight = 26.sp)
      s.people.chunked(2).forEachIndexed { row, pair ->
        Row(Modifier.fillMaxWidth().padding(top = if (row == 0) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          pair.forEachIndexed { i, p -> PersonCard(p, row * 2 + i, Modifier.weight(1f)) }
          if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
    if (checks.isNotEmpty()) Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 24.dp)) {
      CcSectionLabel("Sensors", Modifier.padding(bottom = 6.dp))
      checks.forEachIndexed { i, ck -> CheckRow(ck, divider = i < checks.lastIndex) }
    }
    Row(Modifier.fillMaxWidth().ccCard().padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      CcIcon(if (open > 0) "warn" else "check", if (open > 0) c.text else c.green, 26.dp)
      Text(CcSecurity.note(open), color = c.text, fontSize = 20.sp)
    }
  }
}

/** `.whoC`: avatar (greyed when away), name, where + how HA knows. */
@Composable
private fun PersonCard(p: CcEntity, index: Int, modifier: Modifier) {
  val c = LocalCc.current
  val home = CcSecurity.isHome(p)
  Row(modifier.clip(RoundedCornerShape(22.dp)).background(c.surface2).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    val tint = CcStyle.Avatars[index % CcStyle.Avatars.size]
    Box(Modifier.size(60.dp).alpha(if (home) 1f else AWAY_ALPHA).clip(CircleShape).background(if (home) tint else grey(tint)),
        contentAlignment = Alignment.Center) {
      Text(p.name.take(1).uppercase(), color = Color(0xFF14161B), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
    Column(Modifier.weight(1f)) {
      Text(p.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(CcSecurity.personLine(p), color = c.sec, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
