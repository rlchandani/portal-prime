/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtArtist
import com.immortal.launcher.youtube.YtLoaded
import com.immortal.launcher.youtube.YtPlaylist
import com.immortal.launcher.youtube.YtResult
import kotlinx.coroutines.flow.Flow

/** Where Library is: its shelves, or one playlist / artist / Liked songs opened from them. Immutable. */
internal data class MusicV3LibraryNav(
    val playlist: YtPlaylist? = null,
    val artist: YtArtist? = null,
    val liked: Boolean = false,
) {
  val inDetail: Boolean get() = playlist != null || artist != null || liked
  fun back(): MusicV3LibraryNav = MusicV3LibraryNav()
}

/** Collects a stale-while-refresh library flow once per [key] (account + list). */
@Composable
internal fun <T> rememberLibraryLoad(key: Any, flow: () -> Flow<YtResult<YtLoaded<T>>>): MusicV3Load<T> {
  val result by produceState<YtResult<YtLoaded<T>>?>(null, key) { flow().collect { value = it } }
  return MusicV3Load.from(result)
}

/** Library tab: Playlists, Liked songs and Artists shelves (the mock), or one opened from them. */
@Composable
internal fun MusicV3LibraryPane(
    library: YouTubeMusicLibrary,
    accountId: String,
    nav: MusicV3LibraryNav,
    onNav: (MusicV3LibraryNav) -> Unit,
    actions: MusicV3Actions,
    onSignInAgain: () -> Unit,
) {
  val playlist = nav.playlist
  val artist = nav.artist
  val likedLoad = rememberLibraryLoad(accountId to "liked") { library.likedSongs() }
  when {
    playlist != null -> MusicV3TrackDetail(
        title = playlist.title, subtitle = "Playlist · ${MusicV3Rules.songCount(playlist.itemCount)}",
        art = playlist.thumbnailUrl, round = false, backLabel = "Back to Library", onBack = { onNav(nav.back()) },
        load = rememberLibraryLoad(accountId to "pl:${playlist.id}") { library.playlistTracks(playlist.id) },
        actions = actions, label = playlist.title, onSignInAgain = onSignInAgain)
    artist != null -> MusicV3TrackDetail(
        title = artist.name, subtitle = "Latest uploads from this channel", art = artist.thumbnailUrl,
        round = true, backLabel = "Back to Library", onBack = { onNav(nav.back()) },
        load = rememberLibraryLoad(accountId to "ar:${artist.channelId}") { library.artistTracks(artist.channelId) },
        actions = actions, label = artist.name, onSignInAgain = onSignInAgain)
    nav.liked -> Column(Modifier.fillMaxSize()) {
      LoadOrProblem(likedLoad, onSignInAgain) { tracks ->
        MusicV3LikedDetail(rememberLikedState(library, accountId, tracks), actions) { onNav(nav.back()) }
      }
    }
    else -> LibraryShelves(library, accountId, likedLoad, onNav, actions, onSignInAgain)
  }
}

@Composable
private fun LibraryShelves(
    library: YouTubeMusicLibrary,
    accountId: String,
    likedLoad: MusicV3Load<List<com.immortal.launcher.youtube.YtTrack>>,
    onNav: (MusicV3LibraryNav) -> Unit,
    actions: MusicV3Actions,
    onSignInAgain: () -> Unit,
) {
  val playlists = rememberLibraryLoad(accountId to "playlists") { library.playlists() }
  val artists = rememberLibraryLoad(accountId to "artists") { library.artists() }
  val dims = LocalMusicStageDims.current
  LazyColumn(Modifier.fillMaxSize()) {
    item(key = "pl-h", contentType = MusicV3RowKeys.HEADER) {
      MusicStageHeading("Playlists", Modifier.padding(top = 8.dp), detail = (playlists as? MusicV3Load.Ready)?.value?.size?.toString())
    }
    item(key = "pl", contentType = MusicV3RowKeys.SHELF) {
      ShelfLoad(playlists, onSignInAgain) { MusicV3PlaylistsShelf(library, accountId, it, actions) { pl -> onNav(MusicV3LibraryNav(playlist = pl)) } }
    }
    item(key = "lk", contentType = MusicV3RowKeys.SHELF) {
      ShelfLoad(likedLoad, onSignInAgain) { tracks ->
        val liked = rememberLikedState(library, accountId, tracks)
        val songs = liked.view.tracks
        MusicStageHeading(LIKED_LABEL, Modifier.padding(top = 28.dp), detail = MusicV3Rules.songCount(songs.size)) {
          MusicStagePill("Play", style = MusicPillStyle.SOLID, glyph = MusicV3Glyph.PLAY, enabled = songs.isNotEmpty()) {
            actions.playAll(songs, LIKED_LABEL, shuffle = false)
          }
          MusicStagePill("Shuffle", glyph = MusicV3Glyph.SHUFFLE, enabled = songs.isNotEmpty()) {
            actions.playAll(songs, LIKED_LABEL, shuffle = true)
          }
          MusicStagePill("See all") { onNav(MusicV3LibraryNav(liked = true)) }
        }
        MusicV3LikedShelf(liked, actions)
      }
    }
    item(key = "ar-h", contentType = MusicV3RowKeys.HEADER) {
      MusicStageHeading("Artists", Modifier.padding(top = 28.dp), detail = (artists as? MusicV3Load.Ready)?.value?.size?.toString())
    }
    item(key = "ar", contentType = MusicV3RowKeys.SHELF) {
      ShelfLoad(artists, onSignInAgain) { list ->
        if (list.isEmpty()) MusicStageEmpty("Channels you follow on YouTube show up here.")
        else MusicV3Shelf(list, key = { it.channelId }) { a ->
          MusicV3Tile(MusicV3TileModel(a.channelId, a.name, "Channel", a.thumbnailUrl, round = true, spoken = "${a.name}, channel"),
              dims.tile.dp, onClick = { onNav(MusicV3LibraryNav(artist = a)) })
        }
      }
    }
    item(key = "foot", contentType = MusicV3RowKeys.NOTE) { Footnote() }
  }
}

/** A shelf's content once loaded; a small spinner, or one plain message, otherwise. */
@Composable
private fun <T> ShelfLoad(load: MusicV3Load<T>, onSignInAgain: () -> Unit, content: @Composable (T) -> Unit) {
  when (load) {
    MusicV3Load.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = LocalMusicStageInk.current.ember, modifier = Modifier.size(48.dp))
    }
    is MusicV3Load.Ready -> Column { content(load.value) }
    is MusicV3Load.Problem -> MusicStageEmpty("${load.message.title}. ${load.message.detail}",
        action = if (load.signInAgain) "Sign in again" to onSignInAgain else null)
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

/** The one quiet note about what YouTube doesn't offer outside its app. */
@Composable
private fun Footnote() {
  val ink = LocalMusicStageInk.current
  Row(Modifier.padding(start = 4.dp, top = 28.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    ApprovedIcon("info", Modifier.size(26.dp), ink.muted)
    Text("Albums and mixes aren't available outside the YouTube Music app.", color = ink.muted,
        fontSize = LocalMusicStageDims.current.small.sp)
  }
}
