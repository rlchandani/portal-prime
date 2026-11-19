/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How the other people sit in a Grandma-mode call (this Portal is the self view, not a tile). */
internal enum class GrandmaTiles { ONE, TWO_SIDE, TWO_STACKED, THREE_SIDE, THREE_STACKED }

/** Grandma-mode group rules (pure, JVM-tested): who gets a big face, and how they sit. */
internal object GrandmaGroup {
  /** Every other person in the call, the first one (who called or was called) first. */
  fun members(call: ActiveCall): List<CallTile.Member> = CallTiles.of(call).filterIsInstance<CallTile.Member>()

  fun arrangement(members: Int, layout: LayoutClass): GrandmaTiles = when {
    members <= 1 -> GrandmaTiles.ONE
    members == 2 -> if (layout.compactPortrait) GrandmaTiles.TWO_STACKED else GrandmaTiles.TWO_SIDE
    else -> if (layout.compactPortrait) GrandmaTiles.THREE_STACKED else GrandmaTiles.THREE_SIDE
  }

  /** "Sam", "Sam and Alex", "Sam, Alex and Mia": the live bar's "with …". */
  fun with(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
  }

  /** The self view in a group sits beside the live bar, so it is smaller than in a 1:1 call. */
  fun self(layout: LayoutClass): CallsCompactLayout.Box = when (layout) {
    LayoutClass.LARGE -> CallsCompactLayout.Box(270, 180)
    LayoutClass.COMPACT_LANDSCAPE -> CallsCompactLayout.Box(195, 130)
    LayoutClass.COMPACT_PORTRAIT -> CallsCompactLayout.Box(150, 200)
  }

  /** Big-face sizes per tile: avatar and name stay Grandma-sized on the Mini. */
  data class Face(val avatar: Dp, val name: Float, val status: Float)

  fun face(layout: LayoutClass, big: Boolean): Face = when {
    !layout.compact -> if (big) Face(220.dp, 52f, 30f) else Face(170.dp, 44f, 26f)
    else -> if (big) Face(150.dp, 40f, 24f) else Face(120.dp, 34f, 22f)
  }
}

/**
 * Grandma mode with several people (mock `gmIncallScr` + `.gface`): every other person gets a big
 * rounded face tile with their picture, or their avatar while ringing, connecting or with the camera
 * off, and a big name. Three others: one big face and two beside it (upright Mini: below).
 */
@Composable
internal fun GrandmaGroupStage(call: ActiveCall, media: CallMedia?, motion: Boolean, layout: LayoutClass, sizes: GrandmaSizes,
                               modifier: Modifier = Modifier) {
  val members = GrandmaGroup.members(call)
  val gap = sizes.gap
  val tile: @Composable (Int, Modifier) -> Unit = { i, m -> GrandmaFaceTile(members[i], media, motion, layout, sizes, i == 0 && members.size > 2, m) }
  Box(modifier) {
    when (GrandmaGroup.arrangement(members.size, layout)) {
      GrandmaTiles.ONE -> tile(0, Modifier.fillMaxSize())
      GrandmaTiles.TWO_SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize())
      }
      GrandmaTiles.TWO_STACKED -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize())
      }
      GrandmaTiles.THREE_SIDE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(CallTiles.BIG_SHARE).fillMaxSize())
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
          tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize())
        }
      }
      GrandmaTiles.THREE_STACKED -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        tile(0, Modifier.weight(CallTiles.BIG_SHARE).fillMaxSize())
        Row(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
          tile(1, Modifier.weight(1f).fillMaxSize()); tile(2, Modifier.weight(1f).fillMaxSize())
        }
      }
    }
  }
}

@Composable
private fun GrandmaFaceTile(tile: CallTile.Member, media: CallMedia?, motion: Boolean, layout: LayoutClass, sizes: GrandmaSizes,
                            big: Boolean, modifier: Modifier) {
  val ringing = tile.state == CallTile.State.RINGING
  val sink = if (ringing) null else media?.sink(tile.contact.deviceId)
  var hasFrames by remember(sink) { mutableStateOf(false) }
  LaunchedEffect(sink) { while (sink != null && !hasFrames) { hasFrames = sink.frames > 0; delay(FRAME_POLL_MS) } }
  val face = GrandmaGroup.face(layout, big)
  val status = when {
    ringing -> "Ringing…"
    tile.state == CallTile.State.CONNECTING -> "Connecting…"
    tile.state == CallTile.State.RECONNECTING -> "Reconnecting…"
    hasFrames && !tile.cameraOn -> "Camera off"
    else -> null
  }
  Box(modifier.clip(RoundedCornerShape(sizes.radius)).background(CallColors.RingBg)
      .semantics { contentDescription = listOfNotNull(tile.contact.name, status, if (!tile.micOn) "microphone off" else null).joinToString(", ") }) {
    // SurfaceView per face (no app redraw per video frame); [CornerMask] paints the rounded corners over it.
    if (media != null && sink != null) CallRemoteVideo(media, Modifier.fillMaxSize(), sink = sink)
    val showFace = ringing || !hasFrames || !tile.cameraOn
    if (showFace) {
      Box(Modifier.fillMaxSize().alpha(if (ringing) RINGING_DIM else 1f)) { CallRingBackground(tile.contact.name, motion && ringing) }
      Column(Modifier.align(Alignment.Center).padding(bottom = (face.name * 2).dp), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (ringing) CallRipple(face.avatar, motion) { CallRoomAvatar(tile.contact.name, face.avatar) }
        else CallRoomAvatar(tile.contact.name, face.avatar)
        status?.let { Text(it, color = Color.White, fontSize = face.status.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
      }
    }
    CornerMask(sizes.radius)
    FaceName(tile, face, showFace, Modifier.align(Alignment.BottomCenter))
  }
}

/** `.gface .nm`: the big name, on a dark pill over video so it reads on any picture. */
@Composable
private fun FaceName(tile: CallTile.Member, face: GrandmaGroup.Face, overAvatar: Boolean, modifier: Modifier) {
  Row(modifier.padding(bottom = 22.dp, start = 18.dp, end = 18.dp)
      .then(if (overAvatar) Modifier else Modifier.background(CallColors.Bar, RoundedCornerShape(50)))
      .padding(horizontal = 26.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    if (!tile.micOn && tile.state != CallTile.State.RINGING) {
      Box(Modifier.size((face.name * 1.2f).dp).background(CallColors.Bad, CircleShape), contentAlignment = Alignment.Center) {
        CallIcon("micOff", Color.White, (face.name * 0.7f).dp)
      }
    }
    Text(tile.contact.name, color = Color.White, fontSize = face.name.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

private const val RINGING_DIM = 0.55f
private const val FRAME_POLL_MS = 150L
