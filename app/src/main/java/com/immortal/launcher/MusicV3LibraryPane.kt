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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtArtist
import com.immortal.launcher.youtube.YtLoaded
import com.immortal.launcher.youtube.YtPlaylist
import com.immortal.launcher.youtube.YtResult
import kotlinx.coroutines.flow.Flow

/** Library chips; Playlists opens by default (recommended answer 1). */
internal enum class MusicV3LibraryTab(val label: String) {
  PLAYLISTS("Playlists"), LIKED("Liked songs"), ARTISTS("Artists"), RECENT("Recently played"),
}

/** Where Library is: a chip, or one playlist / artist opened from it. Immutable. */
internal data class MusicV3LibraryNav(
    val tab: MusicV3LibraryTab = MusicV3LibraryTab.PLAYLISTS,
    val playlist: YtPlaylist? = null,
    val artist: YtArtist? = null,
) {
  val inDetail: Boolean get() = playlist != null || artist != null
  fun back(): MusicV3LibraryNav = copy(playlist = null, artist = null)
}

/** Collects a stale-while-refresh library flow once per [key] (account + list). */
@Composable
internal fun <T> rememberLibraryLoad(key: Any, flow: () -> Flow<YtResult<YtLoaded<T>>>): MusicV3Load<T> {
  val result by produceState<YtResult<YtLoaded<T>>?>(null, key) { flow().collect { value = it } }
  return MusicV3Load.from(result)
}

@Composable
internal fun MusicV3LibraryPane(
    library: YouTubeMusicLibrary,
    accountId: String,
    nav: MusicV3LibraryNav,
    onNav: (MusicV3LibraryNav) -> Unit,
    actions: MusicV3Actions,
    onSignInAgain: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    val playlist = nav.playlist
    val artist = nav.artist
    Box(Modifier.weight(1f)) {
      when {
        playlist != null -> MusicV3TrackDetail(
            title = playlist.title, subtitle = "Playlist · ${MusicV3Rules.songCount(playlist.itemCount)}",
            art = playlist.thumbnailUrl, round = false, backLabel = "Back to playlists", onBack = { onNav(nav.back()) },
            load = rememberLibraryLoad(accountId to "pl:${playlist.id}") { library.playlistTracks(playlist.id) },
            actions = actions, label = playlist.title, onSignInAgain = onSignInAgain)
        artist != null -> MusicV3TrackDetail(
            title = artist.name, subtitle = "Latest uploads from this channel", art = artist.thumbnailUrl,
            round = true, backLabel = "Back to artists", onBack = { onNav(nav.back()) },
            load = rememberLibraryLoad(accountId to "ar:${artist.channelId}") { library.artistTracks(artist.channelId) },
            actions = actions, label = artist.name, onSignInAgain = onSignInAgain)
        else -> LibraryHome(library, accountId, nav, onNav, actions, onSignInAgain)
      }
    }
    Footnote()
  }
}

@Composable
private fun LibraryHome(
    library: YouTubeMusicLibrary,
    accountId: String,
    nav: MusicV3LibraryNav,
    onNav: (MusicV3LibraryNav) -> Unit,
    actions: MusicV3Actions,
    onSignInAgain: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      MusicV3LibraryTab.entries.forEach { tab ->
        MusicV3Chip(tab.label, selected = nav.tab == tab) { onNav(MusicV3LibraryNav(tab)) }
      }
    }
    when (nav.tab) {
      MusicV3LibraryTab.PLAYLISTS -> LoadOrProblem(rememberLibraryLoad(accountId to "playlists") { library.playlists() },
          onSignInAgain) { MusicV3PlaylistsTab(library, accountId, it, actions) { pl -> onNav(nav.copy(playlist = pl)) } }
      MusicV3LibraryTab.LIKED -> LoadOrProblem(rememberLibraryLoad(accountId to "liked") { library.likedSongs() },
          onSignInAgain) { tracks -> MusicV3LikedTab(library, accountId, tracks, actions) }
      MusicV3LibraryTab.ARTISTS -> LoadOrProblem(rememberLibraryLoad(accountId to "artists") { library.artists() },
          onSignInAgain) { ArtistList(it) { a -> onNav(nav.copy(artist = a)) } }
      MusicV3LibraryTab.RECENT -> MusicV3RecentTab(actions)
    }
  }
}

/** Shows [content] once loaded; a spinner, or one plain message, otherwise. */
@Composable
internal fun <T> LoadOrProblem(load: MusicV3Load<T>, onSignInAgain: () -> Unit, content: @Composable (T) -> Unit) {
  when (load) {
    MusicV3Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = ApprovedHomePalette.Accent, modifier = Modifier.size(56.dp))
    }
    is MusicV3Load.Ready -> content(load.value)
    is MusicV3Load.Problem -> MusicV3Empty(if (load.message == MusicV3Load.OFFLINE) "wifi" else "info", load.message,
        action = if (load.signInAgain) "Sign in again" to onSignInAgain else null)
  }
}

@Composable
private fun ArtistList(artists: List<YtArtist>, onOpen: (YtArtist) -> Unit) {
  if (artists.isEmpty()) {
    MusicV3Empty("music", MusicV3Message("No channels yet", "Channels you follow on YouTube show up here."))
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    listItems(artists, key = { it.channelId }) { a ->
      Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(20.dp))
          .tvFocusableRow(focusFill = ApprovedHomePalette.Selected) { onOpen(a) }
          .semantics(mergeDescendants = true) { role = Role.Button }.padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MusicV3Thumb(a.thumbnailUrl, Modifier.size(72.dp), androidx.compose.foundation.shape.CircleShape)
        Column(Modifier.weight(1f)) {
          Text(a.name, color = ApprovedHomePalette.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1,
              overflow = TextOverflow.Ellipsis)
          Text("You follow this channel", color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
        }
        MusicV3GlyphIcon(MusicV3Glyph.RIGHT, ApprovedHomePalette.Secondary, Modifier.size(32.dp))
      }
    }
  }
}

/** The one quiet note about what YouTube doesn't offer outside its app. */
@Composable
private fun Footnote() {
  Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 1.5.dp).background(ApprovedHomePalette.Outline))
    Row(Modifier.padding(start = 4.dp, top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ApprovedIcon("info", Modifier.size(26.dp), ApprovedHomePalette.Secondary)
      Text("Albums and mixes aren't available outside the YouTube Music app.", color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp)
    }
  }
}

/** A Library filter chip (Playlists / Liked songs / Artists / Recently played). */
@Composable
internal fun MusicV3Chip(label: String, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(32.dp)
  val fill = if (selected) ApprovedHomePalette.Text else Color.Transparent
  Box(Modifier.heightIn(min = MusicV3Dims.Touch).clip(shape).background(fill).approvedOutline(shape)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { role = Role.Tab; if (selected) contentDescription = "$label, selected" }
      .padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
    Text(label, color = if (selected) ApprovedHomePalette.Card else ApprovedHomePalette.Secondary, fontSize = 23.sp,
        fontWeight = FontWeight.Medium)
  }
}
