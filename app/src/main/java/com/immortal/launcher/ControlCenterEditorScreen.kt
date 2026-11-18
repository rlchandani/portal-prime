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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * What the side panels need: the working state, the registry (real or the no-areas stand-in),
 * live entities and the actions. [update] applies a pure [CcEd] operation to the working plan.
 */
internal class CcEdCtx(
    val st: CcEdState,
    val reg: CcRegistry,
    /** False when HA withheld its registries (non-admin token): rooms are named by hand. */
    val hasAreas: Boolean,
    val s: CcUiState,
    val host: CcEdDragHost,
    val update: ((CcEdState) -> CcEdState) -> Unit,
    val save: () -> Unit,
    val discard: () -> Unit,
) {
  val live: Map<String, CcEntity> get() = s.entities
  val now: Long get() = s.now
  val floor: CcFloor get() = CcEd.floor(st, reg)
}

@Composable
internal fun CcEditorScreen(s: CcUiState, ui: CcUi) {
  val plan = s.plan
  if (plan == null || (s.registry == null && s.connection.phase == CcPhase.CONNECTING)) {
    Box(Modifier.fillMaxSize()) {
      CcCenteredMessage("plan", "Loading your home…", "Reading floors, areas and the saved plan from Home Assistant.")
    }
    return
  }
  CcEditorBody(s, ui, plan)
}

@Composable
private fun CcEditorBody(s: CcUiState, ui: CcUi, saved: CcPlan) {
  val reg = remember(s.registry, s.entities.keys) { s.registry ?: CcEd.fallbackRegistry(s.entities) }
  var st by remember { mutableStateOf(CcEd.start(saved, reg)) }
  val host = remember { CcEdDragHost() }
  var origin by remember { mutableStateOf(Offset.Zero) }
  val floors = remember(reg, st.plan.floors) { CcEd.floors(reg, st.plan) }
  LaunchedEffect(floors) { if (floors.none { it.id == st.floorId }) st = CcEd.setFloor(st, floors.first().id) }
  val ctx = CcEdCtx(st, reg, s.registry != null, s, host, update = { op -> st = op(st) },
      save = { ControlCenter.savePlan(ui.context, CcEd.forSave(st, s.now)); ui.go(CcScreen.MAP) },
      discard = { ui.go(CcScreen.MAP) })
  val floor = ctx.floor
  val motion = LocalCcMotion.current
  Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      Box(Modifier.weight(1f).fillMaxHeight()) {
        if (st.step == CcEdStep.FOUND) {
          CcEditorCanvas(st, floor, reg, s.entities, host, motion, onTap = { _, _ -> }, onDrop = {}, modifier = Modifier.fillMaxSize())
          CcEditorEmptyHint()
        } else {
          CcEditorCanvas(st, floor, reg, s.entities, host, motion,
              onTap = { gx, gy -> st = CcEdGesture.tap(st, CcEd.floor(st, reg), gx, gy, reg, s.now) },
              onDrop = { d -> st = CcEdGesture.commit(st, d, reg, s.entities, s.now) }, modifier = Modifier.fillMaxSize())
        }
        CcEditorTools(ctx, floors, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp))
      }
      Column(Modifier.width(CcStyle.SideWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CcEditorSide(ctx)
      }
    }
    CcEditorGhost(host, s, reg, origin)
  }
}

/** Mock `.edTools`: floor seg, the draw/move seg on Rooms, step dashes (first build) or tabs (editing). */
@Composable
private fun CcEditorTools(ctx: CcEdCtx, floors: List<CcHaFloor>, modifier: Modifier) {
  val st = ctx.st
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    if (floors.size > 1) CcSeg(floors.map { Triple(it.id, it.name, null) }, st.floorId, { id -> ctx.update { CcEd.setFloor(it, id) } })
    if (st.step == CcEdStep.ROOMS) {
      CcSeg(listOf(Triple(CcEdTool.DRAW, "Draw room", "draw"), Triple(CcEdTool.MOVE, "Move", "select")), st.tool,
          { t -> ctx.update { CcEd.setTool(it, t) } })
    }
    Spacer(Modifier.weight(1f))
    if (st.first) CcStepDashes(st.step.number)
    else CcSeg(CcEdStep.TABS.map { Triple(it, it.tab, null) }, st.step, { step -> ctx.update { CcEd.goStep(it, step) } })
  }
}

/** Mock `.steps`: five dashes, ember up to the current step. */
@Composable
private fun CcStepDashes(current: Int) {
  val c = LocalCc.current
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    (1..CcEdStep.COUNT).forEach { i ->
      Box(Modifier.width(46.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (i <= current) CcStyle.Ember else c.surface2))
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CcEditorEmptyHint() {
  val c = LocalCc.current
  Column(Modifier.align(Alignment.Center).padding(top = CANVAS_TOP.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
    CcIcon("plan", c.sec, 56.dp)
    Text("Your rooms appear here.", color = c.sec, fontSize = 26.sp)
    Text("Snap grid · ${CcPlan.GRID_W} × ${CcPlan.GRID_H} squares", color = c.sec, fontSize = 20.sp)
  }
}

/** The chip that follows the finger while a device is carried (mock `.ghost`). */
@Composable
private fun CcEditorGhost(host: CcEdDragHost, s: CcUiState, reg: CcRegistry, origin: Offset) {
  val c = LocalCc.current
  val d = host.drag as? CcEdDrag.Device ?: return
  val at = host.ghost ?: return
  val name = s.entities[d.entityId]?.name ?: d.entityId
  Row(Modifier.offset { IntOffset((at.x - origin.x).roundToInt(), (at.y - origin.y).roundToInt()) }
      .offset(x = (-GHOST_LIFT_X).dp, y = (-GHOST_LIFT_Y).dp)
      .clip(CcStyle.CardSmall).background(c.accent).padding(horizontal = 18.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    CcIcon(CcEd.iconFor(d.entityId, reg, s.entities[d.entityId]), c.accentInk, 26.dp)
    Text(name, color = c.accentInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** Status line under a step: the last action's result. */
@Composable
internal fun CcEdMessage(text: String?) {
  if (text == null) return
  Text(text, color = LocalCc.current.sec, fontSize = 19.sp, modifier = Modifier.fillMaxWidth())
}

private const val GHOST_LIFT_X = 60
private const val GHOST_LIFT_Y = 70
