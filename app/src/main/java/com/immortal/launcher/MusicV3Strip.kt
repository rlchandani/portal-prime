/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val STRIP_TITLE = 0.54f
private const val STRIP_ARTIST = 0.7f

/** What the open sheet's header strip shows to the right of the player. */
internal data class MusicV3StripInputs(
    val move: MusicV3MoveControls,
    val follow: MusicFollowAsk?,
    /** Search has focus: one line only, so the strip can shrink. */
    val searching: Boolean,
    /** Nothing loaded: play the newest Recently played song (null when there is none). */
    val onResume: (() -> Unit)?,
)

/**
 * Header strip while the sheet is open: status, title and artist beside the YouTube player (no
 * second, smaller cover: the player already shows the song), then play/pause and skip. A tap or a
 * drag down on it closes the sheet (the caller attaches that gesture).
 */
@Composable
internal fun MusicV3Strip(now: MusicNowSlice, inputs: MusicV3StripInputs, modifier: Modifier = Modifier) {
  val dims = LocalMusicStageDims.current
  val compact = dims == MusicStageDims.COMPACT
  Row(modifier, verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 28.dp)) {
    val follow = inputs.follow
    when {
      follow != null -> {
        StripText(follow.line, follow.title, null, inputs.searching, Modifier.weight(1f), MusicV3Glyph.FOLLOW, ember = true)
        MusicStagePill(MusicV3StageCopy.CONTINUE, style = MusicPillStyle.EMBER, glyph = MusicV3Glyph.PLAY,
            onClick = { MusicV3FollowMeInScreen.answer(follow, continueHere = true) })
        MusicStagePill(MusicV3StageCopy.NOT_NOW, onClick = { MusicV3FollowMeInScreen.answer(follow, continueHere = false) })
      }
      now.idle -> {
        StripText(null, MusicV3StageCopy.NOTHING_PLAYING, "Pick something below", inputs.searching, Modifier.weight(1f))
        inputs.onResume?.let {
          MusicStagePill(MusicV3StageCopy.RESUME, style = MusicPillStyle.SOLID, glyph = MusicV3Glyph.PLAY, onClick = it)
        }
      }
      else -> Playing(now, inputs, compact, Modifier.weight(1f))
    }
  }
}

@Composable
private fun Playing(now: MusicNowSlice, inputs: MusicV3StripInputs, compact: Boolean, textModifier: Modifier) {
  val dims = LocalMusicStageDims.current
  val ink = LocalMusicStageInk.current
  val arrival by MusicShare.arrival.collectAsState()
  val model = now.model
  val handed = inputs.move.state is MusicMoveState.Handed
  val kicker = when {
    model.isAd -> "Ad"
    else -> inputs.move.banner ?: arrival ?: model.statusLine
  }
  val sub = if (model.isAd) MusicV3NowPlaying.AD_LINE else model.track?.artist.orEmpty()
  Column(textModifier) {
    StripText(kicker, if (model.isAd) MusicV3StageCopy.AD_TITLE else model.track?.title.orEmpty(), sub, inputs.searching,
        Modifier, if (handed) MusicV3Glyph.MOVE else if (model.isAd) null else MusicV3Glyph.EQ)
    if (!inputs.searching && !model.isAd && !handed) MusicV3SleepLine(now.sleep, Modifier.padding(top = if (compact) 6.dp else 10.dp))
  }
  if (handed) {
    MusicStagePill(MusicV3StageCopy.BRING_BACK, style = MusicPillStyle.SOLID, icon = "back", onClick = inputs.move.onBringBack)
    return
  }
  val enabled = model.transportEnabled
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!compact) {
      MusicV3RoundButton("Previous", size = dims.stripSkip.dp, enabled = enabled, onClick = YouTubeMusicEngine::previous) {
        MusicV3GlyphIcon(MusicV3Glyph.PREVIOUS, ink.ink, Modifier.size((dims.skipGlyph * 0.72f).dp))
      }
    }
    MusicV3PlayPause(model.playing, dims.stripPlay, dims.playGlyph * 0.7f, enabled)
    MusicV3RoundButton("Next", size = dims.stripSkip.dp, enabled = enabled, onClick = YouTubeMusicEngine::next) {
      MusicV3GlyphIcon(MusicV3Glyph.NEXT, ink.ink, Modifier.size((dims.skipGlyph * 0.72f).dp))
    }
  }
}

@Composable
private fun StripText(
    kicker: String?,
    title: String,
    sub: String?,
    searching: Boolean,
    modifier: Modifier,
    glyph: MusicV3Glyph? = null,
    ember: Boolean = false,
) {
  val dims = LocalMusicStageDims.current
  val ink = LocalMusicStageInk.current
  Column(modifier) {
    if (kicker != null && !searching) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        glyph?.let { MusicV3GlyphIcon(it, if (ember || it == MusicV3Glyph.EQ) ink.ember else ink.muted, Modifier.size(26.dp)) }
        Text(kicker, color = if (ember) ink.ember else ink.muted, fontSize = dims.small.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Text(title, color = ink.ink, fontSize = (dims.title * STRIP_TITLE).sp, lineHeight = (dims.title * STRIP_TITLE * 1.12f).sp,
        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    if (sub != null && !searching) {
      Text(sub, color = ink.muted, fontSize = (dims.artist * STRIP_ARTIST).sp, fontWeight = FontWeight.Medium, maxLines = 1,
          overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
  }
}
