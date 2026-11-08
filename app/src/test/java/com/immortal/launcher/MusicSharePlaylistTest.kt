/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.MusicSharePolicy.PlaylistSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared playlists on a Portal: the data layer when signed in, otherwise the ONE visible engine
 * player (never a hidden one), and only when the playlist is about to play anyway.
 */
class MusicSharePlaylistTest {
  private val list = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)
  private val visible = YtEngineState(queue = ytQueueOf(3, start = 1), status = YtPlayerStatus.PLAYING,
      wantsPlay = true, foreground = true, positionMs = 42_000)

  @Test fun source_signedInAlwaysUsesTheLibrary() {
    MusicShareAction.entries.forEach { a ->
      assertEquals(PlaylistSource.LIBRARY, MusicSharePolicy.playlistSource(signedIn = true, playing = true, action = a))
    }
  }

  @Test fun source_signedOutUsesTheVisiblePlayerOnlyWhenItWillPlay() {
    MusicShareAction.entries.forEach { a ->
      assertEquals(PlaylistSource.PLAYER, MusicSharePolicy.playlistSource(signedIn = false, playing = false, action = a))
    }
    assertEquals(PlaylistSource.PLAYER, MusicSharePolicy.playlistSource(false, playing = true, action = MusicShareAction.NOW))
    assertEquals(PlaylistSource.NEEDS_SIGN_IN, MusicSharePolicy.playlistSource(false, playing = true, action = MusicShareAction.NEXT))
    assertEquals(PlaylistSource.NEEDS_SIGN_IN, MusicSharePolicy.playlistSource(false, playing = true, action = MusicShareAction.END))
  }

  @Test fun libraryFailure_fallsBackToThePlayerOnlyWhenThatIsAllowed() {
    assertEquals(PlaylistSource.PLAYER, MusicSharePolicy.libraryFallback(playing = false, action = MusicShareAction.END))
    assertEquals(PlaylistSource.NEEDS_SIGN_IN, MusicSharePolicy.libraryFallback(playing = true, action = MusicShareAction.NEXT))
    assertEquals("Sign in to YouTube on Living Area to queue playlists. Play now works without an account.",
        MusicSharePolicy.signInReason("Living Area"))
  }

  @Test fun lookUp_cuesOnTheVisibleReadyPlayer_andForgetsAnOldAnswer() {
    val stale = visible.copy(playlistLookup = YtPlaylistLookup(list, listOf(ytTrack(9).videoId)))
    val step = reduce(stale, YtAction.LookUpPlaylist(list))
    assertEquals(listOf(YtEffect.CuePlaylist(list)), step.effects)
    assertNull(step.state.playlistLookup)
  }

  @Test fun lookUp_neverTouchesAHiddenOrMissingPlayer() {
    listOf(visible.copy(foreground = false), visible.copy(status = YtPlayerStatus.NOT_READY), visible.copy(sizeOk = false)).forEach { s ->
      val step = reduce(s, YtAction.LookUpPlaylist(list))
      assertTrue(step.effects.isEmpty())
      assertEquals(YtPlaylistLookup(list, emptyList()), step.state.playlistLookup)
    }
  }

  @Test fun reportedIds_areRecorded_andAFailedListReCuesOurSong() {
    val ids = listOf(ytTrack(7).videoId, ytTrack(8).videoId)
    val ok = reduce(visible, YtPlayerEvent.Playlist(list, ids))
    assertEquals(YtPlaylistLookup(list, ids), ok.state.playlistLookup)
    assertTrue(ok.effects.isEmpty())
    val failed = reduce(visible, YtPlayerEvent.Playlist(list, emptyList()))
    assertEquals(listOf(YtEffect.Cue(ytTrack(2).videoId, 42_000)), failed.effects)
  }

  @Test fun idsBecomePlaceholderRows_thatPlayNowWithTheInterruptedSongResumingAfter() {
    val ids = listOf(ytTrack(7).videoId, ytTrack(8).videoId)
    val rows = MusicShareQueue.placeholders(ids, "Sunday slow")
    assertEquals(listOf("Song 1", "Song 2"), rows.map { it.title })
    val out = MusicShareQueue.apply(visible.queue, playing = true, positionMs = 42_000, rows, MusicShareAction.NOW, "p", "l")!!
    assertEquals(ids + ytTrack(2).videoId + ytTrack(3).videoId, out.queue.entries.map { it.track.videoId })
    assertEquals(42_000L, out.queue.entries[2].resumeMs)
  }

  @Test fun page_cuePlaylistScriptValidatesTheListId() {
    assertEquals("window.__portalYt&&window.__portalYt.cueList('$list');", YtPlayerPage.cuePlaylist(list))
    assertNull(YtPlayerPage.cuePlaylist("PL');alert(1);//xxxx"))
    assertNull(YtPlayerPage.cuePlaylist("short"))
    val html = YtPlayerPage.html(YtPlayerPage.baseUrl("com.immortal.launcher"))
    assertTrue(html.contains("player.cuePlaylist({list:l,listType:'playlist'})"))
    assertTrue(html.contains("player.getPlaylist()"))
    assertTrue(html.contains("ids.slice(0,200)"))
  }

  @Test fun parser_acceptsPlaylistReports_validIdsOnly_capped() {
    val raw = JSONObject().put("v", 1).put("e", "playlist").put("l", list)
        .put("ids", JSONArray((1..260).map { ytTrack(it).videoId } + "bad")).toString()
    assertTrue(raw.length <= YtPlayerEventParser.MAX_MESSAGE_CHARS)
    val event = YtPlayerEventParser.parse(raw, 0) as YtPlayerEvent.Playlist
    assertEquals(200, event.videoIds.size)
    assertEquals(list, event.listId)
    assertNull(YtPlayerEventParser.parse("""{"v":1,"e":"playlist","l":"x'","ids":[]}""", 0))
    assertEquals(YtPlayerEvent.Playlist(list, emptyList()), YtPlayerEventParser.parse("""{"v":1,"e":"playlist","l":"$list","ids":[]}""", 0))
  }
}
