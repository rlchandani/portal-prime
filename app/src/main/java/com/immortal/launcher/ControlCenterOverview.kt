/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
  if (currentLayoutClass().compactPortrait) { CcOverviewPortrait(s, ui, loading); return }
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

/**
 * The Mini upright: the plan card on top (the plan is wider than tall, so it gets the larger
 * share), the side column's cards below it, scrolling on their own.
 */
@Composable
private fun CcOverviewPortrait(s: CcUiState, ui: CcUi, loading: Boolean) {
  val c = LocalCc.current
  val plan = s.plan
  Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.weight(PORTRAIT_PLAN_WEIGHT).fillMaxWidth().clip(PLAN_CARD).background(c.planBg)) {
      when {
        loading || plan == null -> CcOverviewLoading(s)
        plan.isEmpty -> CcPlanEmpty(s, ui)
        else -> CcPlanCard(s, ui, plan, portrait = true)
      }
    }
    if (loading) CcSideSkeleton(Modifier.weight(1f).fillMaxWidth().clipToBounds())
    else CcSideColumn(s, ui, Modifier.weight(1f).fillMaxWidth().ccRegionLayer(c.bg))
  }
}

@Composable
private fun CcPlanCard(s: CcUiState, ui: CcUi, plan: CcPlan, portrait: Boolean = false) {
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
    val key = CcModelKey(floor, CcPlanDeps.of(depIds, s.entities), s.settings.forPlanModel(), s.minute, s.appliancesDone)
    remember(key) {
      models[floor.id]?.takeIf { it.first == key }?.second
          ?: CcPlanModel.build(floor, s.entities, s.settings, s.now, s.appliancesDone).also { models.put(floor.id, key to it) }
    }
  }
  val legends = remember(model, s.fahrenheit) {
    ccTrace("cc:legendsBuild") { CcLens.entries.map { CcOverviewText.legend(model, it, s.entities, s.fahrenheit) } }
  }
  val floors = remember(plan.floors) { CcFloorTabs(plan.floors.map { CcFloorTab(it.id, CcOverviewText.shortFloor(it.name)) }) }
  val phase = s.connection.phase
  val fresh = remember(s.connection, s.minute) { CcOverviewText.freshness(s.connection, s.now) }
  val staleLine = remember(s.connection, s.minute) { if (phase == CcPhase.STALE) CcOverviewText.staleLine(s.connection, s.now) else null }
  val walk = rememberWalkView(s.sceneRun)
  val latest by rememberUpdatedState(s)
  val onTap = remember(ui) { { hit: CcPlanHit -> onPlanTap(hit, latest, ui) } }
  val onFloor = remember(ui) { { id: String -> ui.floor(id) } }
  val onLens = remember(ui) { { l: CcLens -> ui.lens(l) } }
  // Floors already shown stay composed and laid out, parked unplaced with what they last showed:
  // switching back to one re-places it instead of recomposing its plan, re-measuring its legend
  // texts and re-recording its layers inside the tap's frame.
  val parking = remember { CcFloorParking<CcFloorFrame>(FLOOR_CACHE) }
  val order = parking.show(floor.id, CcFloorFrame(model, legends, lens, view.highlightRoom, phase == CcPhase.STALE, s.fahrenheit,
      view.pointing))
  val motion = LocalCcMotion.current
  val paint = rememberCcPlanPaint()
  val style = rememberPlanStyle(s.settings.plan3d, motion)
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().padding(top = 96.dp, bottom = 84.dp).alpha(if (phase in DOWN_PHASES) DOWN_ALPHA else 1f)) {
      order.forEach { id ->
        key(id) {
          val f = parking.frame(id)
          val active = id == floor.id
          if (f != null) CompositionLocalProvider(LocalCcMotion provides (motion && active)) {
            CcPlanCanvas(f.model, f.lens, f.focus, if (active) walk else null, stale = f.stale, fahrenheit = f.fahrenheit,
                onTap = onTap, modifier = Modifier.fillMaxSize().ccFloorCanvasSlot(active), paint = paint, parked = !active,
                iso = if (active) style.progress else style.rest, pulse = f.pulse)
          }
        }
      }
    }
    CcPlanTopRow(floors, floor.id, lens, onFloor, onLens, style.want3d, style.onStyle,
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp))
    staleLine?.let { CcStaleBar(it, Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) }
    val floorLegends: @Composable (Modifier) -> Unit = { m ->
      Box(m, contentAlignment = Alignment.BottomStart) {
        order.forEach { id ->
          key(id) {
            val f = parking.frame(id)
            val active = id == floor.id
            if (f != null) CcPlanLegends(f.legends, if (active) lens else f.lens, Modifier.ccFloorSlot(active))
          }
        }
      }
    }
    if (portrait) {
      // Narrow card: the legend and the freshness pill share one row; a long legend scrolls instead of colliding.
      Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
          verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        floorLegends(Modifier.weight(1f).horizontalScroll(rememberScrollState()))
        CcFreshnessPill(fresh, phase == CcPhase.LIVE)
      }
    } else {
      floorLegends(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 22.dp))
      CcFreshnessPill(fresh, phase == CcPhase.LIVE, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp))
    }
  }
}

/** What a floor last showed: a parked floor keeps exactly these instances, so it skips recomposition. */
@androidx.compose.runtime.Immutable
internal data class CcFloorFrame(val model: CcPlanModel, val legends: List<CcLegend>, val lens: CcLens, val focus: String?,
                                 val stale: Boolean, val fahrenheit: Boolean, val pulse: Boolean = false)

/**
 * The 2D / 3D switch: [want3d] answers the tap at once (the setting is saved on this Portal in
 * the background), [progress] animates the shown floor's view between them, and parked floors
 * simply wait at [rest]. Reduced motion (or a dimmed screen) switches without the animation.
 */
@androidx.compose.runtime.Stable
internal class CcPlanStyle(val want3d: Boolean, val progress: androidx.compose.runtime.State<Float>,
                           val rest: androidx.compose.runtime.State<Float>, val onStyle: (Boolean) -> Unit)

@Composable
private fun rememberPlanStyle(saved3d: Boolean, motion: Boolean): CcPlanStyle {
  var want3d by remember { androidx.compose.runtime.mutableStateOf(saved3d) }
  // The saved style can change from elsewhere too (the settings page, the phone remote).
  LaunchedEffect(saved3d) { want3d = saved3d }
  val context = LocalContext.current
  val progress = remember { Animatable(if (saved3d) 1f else 0f) }
  val latestSaved by rememberUpdatedState(saved3d)
  LaunchedEffect(want3d, motion) {
    val target = if (want3d) 1f else 0f
    if (motion) progress.animateTo(target, tween(ISO_SWITCH_MS, easing = FastOutSlowInEasing)) else progress.snapTo(target)
    // Saved once the house has landed: saving reloads the settings (a whole-screen recomposition),
    // which mid-switch would cost the animation its first frames.
    if (want3d != latestSaved) com.immortal.launcher.settings.ControlCenterSettings.set(context, "planStyle",
        if (want3d) ControlCenterConfig.PLAN_ISO else ControlCenterConfig.PLAN_2D)
  }
  val rest = remember(want3d) { androidx.compose.runtime.mutableFloatStateOf(if (want3d) 1f else 0f) }
  val onStyle = remember { { d3: Boolean -> want3d = d3 } }
  return CcPlanStyle(want3d, progress.asState(), rest, onStyle)
}

/**
 * A floor's canvas, shown or parked. A parked canvas stays placed (so the renderer still syncs it
 * and frees its offscreen textures: see `parked` on [CcPlanCanvas]) but is fully transparent, sits
 * under the shown floor, ignores taps and is hidden from screen readers.
 */
internal fun Modifier.ccFloorCanvasSlot(active: Boolean): Modifier =
    (if (active) this else this.clearAndSetSemantics {}).zIndex(if (active) 1f else 0f)
        .graphicsLayer { alpha = if (active) 1f else 0f }

/**
 * A shown-or-parked slot: the shown floor is placed and readable; a parked one keeps its
 * composition and measurement but is not placed (so not drawn, tapped or read by a screen reader).
 */
internal fun Modifier.ccFloorSlot(active: Boolean): Modifier =
    (if (active) this else this.clearAndSetSemantics {}).layout { measurable, constraints ->
      val p = measurable.measure(constraints)
      if (active) layout(p.width, p.height) { p.place(0, 0) } else layout(0, 0) {}
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
/** Portrait: the plan card's share of the height against the side cards' 1. */
private const val PORTRAIT_PLAN_WEIGHT = 1.5f
private val DOWN_PHASES = setOf(CcPhase.UNREACHABLE, CcPhase.OFFLINE, CcPhase.AUTH_FAILED, CcPhase.NOT_CONFIGURED)
private const val DOWN_ALPHA = 0.55f
/** How long the finished walk lingers before the run is cleared. */
private const val WALK_HOLD_MS = 1_200L
private const val FLOOR_CACHE = 4
/** The 2D ↔ 3D switch: the house tips back (or lies flat) over this long. */
private const val ISO_SWITCH_MS = 650

/** Everything a floor's [CcPlanModel] depends on. */
internal data class CcModelKey(val floor: CcFloor, val deps: CcPlanDeps, val settings: ControlCenterConfig.Settings, val minute: Long,
                                val done: Map<String, Long>, val occupancy: Map<String, CcRoomOcc> = emptyMap())

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
