/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay

/** Screens kept composed after the first visit so switching back costs no composition or redraw. */
internal val CC_KEEP_ALIVE = setOf(CcScreen.MAP, CcScreen.CAMERAS, CcScreen.TESLA, CcScreen.ALERTS, CcScreen.SECURITY)

/**
 * The current screen, plus every kept-alive screen already visited, parked off-screen with the
 * snapshot it last showed (same instance, so it skips recomposition and keeps its draw lists).
 *
 * Each screen is its own subcomposition, so the prewarm can build an unvisited screen a few
 * milliseconds per frame (a paused precomposition) instead of in one 50-90 ms frame, and a first
 * visit is then a cheap swap.
 */
@Composable
internal fun CcScreenSlots(s: CcUiState, ui: CcUi, content: @Composable (CcScreen, CcUiState) -> Unit) {
  val current = ui.screen
  val visited = remember { mutableStateListOf<CcScreen>() }
  val frozen = remember { HashMap<CcScreen, CcUiState>() }
  if (current in CC_KEEP_ALIVE) { if (current !in visited) visited += current; frozen[current] = s }
  val motion = LocalCcMotion.current
  val latest by rememberUpdatedState(s)
  val shownScreen = rememberUpdatedState(current)
  val shownState = rememberUpdatedState(s)
  val motionOn = rememberUpdatedState(motion)
  val draw by rememberUpdatedState(content)
  val slots = remember { SubcomposeLayoutState() }
  val warming = remember { CcWarming() }
  // One content lambda per screen, created once: it reads the current screen / state / motion
  // through State, so a slot recomposes only when those change, and a prewarmed composition is
  // exactly the one the layout later adopts.
  val slotContent = remember { HashMap<CcScreen, @Composable () -> Unit>() }
  val slot: (CcScreen) -> @Composable () -> Unit = { screen ->
    slotContent.getOrPut(screen) {
      {
        val active = screen == shownScreen.value
        // True once a parked screen has had a frame to record its draw lists (see [ccSlotDraw]).
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(active) {
          settled = false
          if (!active) { withFrameNanos { }; withFrameNanos { }; settled = true }
        }
        val mode = ccSlotDraw(screen, active, settled)
        // A parked screen is off-screen: it stays out of the semantics tree, which Compose diffs
        // after every change while the Portal's accessibility services are on (always).
        val layer = Modifier.graphicsLayer { alpha = if (mode == CcSlotDraw.RECORDING) 0f else 1f }
            .drawWithContent { if (mode != CcSlotDraw.DETACHED) drawContent() }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
        Box(Modifier.fillMaxSize().then(layer)) {
          CompositionLocalProvider(LocalCcScreenActive provides active, LocalCcMotion provides (active && motionOn.value)) {
            draw(screen, if (active || screen !in CC_KEEP_ALIVE) shownState.value else frozen.getValue(screen))
          }
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    delay(PREWARM_AFTER_MS)
    for (screen in CC_KEEP_ALIVE) {
      if (screen in visited || CcPolicy.screenHidden(screen, latest.settings)) continue
      awaitQuiet(ui)
      frozen[screen] = latest
      val paused = slots.createPausedPrecomposition(screen, slot(screen))
      warming.start(screen, paused)
      var done = false
      while (!done && warming.screen == screen) {
        // A composition step can overrun its budget (one big group can't pause), so steps run
        // only while nobody is touching: a tap never lands on a prewarm step.
        awaitQuiet(ui)
        withFrameNanos { }
        if (warming.screen != screen) break // visited (and cancelled by the layout) meanwhile
        // A visit (or another frame's work) owns this frame: compose only within a small budget.
        val deadline = System.nanoTime() + PREWARM_FRAME_BUDGET_NS
        done = shownScreen.value != screen && paused.resume { System.nanoTime() > deadline }
      }
      if (done && warming.screen == screen) {
        paused.apply()
        warming.clear()
        if (screen !in visited) visited += screen
        delay(PREWARM_STEP_MS)
      }
    }
  }

  SubcomposeLayout(slots, Modifier.fillMaxSize().clipToBounds()) { constraints ->
    // A screen visited while it was still warming is composed for real now.
    warming.screen?.let { if (it == current) warming.cancel() }
    val ids = if (current in CC_KEEP_ALIVE) visited.toList() else visited.toList() + current
    val placed = ids.map { screen -> screen to subcompose(screen, slot(screen)).map { it.measure(constraints) } }
    layout(constraints.maxWidth, constraints.maxHeight) {
      for ((screen, ps) in placed) {
        val x = if (screen == current) 0 else PARK_OFFSET_PX
        ps.forEach { it.place(x, 0) }
      }
    }
  }
}

/** How a kept-alive screen's slot draws: on screen, transparent (recording its draw lists), or not at all. */
internal enum class CcSlotDraw { SHOWN, RECORDING, DETACHED }

/**
 * A parked screen is drawn fully transparent until it has recorded its draw lists, then left out
 * of the render tree. The renderer skips transparent nodes when drawing but still walks every node
 * in the tree on every frame (sync + prepare), so the four parked screens cost the map about 0.5 ms
 * of RenderThread per frame. Their draw lists stay recorded, so showing one again only re-adds it.
 * The map is never detached: its plan keeps cached textures, and it is the screen most returned to.
 */
internal fun ccSlotDraw(screen: CcScreen, active: Boolean, settled: Boolean): CcSlotDraw = when {
  active -> CcSlotDraw.SHOWN
  screen == CcScreen.MAP || !settled -> CcSlotDraw.RECORDING
  else -> CcSlotDraw.DETACHED
}

/** Suspends until there has been no touch for [PREWARM_QUIET_MS]. */
private suspend fun awaitQuiet(ui: CcUi) {
  while (true) {
    val wait = ccQuietWait(android.os.SystemClock.uptimeMillis(), ui.lastInputAt)
    if (wait == 0L) return
    delay(wait)
  }
}

/** How long to wait (0 = go now) until [lastInputAt] is [PREWARM_QUIET_MS] in the past; polls at least every [PREWARM_POLL_MS]. */
internal fun ccQuietWait(now: Long, lastInputAt: Long): Long {
  val quietFor = now - lastInputAt
  return if (quietFor >= PREWARM_QUIET_MS) 0L else maxOf(PREWARM_POLL_MS, PREWARM_QUIET_MS - quietFor)
}

/** The one screen being prewarmed (if any) and its paused composition. */
private class CcWarming {
  var screen: CcScreen? = null
    private set
  private var paused: SubcomposeLayoutState.PausedPrecomposition? = null

  fun start(s: CcScreen, p: SubcomposeLayoutState.PausedPrecomposition) { screen = s; paused = p }

  fun cancel() { paused?.cancel(); clear() }

  fun clear() { screen = null; paused = null }
}

private const val PARK_OFFSET_PX = 100_000
/** Past the opening frames and animations. */
private const val PREWARM_AFTER_MS = 4_000L
/** No touch for this long before (and during) prewarm steps. */
private const val PREWARM_QUIET_MS = 3_000L
private const val PREWARM_POLL_MS = 250L
private const val PREWARM_STEP_MS = 700L
/** Composition time a prewarm may take per frame (the rest of the 16.7 ms is the frame's own work). */
private const val PREWARM_FRAME_BUDGET_NS = 5_000_000L
