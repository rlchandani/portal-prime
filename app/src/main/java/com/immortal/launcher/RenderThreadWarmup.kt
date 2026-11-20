/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cold-start RenderThread warm-up.
 *
 * The process's RenderThread creates its EGL context and opens Skia's shader disk cache the first
 * time any window gives it a surface. For Home that happens inside Home's first frame, after the
 * first composition and layout, so the main thread then waits for it (perfetto, cold boot:
 * `setSurface` 46 ms, of which `Creating EGLContext` 19 ms and `initShaderDiskCache` 16 ms).
 *
 * [start] hands the RenderThread a 1×1 offscreen surface while Home's activity is created, so that
 * one-time setup runs in parallel with the first composition instead of after it. Nothing is ever
 * drawn into it, so there is no frame and no pixel change; Home's own window later reuses the same
 * EGL context. The offscreen renderer is released after [OffscreenWarmup.RELEASE_DELAY_MS].
 */
internal object RenderThreadWarmup {
  private const val TAG = "RenderThreadWarmup"

  private val gate = OffscreenWarmup(Build.VERSION.SDK_INT,
      open = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openOffscreenSurface()
        else throw UnsupportedOperationException("HardwareRenderer needs API 29")
      },
      schedule = { release, delayMs -> Handler(Looper.getMainLooper()).postDelayed(release, delayMs) },
      report = { what, error -> Log.w(TAG, what, error) })

  /** Once per process, on the main thread; returns immediately. No-op before API 29. */
  fun start() = gate.start()

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun openOffscreenSurface(): AutoCloseable {
    val reader = ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val renderer = try {
      HardwareRenderer().apply { setSurface(reader.surface) }
    } catch (t: Throwable) {
      reader.close()
      throw t
    }
    return AutoCloseable {
      try { renderer.destroy() } finally { reader.close() }
    }
  }
}

/**
 * The warm-up's policy, separate from the platform objects: open once (API 29+ only), then
 * schedule exactly one release. Failures are reported, never thrown at the caller.
 */
internal class OffscreenWarmup(
    private val sdk: Int,
    private val open: () -> AutoCloseable,
    private val schedule: (Runnable, Long) -> Unit,
    private val report: (String, Throwable) -> Unit,
) {
  private val started = AtomicBoolean(false)

  /** True when an offscreen surface was opened by this call. */
  fun start(): Boolean {
    if (sdk < Build.VERSION_CODES.Q || !started.compareAndSet(false, true)) return false
    val surface = try {
      open()
    } catch (t: Throwable) {
      report("warm-up skipped", t)
      return false
    }
    schedule(Runnable {
      try { surface.close() } catch (t: Throwable) { report("warm-up release failed", t) }
    }, RELEASE_DELAY_MS)
    return true
  }

  companion object {
    /** Long after Home's first frames; the EGL context itself stays with the RenderThread. */
    const val RELEASE_DELAY_MS = 10_000L
  }
}
