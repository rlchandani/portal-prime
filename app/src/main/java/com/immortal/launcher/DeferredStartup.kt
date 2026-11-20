/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.ViewTreeObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One named piece of process setup, run exactly once. */
internal class StartupStep(val name: String, val action: () -> Unit)

/**
 * Ordered, run-once step list. Each step runs at most once, in order, whether it is reached one
 * message at a time ([runNext]) or all at once ([runAll]). A call made from inside a running step
 * returns without running anything; the outer loop continues in order. Not thread-safe: main only.
 */
internal class StartupSequence(private val steps: List<StartupStep>, private val run: (StartupStep) -> Unit) {
  private var next = 0
  private var running = false

  val done: Boolean get() = next >= steps.size

  /** Runs the next step; true while more steps remain. */
  fun runNext(): Boolean {
    if (running || done) return !done
    runOne()
    return !done
  }

  /** Runs every remaining step now. */
  fun runAll() {
    if (running) return
    while (!done) runOne()
  }

  private fun runOne() {
    val step = steps[next++] // advance first: a crashing step is never retried by a later call
    running = true
    try {
      run(step)
    } finally {
      running = false
    }
  }
}

/**
 * Process setup that Home's first frame doesn't need, moved off the cold-start critical path.
 *
 * [ImmortalApp.onCreate] keeps everything a first frame, a dream or a receiver reads synchronously
 * and hands the rest to [arm]. The steps then start right after the first frame any of our
 * activities draws, one main-thread message per step so frames can interleave. When no activity
 * appears (a process started for a service or receiver), [FALLBACK_DELAY_MS] starts them anyway.
 * Entry points that need a step before then call [ensureStarted], which runs what remains now.
 *
 * Exceptions are not caught: a failing step crashes the process exactly as it did in onCreate,
 * and [CrashGuard] recovers the home role.
 */
internal object DeferredStartup {
  private const val TAG = "DeferredStartup"
  /** Home's first frame lands well inside this on a cold boot; it only bounds the no-activity case. */
  const val FALLBACK_DELAY_MS = 3_000L
  /** How long a non-main caller waits for the main thread to finish the remaining steps. */
  private const val OFF_MAIN_WAIT_MS = 5_000L

  private val main = Handler(Looper.getMainLooper())
  @Volatile private var sequence: StartupSequence? = null
  private var app: Application? = null
  private var began = false

  private val fallback = Runnable { begin() }
  private val pump = object : Runnable {
    override fun run() {
      if (sequence?.runNext() == true) main.post(this)
    }
  }

  private val lifecycle = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
      val observer = activity.window?.decorView?.viewTreeObserver ?: return
      // Posted from the draw: it runs once this traversal (and its sync barrier) is done.
      val listener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
          main.post {
            if (observer.isAlive) observer.removeOnDrawListener(this)
            begin()
          }
        }
      }
      observer.addOnDrawListener(listener)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
  }

  /** Main thread, once, from [ImmortalApp.onCreate]. */
  fun arm(application: Application, steps: List<StartupStep>) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "DeferredStartup.arm off the main thread" }
    if (sequence != null) return
    app = application
    sequence = StartupSequence(steps) { step ->
      Trace.beginSection("startup:" + step.name)
      try { step.action() } finally { Trace.endSection() }
    }
    application.registerActivityLifecycleCallbacks(lifecycle)
    main.postDelayed(fallback, FALLBACK_DELAY_MS)
  }

  /** Whether every deferred step has run. */
  val started: Boolean get() = sequence?.done ?: false

  /**
   * Runs every remaining step before returning. On the main thread it runs them inline; elsewhere
   * it hands them to the main thread and waits up to [OFF_MAIN_WAIT_MS].
   */
  fun ensureStarted() {
    if (sequence == null || started) return
    if (Looper.myLooper() == Looper.getMainLooper()) {
      stopTriggers()
      sequence?.runAll()
      return
    }
    val done = CountDownLatch(1)
    main.postAtFrontOfQueue {
      try { ensureStarted() } finally { done.countDown() }
    }
    if (!done.await(OFF_MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "main thread busy; continuing before startup finished")
  }

  private fun begin() {
    if (began) return
    stopTriggers()
    main.post(pump)
  }

  private fun stopTriggers() {
    began = true
    main.removeCallbacks(fallback)
    app?.unregisterActivityLifecycleCallbacks(lifecycle)
  }
}
