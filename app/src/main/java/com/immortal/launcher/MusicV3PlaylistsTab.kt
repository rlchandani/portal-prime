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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtPlaylist
import com.immortal.launcher.youtube.YtPlaylistChoice
import com.immortal.launcher.youtube.YtResult
import kotlinx.coroutines.launch
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/**
 * Library → Playlists with non-music playlists hidden ([MusicV3PlaylistFilter]): each tile's ⋯
 * hides/shows it on this Portal, and a quiet "Show hidden (N)" toggle sits under the grid.
 */
@Composable
internal fun MusicV3PlaylistsTab(
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
  when {
    playlists.isEmpty() -> MusicV3Empty("music", MusicV3Message("No playlists yet", "Playlists you make on YouTube show up here."))
    view.shown.isEmpty() -> MusicV3Empty("music", MusicV3Message("Every playlist is hidden",
        "Playlists that aren't mostly music stay off this Portal."), action = toggle)
    else -> PlaylistGrid(view, toggle, onOpen, onChoose)
  }
}

@Composable
private fun PlaylistGrid(
    view: MusicV3PlaylistView,
    toggle: Pair<String, () -> Unit>?,
    onOpen: (YtPlaylist) -> Unit,
    onChoose: (YtPlaylist) -> Unit,
) {
  LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp),
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    items(view.shown, key = { it.id }) { pl -> PlaylistTile(pl, view.isHidden(pl.id), onOpen, onChoose) }
    if (toggle != null) {
      item(key = "hidden-toggle", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
          MusicV3Button(toggle.first, style = MusicV3ButtonStyle.GHOST, onClick = toggle.second)
        }
      }
    }
  }
}

@Composable
private fun PlaylistTile(pl: YtPlaylist, hidden: Boolean, onOpen: (YtPlaylist) -> Unit, onChoose: (YtPlaylist) -> Unit) {
  var menuOpen by remember(pl.id) { mutableStateOf(false) }
  val shape = RoundedCornerShape(20.dp)
  Column {
    Box {
      Column(Modifier.clip(shape).alpha(if (hidden) 0.5f else 1f)
          .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) { onOpen(pl) }
          .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = "${pl.title}, ${MusicV3Rules.songCount(pl.itemCount)}${if (hidden) ", hidden" else ""}"
          }) {
        MusicV3Thumb(pl.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1.3f), shape)
        Text(pl.title, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Text(if (hidden) "Hidden on this Portal" else MusicV3Rules.songCount(pl.itemCount),
            color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
      }
      MusicV3RoundButton("More for ${pl.title}", Modifier.align(Alignment.TopEnd).padding(10.dp),
          fill = ApprovedHomePalette.Card, onClick = { menuOpen = !menuOpen }) {
        MusicV3GlyphIcon(MusicV3Glyph.MORE, ApprovedHomePalette.Text, Modifier.size(28.dp))
      }
    }
    if (menuOpen) {
      Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(ApprovedHomePalette.Chrome).padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MusicV3Button(MusicV3PlaylistFilter.menuLabel(hidden), Modifier.fillMaxWidth()) { menuOpen = false; onChoose(pl) }
        MusicV3Button("Cancel", Modifier.fillMaxWidth(), style = MusicV3ButtonStyle.GHOST) { menuOpen = false }
      }
    }
  }
}

/** Liked songs filtered to YouTube's Music category, with "Show all liked videos". */
@Composable
internal fun MusicV3LikedTab(library: YouTubeMusicLibrary, accountId: String, tracks: List<YtDataTrack>, actions: MusicV3Actions) {
  val songs by produceState<List<YtDataTrack>?>(null, accountId, tracks) {
    value = (library.musicOnly(tracks) as? YtResult.Ok)?.value
  }
  var showAll by rememberSaveable(accountId) { mutableStateOf(false) }
  val view = MusicV3PlaylistFilter.liked(tracks, songs, showAll)
  val toggle = view.toggleLabel?.let { label -> label to { showAll = !showAll } }
  if (view.tracks.isEmpty() && toggle != null) {
    MusicV3Empty("music", MusicV3Message("No songs among your likes",
        "YouTube doesn't file the videos you've liked as music."), action = toggle)
    return
  }
  MusicV3TrackList(view.tracks, view.summary, actions, "Liked songs", toggle)
}
