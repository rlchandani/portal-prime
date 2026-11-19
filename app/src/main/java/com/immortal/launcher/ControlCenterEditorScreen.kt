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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The editor's actions on its working state (stable: one instance per editor). */
@Stable
internal class CcEdActions(
    private val state: MutableState<CcEdState>,
    val save: () -> Unit,
    val discard: () -> Unit,
) {
  /** Applies a pure [CcEd] operation to the working plan. */
  fun update(op: (CcEdState) -> CcEdState) { state.value = op(state.value) }
  /** Wall clock for stamping edits — read inside handlers only, never in composition. */
  fun now(): Long = System.currentTimeMillis()
}

/**
 * What the side panels need: the working state, the registry (real or the no-areas stand-in),
 * the entity snapshot (refreshed only when entities appear or vanish) and the actions. Immutable,
 * so a step skips unless one of these changed.
 */
@Immutable
internal data class CcEdCtx(
    val st: CcEdState,
    val reg: CcRegistry,
    /** False when HA withheld its registries (non-admin token): rooms are named by hand. */
    val hasAreas: Boolean,
    val live: Map<String, CcEntity>,
    val canControl: Boolean,
    val host: CcEdDragHost,
    val act: CcEdActions,
) {
  val now: Long get() = act.now()
  val floor: CcFloor get() = CcEd.floor(st, reg)
  fun update(op: (CcEdState) -> CcEdState) = act.update(op)
  val save: () -> Unit get() = act.save
  val discard: () -> Unit get() = act.discard
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
  // The editor reads names/classes only: re-snapshot entities when the set of ids changes, not on every state tick.
  val ids = s.entities.keys
  val live = remember(ids.size, ids.hashCode()) { s.entities }
  CcEditorBody(plan, s.registry, live, s.connection.canControl, ui)
}

@Composable
private fun CcEditorBody(saved: CcPlan, registry: CcRegistry?, live: Map<String, CcEntity>, canControl: Boolean, ui: CcUi) {
  val reg = remember(registry, live) { registry ?: CcEd.fallbackRegistry(live) }
  val stState = remember { mutableStateOf(CcEd.start(saved, reg)) }
  val st = stState.value
  val host = remember { CcEdDragHost() }
  val act = remember(ui) {
    CcEdActions(stState, save = { ControlCenter.savePlan(ui.context, CcEd.forSave(stState.value, System.currentTimeMillis())); ui.go(CcScreen.MAP) },
        discard = { ui.go(CcScreen.MAP) })
  }
  val floors = remember(reg, st.plan.floors) { CcEd.floors(reg, st.plan) }
  LaunchedEffect(floors) { if (floors.none { it.id == stState.value.floorId }) act.update { CcEd.setFloor(it, floors.first().id) } }
  val ctx = remember(st, reg, registry != null, live, canControl) { CcEdCtx(st, reg, registry != null, live, canControl, host, act) }
  val floor = remember(st, reg) { CcEd.floor(st, reg) }
  val motion = LocalCcMotion.current
  val onTap = remember(act, reg) { { gx: Float, gy: Float -> act.update { CcEdGesture.tap(it, CcEd.floor(it, reg), gx, gy, reg, act.now()) } } }
  val onDrop = remember(act, reg, live) { { d: CcEdDrag -> act.update { CcEdGesture.commit(it, d, reg, live, act.now()) } } }
  val portrait = currentLayoutClass().compactPortrait
  Box(Modifier.fillMaxSize().onGloballyPositioned { host.editorOrigin = it.positionInRoot() }) {
    if (portrait) Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
      // The Mini upright: steps above the canvas (the canvas keeps its own top row for floor and tool),
      // then the canvas, then the step's panel full width. Devices drag up from the panel onto the plan.
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CcEditorSteps(ctx) }
      Box(Modifier.weight(PORTRAIT_CANVAS_WEIGHT).fillMaxWidth()) {
        CcEditorCanvas(st, floor, reg, live, host, motion, onTap = onTap, onDrop = onDrop, modifier = Modifier.fillMaxSize())
        if (st.step == CcEdStep.FOUND) CcEditorEmptyHint()
        CcEditorTools(ctx, floors, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp),
            steps = false)
      }
      Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CcEditorSide(ctx)
      }
    } else Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      Box(Modifier.weight(1f).fillMaxHeight()) {
        CcEditorCanvas(st, floor, reg, live, host, motion, onTap = onTap, onDrop = onDrop, modifier = Modifier.fillMaxSize())
        if (st.step == CcEdStep.FOUND) CcEditorEmptyHint()
        CcEditorTools(ctx, floors, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp))
      }
      Column(Modifier.width(CcStyle.SideWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CcEditorSide(ctx)
      }
    }
    CcEditorGhost(host, live, reg)
  }
}

/**
 * Mock `.edTools`: floor seg, the draw/move seg on Rooms, step dashes (first build) or tabs (editing).
 * The step tabs and the tool seg always keep their full size; the floor seg (short names, as on the
 * map) takes what is left and scrolls sideways with soft edges when a home has many or long
 * floor names, keeping the chosen floor in view. Nothing is ever clipped out of reach.
 */
@Composable
private fun CcEditorTools(ctx: CcEdCtx, floors: List<CcHaFloor>, modifier: Modifier, steps: Boolean = true) {
  val st = ctx.st
  val floorTabs = remember(floors) { floors.map { Triple(it.id, CcOverviewText.shortFloor(it.name), null as String?) } }
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      if (floors.size > 1) {
        CcScrollingSeg(st.floorId, Modifier.weight(1f, fill = false)) { inView ->
          CcSeg(floorTabs, st.floorId, { id -> ctx.update { CcEd.setFloor(it, id) } }, selectedModifier = inView)
        }
      }
      if (st.step == CcEdStep.ROOMS) {
        CcSeg(listOf(Triple(CcEdTool.DRAW, "Draw room", "draw"), Triple(CcEdTool.MOVE, "Move", "select")), st.tool,
            { t -> ctx.update { CcEd.setTool(it, t) } })
      }
    }
    if (steps) {
      if (st.first) CcStepDashes(st.step.number)
      else CcSeg(CcEdStep.TABS.map { Triple(it, it.tab, null) }, st.step, { step -> ctx.update { CcEd.goStep(it, step) } })
    }
  }
}

/** Portrait: the step dashes (first build) or step tabs, on their own row above the canvas. */
@Composable
private fun CcEditorSteps(ctx: CcEdCtx) {
  val st = ctx.st
  if (st.first) Box(Modifier.height(58.dp), contentAlignment = Alignment.Center) { CcStepDashes(st.step.number) }
  else CcSeg(CcEdStep.TABS.map { Triple(it, it.tab, null) }, st.step, { step -> ctx.update { CcEd.goStep(it, step) } })
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

/** The chip that follows the finger while a device is carried (mock `.ghost`); it moves in layout, never recomposes. */
@Composable
private fun CcEditorGhost(host: CcEdDragHost, live: Map<String, CcEntity>, reg: CcRegistry) {
  val c = LocalCc.current
  val d = host.active as? CcEdDrag.Device ?: return
  val name = live[d.entityId]?.name ?: d.entityId
  Row(Modifier.offset {
        val at = host.ghost ?: host.editorOrigin
        IntOffset((at.x - host.editorOrigin.x).roundToInt(), (at.y - host.editorOrigin.y).roundToInt())
      }
      .offset(x = (-GHOST_LIFT_X).dp, y = (-GHOST_LIFT_Y).dp)
      .clip(CcStyle.CardSmall).background(c.accent).padding(horizontal = 18.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    CcIcon(CcEd.iconFor(d.entityId, reg, live[d.entityId]), c.accentInk, 26.dp)
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
/** Portrait: the canvas's share of the height against the step panel's 1. */
private const val PORTRAIT_CANVAS_WEIGHT = 1.25f
private const val GHOST_LIFT_Y = 70
