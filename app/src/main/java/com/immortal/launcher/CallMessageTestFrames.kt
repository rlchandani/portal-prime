/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.SystemClock
import android.view.Surface

/**
 * Measurement builds only ([BuildConfig.PORTAL_CALL_LOOPBACK]): a generated pattern drawn into the
 * self preview and, while recording, the recorder's input surface, at 30 fps. Replaces the camera
 * so a video message can be tested at night without a picture of any room. Never in normal builds.
 */
internal class CallMessageTestFrames(private val preview: Surface, private val handler: Handler) {
  @Volatile var recorder: Surface? = null
  private val bar = Paint()
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = TEXT_PX; isFakeBoldText = true }
  private val start = SystemClock.elapsedRealtime()
  private var running = false

  private val frame = object : Runnable {
    override fun run() {
      if (!running) return
      draw(preview, hardware = false)
      recorder?.let { draw(it, hardware = true) }
      handler.postDelayed(this, FRAME_MS)
    }
  }

  fun start() { check(BuildConfig.PORTAL_CALL_LOOPBACK); running = true; handler.post(frame) }

  fun stop() { running = false; handler.removeCallbacks(frame) }

  private fun draw(surface: Surface, hardware: Boolean) {
    if (!surface.isValid) return
    val canvas: Canvas = runCatching { if (hardware) surface.lockHardwareCanvas() else surface.lockCanvas(null) }.getOrNull() ?: return
    try {
      val t = (SystemClock.elapsedRealtime() - start) / 1000f
      val w = canvas.width.toFloat()
      val h = canvas.height.toFloat()
      canvas.drawColor(Color.rgb(20, 22, 27))
      for (i in 0 until BARS) {
        bar.color = Color.HSVToColor(floatArrayOf((i * 360f / BARS + t * 40f) % 360f, 0.55f, 0.85f))
        canvas.drawRect(w * i / BARS, h * 0.25f, w * (i + 1) / BARS, h * 0.75f, bar)
      }
      val x = (t * 0.25f % 1f) * w
      bar.color = Color.WHITE
      canvas.drawRect(x, h * 0.2f, x + w * 0.02f, h * 0.8f, bar)
      canvas.drawText("TEST FRAMES · %.1f s".format(t), w * 0.05f, h * 0.15f, text)
    } finally {
      runCatching { surface.unlockCanvasAndPost(canvas) }
    }
  }

  private companion object {
    const val FRAME_MS = 33L
    const val BARS = 8
    const val TEXT_PX = 48f
  }
}
