/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Finite clock cover; photos remain frozen until the returning cover is completely gone. */
internal class NightClockTransition(
    private val nowMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onReveal: (Float) -> Unit,
    private val onSettled: (Boolean) -> Unit,
) {
  private data class Ramp(val from: Float, val target: Float, val started: Long)
  private var ramp: Ramp? = null
  private var active = false
  private var closed = false
  private var night = false
  var reveal = 0f
    private set
  val coversPhotos: Boolean get() = night || reveal > 0f

  private val tick = object : Runnable {
    override fun run() {
      val current = ramp ?: return
      if (!active || closed) return
      val elapsed = (nowMs() - current.started).coerceAtLeast(0)
      val progress = (elapsed.toFloat() / NightBrightnessFade.DURATION_MS).coerceIn(0f, 1f)
      val eased = progress * progress * (3f - 2f * progress)
      reveal = current.from + (current.target - current.from) * eased
      onReveal(reveal)
      if (progress == 1f) {
        ramp = null
        onSettled(night)
      } else postDelayed(this, minOf(16L, NightBrightnessFade.DURATION_MS - elapsed))
    }
  }

  fun setNight(value: Boolean) {
    if (closed || night == value) return
    night = value
    retarget(notifyAtRest = true)
  }

  fun setActive(value: Boolean) {
    if (closed || active == value) return
    active = value
    if (value) retarget() else cancel()
  }

  private fun retarget(notifyAtRest: Boolean = false) {
    if (!active) return
    cancel()
    val target = if (night) 1f else 0f
    if (target == reveal) {
      if (notifyAtRest) onSettled(night)
      return
    }
    ramp = Ramp(reveal, target, nowMs())
    postDelayed(tick, 16L)
  }

  private fun cancel() {
    removeCallbacks(tick)
    ramp = null
  }

  /** Host departure freezes the surface; tap-to-Home never waits for this animation. */
  fun close() {
    closed = true
    active = false
    cancel()
  }
}
