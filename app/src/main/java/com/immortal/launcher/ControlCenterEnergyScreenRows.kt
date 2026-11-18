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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `.consR`: icon · name + room over an ember bar · kW. Tapping opens the room when the plan has it. */
@Composable
internal fun CcConsumerRow(item: CcConsumerItem, ui: CcUi) {
  val c = LocalCc.current
  val k = item.consumer
  val room = item.roomName
  val share = item.share
  val onClick = remember(item.roomId, ui) { { item.roomId?.let(ui::openRoom); Unit } }
  Row(Modifier.fillMaxWidth().heightIn(min = CcStyle.MinTarget).clip(RoundedCornerShape(16.dp))
      .clickable(enabled = room != null, role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { CcIcon(CcEnergyKpis.consumerIcon(k.entityId), c.sec, 34.dp) }
    Column(Modifier.weight(1f)) {
      Text(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.text)) { append(k.name) }
        room?.let { withStyle(SpanStyle(color = c.sec, fontSize = 17.sp)) { append("  $it") } }
      }, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(share.coerceIn(MIN_BAR, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(CcStyle.Ember))
      }
    }
    Text(CcEnergyFlow.value(CcEnergyNode.HOME, k.watts), color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
  }
}

private const val MIN_BAR = 0.03f
