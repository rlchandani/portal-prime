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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PER_ROW = 4

/**
 * The car's controls as tiles, four to a row. Every tap goes through [CcUi.act] (policy, confirm
 * first, dry run). Kids mode replaces the tiles with a note: the screen stays look-only.
 */
@Composable
internal fun CcTeslaControls(actions: List<CcCarAction>, canAct: Boolean, kids: Boolean, entities: Map<String, CcEntity>, ui: CcUi, modifier: Modifier) {
  val c = LocalCc.current
  if (actions.isEmpty() && !kids) return
  Column(modifier.ccCard().padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    CcSectionLabel("Controls")
    if (kids) {
      Text("Kids mode · the car is look only here.", color = c.sec, fontSize = 21.sp)
      return@Column
    }
    actions.chunked(PER_ROW).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { a -> key(a.key) { ActionTile(a, canAct, entities, ui) } }
        repeat(PER_ROW - row.size) { Box(Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun RowScope.ActionTile(a: CcCarAction, enabled: Boolean, entities: Map<String, CcEntity>, ui: CcUi) {
  val c = LocalCc.current
  val tap = remember(a, ui, entities) { { a.call.entityId?.let(entities::get)?.let { ui.act(it, a.call) }; Unit } }
  Column(Modifier.weight(1f).height(108.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(TILE).background(if (a.on) c.sel else c.surface2)
      .clickable(enabled = enabled, role = Role.Button, onClick = tap).ccSpeak(a.label).padding(horizontal = 12.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
    CcIcon(a.icon, if (a.on) c.green else c.text, 34.dp)
    Text(a.label, color = c.text, fontSize = 19.sp, fontWeight = if (a.on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
  }
}

private val TILE = RoundedCornerShape(22.dp)
