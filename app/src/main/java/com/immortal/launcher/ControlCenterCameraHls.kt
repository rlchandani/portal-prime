/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.Matrix
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * True live video for cameras HA can stream: asks HA for the camera's HLS stream (websocket
 * `camera/stream`) and plays it muted with ExoPlayer into a TextureView (so the tile's rounded
 * corners still clip it). Only while resumed; the player and its decoder are released the moment
 * the tile leaves, the screen goes off or another app comes up. [onFailed] when HA refuses, the
 * player errors, or no frame renders within [FIRST_FRAME_MS] — the tile then falls back.
 */
@Composable
internal fun BoxScope.CcHlsVideo(cameraId: String, holder: CcFrameHolder, onFailed: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val resumed by rememberResumed()
  val failed by rememberUpdatedState(onFailed)
  var url by remember(cameraId) { mutableStateOf<String?>(null) }
  LaunchedEffect(cameraId, resumed) {
    // HA's stream may idle out while we're away: ask again after every pause.
    if (!resumed) { url = null; return@LaunchedEffect }
    if (url != null) return@LaunchedEffect
    url = requestStream(context, cameraId) ?: run { Log.i(TAG, "camera hls: HA gave no stream (${CcCameraLogic.logId(cameraId)})"); failed(); null }
  }
  val stream = url
  if (!resumed || stream == null) return
  var player by remember(stream) { mutableStateOf<ExoPlayer?>(null) }
  DisposableEffect(stream) {
    val p = newPlayer(context, stream)
    val listener = object : Player.Listener {
      override fun onRenderedFirstFrame() { holder.video = true; holder.moving(System.currentTimeMillis()) }
      override fun onPlayerError(error: PlaybackException) {
        Log.i(TAG, "camera hls error ${error.errorCodeName} (${CcCameraLogic.logId(cameraId)})")
        holder.streamEnded(); url = null; failed()
      }
    }
    p.addListener(listener)
    p.prepare()
    player = p
    onDispose { p.removeListener(listener); p.release(); player = null; holder.streamEnded() }
  }
  val active = player ?: return
  LaunchedEffect(active) { watchPlayback(active, holder, cameraId) { holder.streamEnded(); url = null; failed() } }
  AndroidView(factory = { ctx -> TextureView(ctx) }, modifier = Modifier.matchParentSize().graphicsLayer { alpha = if (holder.video) 1f else 0f },
      update = { view -> active.setVideoTextureView(view); CcVideoCrop.attach(view, active) })
}

private const val TAG = "HearthpaneCamera"
private const val FIRST_FRAME_MS = 12_000L
private const val TICK_MS = 1_000L
private const val STATS_EVERY = 10

private suspend fun requestStream(context: Context, cameraId: String): String? = suspendCancellableCoroutine { cont ->
  ControlCenter.cameraStream(context, cameraId) { url -> if (cont.isActive) cont.resume(url) }
}

@OptIn(UnstableApi::class)
private fun newPlayer(context: Context, url: String): ExoPlayer = ExoPlayer.Builder(context).build().apply {
  // Never a sound from a camera tile: no audio track is even decoded.
  trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
  volume = 0f
  repeatMode = Player.REPEAT_MODE_OFF
  setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
  playWhenReady = true
}

/** Marks the tile live while playback moves on; fails over if the first frame never comes. Logs fps. */
@OptIn(UnstableApi::class)
private suspend fun watchPlayback(p: ExoPlayer, holder: CcFrameHolder, cameraId: String, onStalled: () -> Unit) {
  val started = System.currentTimeMillis()
  var lastPos = -1L
  var ticks = 0
  var lastRendered = 0
  while (true) {
    delay(TICK_MS)
    val now = System.currentTimeMillis()
    if (!holder.video && now - started > FIRST_FRAME_MS) { Log.i(TAG, "camera hls: no frame (${CcCameraLogic.logId(cameraId)})"); onStalled(); return }
    val pos = p.currentPosition
    if (holder.video && p.isPlaying && pos != lastPos) holder.moving(now)
    lastPos = pos
    if (++ticks % STATS_EVERY == 0) {
      val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
      val rendered = counters?.renderedOutputBufferCount ?: 0
      Log.i(TAG, "camera hls: %.1f fps (${CcCameraLogic.logId(cameraId)})".format((rendered - lastRendered) * 1000.0 / (TICK_MS * STATS_EVERY)))
      lastRendered = rendered
    }
  }
}

/** Centre-crops the video in its TextureView (which otherwise stretches it), like the snapshot tiles. */
private object CcVideoCrop {
  /** Views already following a player (main thread only); weak so a released tile leaves nothing behind. */
  private val attached = java.util.WeakHashMap<TextureView, ExoPlayer>()

  fun attach(view: TextureView, player: ExoPlayer) {
    if (attached[view] === player) return
    attached[view] = player
    val refresh = { crop(view, player.videoSize) }
    player.addListener(object : Player.Listener { override fun onVideoSizeChanged(videoSize: VideoSize) = refresh() })
    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop(view, attached[view]?.videoSize ?: return@addOnLayoutChangeListener) }
  }

  private fun crop(view: TextureView, size: VideoSize) {
    val vw = view.width.toFloat(); val vh = view.height.toFloat()
    if (vw <= 0f || vh <= 0f || size.width <= 0 || size.height <= 0) return
    val video = size.width * size.pixelWidthHeightRatio / size.height
    val box = vw / vh
    val m = Matrix()
    if (video > box) m.setScale(video / box, 1f, vw / 2, vh / 2) else m.setScale(1f, box / video, vw / 2, vh / 2)
    view.setTransform(m)
  }
}
