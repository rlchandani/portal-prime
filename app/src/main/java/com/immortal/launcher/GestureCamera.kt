/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Contact-free "wave to advance" for the photo frame / recipe view, using the standard
 * Camera2 API only — the Meta Smart Camera SDK (gated behind signature permissions) is
 * never touched. A low-resolution luminance stream is differenced frame-to-frame; a
 * broad burst of motion across the view (a hand wave at 50–100 cm) fires [onWave],
 * debounced so one wave is one event.
 *
 * Deliberately defensive: the front camera is shared on Portal and this runs inside the
 * always-on dream, so *every* path is wrapped — any failure just disables the feature
 * (logs and releases) rather than risking the launcher/dream process. Off by default;
 * the user opts in, and it no-ops without the CAMERA permission.
 *
 * NOTE (experimental): feasibility on real Portal hardware is unverified. Frame-difference
 * gesture detection is a heuristic, not the framed Smart Camera tracking. Keep the opt-in.
 */
class GestureCamera(
    private val context: Context,
    private val onWave: () -> Unit,
) {
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null

  private var prev: IntArray? = null
  private var lastWaveAt = 0L
  @Volatile private var running = false

  /** Begin watching for waves. Safe to call when unsupported/ungranted — it no-ops. */
  fun start() {
    if (running) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "no camera permission — gesture control disabled")
      return
    }
    runCatching {
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val cameraId = pickFrontCamera(manager) ?: run {
        Log.i(TAG, "no front camera — gesture control disabled")
        return
      }
      thread = HandlerThread("immortal-gesture").apply { start() }
      handler = Handler(thread!!.looper)
      reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2).apply {
        setOnImageAvailableListener(onFrame, handler)
      }
      running = true
      openCamera(manager, cameraId)
    }.onFailure {
      Log.w(TAG, "gesture camera start failed", it)
      stop()
    }
  }

  /** Stop and release everything. Idempotent. */
  fun stop() {
    running = false
    runCatching { session?.close() }
    runCatching { device?.close() }
    runCatching { reader?.close() }
    runCatching { thread?.quitSafely() }
    session = null
    device = null
    reader = null
    thread = null
    handler = null
    prev = null
  }

  private fun pickFrontCamera(manager: CameraManager): String? =
      manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
      } ?: manager.cameraIdList.firstOrNull()

  @Suppress("MissingPermission") // checked in start()
  private fun openCamera(manager: CameraManager, cameraId: String) {
    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) {
        device = camera
        runCatching { createSession(camera) }.onFailure { Log.w(TAG, "session create failed", it); stop() }
      }
      override fun onDisconnected(camera: CameraDevice) { stop() }
      override fun onError(camera: CameraDevice, error: Int) {
        Log.w(TAG, "camera error $error")
        stop()
      }
    }, handler)
  }

  private fun createSession(camera: CameraDevice) {
    val surface = reader?.surface ?: return
    @Suppress("DEPRECATION")
    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(s: CameraCaptureSession) {
        if (!running) { runCatching { s.close() }; return }
        session = s
        runCatching {
          val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
          }
          s.setRepeatingRequest(req.build(), null, handler)
        }.onFailure { Log.w(TAG, "repeating request failed", it); stop() }
      }
      override fun onConfigureFailed(s: CameraCaptureSession) {
        Log.w(TAG, "session configure failed"); stop()
      }
    }, handler)
  }

  // Downsample the Y plane into a GRID×GRID block grid; a wave is a large jump in the
  // summed block-to-block difference, spread across many blocks (not a tiny local change).
  private val onFrame = ImageReader.OnImageAvailableListener { r ->
    val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@OnImageAvailableListener
    try {
      if (!running) return@OnImageAvailableListener
      val plane = image.planes[0]
      val buf = plane.buffer
      val rowStride = plane.rowStride
      val width = image.width
      val height = image.height
      val grid = IntArray(GRID * GRID)
      for (gy in 0 until GRID) {
        for (gx in 0 until GRID) {
          val px = (gx + 0.5f) / GRID * width
          val py = (gy + 0.5f) / GRID * height
          val idx = py.toInt() * rowStride + px.toInt()
          grid[gy * GRID + gx] = if (idx in 0 until buf.limit()) (buf.get(idx).toInt() and 0xFF) else 0
        }
      }
      detect(grid)
    } finally {
      runCatching { image.close() }
    }
  }

  private fun detect(grid: IntArray) {
    val p = prev
    prev = grid
    if (p == null) return
    var changedBlocks = 0
    var totalDelta = 0
    for (i in grid.indices) {
      val d = abs(grid[i] - p[i])
      if (d > BLOCK_DELTA) { changedBlocks++; totalDelta += d }
    }
    // A wave moves across much of the frame at once.
    val now = System.currentTimeMillis()
    if (changedBlocks >= grid.size / 3 && totalDelta > FRAME_DELTA && now - lastWaveAt > DEBOUNCE_MS) {
      lastWaveAt = now
      runCatching { onWave() }
    }
  }

  private companion object {
    const val TAG = "ImmortalGesture"
    const val W = 320
    const val H = 240
    const val GRID = 12 // 12×12 motion blocks
    const val BLOCK_DELTA = 28 // per-block luminance change that counts as "moved"
    const val FRAME_DELTA = 2200 // summed change across the frame for a wave
    const val DEBOUNCE_MS = 1500L
  }
}
