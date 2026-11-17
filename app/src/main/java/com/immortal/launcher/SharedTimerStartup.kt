/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * What the app does with saved timers when its process starts (after a restart, an update or a
 * reboot). [plan] is pure; [reconcile] applies it.
 * - Running timers keep counting from their saved deadline. One with no alarm left in the
 *   [TimerConfig] ledger is re-armed, or — when its time already passed (the boot cleanup dropped
 *   it) — quietly marked done instead of ringing late.
 * - A ringing timer shows its card again (silently) until its ring times out.
 * - Kitchen timers started before shared timers existed join the household under their own id.
 */
internal object SharedTimerStartup {
  data class Plan(
      val timers: List<SharedTimer>,
      val arm: List<SharedTimer>,
      val reshow: List<SharedTimer>,
      val adopted: List<SharedTimer>,
  )

  fun plan(saved: List<SharedTimer>, ledger: List<TimerConfig.Timer>, nowMs: Long, self: String, room: String): Plan {
    val armed = ledger.map { it.id }.toSet()
    val kept = SharedTimerMerge.prune(saved, nowMs).map { t ->
      when {
        t.state == SharedTimerState.RUNNING && t.id !in armed && t.endAtMs <= nowMs ->
          SharedTimerOps.ring(t, nowMs, self)?.let { SharedTimerOps.stop(it, nowMs, self) } ?: t
        t.state == SharedTimerState.RINGING && nowMs - t.changedAtMs >= SharedTimer.RING_TIMEOUT_MS ->
          SharedTimerOps.stop(t, nowMs, self) ?: t
        else -> t
      }
    }
    val known = kept.map { it.id }.toSet()
    val adopted = ledger.filter { it.id !in known && it.endAtMillis > nowMs && SharedTimerWire.isId(it.id) }
        .mapNotNull { legacy ->
          SharedTimerOps.create(legacy.id, legacy.label, room, self, legacy.durationMs.coerceAtLeast(legacy.endAtMillis - nowMs),
              nowMs, self)?.copy(endAtMs = legacy.endAtMillis)
        }
    val all = kept + adopted
    return Plan(
        timers = all,
        arm = kept.filter { it.state == SharedTimerState.RUNNING && it.id !in armed && it.endAtMs > nowMs },
        reshow = all.filter { it.state == SharedTimerState.RINGING },
        adopted = adopted,
    )
  }

  fun reconcile(context: Context) {
    val self = SharedTimers.selfId(context) ?: return
    val now = SharedTimers.clock()
    val plan = plan(SharedTimers.snapshot(context), TimerConfig.load(context), now, self, SharedTimers.selfName(context))
    SharedTimers.replaceAll(context, plan.timers)
    plan.arm.forEach { TimerConfig.arm(context, TimerConfig.Timer(it.id, it.name, it.durationMs, it.endAtMs)) }
    plan.reshow.forEach { SharedTimerPresenter.reshow(context, it) }
    plan.adopted.forEach { HouseholdBus.send(context, SharedTimerWire.SET, SharedTimerWire.encode(it, now)) }
  }
}
