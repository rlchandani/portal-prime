/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** Queue label for songs started from Recently played. */
internal const val MUSIC_RECENT_LABEL = "Recently played"
/** "2 h ago" is re-read this often while the list is open. */
private const val AGO_TICK_MS = 60_000L

/** The household's newest plays as a [YtDataTrack], for the playable-check the other lists use. */
internal fun MusicPlay.toDataTrack(): YtDataTrack =
    YtDataTrack(videoId, title, artist, thumb.ifBlank { MusicShareQueue.thumbnailFor(videoId) })

/** Clock for "… ago" labels, refreshed once a minute. */
@Composable
internal fun rememberAgoClock(): Long {
  val now by produceState(System.currentTimeMillis()) {
    while (true) {
      delay(AGO_TICK_MS)
      value = System.currentTimeMillis()
    }
  }
  return now
}

/**
 * Recently played tab: every room's plays (newest first, each song once) with where and when.
 * Tap plays now like a search result; ⋯ = Play next / Add to queue / Remove from history.
 */
@Composable
internal fun MusicV3RecentTab(actions: MusicV3Actions) {
  val plays by MusicHistoryFeature.plays.collectAsState()
  val listing = remember(plays) { MusicHistory.listing(plays) }
  if (listing.isEmpty()) {
    MusicStageEmpty("Songs you play show up here.")
    return
  }
  val now = rememberAgoClock()
  LazyColumn(Modifier.fillMaxSize()) {
    item(key = "head", contentType = MusicV3RowKeys.HEADER) {
      MusicStageHeading(MUSIC_RECENT_LABEL, Modifier.padding(top = 8.dp), detail = "On every Portal")
    }
    items(listing, key = { it.videoId }, contentType = { MusicV3RowKeys.TRACK }) { play ->
      MusicV3RecentRow(play, now, actions, withRemove = true)
    }
    item(key = "tail", contentType = MusicV3RowKeys.NOTE) { Spacer(Modifier.height(40.dp)) }
  }
}

/** One Recently played row (also used under an empty search field). */
@Composable
internal fun MusicV3RecentRow(play: MusicPlay, now: Long, actions: MusicV3Actions, withRemove: Boolean) {
  val context = LocalContext.current
  val track = remember(play) { play.toDataTrack() }
  val menu = remember(track, actions, withRemove) {
    listOfNotNull(
        MusicV3RowAction("Play next") { actions.playNext(track) },
        MusicV3RowAction("Add to queue") { actions.addToQueue(track) },
        if (withRemove) MusicV3RowAction("Remove from history", danger = true) {
          MusicHistoryFeature.remove(context, play.videoId)
        } else null)
  }
  val where = MusicHistory.where(play, now)
  val row = remember(play, where) {
    MusicV3RowModel("recent:${play.videoId}", play.title, play.artist, track.thumbnailUrl, null, detail = where)
  }
  val tap = remember(track, actions) { { actions.playOne(track, MUSIC_RECENT_LABEL) } }
  MusicV3TrackRow(row, onPlay = tap, actions = menu)
}
