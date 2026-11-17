/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ShouldPauseCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CompletableDeferred

/** Deferred page work per frame; the frame's own layout and draw still fit in 16.7 ms. */
internal const val SETTINGS_DEFERRED_BUDGET_MS = 6L
private const val NANOS_PER_MS = 1_000_000L
/** Frames with nothing queued before a page counts as settled (nested slots enqueue on apply). */
private const val SETTLE_IDLE_FRAMES = 2
private const val SLOT_ID = "settings-deferred"

/**
 * Spreads the composition of a newly opened page over several frames. Each [SettingsDeferredSlot]
 * queues its content; once per frame, within [budgetNanos], the scheduler resumes a pausable
 * precomposition, applies it, premeasures it in a later pass, and only then lets the slot show
 * it. Main thread only. After the first wave the page is settled: slots composed later (scrolling,
 * new rows) compose directly, as in any lazy list.
 */
internal class SettingsCompositionScheduler(
    private val budgetNanos: Long = SETTINGS_DEFERRED_BUDGET_MS * NANOS_PER_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    internal class Task(val state: SubcomposeLayoutState, val content: @Composable () -> Unit, val onReady: () -> Unit) {
        var constraints: Constraints? = null
        var paused: SubcomposeLayoutState.PausedPrecomposition? = null
        var handle: SubcomposeLayoutState.PrecomposedSlotHandle? = null
    }

    private val queue = ArrayDeque<Task>()
    private val settledSignal = CompletableDeferred<Unit>()

    /** A plain field (not snapshot state): slots read it once, so settling recomposes nothing. */
    var isSettled: Boolean = false
        private set

    val pending: Int get() = queue.size

    fun enqueue(task: Task) {
        if (isSettled) task.onReady() else queue.addLast(task)
    }

    fun cancel(task: Task) {
        queue.remove(task)
        task.paused?.cancel()
        task.paused = null
        // Applied but not yet revealed: release the precomposed slot (a no-op once it is in use).
        task.handle?.dispose()
        task.handle = null
    }

    /** Suspends until the first wave of deferred work is done (previews and engines start after). */
    suspend fun awaitSettled() = settledSignal.await()

    /** Runs for the page's first wave; the caller launches it after the switch frame. */
    suspend fun run() {
        var idleFrames = 0
        try {
            while (idleFrames < SETTLE_IDLE_FRAMES) {
                withFrameNanos {
                    if (queue.isEmpty()) idleFrames++ else {
                        idleFrames = 0
                        work(nanoTime() + budgetNanos)
                    }
                }
            }
        } finally {
            settle()
        }
    }

    private fun settle() {
        isSettled = true
        // Anything still queued (a cancelled run) composes in the normal way from now on.
        while (queue.isNotEmpty()) {
            val task = queue.removeFirst()
            task.paused?.cancel()
            task.paused = null
            task.onReady()
        }
        settledSignal.complete(Unit)
    }

    /** One frame's share: compose (pausable), apply, then premeasure and reveal, in queue order. */
    private fun work(deadline: Long) {
        val shouldPause = ShouldPauseCallback { nanoTime() >= deadline }
        while (queue.isNotEmpty() && nanoTime() < deadline) {
            val task = queue.first()
            val handle = task.handle
            if (handle != null) {
                task.constraints?.let { constraints ->
                    repeat(handle.placeablesCount) { handle.premeasure(it, constraints) }
                }
                task.handle = null
                queue.removeFirst()
                task.onReady()
                continue
            }
            val paused = task.paused ?: task.state.createPausedPrecomposition(SLOT_ID, task.content)
                .also { task.paused = it }
            if (!paused.resume(shouldPause)) return
            task.paused = null
            task.handle = paused.apply()
        }
    }
}

/** The page's scheduler; null outside the split-view pages (standalone Activities compose directly). */
internal val LocalSettingsComposition = staticCompositionLocalOf<SettingsCompositionScheduler?> { null }

/**
 * Shows [content] once the page's scheduler has precomposed and measured it, with an empty
 * [placeholderHeight] until then. Composes directly when there is no scheduler or the page has
 * settled. The path is chosen once, so content state is never recreated by the switch.
 */
@Composable
internal fun SettingsDeferredSlot(placeholderHeight: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheduler = LocalSettingsComposition.current
    val deferred = remember { scheduler != null && !scheduler.isSettled }
    if (!deferred || scheduler == null) {
        content()
        return
    }
    val state = remember { SubcomposeLayoutState() }
    val latest = rememberUpdatedState(content)
    val slot: @Composable () -> Unit = remember { { latest.value() } }
    var ready by remember { mutableStateOf(false) }
    val task = remember { SettingsCompositionScheduler.Task(state, slot) { ready = true } }
    DisposableEffect(task) {
        scheduler.enqueue(task)
        onDispose { scheduler.cancel(task) }
    }
    val placeholderPx = with(LocalDensity.current) { placeholderHeight.roundToPx() }
    SubcomposeLayout(state, modifier) { constraints ->
        if (!ready) {
            task.constraints = constraints
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            layout(width, placeholderPx.coerceIn(constraints.minHeight, constraints.maxHeight)) {}
        } else {
            val placeables = subcompose(SLOT_ID, slot).map { it.measure(constraints) }
            val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
            val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
            layout(width, height) { placeables.forEach { it.place(0, 0) } }
        }
    }
}

/**
 * False until one frame after [ready] first became true: lets content that appears when async data
 * arrives show in two steps (say, the first card now and the rest next frame) instead of one.
 */
@Composable
internal fun rememberFrameAfter(ready: Boolean): Boolean {
    var after by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !after) {
            withFrameNanos { }
            after = true
        }
    }
    return after
}

/** Waits until the current page has settled (or one frame outside a split-view page). */
internal suspend fun SettingsCompositionScheduler?.awaitPageSettled() {
    if (this == null) withFrameNanos { } else awaitSettled()
}
