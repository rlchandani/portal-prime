/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiving Portal: when it declines, when it reports "playing", and the guest queue screen. */
class MusicHandoffTargetTest {
  private val p = MusicTargetPolicy

  @Test fun declineReasonsInPriorityOrder() {
    assertNull(p.decide(quiet = false, routine = false, inCall = false, recall = false))
    assertEquals(MusicHandoffStatus.DECLINED_QUIET, p.decide(quiet = true, routine = false, inCall = false, recall = false))
    assertEquals(MusicHandoffStatus.DECLINED_ROUTINE, p.decide(quiet = true, routine = true, inCall = false, recall = false))
    assertEquals(MusicHandoffStatus.DECLINED_CALL, p.decide(quiet = true, routine = true, inCall = true, recall = false))
  }

  @Test fun bringingItBackIgnoresQuietHoursButNotACallOrRoutine() {
    assertNull(p.decide(quiet = true, routine = false, inCall = false, recall = true))
    assertEquals(MusicHandoffStatus.DECLINED_ROUTINE, p.decide(quiet = false, routine = true, inCall = false, recall = true))
    assertEquals(MusicHandoffStatus.DECLINED_CALL, p.decide(quiet = false, routine = false, inCall = true, recall = true))
  }

  private val adopted = YtEngineReducer.reduce(YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true),
      YtAction.Adopt(ytQueueOf(3, start = 1), 40_000)).state

  @Test fun reportsPlayingOnlyOnceTheNewVideoReportsProgress() {
    val stale = adopted.copy(status = YtPlayerStatus.PLAYING) // the old song's state before the player catches up
    assertNull(p.progress(stale, elapsedMs = 1_000))
    val live = YtEngineReducer.reduce(stale, YtPlayerEvent.Progress(40_500, 200_000, ytTrack(2).videoId, 5)).state
    assertEquals(MusicHandoffStatus.PLAYING, p.progress(live, elapsedMs = 2_000))
    val ad = YtEngineReducer.reduce(stale, YtPlayerEvent.Progress(1_000, 15_000, "adAdAdAdAd1", 5)).state
    assertEquals(MusicHandoffStatus.PLAYING, p.progress(ad, elapsedMs = 2_000, previousVideoId = "oldOldOld01"))
    val late = YtEngineReducer.reduce(stale, YtPlayerEvent.Progress(90_000, 200_000, "oldOldOld01", 5)).state
    assertNull(p.progress(late, elapsedMs = 2_000, previousVideoId = "oldOldOld01"))
  }

  @Test fun applyingTheQueueLandsOnTheSentSongAndPosition() {
    assertEquals(ytTrack(2), adopted.queue.current!!.track)
    assertEquals(40_000, adopted.positionMs)
  }

  @Test fun blockedAutoplayHaltOrTimeoutFail() {
    assertEquals(MusicHandoffStatus.NEEDS_TAP, p.progress(adopted.copy(autoplayBlocked = true), 1_000))
    assertEquals(MusicHandoffStatus.FAILED, p.progress(adopted.copy(halt = YtHalt.PlayerUnavailable), 1_000))
    assertNull(p.progress(adopted, MusicTargetPolicy.START_MS - 1))
    assertEquals(MusicHandoffStatus.FAILED, p.progress(adopted, MusicTargetPolicy.START_MS))
  }

  @Test fun theSourceWaitsLongerThanTheTargetTriesToStart() {
    assertTrue(MusicMoveMachine.ANSWER_MS > MusicTargetPolicy.START_MS + MusicMoveMachine.FETCH_MS / 2)
  }

  // ---- room list ----

  @Test fun roomsExcludeThisPortalAndDisableOfflineAndQuietOnes() {
    val now = 10_000_000L
    val peers = listOf(
        DinnerPeer("self", "Family Area", now, false, true),
        DinnerPeer("living", "Living Area", now - 5_000, false, true),
        DinnerPeer("kitchen", "Kitchen", now - MusicMoveRooms.ONLINE_MS - 1, false, true),
        DinnerPeer("bed", "Bedroom", now, true, true))
    val rooms = MusicMoveRooms.from(peers, "self", now)
    assertEquals(listOf("Bedroom", "Kitchen", "Living Area"), rooms.map { it.name })
    assertEquals(listOf(false, false, true), rooms.map { it.enabled })
    assertEquals(listOf("Quiet hours", "Not answering", null), rooms.map { it.note })
  }

  // ---- guest queue (signed-out target) ----

  @Test fun aReceivedQueuePlaysWithoutAnAccount() {
    assertEquals(MusicV3Gate.GUEST, MusicV3Rules.gate(true, emptyList(), null, adding = false, signedOutLast = false, hasQueue = true))
    assertEquals(MusicV3Gate.GUEST, MusicV3Rules.gate(false, emptyList(), null, adding = false, signedOutLast = false, hasQueue = true))
    val revoked = YtAccount("UC1", "Rohit", null, needsSignIn = true)
    assertEquals(MusicV3Gate.GUEST, MusicV3Rules.gate(true, listOf(revoked), revoked, adding = false, signedOutLast = false, hasQueue = true))
    assertEquals(MusicV3Gate.SIGN_IN, MusicV3Rules.gate(true, emptyList(), null, adding = false, signedOutLast = false, hasQueue = false))
  }

  @Test fun guestCanStillChooseToSignIn() {
    assertEquals(MusicV3Gate.ADD_ACCOUNT, MusicV3Rules.gate(true, emptyList(), null, adding = true, signedOutLast = false, hasQueue = true))
    assertTrue(MusicV3Rules.canCancelSignIn(accounts = 0, hasQueue = true))
    assertFalse(MusicV3Rules.canCancelSignIn(accounts = 0, hasQueue = false))
    val rohit = YtAccount("UC1", "Rohit", null)
    assertEquals(MusicV3Gate.PLAYER, MusicV3Rules.gate(true, listOf(rohit), rohit, adding = false, signedOutLast = false, hasQueue = true))
  }
}
