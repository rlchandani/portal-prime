/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Window
import kotlin.math.pow

/** Finite, main-thread brightness ramp. Hidden or departing windows never keep ticking. */
internal class NightBrightnessFade(
    private val priorBrightness: Float,
    private val readBrightness: () -> Float,
    private val readSystemBrightness: () -> Float,
    private val writeBrightness: (Float) -> Unit,
    private val nowMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val settleBrightness: ((Float) -> Unit)? = null,
) {
  private data class Ramp(
      val from: Float, val target: Float, val finalOverride: Float, val startedMs: Long)
  private var ramp: Ramp? = null
  private var active = false
  private var closed = false
  private var night = false
  private var nightLevel = .03f

  private val tick = object : Runnable {
    override fun run() {
      val current = ramp ?: return
      if (!active || closed) return
      val elapsed = (nowMs() - current.startedMs).coerceAtLeast(0L)
      if (elapsed >= DURATION_MS) {
        if (current.finalOverride < 0f) {
          // A user may change system brightness while our window is dimmed.
          // Recheck once at handoff, rather than polling the settings provider every tick.
          val freshTarget = systemLevel()
          if (freshTarget != current.target) {
            ramp = Ramp(readBrightness().coerceIn(0f, 1f), freshTarget,
                current.finalOverride, nowMs())
            postDelayed(this, STEP_MS)
            return
          }
        }
        ramp = null
        (settleBrightness ?: writeBrightness)(current.finalOverride)
        return
      }
      val progress = elapsed.toFloat() / DURATION_MS
      // Smoothstep has zero slope at both ends: no hard start or stop.
      val eased = progress * progress * (3f - 2f * progress)
      // Android's BrightnessUtils distinguishes linear brightness from perceptual
      // (gamma) brightness. Use our own simple power-law approximation here, not
      // its hidden API or conversion implementation. Equal perceived increments
      // produce smaller absolute commands near dark; hardware response may vary.
      val fromPerceptual = current.from.toDouble().pow(1.0 / GAMMA)
      val targetPerceptual = current.target.toDouble().pow(1.0 / GAMMA)
      val level = (fromPerceptual + (targetPerceptual - fromPerceptual) * eased)
          .pow(GAMMA).toFloat().coerceIn(0f, 1f)
      // Keep continuous logical progress here. The delivery boundary can discard
      // commands that Android 10 would map to the same physical brightness level.
      if (level != readBrightness()) writeBrightness(level)
      postDelayed(this, minOf(STEP_MS, DURATION_MS - elapsed))
    }
  }

  fun setNight(value: Boolean, level: Float) {
    if (closed) return
    night = value
    nightLevel = level.coerceIn(0f, 1f)
    retarget()
  }

  fun setActive(value: Boolean) {
    if (closed || active == value) return
    active = value
    if (value) retarget() else cancel()
  }

  private fun retarget() {
    if (!active) return
    val finalOverride = if (night) nightLevel else priorBrightness
    val target = if (finalOverride >= 0f) finalOverride else systemLevel()
    val pending = ramp
    if (pending?.target == target && pending.finalOverride == finalOverride) return
    cancel()
    val actual = readBrightness()
    if (actual == finalOverride) {
      settleBrightness?.invoke(finalOverride)
      return
    }
    val from = if (actual >= 0f && actual.isFinite()) actual.coerceIn(0f, 1f) else systemLevel()
    if (from == target) {
      (settleBrightness ?: writeBrightness)(finalOverride)
      return
    }
    ramp = Ramp(from, target, finalOverride, nowMs())
    postDelayed(tick, STEP_MS)
  }

  private fun cancel() {
    removeCallbacks(tick)
    ramp = null
  }

  private fun systemLevel(): Float =
      readSystemBrightness().let { if (it.isFinite()) it.coerceIn(0f, 1f) else .5f }

  /** Do not restore here: removing the window restores the underlying window's own policy. */
  fun close() {
    closed = true
    active = false
    cancel()
  }

  internal companion object {
    const val DURATION_MS = 3_000L
    const val STEP_MS = 33L
    private const val GAMMA = 2.2
  }
}

/** Both the native Dream and its retained preview use the same window-only transition. */
internal class NightWindowBrightness(context: Context, private val window: Window) {
  private val handler = Handler(Looper.getMainLooper())
  private val delivery = NightBrightnessDelivery(
      apiLevel = Build.VERSION.SDK_INT,
      readWindow = { window.attributes.screenBrightness },
      writeWindow = { level ->
        window.attributes = window.attributes.apply { screenBrightness = level }
      })
  private val fade = NightBrightnessFade(
      priorBrightness = window.attributes.screenBrightness,
      readBrightness = delivery::read,
      readSystemBrightness = {
        // -1 is Android's policy sentinel, not a brightness level to interpolate towards.
        // Read the selected system level without changing global brightness or auto mode.
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
      },
      writeBrightness = delivery::write,
      nowMs = SystemClock::uptimeMillis,
      postDelayed = { task, delay -> handler.postDelayed(task, delay) },
      removeCallbacks = { task -> handler.removeCallbacks(task) },
      settleBrightness = delivery::settle)

  fun setNight(night: Boolean, brightnessPercent: Int) = fade.setNight(night, brightnessPercent / 100f)
  fun setActive(active: Boolean) = fade.setActive(active)
  fun close() = fade.close()
}
