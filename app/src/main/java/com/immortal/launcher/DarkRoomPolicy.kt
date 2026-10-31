/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * A light sensor reports changes, not periodic heartbeats. A single dark reading arms one
 * deadline; its owner must call [settle] then, even if the sensor sends nothing else.
 * Separate thresholds and dwell times prevent a passing shadow or screen glow from chattering.
 * All times are monotonic, and calls share the host's main thread.
 */
internal class DarkRoomPolicy(
    private val darkLux: Float = 3f,
    private val brightLux: Float = maxOf(10f, darkLux * 3f),
    private val darkDwellMs: Long = 20_000L,
    private val brightDwellMs: Long = 5_000L,
    initialNight: Boolean = false,
) {
  var night = initialNight
    private set
  var deadlineMs: Long? = null
    private set
  private var candidate: Boolean? = null

  fun read(lux: Float, nowMs: Long): Boolean {
    if (!lux.isFinite() || lux < 0f) {
      cancelPending()
      return false
    }
    val target = when {
      !night && lux <= darkLux -> true
      night && lux >= brightLux -> false
      else -> null
    }
    if (target == null) cancelPending()
    else if (candidate != target) {
      candidate = target
      deadlineMs = nowMs + if (target) darkDwellMs else brightDwellMs
    }
    return settle(nowMs)
  }

  fun settle(nowMs: Long): Boolean {
    val deadline = deadlineMs ?: return false
    if (nowMs < deadline) return false
    night = candidate ?: return false
    cancelPending()
    return true
  }

  fun cancelPending() {
    deadlineMs = null
    candidate = null
  }

  companion object {
    fun photosActive(hostVisible: Boolean, night: Boolean) = hostVisible && !night
    fun nextMinuteDelay(wallClockMs: Long) = 60_000L - Math.floorMod(wallClockMs, 60_000L)
  }
}
