/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The minimized tile's ✕: pause and close, "Paused · Undo" for 5 s, and "paused" (not stopped) everywhere. */
class MusicTileCloseTest {
  private val home = MusicHomeCardInputs(hasCurrent = true, musicOpen = false, customizing = false, dismissed = false)
  private val room = YtRemoteRoom(quiet = false, quietUntil = null, routine = false, inCall = false,
      account = null, volume = null)

  @After fun reset() {
    MusicHomeCardState.restore()
    MusicHomeCardState.expand()
    MusicHomeCardState.place(null)
  }

  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)

  private fun playingOnTile(): YtEngineState {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val loaded = reduce(ready, YtAction.PlayNow((1..3).map(::ytTrack), 1, "Mix")).state
    return reduce(loaded, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
  }

  /** ✕ as [MusicTileClose.close] does it, then the tile leaves the screen and the 30 s release passes. */
  private fun closedAndReleased(s: YtEngineState): YtEngineState {
    val paused = reduce(s, YtAction.Pause).state
    val reported = reduce(paused, YtPlayerEvent.StateChanged(YtPlayerStatus.PAUSED)).state
    val hidden = reduce(reported, YtAction.Foreground(false)).state
    return reduce(hidden, YtAction.PlayerLost).state
  }

  private fun shows() = MusicHomeCardRules.shows(home.copy(closed = MusicHomeCardState.closed.value))

  @Test fun closeHidesTheTileKeepsItMinimizedAndOffersUndo() {
    MusicHomeCardState.minimize()
    MusicHomeCardState.close(nowMs = 1_000, wasPlaying = true)
    assertFalse(shows())
    assertTrue("the tile, not the card, comes back", MusicHomeCardState.minimized.value)
    assertEquals(MusicTileUndo(1_000, wasPlaying = true), MusicHomeCardState.undo.value)
    assertFalse("closing is not the paused auto-hide", MusicHomeCardState.dismissed.value)
  }

  @Test fun closePausesAndSavesInsteadOfStopping() {
    val s = playingOnTile()
    assertTrue(MusicTileCloseRules.wasPlaying(s))
    val step = reduce(s, YtAction.Pause)
    assertEquals(listOf(YtEffect.Pause, YtEffect.Persist), step.effects)
    val after = closedAndReleased(s)
    assertEquals("the same song stays current", s.queue.current, after.queue.current)
    assertEquals(3, after.queue.entries.size)
    assertFalse(after.isPlaying)
    assertFalse(MusicTileCloseRules.playRequested(after))
  }

  @Test fun aPhoneSeesPausedNotIdle() {
    val after = closedAndReleased(playingOnTile())
    assertEquals("paused", YtRemoteProtocol.state(after, nowElapsedMs = 0, room = room).getString("state"))
  }

  @Test fun theHeaderChipOffersResumeForTheSameSong() {
    val after = closedAndReleased(playingOnTile())
    val chip = musicV3Chip(after, saved = null)
    assertNotNull(chip)
    assertTrue(chip!!.resume)
    assertEquals("Resume · ${after.queue.current!!.track.title}", chip.title)
  }

  @Test fun followMeSeesTheRoomAsNotPlayingLikeAnyPause() {
    // Follow-me only follows a room that is playing; a closed tile reads exactly like Pause.
    val note = FollowMeWire.LiveNote(Presence.PRESENT, 0L, "Family", track = null)
    assertEquals(0, FollowMeWire.encode(note).getInt("on"))
    assertFalse(closedAndReleased(playingOnTile()).isPlaying)
  }

  @Test fun closingDoesNotShowTheWhyItPausedNoteEvenBeforeThePlayerReportsPaused() {
    val playing = playingOnTile()
    assertTrue("leaving while playing still explains", MusicHostHandoff.explainsPause(playing))
    val justPaused = reduce(playing, YtAction.Pause).state
    assertTrue("the player hasn't reported paused yet", justPaused.isPlaying)
    assertFalse(MusicHostHandoff.explainsPause(justPaused))
  }

  @Test fun undoWithinFiveSecondsBringsTheTileBackAndResumes() {
    MusicHomeCardState.minimize()
    MusicHomeCardState.close(nowMs = 10_000, wasPlaying = true)
    assertEquals(true, MusicHomeCardState.undoClose(nowMs = 10_000 + MusicTileCloseRules.UNDO_MS - 1))
    assertTrue(shows())
    assertTrue(MusicHomeCardState.minimized.value)
    assertNull(MusicHomeCardState.undo.value)
  }

  @Test fun undoResumesOnceTheTileIsVisibleAgain() {
    val hidden = reduce(reduce(playingOnTile(), YtAction.Pause).state, YtAction.Foreground(false)).state
    val asked = reduce(hidden, YtAction.PlayWhenVisible).state
    assertTrue(asked.playWhenVisible)
    assertTrue(MusicTileCloseRules.playRequested(asked))
    val shown = reduce(asked, YtAction.Foreground(true))
    assertTrue(shown.effects.contains(YtEffect.Play))
  }

  @Test fun undoOfAnAlreadyPausedTileDoesNotStartTheMusic() {
    MusicHomeCardState.close(nowMs = 0, wasPlaying = false)
    assertEquals(false, MusicHomeCardState.undoClose(nowMs = 100))
    assertTrue(shows())
  }

  @Test fun undoAfterFiveSecondsDoesNothing() {
    MusicHomeCardState.close(nowMs = 0, wasPlaying = true)
    assertNull(MusicHomeCardState.undoClose(nowMs = MusicTileCloseRules.UNDO_MS))
    assertFalse(shows())
  }

  @Test fun theToastsTimerOnlyClearsItsOwnOffer() {
    MusicHomeCardState.close(nowMs = 0, wasPlaying = true)
    val first = MusicHomeCardState.undo.value!!
    MusicHomeCardState.undoClose(nowMs = 1)
    MusicHomeCardState.close(nowMs = 2_000, wasPlaying = true)
    MusicHomeCardState.undoExpired(first)
    assertEquals(MusicTileUndo(2_000, true), MusicHomeCardState.undo.value)
    MusicHomeCardState.undoExpired(MusicHomeCardState.undo.value!!)
    assertNull(MusicHomeCardState.undo.value)
    assertFalse("expiry keeps the tile closed", shows())
  }

  @Test fun undoCountdown() {
    val offer = MusicTileUndo(1_000, true)
    assertEquals(5_000L, MusicTileCloseRules.remainingMs(offer, 1_000))
    assertEquals(1_500L, MusicTileCloseRules.remainingMs(offer, 4_500))
    assertEquals(0L, MusicTileCloseRules.remainingMs(offer, 9_000))
    assertFalse(MusicTileCloseRules.canUndo(offer, 999))
    assertFalse(MusicTileCloseRules.canUndo(null, 1_000))
  }

  @Test fun anyRequestToPlayBringsTheTileBack() {
    MusicHomeCardState.close(nowMs = 0, wasPlaying = true)
    MusicHomeCardState.onPlaying(false)
    assertFalse(shows())
    MusicHomeCardState.onPlaying(true)
    assertTrue(shows())
    assertNull(MusicHomeCardState.undo.value)
  }

  @Test fun theChipsPlayBringsTheTileBack() {
    MusicHomeCardState.close(nowMs = 0, wasPlaying = true)
    MusicHomeCardState.restore()
    assertTrue(shows())
  }

  @Test fun theCloseTargetIs64DpAndTheRailStillFitsBesideThePlayer() {
    assertTrue(MusicHomeCardDims.TILE_CLOSE >= 64)
    assertTrue(MusicHomeCardDims.TILE_CLOSE_GLYPH < MusicHomeCardDims.TILE_CLOSE)
    // ✕ + expand stacked, then the grip (26 dp + 18 dp bottom padding), inside the 208 dp tile.
    assertTrue(MusicHomeCardDims.TILE_CLOSE + MusicHomeCardDims.RAIL_W + 26 + 18 <= MusicHomeCardDims.TILE_H)
    assertTrue(MusicTileUndoDims.UNDO_H >= 64)
  }

  @Test fun theTileAndTheUndoToastFitTheMiniInBothOrientations() {
    // The Mini: 800×1220 dp upright, 1280×740 dp turned (LayoutClass); Home's side edge is 24 dp a side.
    val narrowest = 800
    assertTrue(MusicHomeCardDims.TILE_W + 2 * 24 <= narrowest)
    assertTrue(MusicTileUndoDims.MAX_W + 2 * 24 <= narrowest)
    assertTrue(MusicHomeCardDims.TILE_H < 740 / 2)
  }
}
