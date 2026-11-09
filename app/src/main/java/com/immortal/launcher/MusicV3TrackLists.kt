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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

private val DETAIL_ART = 112.dp

/** An opened playlist or artist: header with Play / Shuffle, then its tracks. */
@Composable
internal fun MusicV3TrackDetail(
    title: String,
    subtitle: String,
    art: String?,
    round: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    load: MusicV3Load<List<YtDataTrack>>,
    actions: MusicV3Actions,
    label: String,
    onSignInAgain: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    val tracks = (load as? MusicV3Load.Ready)?.value.orEmpty()
    MusicV3DetailHeader(title, subtitle, art, round, backLabel, onBack, tracks, actions, label)
    LoadOrProblem(load, onSignInAgain) { ready -> TrackRows(ready, actions, label) }
  }
}

/** Back · art · title/subtitle · [toggle] · Play · Shuffle. */
@Composable
internal fun MusicV3DetailHeader(
    title: String,
    subtitle: String,
    art: String?,
    round: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    tracks: List<YtDataTrack>,
    actions: MusicV3Actions,
    label: String,
    toggle: Pair<String, () -> Unit>? = null,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    MusicV3RoundButton(backLabel, size = dims.target.dp, fill = ink.fill2, onClick = onBack) {
      ApprovedIcon("back", Modifier.size(30.dp), ink.ink)
    }
    if (art != null) MusicV3Thumb(art, Modifier.size(DETAIL_ART), if (round) CircleShape else RoundedCornerShape(20.dp), DETAIL_ART)
    Column(Modifier.weight(1f)) {
      Text(title, color = ink.ink, fontSize = dims.heading.sp, fontWeight = FontWeight.Bold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      Text(subtitle, color = ink.muted, fontSize = dims.small.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
    toggle?.let { (text, run) -> MusicStagePill(text, onClick = run) }
    val enabled = tracks.isNotEmpty()
    MusicStagePill("Play", style = MusicPillStyle.SOLID, glyph = MusicV3Glyph.PLAY, enabled = enabled) {
      actions.playAll(tracks, label, shuffle = false)
    }
    MusicStagePill("Shuffle", glyph = MusicV3Glyph.SHUFFLE, enabled = enabled) { actions.playAll(tracks, label, shuffle = true) }
  }
}

/**
 * Rows for library/search lists; a tap plays from that song, ⋯ offers Play next / Add to queue.
 * Keys are the video ids (a repeat gets "#2"), never the list index; row models, menus and taps
 * are remembered per key so scrolling recomposes only rows that enter the screen.
 */
@Composable
internal fun TrackRows(tracks: List<YtDataTrack>, actions: MusicV3Actions, label: String,
    onTap: ((YtDataTrack) -> Unit)? = null) {
  val keys = remember(tracks) { MusicV3RowKeys.unique(tracks.map { it.videoId }) }
  val tap by rememberUpdatedState(onTap)
  LazyColumn(Modifier.fillMaxSize()) {
    items(tracks.size, key = { keys[it] }, contentType = { MusicV3RowKeys.TRACK }) { i ->
      val t = tracks[i]
      val key = keys[i]
      val row = remember(key, t, label) { trackRowModel(t, "$label:$key") }
      val menu = remember(t, actions) {
        listOf(MusicV3RowAction("Play next") { actions.playNext(t) }, MusicV3RowAction("Add to queue") { actions.addToQueue(t) })
      }
      val play = remember(t, tracks, actions, label) { { tap?.invoke(t) ?: actions.playFrom(tracks, t, label) } }
      MusicV3TrackRow(row, onPlay = play, actions = menu)
    }
    item(key = "tail", contentType = MusicV3RowKeys.NOTE) { Spacer(Modifier.height(40.dp)) }
  }
}

/** The immutable row model for a Data API track. */
internal fun trackRowModel(t: YtDataTrack, key: String) =
    MusicV3RowModel(key, t.title, t.artist, t.thumbnailUrl, t.durationMs)
