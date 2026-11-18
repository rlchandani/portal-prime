/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The Home overview (mock `map`, `map-climate`, `map-energy`, `loading`, `stale`): the live
 * floor plan card on the left — floor / lens controls, the plan canvas, legend and freshness
 * pill — and the side column (attention, scenes, energy, cameras) on the right.
 */
@Composable
internal fun CcOverviewScreen(s: CcUiState, ui: CcUi) {
  val c = LocalCc.current
  val plan = s.plan
  val loading = plan == null || (s.connection.phase == CcPhase.CONNECTING && s.entities.isEmpty())
  CcSceneWalkClock(s)
  Row(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    Box(Modifier.weight(1f).fillMaxHeight().clip(PLAN_CARD).background(c.planBg)) {
      when {
        loading -> CcOverviewLoading(s)
        plan.isEmpty -> CcPlanEmpty(s, ui)
        else -> CcPlanCard(s, ui, plan)
      }
    }
    if (loading) CcSideSkeleton(Modifier.width(CcStyle.SideWidth).fillMaxHeight())
    else CcSideColumn(s, ui, Modifier.width(CcStyle.SideWidth).fillMaxHeight())
  }
}

@Composable
private fun CcPlanCard(s: CcUiState, ui: CcUi, plan: CcPlan) {
  val floor = plan.floor(ui.nav.floorId) ?: return
  val lens = ui.nav.lens
  val minute = s.now / MS_PER_MIN
  val model = remember(floor, s.entities, s.settings, minute, s.appliancesDone) {
    CcPlanModel.build(floor, s.entities, s.settings, s.now, s.appliancesDone)
  }
  // House-level sources (solar, grid, battery) belong to the first floor, as in the mock.
  val sources = remember(s.entities, lens, floor.id) {
    if (lens == CcLens.ENERGY && floor.id == plan.floors.firstOrNull()?.id) CcMapSources.of(CcEnergy.now(s.entities)) else CcMapSources.NONE
  }
  val walk = rememberWalkView(s.sceneRun)
  val down = s.connection.phase in DOWN_PHASES
  val latest by rememberUpdatedState(s)
  val onTap = remember(ui) { { hit: CcPlanHit -> onPlanTap(hit, latest, ui) } }
  Box(Modifier.fillMaxSize()) {
    CcPlanCanvas(model, lens, ui.nav.highlightRoom, walk, sources, stale = s.connection.phase == CcPhase.STALE,
        fahrenheit = s.fahrenheit, onTap = onTap,
        modifier = Modifier.fillMaxSize().padding(top = 96.dp, bottom = 84.dp).alpha(if (down) DOWN_ALPHA else 1f))
    CcPlanTopRow(plan, floor, lens, ui, Modifier.align(Alignment.TopStart).padding(start = 24.dp, end = 24.dp, top = 22.dp))
    if (s.connection.phase == CcPhase.STALE) CcStaleBar(s, Modifier.align(Alignment.TopCenter).padding(top = 100.dp))
    CcPlanLegend(s, model, lens, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 22.dp))
    CcFreshnessPill(s, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp))
  }
}

private fun onPlanTap(hit: CcPlanHit, s: CcUiState, ui: CcUi) {
  when (hit) {
    is CcPlanHit.Room -> ui.openRoom(hit.roomId)
    is CcPlanHit.Device ->
      if (hit.kind == CcMapBadgeKind.CAMERA && !CcPolicy.screenHidden(CcScreen.CAMERAS, s.settings)) ui.camera(hit.entityId)
      else { ui.openRoom(hit.roomId); ui.openDevice(hit.entityId) }
  }
}

/** The scene walk's progress for the canvas (null when no scene is running). */
@Composable
private fun rememberWalkView(run: CcSceneRun?): CcWalkView? {
  val progress = remember(run?.sceneId, run?.startedAt) {
    Animatable(run?.progress(System.currentTimeMillis()) ?: 0f)
  }
  val motion = LocalCcMotion.current
  LaunchedEffect(run?.sceneId, run?.startedAt, motion) {
    if (run == null) return@LaunchedEffect
    val remaining = (run.startedAt + run.durationMs - System.currentTimeMillis()).coerceAtLeast(0L)
    if (motion && remaining > 0) progress.animateTo(1f, tween(remaining.toInt(), easing = LinearEasing))
    else progress.snapTo(1f)
  }
  if (run == null || !motion) return null
  return remember(run.sceneId, run.startedAt, run.rooms) { CcWalkView(run.rooms, progress.asState()) }
}

/**
 * Ends the scene walk: once its time is up (plus a short hold so the last room reads), the
 * run is cleared. Reduced motion draws no walk at all and simply clears when the time is up.
 */
@Composable
private fun CcSceneWalkClock(s: CcUiState) {
  val context = LocalContext.current
  val run = s.sceneRun ?: return
  LaunchedEffect(run.sceneId, run.startedAt) {
    val remaining = (run.startedAt + run.durationMs - System.currentTimeMillis()).coerceAtLeast(0L)
    delay(remaining + WALK_HOLD_MS)
    ControlCenter.clearSceneRun(context)
  }
}

private val PLAN_CARD = RoundedCornerShape(36.dp)
private val DOWN_PHASES = setOf(CcPhase.UNREACHABLE, CcPhase.OFFLINE, CcPhase.AUTH_FAILED, CcPhase.NOT_CONFIGURED)
private const val DOWN_ALPHA = 0.55f
private const val MS_PER_MIN = 60_000L
/** How long the finished walk lingers before the run is cleared. */
private const val WALK_HOLD_MS = 1_200L
