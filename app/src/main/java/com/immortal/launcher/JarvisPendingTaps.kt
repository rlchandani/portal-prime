/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * UI-thread only. One pending Jarvis preparation per host Activity: a second tap while the first is still being
 * prepared joins it (rapid taps open one card). Preparation is a single SharedPreferences commit, so it settles in
 * milliseconds; a pending entry older than [staleAfterMs] can only be a preparation whose completion never came
 * back, and it must not swallow every later tap on that screen. Such an entry is cancelled and replaced.
 */
internal class JarvisPendingTaps<K : Any>(private val staleAfterMs: Long = STALE_AFTER_MS) {
  enum class Admission { NEW, JOINED, REPLACED_STALE }

  /** Identifies one held preparation, so a retired one can never release its replacement. */
  class Ticket internal constructor(internal val sinceMs: Long, internal val cancel: () -> Unit)

  private val pending = mutableMapOf<K, Ticket>()

  fun admit(host: K, nowMs: Long): Admission {
    val held = pending[host] ?: return Admission.NEW
    if (nowMs - held.sinceMs in 0 until staleAfterMs) return Admission.JOINED
    pending.remove(host)
    runCatching(held.cancel)
    return Admission.REPLACED_STALE
  }

  fun hold(host: K, nowMs: Long, cancel: () -> Unit): Ticket = Ticket(nowMs, cancel).also { pending[host] = it }

  fun release(host: K, ticket: Ticket?) {
    if (ticket != null && pending[host] === ticket) pending.remove(host)
  }

  /** Tab changes retain their Activity, so they cancel the pending preparation explicitly. */
  fun cancel(host: K) { pending[host]?.cancel?.invoke() }

  fun isHeld(host: K): Boolean = host in pending

  /** A preparation still settling on [host] (not a stuck one): the screen must not supersede its bridge. */
  fun isPreparing(host: K, nowMs: Long): Boolean =
      pending[host]?.let { nowMs - it.sinceMs in 0 until staleAfterMs } == true

  companion object {
    const val STALE_AFTER_MS = 2_000L
  }
}

/**
 * Whether the screen a Jarvis tap came from still shows when its preparation completes. A tap on a resumed screen
 * needs it still resumed (it pausing means the user left). A tap can also land on a screen that is visible but
 * paused — Hearthpane behind a Jarvis card that is playing its exit lets touches through — and that one needs the
 * screen still visible; it stopping means the user left.
 */
internal object JarvisTapHost {
  fun stillShowing(resumedAtTap: Boolean, now: androidx.lifecycle.Lifecycle.State?): Boolean =
      now?.isAtLeast(
          if (resumedAtTap) androidx.lifecycle.Lifecycle.State.RESUMED else androidx.lifecycle.Lifecycle.State.STARTED,
      ) == true
}
