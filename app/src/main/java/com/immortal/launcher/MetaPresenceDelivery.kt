/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Timer
import java.util.TimerTask

/** Samples expiry even while logcat is quiet; owns only this reader's timer and verdicts. */
internal class MetaPresenceDelivery(
    private val tracker: MetaPresence.Tracker,
    private val now: () -> Long = System::currentTimeMillis,
    private val publish: (Boolean) -> Unit,
) {
  private var timer: Timer? = null
  private var generation = 0L
  private var active = false
  private var lastReported: Boolean? = null

  @Synchronized fun start() {
    if (active) return
    active = true
    val owner = ++generation
    timer = Timer("MetaPresenceExpiry", true).apply {
      schedule(object : TimerTask() {
        override fun run() {
          runCatching { sample(owner) }
        }
      }, 1_000L, 1_000L)
    }
  }

  @Synchronized fun stop() {
    active = false
    generation++
    timer?.cancel()
    timer = null
  }

  @Synchronized fun onSignal(signal: MetaPresence.Signal) {
    if (!active) return
    tracker.onSignal(signal, now())
    report()
  }

  @Synchronized internal fun sample(owner: Long = generation) {
    if (!active || owner != generation) return
    report()
  }

  private fun report() {
    val state = tracker.state(now())
    if (state == lastReported) return
    lastReported = state
    if (state != null) publish(state)
  }
}
