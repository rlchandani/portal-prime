/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A camera picture for any screen (`.thumb`): a snapshot every 5 s while on screen, or a live
 * stream when [live] (HLS or MJPEG; one live stream Portal-wide, see [CcLiveStream]; an older live
 * tile falls back to snapshots). It says LIVE only while the pictures actually change. Kids mode hides cameras entirely; without HA it shows why.
 * A thin wrapper: it reads the few primitives the picture needs, so the tile skips on HA updates.
 */
@Composable
internal fun CcCameraTile(s: CcUiState, cameraId: String, live: Boolean, modifier: Modifier = Modifier,
                          label: String? = null, onClick: (() -> Unit)? = null) {
  if (ccCamerasHidden(s.settings)) return
  val name = label ?: s.entity(cameraId)?.name ?: CcEntity.prettyId(cameraId)
  CcCameraPicture(cameraId, name, live, s.connection.phase != CcPhase.NOT_CONFIGURED, modifier, onClick, CcCameraTileSize.GRID)
}

/** What a camera picture needs from the state, so a card of pictures skips on HA updates. */
@androidx.compose.runtime.Immutable
internal data class CcCameraFeedState(val hidden: Boolean, val configured: Boolean) {
  companion object {
    fun of(s: CcUiState) = CcCameraFeedState(ccCamerasHidden(s.settings), s.connection.phase != CcPhase.NOT_CONFIGURED)
  }
}

/** Kids mode on a Portal that hides cameras. */
internal fun ccCamerasHidden(settings: ControlCenterConfig.Settings): Boolean =
    settings.kids && CcPolicy.hiddenKind(CcKind.CAMERA, null, settings)

/** Label/tag sizes per context (`.camGrid .thumb`, `.camFull .thumb`, small strip thumbs). */
internal enum class CcCameraTileSize(val radius: Dp, val label: Int, val labelInset: Dp, val tag: Int, val tagInset: Dp) {
  GRID(30.dp, 24, 22.dp, 16, 18.dp),
  FULL(34.dp, 30, 30.dp, 16, 18.dp),
  SMALL(20.dp, 17, 12.dp, 13, 10.dp),
}

/** The camera's slice of the HA state for one tile; recomposes only when that camera's info changes. */
@Composable
internal fun rememberCamInfo(cameraId: String): State<CcCamInfo> {
  val context = LocalContext.current.applicationContext
  val flow = remember(cameraId) { ControlCenter.state(context).map { CcCamInfo.of(it, cameraId) }.distinctUntilChanged() }
  val initial = remember(cameraId) { CcCamInfo.of(ControlCenter.state(context).value, cameraId) }
  return flow.collectAsState(initial)
}

/**
 * The picture itself; sees only primitives, never the whole state. A [live] tile that owns the one
 * Portal-wide stream tries, in order: HA's HLS video (cameras HA can stream), the MJPEG proxy, then
 * snapshots; snapshots also fill in until a stream shows its first frame. Pass [holder] to share
 * what the picture knows (live or not, how old) with a caption next to it.
 */
@Composable
internal fun CcCameraPicture(cameraId: String, name: String, live: Boolean, configured: Boolean, modifier: Modifier,
                             onClick: (() -> Unit)?, size: CcCameraTileSize, holder: CcFrameHolder = remember(cameraId) { CcFrameHolder(cameraId) }) {
  val onScreen = LocalCcScreenActive.current
  var token by remember(cameraId, live) { mutableLongStateOf(CcLiveGuard.NONE) }
  DisposableEffect(cameraId, live, configured, onScreen) {
    val claimed = if (live && configured && onScreen) CcLiveStream.claim() else CcLiveGuard.NONE
    token = claimed
    onDispose { CcLiveStream.release(claimed) }
  }
  val owner by CcLiveStream.owner.collectAsState()
  val streaming = token != CcLiveGuard.NONE && owner == token
  val info by rememberCamInfo(cameraId)
  var source by remember(cameraId, streaming, info.canStream) { mutableStateOf(firstSource(cameraId, streaming, info)) }
  val width = remember(cameraId) { mutableIntStateOf(0) }
  // The tile is one accessibility node; its status changes at most every SPEECH_MS (derived, so a
  // new frame alone recomposes nothing).
  val speechNow = rememberCamNow(SPEECH_MS)
  val status by remember(holder) {
    derivedStateOf {
      when { holder.hasFrame -> CcCamLiveness.speech(holder.watch, speechNow.value); holder.failed -> "unavailable"; else -> "loading" }
    }
  }
  val captured = info.captured
  Box(modifier.graphicsLayer().clip(RoundedCornerShape(size.radius)).background(TILE_BG).onSizeChanged { width.intValue = it.width }
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
      .ccSpeak("$name camera, $status")) {
    val has = holder.hasFrame
    if (holder.hasImage) FrameLayer(holder)
    // A parked (kept-alive) screen keeps its last picture but fetches nothing.
    if (configured && onScreen) {
      when (source) {
        CcCamSource.HLS -> CcHlsVideo(cameraId, holder) { source = afterHls(cameraId) }
        CcCamSource.MJPEG -> CcMjpegFeed(cameraId, holder, captured) {
          CcCamMemory.mjpegFailed(cameraId, System.currentTimeMillis()); source = CcCamSource.SNAPSHOT
        }
        CcCamSource.SNAPSHOT -> Unit
      }
      if (source == CcCamSource.SNAPSHOT || !holder.streamFeeding) CcSnapshotLoop(cameraId, holder, captured)
    }
    if (!has) Placeholder(when { !configured -> "Home Assistant isn't set up"; holder.failed -> "Camera unavailable"; else -> "Loading camera…" })
    Box(Modifier.fillMaxSize().background(SHADE))
    if (has) CamStatus(holder, size, Modifier.align(Alignment.TopEnd), compact = { width.intValue < COMPACT_PX })
    Text(name, style = overlay(size.label), fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = size.labelInset, bottom = size.labelInset * LABEL_BOTTOM))
  }
}

/** HLS when HA can stream it, else MJPEG unless it recently gave nothing, else snapshots. */
private fun firstSource(cameraId: String, streaming: Boolean, info: CcCamInfo): CcCamSource = when {
  !streaming || info.unavailable -> CcCamSource.SNAPSHOT
  info.canStream -> CcCamSource.HLS
  CcCamMemory.mjpegWorthTrying(cameraId, System.currentTimeMillis()) -> CcCamSource.MJPEG
  else -> CcCamSource.SNAPSHOT
}

private fun afterHls(cameraId: String): CcCamSource =
    if (CcCamMemory.mjpegWorthTrying(cameraId, System.currentTimeMillis())) CcCamSource.MJPEG else CcCamSource.SNAPSHOT

/** Centre-crops the latest frame in its own layer; reading the frame here only re-records this layer. */
@Composable
private fun BoxScope.FrameLayer(holder: CcFrameHolder) {
  Box(Modifier.matchParentSize().graphicsLayer().drawBehind {
    val f = holder.frame ?: return@drawBehind
    val crop = CcCameraLogic.centerCrop(f.image.width, f.image.height, size.width.toInt(), size.height.toInt())
    drawImage(f.image, IntOffset(crop.x, crop.y), IntSize(crop.w, crop.h), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
  })
}

/**
 * The tag and the burnt-in stamp, from what the pictures actually did: LIVE only while they keep
 * changing; otherwise "Snapshot · 3 h ago" and a stamp that shows when the picture was taken (and
 * stands still). With no capture time from HA there is no stamp — never a clock pretending to be one.
 */
@Composable
private fun BoxScope.CamStatus(holder: CcFrameHolder, size: CcCameraTileSize, modifier: Modifier, compact: () -> Boolean) {
  val now by rememberCamNow()
  val label = CcCamLiveness.label(holder.watch, now)
  val small = compact()
  // Small thumbnails keep the tag and skip the stamp, which would run into it.
  if (!small) label.stampMs?.let { FrameStamp(it) }
  CamTag(label, size, small, modifier)
}

/** The stamp, drawn rather than composed so a new second only re-records this layer. */
@Composable
private fun BoxScope.FrameStamp(ms: Long) {
  val format = remember { CcCameraLogic.timestampFormat() }
  val measurer = rememberTextMeasurer(cacheSize = STAMP_CACHE)
  val style = remember { STAMP.merge(TextStyle(color = Color(0xD9FFFFFF), fontFamily = CcStyle.Mono, fontWeight = FontWeight.SemiBold)) }
  val second = ms / MS_PER_S
  Box(Modifier.matchParentSize().graphicsLayer().drawBehind {
    val text = format.format(java.util.Date(second * MS_PER_S))
    drawText(measurer.measure(text, style), topLeft = Offset(STAMP_X.dp.toPx(), STAMP_Y.dp.toPx()))
  })
}

/** Red LIVE while frames arrive; a dark "SNAPSHOT · 3 H AGO" chip otherwise. */
@Composable
private fun CamTag(label: CcCamLabel, size: CcCameraTileSize, compact: Boolean, modifier: Modifier) {
  val text = if (label.live) "LIVE" else (if (compact) label.text.substringAfter(SNAPSHOT_PREFIX) else label.text).uppercase()
  Text(text, color = Color.White, fontSize = size.tag.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1,
      modifier = modifier.padding(top = size.tagInset, end = size.tagInset).clip(RoundedCornerShape(6.dp))
          .background(if (label.live) LIVE_RED else SNAPSHOT_BG).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun Placeholder(text: String) {
  Box(Modifier.fillMaxSize().background(PLACEHOLDER), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CcIcon("cam", Color(0x80FFFFFF), 44.dp)
      Text(text, color = Color(0x99FFFFFF), fontSize = 19.sp)
    }
  }
}

private fun overlay(size: Int) = TextStyle(fontSize = size.sp, shadow = Shadow(Color(0x99000000), blurRadius = 4f))

private val STAMP = overlay(13)
private val TILE_BG = Color(0xFF0B0D10)
private val LIVE_RED = Color(0xEBD33D4C)
private val SNAPSHOT_BG = Color(0xB3141820)
private val SHADE = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x8C000000))
private val PLACEHOLDER = Brush.verticalGradient(listOf(Color(0xFF151A24), Color(0xFF0B0D10)))
private const val LABEL_BOTTOM = 0.82f
/** Below this width (px) a tile shows "3 H AGO" instead of "SNAPSHOT · 3 H AGO" and no stamp. */
private const val COMPACT_PX = 560
private const val SPEECH_MS = 30_000L
private const val SNAPSHOT_PREFIX = "Snapshot · "
private const val MS_PER_S = 1000L
private const val STAMP_X = 12
private const val STAMP_Y = 10
private const val STAMP_CACHE = 4
