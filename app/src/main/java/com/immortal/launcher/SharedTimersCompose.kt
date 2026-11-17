/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live household timers for a Compose surface, plus a clock that ticks only while one runs. */
internal class SharedTimersView(val timers: List<SharedTimer>, val clock: State<Long>)

private const val TICK_MS = 1_000L

/**
 * Reads [SharedTimers] off the main thread once, then follows its flow. The clock [State] ticks
 * each second while the surface is STARTED and some timer is running, so only the composables that
 * read it (the countdown texts) recompose on ticks.
 */
@Composable
internal fun rememberSharedTimers(context: Context): SharedTimersView {
  val app = context.applicationContext
  LaunchedEffect(app) { withContext(Dispatchers.IO) { runCatching { SharedTimers.snapshot(app) } } }
  val all by SharedTimers.timers.collectAsState()
  val clock = remember { mutableLongStateOf(SharedTimers.clock()) }
  val owner = LocalLifecycleOwner.current
  val running = all.any { it.state == SharedTimerState.RUNNING }
  LaunchedEffect(owner, running) {
    clock.longValue = SharedTimers.clock()
    if (!running) return@LaunchedEffect
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        clock.longValue = SharedTimers.clock()
        delay(TICK_MS)
      }
    }
  }
  val live = remember(all) { SharedTimerMerge.display(all, SharedTimers.clock()) }
  return SharedTimersView(live, clock)
}

/** "Kitchen", "Kitchen · Paused", "Kitchen · Time’s up". */
internal fun sharedTimerSubtitle(t: SharedTimer): String = when (t.state) {
  SharedTimerState.PAUSED -> "${t.room} · Paused"
  SharedTimerState.RINGING -> "${t.room} · Time’s up"
  else -> t.room
}

/** Runs a timer change off the main thread (prefs + alarm + bus); results arrive through the flow. */
internal suspend fun sharedTimerAct(context: Context, action: SharedTimerAction, id: String): SharedTimers.ActResult =
    withContext(Dispatchers.IO) { SharedTimers.act(context.applicationContext, action, id) }

internal suspend fun sharedTimerStart(context: Context, name: String, durationMs: Long): SharedTimer? =
    withContext(Dispatchers.IO) { SharedTimers.start(context.applicationContext, name, durationMs) }
