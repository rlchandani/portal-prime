/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera 0 (Meta's virtual, AI-framed camera: the only one third-party apps can open) with a
 * visible preview surface and a small YUV reader. Frames are copied into a [YuvFrame] only when
 * the analysis side is free ([wantFrame]), handed over, and the [android.media.Image] is closed
 * at once. Nothing is ever written to storage. [close] releases everything immediately; it is
 * idempotent and safe from any thread.
 */
internal class WhosThereCamera(
    private val context: Context,
    private val preview: Surface?,
    private val listener: Listener,
) {
  interface Listener {
    fun onOpened(openMs: Long)
    /** Called on the camera thread. Return quickly; heavy work belongs on the analysis thread. */
    fun onFrame(frame: YuvFrame)
    fun wantFrame(): Boolean
    fun onFailed(reason: String)
  }

  private val thread = HandlerThread("whos-there-camera").apply { start() }
  private val handler = Handler(thread.looper)
  private val closed = AtomicBoolean(false)
  @Volatile private var reader: ImageReader? = null
  @Volatile private var device: CameraDevice? = null
  @Volatile private var session: CameraCaptureSession? = null
  private var openedAt = 0L

  /** Opens on the camera thread: no binder calls or allocations on the UI thread mid-frame. */
  fun open() {
    handler.post { if (!closed.get()) openNow() }
  }

  @SuppressLint("MissingPermission") // checked just below
  private fun openNow() {
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      fail("camera permission missing"); return
    }
    val manager = context.getSystemService(CameraManager::class.java) ?: return fail("no camera service")
    val t0 = SystemClock.elapsedRealtime()
    val r = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)
    reader = r
    r.setOnImageAvailableListener({ onImage(it) }, handler)
    try {
      manager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
          if (closed.get()) { camera.close(); return }
          device = camera
          openedAt = SystemClock.elapsedRealtime()
          listener.onOpened(openedAt - t0)
          startSession(camera, r)
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); fail("camera disconnected") }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail("camera error $error") }
      }, handler)
    } catch (e: Exception) {
      // CameraAccessException (in use, disabled by policy), SecurityException, IllegalArgumentException.
      fail("camera unavailable: ${e.javaClass.simpleName}")
    }
  }

  @Suppress("DEPRECATION") // createCaptureSession(List) is the API 24-compatible form
  private fun startSession(camera: CameraDevice, r: ImageReader) {
    val targets = listOfNotNull(preview, r.surface)
    try {
      camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
          if (closed.get()) { s.close(); return }
          session = s
          try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
              targets.forEach(::addTarget)
            }.build()
            s.setRepeatingRequest(request, null, handler)
          } catch (e: Exception) {
            fail("capture failed: ${e.javaClass.simpleName}")
          }
        }
        override fun onConfigureFailed(s: CameraCaptureSession) = fail("session configure failed")
      }, handler)
    } catch (e: Exception) {
      fail("session failed: ${e.javaClass.simpleName}")
    }
  }

  private fun onImage(r: ImageReader) {
    val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
    try {
      if (closed.get() || !listener.wantFrame()) return
      val planes = image.planes
      val y = planes[0]
      val u = planes[1]
      val v = planes[2]
      val frame = YuvFrame(image.width, image.height, copy(y.buffer), y.rowStride, copy(u.buffer), copy(v.buffer),
          u.rowStride, u.pixelStride, SystemClock.elapsedRealtime())
      listener.onFrame(frame)
    } catch (e: Exception) {
      Log.w(TAG, "frame copy failed: ${e.javaClass.simpleName}")
    } finally {
      image.close()
    }
  }

  private fun copy(buffer: java.nio.ByteBuffer): ByteArray {
    val out = ByteArray(buffer.remaining())
    buffer.get(out)
    return out
  }

  private fun fail(reason: String) {
    if (closed.get()) return
    Log.w(TAG, "glance camera: $reason")
    listener.onFailed(reason)
    close()
  }

  /**
   * Release now: session, device, reader, thread. From the UI thread the (blocking) release is
   * handed to the camera thread at once, so leaving Home never stalls a frame; from any other
   * thread it runs inline. Returns the inline close duration in ms (0 when handed over).
   */
  fun close(): Long {
    if (!closed.compareAndSet(false, true)) return 0L
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      handler.post { release() }
      return 0L
    }
    return release()
  }

  private fun release(): Long {
    val t0 = SystemClock.elapsedRealtime()
    runCatching { session?.stopRepeating() }
    runCatching { session?.close() }
    runCatching { device?.close() }
    runCatching { reader?.close() }
    session = null
    device = null
    reader = null
    thread.quitSafely()
    return SystemClock.elapsedRealtime() - t0
  }

  companion object {
    private const val TAG = "HearthpaneWhosThere"
    const val CAMERA_ID = "0"
    const val WIDTH = 640
    const val HEIGHT = 480

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  }
}
