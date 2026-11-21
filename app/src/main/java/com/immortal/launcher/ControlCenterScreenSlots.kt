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
import androidx.compose.runtime.derivedStateOf
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
        // Derived: a switch recomposes only the two slots whose screen comes or goes, not every parked one.
        val active by remember { derivedStateOf { screen == shownScreen.value } }
        // True once a parked screen has had a frame to record its draw lists (see [ccSlotDraw]).
        var settled by remember { mutableStateOf(false) }
        // True from a visit until a few frames after leaving (see [ccSlotLive]).
        var lingering by remember { mutableStateOf(false) }
        // True a few frames into a visit (see [ccSlotSemantics]); a slot composed already shown starts exposed.
        var exposed by remember { mutableStateOf(active) }
        LaunchedEffect(active) {
          settled = false
          if (active) {
            lingering = true
            repeat(EXPOSE_FRAMES) { withFrameNanos { } }
            exposed = true
          } else {
            exposed = false
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            settled = true
            repeat(LINGER_FRAMES) { withFrameNanos { } }
            lingering = false
          }
        }
        val mode = ccSlotDraw(screen, active, settled)
        val live = ccSlotLive(active, lingering)
        val layer = Modifier.graphicsLayer { alpha = if (mode == CcSlotDraw.RECORDING) 0f else 1f }
            .drawWithContent { if (mode != CcSlotDraw.DETACHED) drawContent() }
            .then(if (ccSlotSemantics(active, exposed)) Modifier else Modifier.clearAndSetSemantics {})
        Box(Modifier.fillMaxSize().then(layer)) {
          CompositionLocalProvider(LocalCcScreenActive provides live, LocalCcMotion provides (live && motionOn.value)) {
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
 * of RenderThread per frame. Android 10's renderer deletes the display lists of a node that leaves
 * the tree, so showing a detached screen again re-records all of it.
 * [CC_KEEP_RECORDED] screens are never detached: the map's plan keeps cached textures and it is the
 * screen most returned to; Alerts is small but is opened from the map's attention rows, and
 * re-recording it (text, icons, cards) was about 20 ms of the switch frame.
 */
internal fun ccSlotDraw(screen: CcScreen, active: Boolean, settled: Boolean): CcSlotDraw = when {
  active -> CcSlotDraw.SHOWN
  screen in CC_KEEP_RECORDED || !settled -> CcSlotDraw.RECORDING
  else -> CcSlotDraw.DETACHED
}

/**
 * Whether a slot's content behaves as on screen ([LocalCcScreenActive], [LocalCcMotion]): at once
 * when it is shown, but only [SETTLE_FRAMES] + [LINGER_FRAMES] frames after it is parked. Leaving a
 * screen stops its cameras and animations (effects cancelled, streams released, the tile
 * recomposed), and doing that in the switch frame made it longer; a parked screen is already
 * transparent and out of the semantics tree, so the stop is invisible a few frames later too.
 * A screen that was never shown (prewarmed) is never live.
 */
internal fun ccSlotLive(active: Boolean, lingering: Boolean): Boolean = active || lingering

/**
 * Whether a slot's screen is in the semantics tree. A parked screen is off-screen and leaves it at
 * once; a shown screen joins it [EXPOSE_FRAMES] frames after the switch. Compose rebuilds and
 * re-sorts the whole accessibility tree after any semantics change while the Portal's
 * accessibility services are on (always), 10-12 ms here, and doing that right after the switch
 * frame stacked it on the switch's own work. A few frames (~35 ms) later it runs on its own,
 * still well inside the 100 ms that Compose batches accessibility events by.
 */
internal fun ccSlotSemantics(active: Boolean, exposed: Boolean): Boolean = active && exposed

/** Frames a shown screen waits before joining the semantics tree (see [ccSlotSemantics]). */
private const val EXPOSE_FRAMES = 2

/** Frames a parked screen keeps drawing (transparent) to record its draw lists. */
private const val SETTLE_FRAMES = 2
/** Further frames before a parked screen stops its cameras and animations (off the settle frame too). */
private const val LINGER_FRAMES = 2

/** Parked screens that stay in the render tree (transparent) so their draw lists survive; see [ccSlotDraw]. */
internal val CC_KEEP_RECORDED = setOf(CcScreen.MAP, CcScreen.ALERTS)

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
