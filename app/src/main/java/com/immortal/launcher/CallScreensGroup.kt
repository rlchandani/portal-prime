/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Group call tiles (mock `.tiles`): 3 people = one big tile and two stacked (upright Mini: big
 * on top, two below), 4 people = 2×2; this Portal's own tile is part of the grid. Each member's
 * picture renders in its own rounded TextureView tile; a member still being rung shows a dimmed
 * ripple card.
 */
@Composable
internal fun CallGroupStage(call: ActiveCall, media: CallMedia?, motion: Boolean, layout: LayoutClass, modifier: Modifier = Modifier,
                            probe: Boolean = false) {
  val tiles = CallTiles.of(call, probe)
  val gap = if (layout.compact) COMPACT_GAP else GAP
  val box = Modifier.fillMaxSize().background(CallColors.VideoBg).padding(gap)
  val tile: @Composable (Int, Modifier) -> Unit = { i, m -> GroupTile(tiles[i], media, motion, layout, m) }
  Box(modifier.then(box)) {
    when (CallTiles.arrangement(tiles.size, layout)) {
      TileArrangement.THREE_SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(CallTiles.BIG_SHARE).fillMaxSize())
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
          tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize())
        }
      }
      TileArrangement.THREE_STACKED -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(CallTiles.BIG_SHARE).fillMaxSize())
        Row(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
          tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize())
        }
      }
      else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0 until 2) Row(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
          for (col in 0 until 2) {
            val i = row * 2 + col
            if (i < tiles.size) tile(i, Modifier.weight(1f).fillMaxSize()) else Box(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

@Composable
private fun GroupTile(tile: CallTile, media: CallMedia?, motion: Boolean, layout: LayoutClass, modifier: Modifier) {
  val shape = RoundedCornerShape(if (layout.compact) COMPACT_RADIUS else RADIUS)
  Box(modifier.clip(shape).background(CallColors.RingBg)) {
    when (tile) {
      is CallTile.Self -> SelfTile(tile, media, layout)
      is CallTile.Member -> MemberTile(tile, media, motion, layout)
    }
  }
}

@Composable
private fun BoxScope.SelfTile(tile: CallTile.Self, media: CallMedia?, layout: LayoutClass) {
  // Visible whenever the camera is open: Portal's camera service closes a camera without a preview.
  if (media != null && tile.cameraOn) CallTileVideo(media, media.local, Modifier.fillMaxSize(), mirror = true)
  if (!tile.cameraOn) CallShutter(Modifier.fillMaxSize(), compact = true)
  TileLabel(if (tile.cameraOn) CallingCopy.SELF_LABEL_SHORT else CallingCopy.CAMERA_OFF, layout,
      Modifier.align(Alignment.TopEnd))
}

@Composable
private fun BoxScope.MemberTile(tile: CallTile.Member, media: CallMedia?, motion: Boolean, layout: LayoutClass) {
  val ringing = tile.state == CallTile.State.RINGING
  val sink = if (ringing) null else media?.sink(tile.contact.deviceId)
  var hasFrames by remember(sink) { mutableStateOf(false) }
  LaunchedEffect(sink) { while (sink != null && !hasFrames) { hasFrames = sink.frames > 0; delay(FRAME_POLL_MS) } }
  if (media != null && sink != null) CallTileVideo(media, sink, Modifier.fillMaxSize())
  val avatar = if (layout.compact) COMPACT_AVATAR else AVATAR
  if (ringing || !hasFrames || !tile.cameraOn) {
    Box(Modifier.fillMaxSize().alpha(if (ringing) RINGING_DIM else 1f)) { CallRingBackground(tile.contact.name, motion && ringing) }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
      if (ringing) CallRipple(avatar, motion) { CallRoomAvatar(tile.contact.name, avatar) } else CallRoomAvatar(tile.contact.name, avatar)
      val status = when {
        ringing -> "Ringing ${tile.contact.name}…"
        tile.state == CallTile.State.CONNECTING -> "Connecting securely…"
        tile.state == CallTile.State.RECONNECTING -> "Reconnecting…"
        !tile.cameraOn -> "${tile.contact.name}’s camera is off"
        else -> null
      }
      status?.let { Text(it, color = Color.White, fontSize = if (layout.compact) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold,
          maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
  }
  TileLabel(tile.contact.name, layout, Modifier.align(Alignment.TopEnd))
  if (!ringing && !tile.micOn) Box(Modifier.align(Alignment.BottomStart).padding(if (layout.compact) 16.dp else 24.dp)
      .size(if (layout.compact) 52.dp else 64.dp).background(CallColors.Bar, CircleShape), contentAlignment = Alignment.Center) {
    CallIcon("micOff", Color.White, if (layout.compact) 26.dp else 32.dp)
  }
}

/** `.tiles .feed .lbl`: the name pill, top-right of each tile. */
@Composable
private fun TileLabel(text: String, layout: LayoutClass, modifier: Modifier) {
  val edge: Dp = if (layout.compact) 16.dp else 22.dp
  Text(text, color = Color.White, fontSize = if (layout.compact) 19.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
      overflow = TextOverflow.Ellipsis, modifier = modifier.padding(top = edge, end = edge + 4.dp)
          .background(CallColors.Bar, RoundedCornerShape(50)).border(1.dp, CallColors.GlassChipEdge, RoundedCornerShape(50))
          .padding(horizontal = 18.dp, vertical = 8.dp))
}

private val GAP = 10.dp
private val COMPACT_GAP = 8.dp
private val RADIUS = 26.dp
private val COMPACT_RADIUS = 22.dp
private val AVATAR = 150.dp
private val COMPACT_AVATAR = 110.dp
private const val RINGING_DIM = 0.55f
private const val FRAME_POLL_MS = 150L
