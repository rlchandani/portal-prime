/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Step 3 — Auto-place by HA area (devices fly in), then drag the leftovers onto rooms. */
@Composable
internal fun ColumnScope.CcStepDevices(ctx: CcEdCtx) {
  val un = remember(ctx.st.plan, ctx.st.floorId, ctx.reg, ctx.live.keys) { CcEd.unplacedDevices(ctx.st, ctx.reg, ctx.live) }
  val (placed, total) = remember(ctx.st.plan, ctx.st.floorId, ctx.reg, ctx.live.keys) { CcEd.deviceProgress(ctx.st, ctx.reg, ctx.live) }
  val auto = CcEd.autoPlaceable(un, ctx.st, ctx.reg)
  val tag = "$placed of $total placed" to if (un.isEmpty()) CcTone.GREEN else CcTone.NEUTRAL
  CcWizCard(ctx, "Anything with a Home Assistant area goes in automatically. Drag the rest onto a room.", tag = tag) {
    if (auto > 0) CcButton("Auto-place $auto by HA area", { ctx.update { CcEd.autoPlaceDevices(it, ctx.reg, ctx.live, ctx.now) } },
        icon = "sparkle", style = CcButtonStyle.EMBER)
  }
  val empty = if (ctx.st.first) "Everything on this floor is placed." else "Everything on this floor is placed. Drag a dot to move it, or off the plan to take it out."
  CcEntityList(ctx, un.sortedBy { if (ctx.reg.areaOf(it) == null) 0 else 1 }, empty,
      continueLabel = if (un.isEmpty()) "Continue" else "Continue (${un.size} unplaced)")
}

/** Step 4 — door, window and garage sensors snapped to walls; drag one along the walls to fix it. */
@Composable
internal fun ColumnScope.CcStepOpenings(ctx: CcEdCtx) {
  val un = remember(ctx.st.plan, ctx.st.floorId, ctx.reg, ctx.live.keys) { CcEd.unplacedOpenings(ctx.st, ctx.reg, ctx.live) }
  val auto = CcEd.autoPlaceable(un, ctx.st, ctx.reg)
  val onWalls = ctx.floor.openings
  CcWizCard(ctx, "Contact sensors and covers with a door, window or garage class snap to the nearest wall of their room. " +
      "Drag one along the walls to move it.", tag = "${onWalls.size} on walls" to if (un.isEmpty()) CcTone.GREEN else CcTone.NEUTRAL) {
    onWalls.take(MAX_WALL_ROWS).forEach { o ->
      val kind = CcEdGeo.openingKind(o.entityId, ctx.reg, ctx.live[o.entityId])
      CcAreaRow("check", ctx.live[o.entityId]?.name ?: o.entityId, kind.name.lowercase(), iconTint = LocalCc.current.green)
    }
    if (onWalls.size > MAX_WALL_ROWS) Text("and ${onWalls.size - MAX_WALL_ROWS} more", color = LocalCc.current.sec, fontSize = 18.sp)
    if (auto > 0) CcButton("Snap $auto to walls", { ctx.update { CcEd.snapOpenings(it, ctx.reg, ctx.live, ctx.now) } },
        icon = "sparkle", style = CcButtonStyle.EMBER)
  }
  CcEntityList(ctx, un, "Every door and window sensor on this floor is on a wall.", continueLabel = "Continue")
}

/** Mock `.card.ents`: the unplaced chips (drag sideways onto the plan) and the step's Continue. */
@Composable
private fun ColumnScope.CcEntityList(ctx: CcEdCtx, ids: List<String>, emptyText: String, continueLabel: String) {
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().weight(1f).ccCard().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (ids.isEmpty()) Text(emptyText, color = c.sec, fontSize = 20.sp, modifier = Modifier.padding(10.dp))
    else Text("Drag sideways onto a room", color = c.sec, fontSize = 18.sp)
    ids.forEach { id -> CcDeviceChip(ctx, id) }
    CcWizNav(ctx, continueLabel)
  }
}

/** Mock `.ent`: icon, name with the entity id in mono, and its area (or "no area"). */
@Composable
private fun CcDeviceChip(ctx: CcEdCtx, id: String) {
  val c = LocalCc.current
  val e = ctx.live[id]
  val name = e?.name ?: id
  val area = ctx.reg.areaOf(id)?.let { a -> ctx.reg.areas.firstOrNull { it.id == a }?.name }
  var origin by remember { mutableStateOf(Offset.Zero) }
  val carrying = (ctx.host.drag as? CcEdDrag.Device)?.entityId == id
  val latestCtx by rememberUpdatedState(ctx)
  Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).alpha(if (carrying) CARRY_ALPHA else 1f).clip(CcStyle.CardSmall).background(c.surface2)
      .onGloballyPositioned { origin = it.positionInRoot() }
      .pointerInput(id) { chipDrag(id, { origin }, { latestCtx }) }
      .semantics { contentDescription = "$name, drag onto a room" }
      .padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    CcIcon(CcEd.iconFor(id, ctx.reg, e), c.sec, 30.dp)
    Column(Modifier.weight(1f)) {
      Text(name, color = c.text, fontSize = 20.sp, maxLines = 1)
      Text(id, color = c.sec, fontSize = 15.sp, fontFamily = CcStyle.Mono, maxLines = 1)
    }
    if (area == null) CcTag("no area", CcTone.AMBER) else CcTag(area)
  }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.chipDrag(id: String, origin: () -> Offset, ctx: () -> CcEdCtx) =
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      val start = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() } ?: return@awaitEachGesture
      val host = ctx().host
      host.begin(CcEdDrag.Device(id, null, null), origin() + start.position)
      drag(start.id) { ch -> host.move(origin() + ch.position); ch.consume() }
      val d = host.end() ?: return@awaitEachGesture
      val now = ctx()
      now.update { CcEdGesture.commit(it, d, now.reg, now.live, now.now) }
    }

private const val CARRY_ALPHA = 0.5f
private const val MAX_WALL_ROWS = 6
