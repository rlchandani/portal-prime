/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paused artwork eases down to this size, anchored at its bottom edge (so it never nears the player). */
private const val PAUSED_SCALE = 0.86f
private const val MOVED_ALPHA = 0.5f
private val AdArt = Brush.linearGradient(listOf(Color(0xFF9A979C), Color(0xFF5D5A60)))

/** The closed Now Playing column's inputs besides the engine slice. */
internal data class MusicV3NowInputs(
    val room: String,
    val move: MusicV3MoveControls,
    val quietNote: String?,
    val onSleep: () -> Unit,
    val onToast: (String) -> Unit,
    /** Where the Move / Sleep capsules sit, so the sleep chips can open just above them. */
    val onUtilPlaced: (LayoutCoordinates) -> Unit,
)

/**
 * Closed Now Playing, right column: status, title, artist, a thin scrubber, the big transport,
 * volume, then Move to… and the sleep timer. Ads and "moved" dim what can't be used.
 */
@Composable
internal fun MusicV3NowColumn(now: MusicNowSlice, inputs: MusicV3NowInputs, modifier: Modifier = Modifier) {
  val dims = LocalMusicStageDims.current
  val ink = LocalMusicStageInk.current
  val arrival by MusicShare.arrival.collectAsState()
  val model = now.model
  val handed = inputs.move.state is MusicMoveState.Handed
  val compact = dims == MusicStageDims.COMPACT
  Column(modifier, verticalArrangement = Arrangement.Center) {
    Kicker(model, inputs.move.banner ?: arrival ?: model.statusLine, handed)
    Text(if (model.isAd) MusicV3StageCopy.AD_TITLE else model.track?.title.orEmpty(), color = ink.ink,
        fontSize = dims.title.sp, lineHeight = (dims.title * 1.1f).sp, fontWeight = FontWeight.Bold,
        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    Text(if (model.isAd) MusicV3NowPlaying.AD_LINE else model.track?.artist.orEmpty(), color = ink.muted,
        fontSize = dims.artist.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(if (compact) 18.dp else 44.dp))
    if (model.isAd) {
      Text(MusicV3NowPlaying.AD_NOTE, color = ink.muted, fontSize = dims.small.sp, modifier = Modifier.heightIn(min = 84.dp))
    } else {
      MusicV3Scrubber(enabled = !handed)
    }
    Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
    MusicV3Transport(model, now.queue, enabled = model.transportEnabled && !handed, onToast = inputs.onToast)
    Spacer(Modifier.height(if (compact) 6.dp else 22.dp))
    MusicV3StageVolume(inputs.room)
    Spacer(Modifier.height(if (compact) 8.dp else 22.dp))
    Row(Modifier.alpha(if (model.isAd) 0.32f else 1f).onGloballyPositioned(inputs.onUtilPlaced),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      MusicV3MoveButton(inputs.move)
      MusicV3SleepPill(now.sleep, enabled = model.hasTrack && !model.isAd && !handed, onClick = inputs.onSleep)
    }
    model.halt?.let { MusicStageNote("${it.title}. ${it.detail}", glyph = null, icon = "info", modifier = Modifier.padding(top = 18.dp)) }
    inputs.quietNote?.let { MusicStageNote(it, MusicV3Glyph.HUSH, modifier = Modifier.padding(top = if (compact) 10.dp else 18.dp)) }
  }
}

@Composable
private fun Kicker(model: MusicV3NowPlaying, status: String, handed: Boolean) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Row(Modifier.heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    val glyph = when {
      model.isAd -> null
      handed -> MusicV3Glyph.MOVE
      else -> MusicV3Glyph.EQ
    }
    glyph?.let { MusicV3GlyphIcon(it, if (it == MusicV3Glyph.EQ) ink.ember else ink.muted, Modifier.size(28.dp)) }
    Text(if (model.isAd) MusicV3StageCopy.AD_KICKER else status, color = ink.muted, fontSize = dims.small.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/**
 * The big artwork. Playing: full size; paused (or elsewhere): eases to [PAUSED_SCALE] from its
 * bottom edge with a springy settle. During an ad it shows a plain grey card instead of the song.
 */
@Composable
internal fun MusicV3HeroArt(
    track: YtTrack?,
    ad: Boolean,
    live: Boolean,
    moved: Boolean,
    size: Dp,
    radius: Dp,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
  val scale by animateFloatAsState(if (live) 1f else PAUSED_SCALE,
      if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "art-scale")
  val shape = RoundedCornerShape(radius)
  Box(modifier.size(size).graphicsLayer {
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 1f)
    alpha = if (moved) MOVED_ALPHA else 1f
  }.shadow(if (live) 40.dp else 16.dp, shape, clip = false)) {
    when {
      ad -> AdCard(shape)
      track != null -> MusicV3Cover(track, Modifier.fillMaxSize(), radius)
    }
  }
}

@Composable
private fun AdCard(shape: RoundedCornerShape) {
  val dims = LocalMusicStageDims.current
  Column(Modifier.fillMaxSize().clip(shape).background(AdArt).padding(if (dims == MusicStageDims.COMPACT) 30.dp else 56.dp),
      verticalArrangement = Arrangement.Bottom) {
    Text("Ad", color = Color.White.copy(alpha = 0.8f), fontSize = (dims.heading * 0.85f).sp, fontWeight = FontWeight.Bold)
    Text("Your music continues after this.", color = Color.White, fontSize = (dims.title * 0.66f).sp,
        lineHeight = (dims.title * 0.74f).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
  }
}
