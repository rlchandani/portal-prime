/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Measurement builds only ([CallTestMode]): a generated picture (moving colour bars and a sweep)
 * fed to the call's video source instead of camera 0, so night-time device tests exercise the
 * whole encode → LAN → decode → render path without ever seeing a room. Never used otherwise.
 */
internal class CallTestFrames(private val observer: CapturerObserver, width: Int, height: Int, fps: Int) {
  private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hearthpane-call-testframes") }
  @Volatile private var size = Triple(width, height, fps)
  private var task: ScheduledFuture<*>? = null
  private var n = 0

  fun start() {
    observer.onCapturerStarted(true)
    schedule()
  }

  fun format(width: Int, height: Int, fps: Int) {
    size = Triple(width, height, fps)
    timer.execute { task?.cancel(false); schedule() }
  }

  fun stop() {
    task?.cancel(false)
    timer.shutdownNow()
    runCatching { observer.onCapturerStopped() }
  }

  private fun schedule() {
    val periodMs = MS_PER_S / size.third.coerceAtLeast(1)
    task = timer.scheduleWithFixedDelay(::frame, 0, periodMs, TimeUnit.MILLISECONDS)
  }

  private fun frame() {
    val (w, h) = size
    val buffer = JavaI420Buffer.allocate(w, h)
    paint(buffer, w, h, n++)
    val frame = VideoFrame(buffer, 0, TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime()))
    observer.onFrameCaptured(frame)
    frame.release()
  }

  /** Eight luma bars drifting right, a bright sweep line, and a slowly cycling chroma tint. */
  private fun paint(b: JavaI420Buffer, w: Int, h: Int, i: Int) {
    val y = b.dataY
    val shift = (i * 4) % w
    val row = ByteArray(w) { x -> (BAR_LUMA[((x + shift) * BARS / w) % BARS] ).toByte() }
    val sweep = (i * 6) % h
    for (r in 0 until h) {
      y.position(r * b.strideY)
      if (r in sweep until sweep + SWEEP_ROWS) y.put(ByteArray(w) { SWEEP_LUMA.toByte() }) else y.put(row)
    }
    val tint = ((i / TINT_EVERY) % TINT_RANGE + TINT_BASE).toByte()
    fill(b.dataU, b.strideU, (w + 1) / 2, (h + 1) / 2, tint)
    fill(b.dataV, b.strideV, (w + 1) / 2, (h + 1) / 2, (TINT_MAX - tint).toByte())
  }

  private fun fill(plane: java.nio.ByteBuffer, stride: Int, w: Int, h: Int, value: Byte) {
    val row = ByteArray(w) { value }
    for (r in 0 until h) { plane.position(r * stride); plane.put(row) }
  }

  private companion object {
    const val MS_PER_S = 1_000L
    const val BARS = 8
    val BAR_LUMA = intArrayOf(235, 210, 180, 150, 120, 90, 60, 30)
    const val SWEEP_ROWS = 6
    const val SWEEP_LUMA = 250
    const val TINT_EVERY = 4
    const val TINT_RANGE = 96
    const val TINT_BASE = 80
    const val TINT_MAX = 255
  }
}

/** Measurement-build switches set over `/perf/calling` (fleet token); inert unless PORTAL_CALL_LOOPBACK. */
internal object CallTestMode {
  @Volatile var frames: Boolean = false
    get() = field && BuildConfig.PORTAL_CALL_LOOPBACK
}
