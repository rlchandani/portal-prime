/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

internal val LocalApprovedLauncherPageActive = compositionLocalOf { true }

/**
 * A retained page's input permission is checked by input/focus handlers, not every
 * button's composition. The generation also cancels a held touch across hide/show.
 * All mutation is owned by the page's main-thread lifecycle/pointer handlers.
 */
internal class LauncherPageInputGate {
  var active by mutableStateOf(false)
    private set
  private var generation = 0L
  private var touchGeneration: Long? = null

  fun update(active: Boolean) {
    if (this.active != active) {
      generation++
      this.active = active
    }
  }

  fun beginTouch() {
    if (touchGeneration == null) touchGeneration = generation
  }

  fun acceptsAction(): Boolean = active && (touchGeneration == null || touchGeneration == generation)
  fun endTouch() { touchGeneration = null }
}

internal val LocalApprovedLauncherInputGate = compositionLocalOf<LauncherPageInputGate?> { null }
internal val LocalApprovedLauncherHostOwner = compositionLocalOf<LifecycleOwner?> { null }
internal val LocalApprovedWidgetPageInput = compositionLocalOf { true }

private class HomeRefreshDeadline : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<HomeRefreshDeadline>
  var elapsedAt: Long = 0
}

/** Refresh cadence survives visibility changes; realtime clocks continue using ordinary delay. */
internal suspend fun homeRefreshDelay(milliseconds: Long) {
  currentCoroutineContext()[HomeRefreshDeadline]?.elapsedAt =
      android.os.SystemClock.elapsedRealtime() + milliseconds
  delay(milliseconds)
}

/** Retain the last value while stopping hidden Home producers and their refresh timers. */
@Composable
internal fun <T> produceHomeState(
    initialValue: T,
    vararg keys: Any?,
    producer: suspend ProduceStateScope<T>.() -> Unit,
): State<T> {
  val active = LocalApprovedLauncherPageActive.current
  val flight = remember { Mutex() }
  val deadline = remember(keys.toList()) { HomeRefreshDeadline() }
  return produceState(initialValue, keys = arrayOf(*keys, active)) {
    // A blocking request may need its timeout to finish after cancellation. Re-entry
    // waits for that producer to release ownership instead of duplicating its requests.
    if (active) flight.withLock {
      withContext(deadline) {
        val remaining = deadline.elapsedAt - android.os.SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)
        producer()
        // One-shot forecast/sun loads also retain a normal thirty-minute cache window.
        if (deadline.elapsedAt == 0L && value != initialValue)
          deadline.elapsedAt = android.os.SystemClock.elapsedRealtime() + 30L * 60 * 1000
      }
    }
  }
}

internal enum class ApprovedLauncherTab(val id: String) {
  HOME("home"), APPS("apps"), TOOLS("tools");

  companion object {
    fun fromId(id: String?): ApprovedLauncherTab? = entries.firstOrNull { it.id == id }
  }
}

/** Retain visited pages, pause hidden work, and slide display lists without per-frame composition. */
@Composable
internal fun ApprovedLauncherTabs(
    selected: ApprovedLauncherTab,
    modifier: Modifier = Modifier,
    contentActive: Boolean = true,
    content: @Composable (ApprovedLauncherTab) -> Unit,
) {
  val hostOwner = LocalLifecycleOwner.current
  val transition = remember { ApprovedHomeTabTransition(selected) }
  val clock = remember { Animatable(0f) }
  var animation by remember { mutableStateOf<ApprovedHomeTabAnimation?>(null) }
  var visited by remember { mutableStateOf(setOf(selected)) }
  var visible by remember { mutableStateOf(setOf(selected)) }
  val focus = LocalFocusManager.current
  val view = LocalView.current
  LaunchedEffect(selected) {
    if (selected == transition.selected) return@LaunchedEffect
    focus.clearFocus(force = true)
    view.findFocus()?.clearFocus()
    // Cancellation stopped the old coroutine. Preserve both displayed positions before
    // the replacement plan chooses its entrance side or the clock's next endpoint.
    animation?.let { transition.advance(it.plan, it.progress(clock.value)) }
    val next = ApprovedHomeTabAnimation(
        transition.select(selected) ?: return@LaunchedEffect, clock.value)
    visited = visited + selected
    visible = transition.visible
    animation = next
    // The new page gets one initial layout; subsequent frames read offsets during placement.
    withFrameNanos { }
    clock.animateTo(next.target, tween(160, easing = FastOutSlowInEasing))
    if (transition.finish(next.plan)) {
      animation = null
      visible = transition.visible
    }
  }
  BoxWithConstraints(modifier.clipToBounds()) {
    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
    ApprovedLauncherTab.entries.filter { it in visited }.forEach { tab ->
      key(tab) {
      val active = contentActive && tab == selected
      val drawing = contentActive && tab in visible
      val input = remember(hostOwner) { LauncherPageInputGate() }
      val moving = animation?.takeIf { motion -> motion.plan.motions.any { it.tab == tab } }
      Box(Modifier.fillMaxSize()
          // Placement keeps descendant geometry on cached integer offsets rather than
          // transforming every descendant rectangle through an animated layer matrix.
          .absoluteOffset {
            // Hidden retained pages do not subscribe to animation frames.
            val offset = moving?.offset(tab, clock.value) ?: transition.offset(tab)
            IntOffset((offset * widthPx).roundToInt(), 0)
          }
          .graphicsLayer { alpha = if (drawing) 1f else 0f }
          .focusProperties { canFocus = active }
          .pointerInput(input) {
            try {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Initial)
                  if (event.changes.any { it.pressed }) input.beginTouch()
                  if (!input.acceptsAction()) event.changes.forEach { it.consume() }
                  // Consume a stale release before clearing the gesture. Clickable's
                  // child handler then sees cancellation, even after a quick return.
                  if (event.changes.none { it.pressed }) input.endTouch()
                }
              }
            } finally { input.endTouch() }
          }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
        CompositionLocalProvider(
            LocalApprovedLauncherPageActive provides active,
            LocalApprovedLauncherHostOwner provides hostOwner,
        ) {
          ApprovedLauncherPageLifecycle(active, input) { content(tab) }
        }
      }
      }
    }
  }
}

private class LauncherPageOwner : LifecycleOwner {
  val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
  override val lifecycle: Lifecycle get() = registry
}

/** CREATED disables inactive Back callbacks and existing RESUMED/STARTED refresh hooks. */
@Composable
internal fun ApprovedLauncherPageLifecycle(
    active: Boolean,
    input: LauncherPageInputGate? = null,
    content: @Composable () -> Unit,
) {
  val host = LocalLifecycleOwner.current
  val page = remember(host) { LauncherPageOwner() }
  var foreground by remember(host) { mutableStateOf(host.lifecycle.currentState) }
  DisposableEffect(host, page, input) {
    val observer = LifecycleEventObserver { _, _ -> foreground = host.lifecycle.currentState }
    host.lifecycle.addObserver(observer)
    onDispose {
      host.lifecycle.removeObserver(observer)
      page.registry.currentState = Lifecycle.State.DESTROYED
      input?.update(false)
    }
  }
  SideEffect {
    input?.update(active && foreground.isAtLeast(Lifecycle.State.STARTED))
    val state = if (active || foreground <= Lifecycle.State.CREATED) foreground else Lifecycle.State.CREATED
    if (page.registry.currentState != state) page.registry.currentState = state
  }
  CompositionLocalProvider(
      LocalLifecycleOwner provides page,
      LocalApprovedLauncherInputGate provides input,
      LocalApprovedLauncherPageActive provides (active && foreground.isAtLeast(Lifecycle.State.STARTED)),
      content = content,
  )
}
