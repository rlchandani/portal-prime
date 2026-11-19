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
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The last picture a tile shows and when it arrived. */
internal class CcFrame(val image: ImageBitmap, val at: Long)

/**
 * A tile's picture state. Frames replace [frame] (read only in the draw phase), [watch] records
 * what actually changed (read by the small tag and stamp), [video] is true while a video stream
 * renders on top. [hasFrame] and [failed] change rarely and are what the tile's composition reads.
 */
@Stable
internal class CcFrameHolder(val cameraId: String) {
  var frame: CcFrame? by mutableStateOf(null)
    private set
  var watch: CcCamWatch by mutableStateOf(CcCamWatch().remembered(CcCamMemory.get(cameraId)))
    private set
  var failed: Boolean by mutableStateOf(false)
    private set
  var video: Boolean by mutableStateOf(false)
  val hasImage: Boolean by derivedStateOf { frame != null }
  val hasFrame: Boolean by derivedStateOf { frame != null || video }
  /** A stream (not the snapshot loop) is supplying pictures, so the loop can rest. */
  val streamFeeding: Boolean by derivedStateOf { video || watch.hasFrame && watch.source != CcCamSource.SNAPSHOT }

  /** A picture arrived; [image] null = byte-for-byte the one already shown (nothing to decode or draw). */
  fun deliver(image: ImageBitmap?, hash: Long, now: Long, captured: Long?, source: CcCamSource) {
    watch = watch.frame(hash, now, captured, source)
    CcCamMemory.put(cameraId, watch)
    if (image != null) frame = CcFrame(image, now)
    if (failed && frame != null) failed = false
  }

  /** The video stream played on. */
  fun moving(now: Long) {
    watch = watch.moving(now, CcCamSource.HLS)
    if (failed) failed = false
  }

  /** The stream stopped: pictures come from snapshots again. */
  fun streamEnded() {
    video = false
    if (watch.source != CcCamSource.SNAPSHOT) watch = watch.copy(source = CcCamSource.SNAPSHOT)
  }

  fun fail(onlyWhenEmpty: Boolean) { if (!onlyWhenEmpty || frame == null) failed = true }
}

/** True while the activity is resumed: screen on, no dream or other app on top. Streams run only then. */
@Composable
internal fun rememberResumed(): State<Boolean> {
  val owner = LocalLifecycleOwner.current
  val resumed = remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, _ -> resumed.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  return resumed
}

/** A clock for the tag ("Snapshot · 3 h ago"), ticking every [everyMs] only while resumed. */
@Composable
internal fun rememberCamNow(everyMs: Long = CLOCK_MS): State<Long> {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  return produceState(System.currentTimeMillis(), lifecycle, everyMs) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) { value = System.currentTimeMillis(); delay(everyMs) }
    }
  }
}

private const val CLOCK_MS = 1_000L

/**
 * Snapshots every [CcCameraLogic.SNAPSHOT_INTERVAL_MS] while resumed and this tile is composed.
 * Each request is uncacheable; an unchanged picture is not decoded again. [captured] is the
 * camera's own capture time from HA (ring-mqtt `timestamp`), read fresh on every round.
 */
@Composable
internal fun CcSnapshotLoop(cameraId: String, holder: CcFrameHolder, captured: Long?) {
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val capturedNow by rememberUpdatedState(captured)
  LaunchedEffect(cameraId, lifecycle, holder) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        val known = holder.watch.hash.takeIf { holder.frame != null } ?: 0L
        val shot = withContext(Dispatchers.IO) { CcSnapshots.fetch(context, cameraId, known) }
        if (shot != null) holder.deliver(shot.image, shot.hash, System.currentTimeMillis(), capturedNow ?: shot.lastModified, CcCamSource.SNAPSHOT)
        else holder.fail(onlyWhenEmpty = true)
        delay(CcCameraLogic.SNAPSHOT_INTERVAL_MS)
      }
    }
  }
}

/** One snapshot: its hash, the picture when new (null = same bytes as [CcSnapshots.fetch]'s `known`), `Last-Modified`. */
internal class CcShot(val hash: Long, val image: ImageBitmap?, val lastModified: Long?)

internal object CcSnapshots {
  private const val TAG = "HearthpaneCamera"
  private const val CONNECT_S = 8L
  private const val READ_S = 10L
  private val client by lazy {
    OkHttpClient.Builder().connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(READ_S, TimeUnit.SECONDS).cache(null).build()
  }

  /** Fetch + (only when the bytes changed) a downsampled decode, all on the calling IO thread. */
  fun fetch(context: Context, cameraId: String, known: Long): CcShot? {
    if (!HaConfig.isConfigured(context)) return null
    val url = CcCameraLogic.freshSnapshotUrl(HaConfig.baseUrl(context), cameraId, System.currentTimeMillis())
    val request = runCatching {
      Request.Builder().url(url).header("Cache-Control", "no-cache").header("Pragma", "no-cache")
          .apply { HaConfig.authHeaders(context).forEach { (k, v) -> header(k, v) } }.build()
    }.getOrNull() ?: return null
    return runCatching {
      client.newCall(request).execute().use { r ->
        if (!r.isSuccessful) { Log.i(TAG, "camera snapshot HTTP ${r.code} (${CcCameraLogic.logId(cameraId)})"); return null }
        val bytes = r.body?.bytes() ?: return null
        val lastModified = r.headers.getDate("Last-Modified")?.time
        val hash = CcCamLiveness.hash(bytes)
        if (hash == known) CcShot(hash, null, lastModified) else decode(bytes)?.let { CcShot(hash, it, lastModified) }
      }
    }.onFailure { Log.i(TAG, "camera snapshot failed: ${it.javaClass.simpleName} (${CcCameraLogic.logId(cameraId)})") }.getOrNull()
  }

  private fun decode(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = CcCameraLogic.sampleSize(bounds.outWidth) }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull() ?: return null
    return bitmap.uploaded().asImageBitmap()
  }
}

/** Starts the texture upload off the UI thread so the first draw doesn't pay for it. */
internal fun Bitmap.uploaded(): Bitmap = also { it.prepareToDraw() }

/**
 * HA's MJPEG stream (`camera_proxy_stream`), only while resumed. Each frame is hashed so a stream
 * that repeats one still (HA does that for snapshot-only cameras) never reads as live. When the
 * stream ends or never starts, [onEnded] hands over to snapshots.
 */
@Composable
internal fun CcMjpegFeed(cameraId: String, holder: CcFrameHolder, captured: Long?, onEnded: () -> Unit) {
  val context = LocalContext.current
  val resumed by rememberResumed()
  val capturedNow by rememberUpdatedState(captured)
  val ended by rememberUpdatedState(onEnded)
  DisposableEffect(cameraId, resumed, holder) {
    val feed = if (!resumed) null else HaCameraFeed(context, cameraId, onFrame = {}, onUnavailable = { holder.streamEnded(); ended() },
        snapshotFallback = false,
        onPicture = { b, hash -> holder.deliver(b?.uploaded()?.asImageBitmap(), hash, System.currentTimeMillis(), capturedNow, CcCamSource.MJPEG) })
    feed?.start()
    onDispose { feed?.stop() }
  }
}
