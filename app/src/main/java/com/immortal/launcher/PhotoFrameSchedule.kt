/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The photo dwell: how long each photo holds, and the deadline of the one on screen across a
 * viewer pause and a suspended host. Deadlines run on [PhotoFrameClock.uptimeMs], frozen while the
 * host is suspended, the same clock as the frame's pausable callback queue.
 */
internal class PhotoFrameSchedule(private val clock: PhotoFrameClock) {
  /** Uptime at which the current photo's hold ends (0 before the first hold). */
  var holdDeadline = 0L
    private set
  /** What was left of the hold when it was last armed or paused. */
  var remainingHold = 0L
    private set
  /** Uptime at which the host was last suspended. */
  var suspendedAt = 0L
    private set

  /** Now on the dwell clock: the moment of suspension while the host is away. */
  fun playbackUptime(hostActive: Boolean): Long = if (hostActive) clock.uptimeMs() else suspendedAt

  /** A fresh hold of [delay] starts now. */
  fun arm(delay: Long, hostActive: Boolean) {
    holdDeadline = playbackUptime(hostActive) + delay
    remainingHold = delay
  }

  /** The viewer paused: keep what's left (at least [MIN_HOLD_MS]). */
  fun pause(hostActive: Boolean) {
    remainingHold = (holdDeadline - playbackUptime(hostActive)).coerceAtLeast(MIN_HOLD_MS)
  }

  /** The hold to re-arm on resume. */
  fun resumeDelay(): Long = remainingHold.coerceAtLeast(MIN_HOLD_MS)

  /** The host went away at [now]. */
  fun suspend(now: Long) { suspendedAt = now }

  /** The host is back at [now]: the queue froze the delay, so the deadline moves with it. */
  fun resume(now: Long) { if (holdDeadline > 0L) holdDeadline += now - suspendedAt }

  /** What is left of the hold after the remote list changed under it. */
  fun leftAfterManifest(): Long = (holdDeadline - clock.uptimeMs()).coerceAtLeast(MIN_HOLD_MS)

  companion object {
    const val MIN_HOLD_MS = 100L
    /** A video advances by this at the latest, even if it never reports done. */
    const val VIDEO_SAFETY_MS = 5 * 60_000L
    /** The dashboard skips its turn over a night photo and looks again after this. */
    const val DASHBOARD_RETRY_MS = 90_000L
    /** Ken Burns outlasts the dwell by this, so the motion never stalls on screen. */
    const val KEN_BURNS_TAIL_MS = 1_200L

    /** Each photo's dwell: the household interval, slower under the night photo veil. */
    fun intervalMs(intervalSec: Int, nightPhoto: Boolean): Long =
        (intervalSec * 1000L).let { if (nightPhoto) NightPhotoOverlay.intervalMs(it) else it }

    /** The hold armed for a video: its dwell, or the safety net when that is longer. */
    fun videoHoldMs(intervalMs: Long): Long = maxOf(intervalMs, VIDEO_SAFETY_MS)

    /** Shared albums and the engine's album re-fetch on the household's refresh interval. */
    fun albumRefreshMs(minutes: Int): Long = minutes * 60_000L
  }
}
