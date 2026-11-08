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
import org.junit.Assert.*
import org.junit.Test

class MusicV3LibraryFilterTest {
  private val songs = YtPlaylist("PLs", "Songs", null, 10)
  private val games = YtPlaylist("PLg", "Game clips", null, 4)
  private val unknown = YtPlaylist("PLu", "New", null, 2)
  private val all = listOf(songs, games, unknown)

  @Test fun autoHidesNonMusicAndShowsUnknown() {
    val view = MusicV3PlaylistFilter.view(all, mapOf("PLs" to true, "PLg" to false), emptyMap(), showHidden = false)
    assertEquals(listOf(songs, unknown), view.shown)
    assertEquals(1, view.hiddenCount)
    assertEquals("Show hidden (1)", view.toggleLabel)
  }

  @Test fun manualChoicesBeatTheVerdict() {
    val choices = mapOf("PLg" to YtPlaylistChoice.SHOW, "PLs" to YtPlaylistChoice.HIDE)
    val view = MusicV3PlaylistFilter.view(all, mapOf("PLs" to true, "PLg" to false), choices, showHidden = false)
    assertEquals(listOf(games, unknown), view.shown)
    assertTrue(view.isHidden("PLs"))
    assertFalse(view.isHidden("PLg"))
  }

  @Test fun showHiddenListsEverythingInOrderAndOffersToHideAgain() {
    val view = MusicV3PlaylistFilter.view(all, mapOf("PLg" to false), emptyMap(), showHidden = true)
    assertEquals(all, view.shown)
    assertTrue(view.isHidden("PLg"))
    assertEquals("Hide them again", view.toggleLabel)
  }

  @Test fun noToggleWhenNothingIsHidden() {
    val view = MusicV3PlaylistFilter.view(all, emptyMap(), emptyMap(), showHidden = true)
    assertNull(view.toggleLabel)
    assertEquals(0, view.hiddenCount)
  }

  @Test fun menuLabelAndChoiceFollowTheCurrentState() {
    assertEquals("Hide from Portal", MusicV3PlaylistFilter.menuLabel(hidden = false))
    assertEquals("Show on Portal", MusicV3PlaylistFilter.menuLabel(hidden = true))
    assertEquals(YtPlaylistChoice.HIDE, MusicV3PlaylistFilter.choiceAfterTap(hidden = false))
    assertEquals(YtPlaylistChoice.SHOW, MusicV3PlaylistFilter.choiceAfterTap(hidden = true))
    assertEquals("“Songs” is hidden on this Portal", MusicV3PlaylistFilter.toast("Songs", nowHidden = true))
    assertEquals("“Songs” shows on this Portal again", MusicV3PlaylistFilter.toast("Songs", nowHidden = false))
  }

  // ---- liked songs ----

  private fun t(id: String) = YtDataTrack(id, "T$id", "A", null)
  private val liked = listOf(t("a"), t("b"), t("c"))

  @Test fun likedShowsSongsOnlyWithAToggleToShowAll() {
    val view = MusicV3PlaylistFilter.liked(liked, songs = listOf(t("a"), t("c")), showAll = false)
    assertEquals(listOf("a", "c"), view.tracks.map { it.videoId })
    assertEquals("2 songs you've liked on YouTube", view.summary)
    assertEquals("Show all liked videos", view.toggleLabel)
  }

  @Test fun likedShowAllListsEveryVideo() {
    val view = MusicV3PlaylistFilter.liked(liked, songs = listOf(t("a")), showAll = true)
    assertEquals(liked, view.tracks)
    assertEquals("3 videos you've liked on YouTube", view.summary)
    assertEquals("Only songs", view.toggleLabel)
  }

  @Test fun likedWithoutCategoriesOrNothingFilteredHasNoToggle() {
    assertNull(MusicV3PlaylistFilter.liked(liked, songs = null, showAll = false).toggleLabel)
    assertEquals(liked, MusicV3PlaylistFilter.liked(liked, songs = null, showAll = false).tracks)
    assertNull(MusicV3PlaylistFilter.liked(liked, songs = liked, showAll = false).toggleLabel)
    assertEquals("1 video you've liked on YouTube",
        MusicV3PlaylistFilter.liked(listOf(t("a")), songs = emptyList(), showAll = true).summary)
  }
}
