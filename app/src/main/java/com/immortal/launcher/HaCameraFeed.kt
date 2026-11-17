/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Live pictures of a Home Assistant camera for the doorbell card and the full camera view.
 * First HA's MJPEG proxy (`/api/camera_proxy_stream/<camera>`); if that gives no frame within
 * [FIRST_FRAME_MS] or breaks, a snapshot loop (`/api/camera_proxy/<camera>`) about once a second.
 * Frames are downsampled to at most [MAX_WIDTH] wide and delivered on the main thread. [stop]
 * ends everything; a stopped feed never delivers again. Never logs the address or the token.
 */
internal class HaCameraFeed(
    context: Context,
    private val camera: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onUnavailable: () -> Unit,
) {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var stopped = false
  @Volatile private var call: Call? = null
  @Volatile private var lastFrameAt = 0L
  private var sampleSize = 0

  fun start() {
    if (!HaConfig.isConfigured(app) || !DoorbellEntities.isCamera(camera)) { main.post { if (!stopped) onUnavailable() }; return }
    thread(name = "hearthpane-camera", isDaemon = true) { run() }
  }

  fun stop() {
    stopped = true
    runCatching { call?.cancel() }
  }

  private fun run() {
    val base = HaConfig.baseUrl(app)
    val headers = HaConfig.authHeaders(app)
    val streamed = runCatching { stream(DoorbellUrls.stream(base, camera), headers) }
        .onFailure { if (!stopped) Log.i(TAG, "camera stream ended (${it.javaClass.simpleName}); using snapshots") }
    if (stopped) return
    if (streamed.isSuccess) Log.i(TAG, "camera stream closed; using snapshots")
    snapshots(DoorbellUrls.snapshot(base, camera), headers)
  }

  private fun stream(url: String, headers: Map<String, String>) {
    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    val c = streamClient.newCall(request).also { call = it }
    val started = System.currentTimeMillis()
    val watchdog = Runnable { if (lastFrameAt == 0L) runCatching { c.cancel() } }
    main.postDelayed(watchdog, FIRST_FRAME_MS)
    try {
      c.execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val input = response.body?.byteStream() ?: return
        val scanner = MjpegScanner()
        val buffer = ByteArray(READ_BUFFER)
        while (!stopped) {
          val n = input.read(buffer)
          if (n < 0) return
          val frame = scanner.feed(buffer, n).lastOrNull()
          if (frame != null && System.currentTimeMillis() - lastFrameAt >= MIN_FRAME_GAP_MS) deliver(frame)
          if (lastFrameAt == 0L && System.currentTimeMillis() - started > FIRST_FRAME_MS) return
        }
      }
    } finally {
      main.removeCallbacks(watchdog)
    }
  }

  private fun snapshots(url: String, headers: Map<String, String>) {
    var failures = 0
    while (!stopped) {
      val bytes = runCatching {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        snapshotClient.newCall(request).also { call = it }.execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
      }.getOrNull()
      if (bytes != null && deliver(bytes)) failures = 0 else failures++
      if (failures == MAX_SNAPSHOT_FAILURES && lastFrameAt == 0L) main.post { if (!stopped) onUnavailable() }
      runCatching { Thread.sleep(SNAPSHOT_INTERVAL_MS) }
    }
  }

  private fun deliver(jpeg: ByteArray): Boolean {
    if (sampleSize == 0) {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
      if (bounds.outWidth <= 0) return false
      var s = 1
      while (bounds.outWidth / (s * 2) >= MAX_WIDTH) s *= 2
      sampleSize = s
    }
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        ?: return false
    lastFrameAt = System.currentTimeMillis()
    main.post { if (!stopped) onFrame(bitmap) }
    return true
  }

  companion object {
    private const val TAG = "HearthpaneCamera"
    const val FIRST_FRAME_MS = 6_000L
    const val SNAPSHOT_INTERVAL_MS = 1_000L
    /** At most ~8 decoded frames a second: plenty for a door, light on a Portal. */
    private const val MIN_FRAME_GAP_MS = 120L
    private const val MAX_SNAPSHOT_FAILURES = 3
    private const val MAX_WIDTH = 1280
    private const val READ_BUFFER = 16 * 1024
    private const val CONNECT_S = 8L
    private const val STREAM_READ_S = 15L
    private const val SNAPSHOT_READ_S = 8L

    private val streamClient by lazy {
      OkHttpClient.Builder().connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(STREAM_READ_S, TimeUnit.SECONDS).build()
    }
    private val snapshotClient by lazy {
      streamClient.newBuilder().readTimeout(SNAPSHOT_READ_S, TimeUnit.SECONDS).build()
    }
  }
}
