/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Window focus also excludes a Dream, preview, dialog or another app above a resumed Home. */
internal fun approvedRetainedHomeEligible(
    resumed: Boolean,
    focused: Boolean,
    destroyed: Boolean,
    finishing: Boolean,
    stockHandoff: Boolean,
): Boolean = resumed && focused && !destroyed && !finishing && !stockHandoff

/** Actual Activity lifecycle owns this weak registration; delayed old callbacks are harmless. */
internal class ApprovedHomeHostRegistry<H : Any> {
  @Volatile private var live = WeakReference<H>(null)

  fun resumed(host: H) { live = WeakReference(host) }

  fun unavailable(host: H) {
    val registration = live
    if (registration.get() === host) registration.clear()
  }

  fun capture(): WeakReference<H> = live

  fun foreground(expected: WeakReference<H>, eligible: (H) -> Boolean): H? {
    val host = expected.get() ?: return null
    if (live !== expected || !eligible(host) || live !== expected || expected.get() !== host) return null
    return host
  }
}

/**
 * Prepare on main without side effects, then admit one navigation before a monotonic deadline.
 * Cancellation wins while queued/preparing. Once admitted, report accepted even if Android's
 * launch call is still returning: reporting a timeout failure then would invite a duplicate.
 */
internal class ApprovedHomeNavigationRequest(
    private val deadlineNanos: Long,
    private val prepare: () -> (() -> Boolean),
) : Runnable {
  private enum class State { QUEUED, PREPARING, RUNNING, COMPLETED, CANCELLED }
  private val lock = Any()
  private val completed = CountDownLatch(1)
  private var state = State.QUEUED
  private var result = false

  override fun run() {
    synchronized(lock) {
      if (state != State.QUEUED) return
      if (System.nanoTime() >= deadlineNanos) {
        state = State.CANCELLED
        completed.countDown()
        return
      }
      state = State.PREPARING
    }
    val action = try { prepare() } catch (_: Exception) {
      finish(false)
      return
    }
    synchronized(lock) {
      if (state != State.PREPARING) return
      if (System.nanoTime() >= deadlineNanos) {
        state = State.CANCELLED
        completed.countDown()
        return
      }
      state = State.RUNNING
    }
    finish(runCatching { action() }.getOrDefault(false))
  }

  fun await(): Boolean {
    try {
      completed.await((deadlineNanos - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    return cancel()
  }

  /** False proves no action can start later; true acknowledges completed or admitted work. */
  fun cancel(): Boolean = synchronized(lock) {
    when (state) {
      State.COMPLETED -> result
      State.RUNNING -> true
      State.CANCELLED -> false
      State.QUEUED, State.PREPARING -> {
        state = State.CANCELLED
        completed.countDown()
        false
      }
    }
  }

  private fun finish(success: Boolean) = synchronized(lock) {
    if (state != State.CANCELLED) {
      result = success
      state = State.COMPLETED
    }
    completed.countDown()
  }
}

/** Foreground tab requests use the same retained state as dock taps, entirely on main. */
internal object ApprovedHomeNavigation {
  private val hosts = ApprovedHomeHostRegistry<HomeActivity>()
  private const val DISPATCH_TIMEOUT_MS = 2_000L

  fun resumed(host: HomeActivity) = hosts.resumed(host)
  fun unavailable(host: HomeActivity) = hosts.unavailable(host)

  fun dispatch(
      caller: Activity?,
      tab: ApprovedLauncherTab,
      searchApps: Boolean,
      fallback: () -> Boolean,
  ): Boolean {
    val expected = hosts.capture()
    // A cold/paused Home has no retained target. Preserve the existing Intent launch boundary;
    // Android accepts startActivity from service/HTTP threads as well as Activity callers.
    if (expected.get() == null) return fallback()

    val prepare: () -> (() -> Boolean) = {
      val host = hosts.foreground(expected) {
        (caller == null || caller === it) && approvedRetainedHomeEligible(
            resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED,
            focused = it.hasWindowFocus(),
            destroyed = it.isDestroyed,
            finishing = it.isFinishing,
            stockHandoff = DreamPolicy.inStockHandoff,
        )
      }
      if (host == null) fallback else {
        val select: () -> Boolean = { host.selectApprovedHomeTab(tab, searchApps); true }
        select
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) return prepare()()

    val request = ApprovedHomeNavigationRequest(
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISPATCH_TIMEOUT_MS), prepare)
    val main = Handler(Looper.getMainLooper())
    return try {
      if (!main.post(request)) request.cancel() else request.await()
    } finally {
      request.cancel()
      main.removeCallbacks(request)
    }
  }
}
