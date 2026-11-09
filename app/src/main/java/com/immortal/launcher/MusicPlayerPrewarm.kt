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
import android.os.MessageQueue
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread

/**
 * Schedules [YouTubeMusicEngine.prewarm] for when Home is idle: after its first draw, a settle
 * delay, then a main-looper idle handler (so it never competes with Home's first frames or touch).
 * Once per process; only when the YouTube route is configured. Home cancels it on pause.
 */
internal object MusicPlayerPrewarm {
  /** Leave Home's start-up animations and first loads alone for this long after the first draw. */
  private const val SETTLE_MS = 3_000L
  @Volatile private var done = false

  /** Main-only. Returns a cancel function. */
  @MainThread
  fun scheduleForHome(context: Context, decorView: View): () -> Unit {
    if (done || BuildConfig.YT_OAUTH_CLIENT_ID.isBlank()) return {}
    val app = context.applicationContext
    val handler = Handler(Looper.getMainLooper())
    var cancelled = false
    var drawn = false
    val idle = MessageQueue.IdleHandler {
      if (!cancelled && !done) {
        done = true
        YouTubeMusicEngine.prewarm(app)
      }
      false
    }
    val settle = Runnable { if (!cancelled) Looper.myQueue().addIdleHandler(idle) }
    var observer: ViewTreeObserver? = null
    lateinit var onDraw: ViewTreeObserver.OnDrawListener
    val afterDraw = Runnable {
      observer?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw)
      observer = null
      if (!cancelled) handler.postDelayed(settle, SETTLE_MS)
    }
    onDraw = ViewTreeObserver.OnDrawListener {
      if (!drawn) {
        drawn = true
        handler.post(afterDraw)
      }
    }
    runCatching { decorView.viewTreeObserver.also { observer = it }.addOnDrawListener(onDraw) }
    return {
      cancelled = true
      handler.removeCallbacks(afterDraw)
      handler.removeCallbacks(settle)
      Looper.myQueue().removeIdleHandler(idle)
      runCatching { observer?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw) }
      observer = null
    }
  }
}
