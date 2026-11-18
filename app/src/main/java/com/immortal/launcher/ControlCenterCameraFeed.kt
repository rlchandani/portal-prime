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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** The last picture a tile shows and when it arrived. */
internal class CcFrame(val image: ImageBitmap, val at: Long)

/**
 * A tile's picture state. Frames replace [frame] (read only in the draw phase and by the tiny
 * timestamp), so a new snapshot invalidates the image layer and nothing else; [hasFrame] and
 * [failed] change at most once and are what the tile's composition reads.
 */
@Stable
internal class CcFrameHolder {
  var frame: CcFrame? by mutableStateOf(null)
    private set
  var failed: Boolean by mutableStateOf(false)
    private set
  val hasFrame: Boolean by derivedStateOf { frame != null }

  fun deliver(f: CcFrame) { frame = f; if (failed) failed = false }
  fun fail(onlyWhenEmpty: Boolean) { if (!onlyWhenEmpty || frame == null) failed = true }
}

/** Snapshots every [CcCameraLogic.SNAPSHOT_INTERVAL_MS] while the activity is started and this tile is composed. */
@Composable
internal fun CcSnapshotLoop(cameraId: String, holder: CcFrameHolder) {
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(cameraId, lifecycle, holder) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val image = withContext(Dispatchers.IO) { fetchSnapshot(context, cameraId) }
        if (image != null) holder.deliver(CcFrame(image, System.currentTimeMillis())) else holder.fail(onlyWhenEmpty = true)
        delay(CcCameraLogic.SNAPSHOT_INTERVAL_MS)
      }
    }
  }
}

/** Fetch + downsampled decode, entirely on the IO thread; the GPU upload is started here too. */
private fun fetchSnapshot(context: Context, cameraId: String): ImageBitmap? {
  if (!HaConfig.isConfigured(context)) return null
  val bytes = Http.getBytes(CcCameraLogic.snapshotUrl(HaConfig.baseUrl(context), cameraId), HaConfig.authHeaders(context)) ?: return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0) return null
  val opts = BitmapFactory.Options().apply { inSampleSize = CcCameraLogic.sampleSize(bounds.outWidth) }
  val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull() ?: return null
  return bitmap.uploaded().asImageBitmap()
}

/** Starts the texture upload off the UI thread so the first draw doesn't pay for it. */
private fun Bitmap.uploaded(): Bitmap = also { it.prepareToDraw() }

/** The MJPEG stream (with HaCameraFeed's own snapshot fallback), running only while started. */
@Composable
internal fun CcLiveFeed(cameraId: String, holder: CcFrameHolder) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, _ -> started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  DisposableEffect(cameraId, started, holder) {
    val feed = if (!started) null else HaCameraFeed(context, cameraId,
        onFrame = { b -> holder.deliver(CcFrame(b.uploaded().asImageBitmap(), System.currentTimeMillis())) },
        onUnavailable = { holder.fail(onlyWhenEmpty = false) })
    feed?.start()
    onDispose { feed?.stop() }
  }
}
