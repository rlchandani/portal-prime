/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Rings a finished household timer in this room: wakes the screen, shows the "Timer done" card
 * ([TimerDoneOverlay]) over whatever is on screen, and plays the kitchen-timer ring unless quiet
 * hours make this room visual only ([SharedTimerRing]). One reminder ring follows after
 * [SharedTimerRing.REMINDER_MS]; the ring ends by itself after [SharedTimer.RING_TIMEOUT_MS].
 * When any device stops it, the card closes here too. State lives in [SharedTimers]; everything
 * here runs on the main thread.
 */
internal object SharedTimerPresenter {
  private const val TAG = "HearthpaneTimers"
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val reminders = HashMap<String, Runnable>()
  private val timeouts = HashMap<String, Runnable>()

  fun changed(context: Context, before: SharedTimer?, after: SharedTimer) {
    val app = context.applicationContext
    val wasRinging = before?.state == SharedTimerState.RINGING
    val ringing = after.state == SharedTimerState.RINGING
    when {
      ringing && !wasRinging -> main.post { ring(app, after, sound = true) }
      !ringing && wasRinging -> main.post { ended(app, after.id) }
    }
  }

  /** After a restart: the card comes back without sound. */
  fun reshow(context: Context, timer: SharedTimer) {
    val app = context.applicationContext
    main.post { ring(app, timer, sound = false) }
  }

  /** The overlay host just attached (app start): show the newest ringing timer, if any. */
  fun hostReady(context: Context) {
    if (TimerDoneOverlay.showing) return
    newestRinging(except = null)?.let { show(context, it, decision(context, it)) }
  }

  private fun newestRinging(except: String?): SharedTimer? = SharedTimers.timers.value
      .filter { it.state == SharedTimerState.RINGING && it.id != except }.maxByOrNull { it.changedAtMs }

  private fun ring(context: Context, timer: SharedTimer, sound: Boolean) {
    val decision = decision(context, timer)
    ScreenControl.wake(context)
    show(context, timer, decision)
    if (sound && decision.sound) {
      runCatching { TimerRingSound.play(context, timer.name) }.onFailure { Log.w(TAG, "timer ring failed", it) }
      schedule(reminders, timer.id, SharedTimerRing.REMINDER_MS) { remind(context, timer.id) }
    }
    val sinceRing = (SharedTimers.clock() - timer.changedAtMs).coerceAtLeast(0L)
    schedule(timeouts, timer.id, (SharedTimer.RING_TIMEOUT_MS - sinceRing).coerceAtLeast(0L)) {
      SharedTimers.expireRing(context, timer.id)
    }
  }

  private fun show(context: Context, timer: SharedTimer, decision: SharedTimerRing.Decision) {
    val card = TimerDoneOverlay.Card(timer.id, timer.name, SharedTimerRing.doneLine(timer), SharedTimerRing.statusLine(decision),
        decision.quiet)
    TimerDoneOverlay.show(card,
        onStop = { SharedTimers.act(context, SharedTimerAction.STOP, timer.id) },
        onAddMinute = { SharedTimers.act(context, SharedTimerAction.ADD, timer.id) })
  }

  private fun remind(context: Context, id: String) {
    val timer = SharedTimers.timers.value.firstOrNull { it.id == id && it.state == SharedTimerState.RINGING } ?: return
    if (decision(context, timer).sound) runCatching { TimerRingSound.play(context, timer.name) }
  }

  /** Stopped, +1 min or cancelled anywhere: close the card, then show any other timer still ringing. */
  private fun ended(context: Context, id: String) {
    reminders.remove(id)?.let(main::removeCallbacks)
    timeouts.remove(id)?.let(main::removeCallbacks)
    TimerDoneOverlay.hide(id)
    if (TimerDoneOverlay.showing) return
    val next = newestRinging(except = id) ?: return
    show(context, next, decision(context, next))
  }

  private fun decision(context: Context, timer: SharedTimer): SharedTimerRing.Decision {
    val quiet = runCatching { SharedTimerRing.isQuiet(ChimeConfig.load(context), SharedTimers.clock(), SharedTimers.zone()) }
        .getOrDefault(false)
    return SharedTimerRing.decide(quiet, startedHere = timer.origin == SharedTimers.selfId(context))
  }

  private fun schedule(slots: HashMap<String, Runnable>, id: String, delayMs: Long, block: () -> Unit) {
    slots.remove(id)?.let(main::removeCallbacks)
    val task = Runnable { slots.remove(id); block() }
    slots[id] = task
    main.postDelayed(task, delayMs)
  }
}
