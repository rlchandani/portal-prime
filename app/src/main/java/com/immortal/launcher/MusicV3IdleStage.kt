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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtPlaylist

/** Recently played tiles on the idle screen. */
private const val IDLE_RECENT_TILES = 6

/** What the idle (nothing loaded) screen needs. */
internal data class MusicV3IdleInputs(
    val library: YouTubeMusicLibrary?,
    val accountId: String?,
    val actions: MusicV3Actions,
    val follow: MusicFollowAsk?,
    val quietNote: String?,
    val now: Long,
    val onOpenPlaylist: (YtPlaylist) -> Unit,
    val onOpenLibrary: () -> Unit,
)

/** Nothing loaded: a big Resume card where the artwork goes (follow-me takes it over), then shelves. */
@Composable
internal fun MusicV3IdleResumeCard(layout: MusicStageLayout, inputs: MusicV3IdleInputs, modifier: Modifier = Modifier) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val spec = layout.spec
  val plays by MusicHistoryFeature.plays.collectAsState()
  val last = remember(plays) { MusicHistory.listing(plays).firstOrNull() }
  val shape = RoundedCornerShape(if (spec.compact) 28.dp else 40.dp)
  Column(modifier.clip(shape).background(ink.fill).border(1.dp, ink.line, shape)
      .padding(if (spec.compact) 24.dp else 44.dp), verticalArrangement = Arrangement.Center) {
    val follow = inputs.follow
    val art = spec.resumeArt.dp
    when {
      follow != null -> {
        Box(Modifier.size(art).clip(RoundedCornerShape(26.dp)).background(ink.fill2), contentAlignment = Alignment.Center) {
          MusicV3GlyphIcon(MusicV3Glyph.FOLLOW, ink.ember, Modifier.size(art / 3))
        }
        Kicker(MusicV3Glyph.FOLLOW, null, follow.line, ember = true)
        Title(follow.title)
        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          MusicStagePill(MusicV3StageCopy.CONTINUE, style = MusicPillStyle.EMBER, glyph = MusicV3Glyph.PLAY) {
            MusicV3FollowMeInScreen.answer(follow, continueHere = true)
          }
          MusicStagePill(MusicV3StageCopy.NOT_NOW) { MusicV3FollowMeInScreen.answer(follow, continueHere = false) }
        }
      }
      last != null -> {
        val track = remember(last) { last.toDataTrack() }
        MusicV3Thumb(track.thumbnailUrl, Modifier.size(art), RoundedCornerShape(26.dp), displaySize = art)
        Kicker(null, "clock", "Last played · ${MusicHistory.where(last, inputs.now)}", ember = false)
        Title(last.title)
        Text(last.artist, color = ink.muted, fontSize = dims.small.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        MusicStagePill(MusicV3StageCopy.RESUME, Modifier.padding(top = 26.dp), style = MusicPillStyle.SOLID,
            glyph = MusicV3Glyph.PLAY) { inputs.actions.playOne(track, MUSIC_RECENT_LABEL) }
      }
      else -> {
        Title(MusicV3StageCopy.NOTHING_PLAYING)
        Text("Pick a playlist, or search for a song.", color = ink.muted, fontSize = dims.small.sp,
            modifier = Modifier.padding(top = 6.dp))
        MusicStagePill(MusicV3StageCopy.OPEN_LIBRARY, Modifier.padding(top = 26.dp), style = MusicPillStyle.SOLID,
            glyph = MusicV3Glyph.GRID, onClick = inputs.onOpenLibrary)
      }
    }
  }
}

/** Right column with nothing loaded: "Nothing playing", then Recently played and playlist shelves. */
@Composable
internal fun MusicV3IdleColumn(inputs: MusicV3IdleInputs, modifier: Modifier = Modifier) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val plays by MusicHistoryFeature.plays.collectAsState()
  val recent = remember(plays) { MusicHistory.listing(plays).take(IDLE_RECENT_TILES) }
  Column(modifier, verticalArrangement = Arrangement.Center) {
    Text(MusicV3StageCopy.NOTHING_PLAYING, color = ink.ink, fontSize = dims.bigTitle.sp, fontWeight = FontWeight.ExtraBold)
    Text(MusicV3StageCopy.NOTHING_PLAYING_SUB, color = ink.muted, fontSize = dims.small.sp, modifier = Modifier.padding(top = 8.dp))
    if (recent.isNotEmpty()) {
      MusicStageHeading(MUSIC_RECENT_LABEL, Modifier.padding(top = if (dims == MusicStageDims.COMPACT) 14.dp else 36.dp))
      MusicV3Shelf(recent, key = { it.videoId }) { play ->
        val track = remember(play) { play.toDataTrack() }
        val model = remember(play, inputs.now) {
          MusicV3TileModel(play.videoId, play.title, MusicHistory.where(play, inputs.now), track.thumbnailUrl, spoken = "Play ${play.title}")
        }
        MusicV3Tile(model, dims.idleTile.dp, onClick = { inputs.actions.playOne(track, MUSIC_RECENT_LABEL) })
      }
    }
    val library = inputs.library
    val account = inputs.accountId
    if (library != null && account != null) {
      val load = rememberLibraryLoad(account to "playlists") { library.playlists() }
      (load as? MusicV3Load.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { playlists ->
        MusicStageHeading("Your playlists", Modifier.padding(top = if (dims == MusicStageDims.COMPACT) 10.dp else 28.dp))
        MusicV3PlaylistsShelf(library, account, playlists, inputs.actions, inputs.onOpenPlaylist)
      }
    }
    inputs.quietNote?.let { MusicStageNote(it, MusicV3Glyph.HUSH, modifier = Modifier.padding(top = 18.dp)) }
  }
}

@Composable
private fun Kicker(glyph: MusicV3Glyph?, icon: String?, text: String, ember: Boolean) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val tint = if (ember) ink.ember else ink.muted
  Row(Modifier.padding(top = if (dims == MusicStageDims.COMPACT) 18.dp else 36.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    glyph?.let { MusicV3GlyphIcon(it, tint, Modifier.size(28.dp)) }
    icon?.let { ApprovedIcon(it, Modifier.size(28.dp), tint) }
    Text(text.uppercase(), color = tint, fontSize = dims.small.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun Title(text: String) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Text(text, color = ink.ink, fontSize = (dims.title * 0.62f).sp, lineHeight = (dims.title * 0.68f).sp,
      fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
}
