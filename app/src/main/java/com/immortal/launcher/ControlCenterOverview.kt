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
 * The Home overview (mock `map`, `map-climate`, `loading`, `stale`): the live
 * floor plan card on the left — floor / lens controls, the plan canvas, legend and freshness
 * pill — and the side column (attention, scenes, the car, cameras) on the right.
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
    else CcSideColumn(s, ui, Modifier.width(CcStyle.SideWidth).fillMaxHeight().ccRegionLayer(c.bg))
  }
}

@Composable
private fun CcPlanCard(s: CcUiState, ui: CcUi, plan: CcPlan) {
  val view = ui.planView
  val floor = plan.floor(view.floorId) ?: return
  val lens = view.lens
  // Derived once per entity version / minute; an equal model makes every plan layer skip.
  // Floors already shown keep their model while nothing they depend on changed (LRU of a few floors).
  val models = remember { CcLru<String, Pair<CcModelKey, CcPlanModel>>(FLOOR_CACHE) }
  // Keyed by the entities this floor actually shows, so an HA update elsewhere in the house
  // (power meters, other floors) reuses the model instead of rebuilding it.
  val depIds = remember(floor) { CcPlanDeps.ids(floor) }
  // Normally the engine already built it off the main thread ([CcUiState.planModels]).
  val built = s.planModels[floor.id]?.takeIf { it.floor == floor }
  val model = if (built != null) built else {
    val key = CcModelKey(floor, CcPlanDeps.of(depIds, s.entities), s.settings, s.minute, s.appliancesDone)
    remember(key) {
      models[floor.id]?.takeIf { it.first == key }?.second
          ?: CcPlanModel.build(floor, s.entities, s.settings, s.now, s.appliancesDone).also { models.put(floor.id, key to it) }
    }
  }
  val legends = remember(model, s.fahrenheit) { CcLens.entries.map { CcOverviewText.legend(model, it, s.entities, s.fahrenheit) } }
  val floors = remember(plan.floors) { CcFloorTabs(plan.floors.map { CcFloorTab(it.id, CcOverviewText.shortFloor(it.name)) }) }
  val phase = s.connection.phase
  val fresh = remember(s.connection, s.minute) { CcOverviewText.freshness(s.connection, s.now) }
  val staleLine = remember(s.connection, s.minute) { if (phase == CcPhase.STALE) CcOverviewText.staleLine(s.connection, s.now) else null }
  val walk = rememberWalkView(s.sceneRun)
  val latest by rememberUpdatedState(s)
  val onTap = remember(ui) { { hit: CcPlanHit -> onPlanTap(hit, latest, ui) } }
  val onFloor = remember(ui) { { id: String -> ui.floor(id) } }
  val onLens = remember(ui) { { l: CcLens -> ui.lens(l) } }
  Box(Modifier.fillMaxSize()) {
    CcPlanCanvas(model, lens, view.highlightRoom, walk, stale = phase == CcPhase.STALE,
        fahrenheit = s.fahrenheit, onTap = onTap,
        modifier = Modifier.fillMaxSize().padding(top = 96.dp, bottom = 84.dp).alpha(if (phase in DOWN_PHASES) DOWN_ALPHA else 1f))
    CcPlanTopRow(floors, floor.id, lens, onFloor, onLens, Modifier.align(Alignment.TopStart).padding(start = 24.dp, end = 24.dp, top = 22.dp))
    staleLine?.let { CcStaleBar(it, Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) }
    CcPlanLegends(legends, lens, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 22.dp))
    CcFreshnessPill(fresh, phase == CcPhase.LIVE, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp))
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
/** How long the finished walk lingers before the run is cleared. */
private const val WALK_HOLD_MS = 1_200L
private const val FLOOR_CACHE = 4

/** Everything a floor's [CcPlanModel] depends on. */
internal data class CcModelKey(val floor: CcFloor, val deps: CcPlanDeps, val settings: ControlCenterConfig.Settings, val minute: Long,
                                val done: Map<String, Long>)

/**
 * The entities one floor's model reads — its rooms' devices and its openings — in a fixed order.
 * Equal when every slot holds the same instance (the engine keeps unchanged entities) or an
 * equal one (HA re-sent the same state).
 */
internal class CcPlanDeps(private val refs: Array<CcEntity?>) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CcPlanDeps || other.refs.size != refs.size) return false
    for (i in refs.indices) if (refs[i] !== other.refs[i] && refs[i] != other.refs[i]) return false
    return true
  }

  override fun hashCode(): Int = refs.size

  companion object {
    fun ids(floor: CcFloor): Array<String> =
        (floor.rooms.flatMap { r -> r.devices.map { it.entityId } } + floor.openings.map { it.entityId }).distinct().toTypedArray()

    fun of(ids: Array<String>, entities: Map<String, CcEntity>): CcPlanDeps = CcPlanDeps(Array(ids.size) { entities[ids[it]] })
  }
}
