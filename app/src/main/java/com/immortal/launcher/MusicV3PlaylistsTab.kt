/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtPlaylist
import com.immortal.launcher.youtube.YtPlaylistChoice
import com.immortal.launcher.youtube.YtResult
import kotlinx.coroutines.launch
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** Liked songs shown on the shelf before "See all". */
private const val LIKED_SHELF_MAX = 12

/**
 * Library › Playlists shelf with non-music playlists hidden ([MusicV3PlaylistFilter]): each tile's ⋯
 * hides/shows it on this Portal, and a quiet "Show hidden (N)" toggle sits under the shelf.
 */
@Composable
internal fun MusicV3PlaylistsShelf(
    library: YouTubeMusicLibrary,
    accountId: String,
    playlists: List<YtPlaylist>,
    actions: MusicV3Actions,
    onOpen: (YtPlaylist) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val verdicts by produceState(emptyMap<String, Boolean>(), accountId, playlists) {
    library.playlistVerdicts(playlists).collect { value = it }
  }
  var choices by remember(accountId) { mutableStateOf<Map<String, YtPlaylistChoice>?>(null) }
  LaunchedEffect(accountId) { choices = library.playlistChoices() }
  var showHidden by rememberSaveable(accountId) { mutableStateOf(false) }
  val known = choices ?: return // a moment's read from disk; avoids flashing a hidden playlist
  val view = MusicV3PlaylistFilter.view(playlists, verdicts, known, showHidden)
  val toggle = view.toggleLabel?.let { label -> label to { showHidden = !showHidden } }
  val onChoose = { pl: YtPlaylist ->
    val hidden = view.isHidden(pl.id)
    val choice = MusicV3PlaylistFilter.choiceAfterTap(hidden)
    choices = known + (pl.id to choice)
    scope.launch { library.setPlaylistChoice(pl.id, choice) }
    actions.notify(MusicV3PlaylistFilter.toast(pl.title, nowHidden = !hidden))
  }
  Column {
    when {
      playlists.isEmpty() -> MusicStageEmpty("Playlists you make on YouTube show up here.")
      view.shown.isEmpty() -> MusicStageEmpty(MusicV3PlaylistFilter.allHiddenMessage(playlists).detail)
      else -> MusicV3Shelf(view.shown, key = { it.id }) { pl -> PlaylistTile(pl, view.isHidden(pl.id), onOpen, onChoose) }
    }
    toggle?.let { (label, run) -> MusicStagePill(label, Modifier.padding(top = 4.dp), onClick = run) }
  }
}

@Composable
private fun PlaylistTile(pl: YtPlaylist, hidden: Boolean, onOpen: (YtPlaylist) -> Unit, onChoose: (YtPlaylist) -> Unit) {
  var menuOpen by remember(pl.id) { mutableStateOf(false) }
  val dims = LocalMusicStageDims.current
  val caption = if (hidden) "Hidden on this Portal" else "Playlist · ${MusicV3Rules.songCount(pl.itemCount)}"
  val model = remember(pl, hidden) {
    MusicV3TileModel(pl.id, pl.title, caption, pl.thumbnailUrl, dimmed = hidden,
        spoken = "${pl.title}, ${MusicV3Rules.songCount(pl.itemCount)}${if (hidden) ", hidden" else ""}")
  }
  MusicV3Tile(model, dims.tile.dp, onClick = { onOpen(pl) },
      corner = { MusicV3TileMore("More for ${pl.title}") { menuOpen = !menuOpen } },
      below = if (!menuOpen) null else {
        {
          Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicStagePill(MusicV3PlaylistFilter.menuLabel(hidden), Modifier.fillMaxWidth()) { menuOpen = false; onChoose(pl) }
            MusicStagePill("Cancel", Modifier.fillMaxWidth()) { menuOpen = false }
          }
        }
      })
}

/** Liked songs filtered to YouTube's Music category, with "Show all liked videos". */
internal data class MusicV3LikedState(val view: MusicV3LikedView, val toggle: Pair<String, () -> Unit>?)

@Composable
internal fun rememberLikedState(library: YouTubeMusicLibrary, accountId: String, tracks: List<YtDataTrack>): MusicV3LikedState {
  val songs by produceState<List<YtDataTrack>?>(null, accountId, tracks) {
    value = (library.musicOnly(tracks) as? YtResult.Ok)?.value
  }
  var showAll by rememberSaveable(accountId) { mutableStateOf(false) }
  val view = MusicV3PlaylistFilter.liked(tracks, songs, showAll)
  return MusicV3LikedState(view, view.toggleLabel?.let { label -> label to { showAll = !showAll } })
}

/** Library › Liked songs shelf: the first songs; a tap plays from there. */
@Composable
internal fun MusicV3LikedShelf(liked: MusicV3LikedState, actions: MusicV3Actions) {
  val tracks = liked.view.tracks
  if (tracks.isEmpty()) {
    MusicStageEmpty(if (liked.toggle != null) "YouTube doesn't file the videos you've liked as music." else
      "Songs you like on YouTube show up here.", action = liked.toggle)
    return
  }
  val dims = LocalMusicStageDims.current
  val shown = remember(tracks) { tracks.take(LIKED_SHELF_MAX) }
  val keys = remember(shown) { MusicV3RowKeys.unique(shown.map { it.videoId }) }
  MusicV3Shelf(shown.indices.toList(), key = { keys[it] }) { i ->
    val t = shown[i]
    val model = remember(keys[i], t) { MusicV3TileModel(keys[i], t.title, t.artist, t.thumbnailUrl, spoken = "Play ${t.title}") }
    MusicV3Tile(model, dims.tile.dp, onClick = { actions.playFrom(tracks, t, LIKED_LABEL) })
  }
}

/** Liked songs, full list (from "See all"). */
@Composable
internal fun MusicV3LikedDetail(liked: MusicV3LikedState, actions: MusicV3Actions, onBack: () -> Unit) {
  MusicV3DetailHeader(LIKED_LABEL, liked.view.summary, art = null, round = false, backLabel = "Back to Library",
      onBack = onBack, tracks = liked.view.tracks, actions = actions, label = LIKED_LABEL, toggle = liked.toggle)
  TrackRows(liked.view.tracks, actions, LIKED_LABEL)
}

internal const val LIKED_LABEL = "Liked songs"
