/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import android.os.SystemClock
import com.immortal.launcher.RemoteCompanionJarvis
import java.util.concurrent.atomic.AtomicLong

/**
 * A lightweight keep-warm for Floating Jarvis: when a Hearthpane screen comes up, at most every [MIN_INTERVAL_MS],
 * ask Jarvis to make sure its resident service is running. A tap after Android reclaimed Jarvis's process otherwise
 * pays a cold start (~750 ms to listening on Family instead of ~450 ms). It only starts the service's standby
 * (prewarm); the Vosk wake model stays unloaded (Jarvis loads it only on screen or with background wake on).
 */
internal object JarvisKeepWarm {
  const val MIN_INTERVAL_MS = 120_000L
  private val lastMs = AtomicLong(0L)

  /** Whether a warm is due at [nowMs] after one at [lastMs] (0 = never). Pure. */
  fun due(nowMs: Long, lastMs: Long): Boolean = lastMs == 0L || nowMs - lastMs >= MIN_INTERVAL_MS

  /** A Hearthpane screen resumed. Main thread; the call itself runs on the companion IPC thread. */
  fun onScreenResumed(context: Context) {
    val now = SystemClock.elapsedRealtime()
    val last = lastMs.get()
    if (!due(now, last) || !lastMs.compareAndSet(last, now)) return
    RemoteCompanionJarvis.warm(context)
  }
}
