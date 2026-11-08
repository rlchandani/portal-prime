/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtPlaylist
import com.immortal.launcher.youtube.YtPlaylistChoice
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** Library → Playlists after hiding non-music ones. [shown] keeps the account's order. Immutable. */
internal data class MusicV3PlaylistView(
    val shown: List<YtPlaylist>,
    val hiddenIds: Set<String>,
    val showingHidden: Boolean,
) {
  val hiddenCount: Int get() = hiddenIds.size

  fun isHidden(playlistId: String): Boolean = playlistId in hiddenIds

  /** The quiet toggle under the grid; none when nothing is hidden. */
  val toggleLabel: String?
    get() = when {
      hiddenCount == 0 -> null
      showingHidden -> "Hide them again"
      else -> "Show hidden ($hiddenCount)"
    }
}

/** Liked songs as shown: music only by default, every liked video on request. */
internal data class MusicV3LikedView(val tracks: List<YtDataTrack>, val summary: String, val toggleLabel: String?)

/** Pure rules for hiding non-music playlists and likes; the composables only render these. */
internal object MusicV3PlaylistFilter {
  /** A manual choice wins; otherwise only a known "not mostly music" verdict hides. */
  fun hidden(playlist: YtPlaylist, verdicts: Map<String, Boolean>, choices: Map<String, YtPlaylistChoice>): Boolean =
      when (choices[playlist.id]) {
        YtPlaylistChoice.HIDE -> true
        YtPlaylistChoice.SHOW -> false
        null -> verdicts[playlist.id] == false
      }

  fun view(
      playlists: List<YtPlaylist>,
      verdicts: Map<String, Boolean>,
      choices: Map<String, YtPlaylistChoice>,
      showHidden: Boolean,
  ): MusicV3PlaylistView {
    val hiddenIds = playlists.filter { hidden(it, verdicts, choices) }.map { it.id }.toSet()
    val shown = if (showHidden) playlists else playlists.filterNot { it.id in hiddenIds }
    return MusicV3PlaylistView(shown, hiddenIds, showHidden)
  }

  fun menuLabel(hidden: Boolean): String = if (hidden) "Show on Portal" else "Hide from Portal"

  /** An explicit choice is stored either way, so a later verdict change can't undo it. */
  fun choiceAfterTap(hidden: Boolean): YtPlaylistChoice = if (hidden) YtPlaylistChoice.SHOW else YtPlaylistChoice.HIDE

  fun toast(title: String, nowHidden: Boolean): String =
      if (nowHidden) "“$title” is hidden on this Portal" else "“$title” shows on this Portal again"

  /** [songs] = [all] filtered to Music, or null while unknown (then everything shows, no toggle). */
  fun liked(all: List<YtDataTrack>, songs: List<YtDataTrack>?, showAll: Boolean): MusicV3LikedView = when {
    songs == null -> MusicV3LikedView(all, "${MusicV3Rules.songCount(all.size)} you've liked on YouTube", null)
    showAll -> MusicV3LikedView(all, "${videoCount(all.size)} you've liked on YouTube", "Only songs")
    else -> MusicV3LikedView(songs, "${MusicV3Rules.songCount(songs.size)} you've liked on YouTube",
        if (songs.size < all.size) "Show all liked videos" else null)
  }

  /**
   * Every playlist was judged "not mostly music" (often tutorials or clips). Say that plainly and
   * name a few, so it doesn't read as an error, and point to where music playlists come from.
   */
  fun allHiddenMessage(hidden: List<YtPlaylist>): MusicV3Message {
    val tail = "Make a playlist in YouTube Music and it shows up on this Portal."
    val detail = if (hidden.size == 1) {
      "Your playlist “${hidden.first().title}” looks like videos, so it’s tucked away here. $tail"
    } else {
      val named = hidden.take(NAMED_PLAYLISTS).joinToString(", ") { it.title }
      val more = if (hidden.size > NAMED_PLAYLISTS) "…" else ""
      "Your ${hidden.size} playlists look like videos ($named$more), so they’re tucked away here. $tail"
    }
    return MusicV3Message("No music playlists yet", detail)
  }

  /** How many hidden playlists the empty state names. */
  private const val NAMED_PLAYLISTS = 2

  private fun videoCount(count: Int): String = if (count == 1) "1 video" else "$count videos"
}
