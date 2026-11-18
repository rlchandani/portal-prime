/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The last picture a tile shows and when it was taken (null until the first frame). */
private data class CcFrame(val image: ImageBitmap, val at: Long)

/**
 * A camera picture for any screen (`.thumb`): a snapshot every 5 s while on screen, or the live
 * MJPEG stream when [live] (one live stream Portal-wide, see [CcLiveStream]; an older live tile
 * falls back to snapshots). Kids mode hides cameras entirely; without HA it shows why.
 */
@Composable
internal fun CcCameraTile(s: CcUiState, cameraId: String, live: Boolean, modifier: Modifier = Modifier,
                          label: String? = null, onClick: (() -> Unit)? = null) {
  if (s.settings.kids && CcPolicy.hiddenKind(CcKind.CAMERA, null, s.settings)) return
  CcCameraTileSized(s, cameraId, live, modifier, label, onClick, CcCameraTileSize.GRID)
}

/** Label/tag sizes per context (`.camGrid .thumb`, `.camFull .thumb`, small strip thumbs). */
internal enum class CcCameraTileSize(val radius: Dp, val label: Int, val labelInset: Dp, val tag: Int, val tagInset: Dp) {
  GRID(30.dp, 24, 22.dp, 16, 18.dp),
  FULL(34.dp, 30, 30.dp, 16, 18.dp),
  SMALL(20.dp, 17, 12.dp, 13, 10.dp),
}

@Composable
internal fun CcCameraTileSized(s: CcUiState, cameraId: String, live: Boolean, modifier: Modifier, label: String?,
                               onClick: (() -> Unit)?, size: CcCameraTileSize) {
  if (s.settings.kids && CcPolicy.hiddenKind(CcKind.CAMERA, null, s.settings)) return
  val onScreen = LocalCcScreenActive.current
  val configured = s.connection.phase != CcPhase.NOT_CONFIGURED
  var token by remember(cameraId, live) { mutableLongStateOf(CcLiveGuard.NONE) }
  DisposableEffect(cameraId, live, configured, onScreen) {
    val claimed = if (live && configured && onScreen) CcLiveStream.claim() else CcLiveGuard.NONE
    token = claimed
    onDispose { CcLiveStream.release(claimed) }
  }
  val owner by CcLiveStream.owner.collectAsState()
  val streaming = token != CcLiveGuard.NONE && owner == token
  var frame by remember(cameraId) { mutableStateOf<CcFrame?>(null) }
  var failed by remember(cameraId) { mutableStateOf(false) }
  // A parked (kept-alive) screen keeps its last picture but fetches nothing.
  if (configured && onScreen) {
    if (streaming) LiveFeed(cameraId, { frame = it; failed = false }, { failed = true })
    else SnapshotLoop(cameraId, { frame = it; failed = false }, { if (frame == null) failed = true })
  }
  val name = label ?: s.entity(cameraId)?.name ?: CcEntity.prettyId(cameraId)
  Box(modifier.graphicsLayer().clip(RoundedCornerShape(size.radius)).background(TILE_BG)
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
      .semantics { contentDescription = "$name camera${if (streaming) ", live" else ""}" }) {
    val f = frame
    if (f != null) Image(f.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    else Placeholder(when { !configured -> "Home Assistant isn't set up"; failed -> "Camera unavailable"; else -> "Loading camera…" })
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x8C000000))))
    if (f != null) Text(CcCameraLogic.timestamp(f.at), style = overlay(13), fontFamily = CcStyle.Mono, fontWeight = FontWeight.SemiBold,
        color = Color(0xD9FFFFFF), modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp))
    if (streaming && f != null) Text("LIVE", color = Color.White, fontSize = size.tag.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = size.tagInset, end = size.tagInset).clip(RoundedCornerShape(6.dp))
            .background(LIVE_RED).padding(horizontal = 10.dp, vertical = 5.dp))
    Text(name, style = overlay(size.label), fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = size.labelInset, bottom = size.labelInset * LABEL_BOTTOM))
  }
}

@Composable
private fun Placeholder(text: String) {
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151A24), Color(0xFF0B0D10)))), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CcIcon("cam", Color(0x80FFFFFF), 44.dp)
      Text(text, color = Color(0x99FFFFFF), fontSize = 19.sp)
    }
  }
}

/** Snapshots every [CcCameraLogic.SNAPSHOT_INTERVAL_MS] while the activity is started and this tile is composed. */
@Composable
private fun SnapshotLoop(cameraId: String, onFrame: (CcFrame) -> Unit, onFail: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(cameraId, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val image = withContext(Dispatchers.IO) { fetchSnapshot(context, cameraId) }
        if (image != null) onFrame(CcFrame(image, System.currentTimeMillis())) else onFail()
        delay(CcCameraLogic.SNAPSHOT_INTERVAL_MS)
      }
    }
  }
}

private fun fetchSnapshot(context: Context, cameraId: String): ImageBitmap? {
  if (!HaConfig.isConfigured(context)) return null
  val bytes = Http.getBytes(CcCameraLogic.snapshotUrl(HaConfig.baseUrl(context), cameraId), HaConfig.authHeaders(context)) ?: return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0) return null
  val opts = BitmapFactory.Options().apply { inSampleSize = CcCameraLogic.sampleSize(bounds.outWidth) }
  return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() }.getOrNull()
}

/** The MJPEG stream (with HaCameraFeed's own snapshot fallback), running only while started. */
@Composable
private fun LiveFeed(cameraId: String, onFrame: (CcFrame) -> Unit, onUnavailable: () -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, _ -> started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  DisposableEffect(cameraId, started) {
    val feed = if (!started) null else HaCameraFeed(context, cameraId,
        onFrame = { b -> onFrame(CcFrame(b.asImageBitmap(), System.currentTimeMillis())) }, onUnavailable = onUnavailable)
    feed?.start()
    onDispose { feed?.stop() }
  }
}

private fun overlay(size: Int) = TextStyle(fontSize = size.sp, shadow = Shadow(Color(0x99000000), blurRadius = 4f))

private val TILE_BG = Color(0xFF0B0D10)
private val LIVE_RED = Color(0xEBD33D4C)
private const val LABEL_BOTTOM = 0.82f
