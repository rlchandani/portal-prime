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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

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
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      MusicV3RoundButton(backLabel, fill = ApprovedHomePalette.Chrome, onClick = onBack) {
        ApprovedIcon("back", Modifier.size(30.dp), ApprovedHomePalette.Text)
      }
      MusicV3Thumb(art, Modifier.size(112.dp), if (round) CircleShape else RoundedCornerShape(20.dp))
      Column(Modifier.weight(1f)) {
        Text(title, color = ApprovedHomePalette.Text, fontSize = 36.sp, lineHeight = 44.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, modifier = Modifier.padding(top = 4.dp))
      }
      val tracks = (load as? MusicV3Load.Ready)?.value.orEmpty()
      PlayButtons(tracks, actions, label)
    }
    LoadOrProblem(load, onSignInAgain) { tracks -> TrackRows(tracks, actions, label) }
  }
}

/** Liked songs: a one-line summary with Play / Shuffle, then the tracks. */
@Composable
internal fun MusicV3TrackList(tracks: List<YtDataTrack>, summary: String, actions: MusicV3Actions, label: String) {
  if (tracks.isEmpty()) {
    MusicV3Empty("music", MusicV3Message("No liked songs yet", "Songs you like on YouTube show up here."))
    return
  }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(summary, color = ApprovedHomePalette.Secondary, fontSize = 23.sp, modifier = Modifier.weight(1f))
      PlayButtons(tracks, actions, label)
    }
    TrackRows(tracks, actions, label)
  }
}

@Composable
private fun PlayButtons(tracks: List<YtDataTrack>, actions: MusicV3Actions, label: String) {
  val enabled = tracks.isNotEmpty()
  MusicV3Button("Play", style = MusicV3ButtonStyle.SOLID, glyph = MusicV3Glyph.PLAY, enabled = enabled) {
    actions.playAll(tracks, label, shuffle = false)
  }
  MusicV3Button("Shuffle", glyph = MusicV3Glyph.SHUFFLE, enabled = enabled) { actions.playAll(tracks, label, shuffle = true) }
}

/** Rows for library/search lists; a tap plays from that song, ⋯ offers Play next / Add to queue. */
@Composable
internal fun TrackRows(tracks: List<YtDataTrack>, actions: MusicV3Actions, label: String,
    onTap: ((YtDataTrack) -> Unit)? = null) {
  LazyColumn(Modifier.fillMaxSize()) {
    itemsIndexed(tracks, key = { i, t -> "$i:${t.videoId}" }) { i, t ->
      MusicV3TrackRow(MusicV3RowModel("$label:$i:${t.videoId}", t.title, t.artist, t.thumbnailUrl, t.durationMs),
          onPlay = { onTap?.invoke(t) ?: actions.playFrom(tracks, t, label) },
          actions = listOf(MusicV3RowAction("Play next") { actions.playNext(t) },
              MusicV3RowAction("Add to queue") { actions.addToQueue(t) }))
    }
    item { Spacer(Modifier.height(40.dp)) }
  }
}
