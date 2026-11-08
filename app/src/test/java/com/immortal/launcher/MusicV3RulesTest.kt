/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtAccount
import com.immortal.launcher.youtube.YtSignInState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

class MusicV3RulesTest {
  private val rohit = YtAccount("UC1", "Rohit", null)
  private val revoked = YtAccount("UC2", "Maya", null, needsSignIn = true)

  @Test fun noClientAndNoAccountsSaysNotConfigured() =
      assertEquals(MusicV3Gate.NOT_CONFIGURED, MusicV3Rules.gate(false, emptyList(), null, adding = false, signedOutLast = false))

  @Test fun configuredWithoutAccountsShowsSignIn() =
      assertEquals(MusicV3Gate.SIGN_IN, MusicV3Rules.gate(true, emptyList(), null, adding = false, signedOutLast = false))

  @Test fun afterTheLastSignOutSaysNoOneIsSignedIn() =
      assertEquals(MusicV3Gate.NO_ONE, MusicV3Rules.gate(true, emptyList(), null, adding = false, signedOutLast = true))

  @Test fun anAccountOpensThePlayer() =
      assertEquals(MusicV3Gate.PLAYER, MusicV3Rules.gate(true, listOf(rohit), rohit, adding = false, signedOutLast = false))

  @Test fun storedAccountsStillPlayWithoutAClient() =
      assertEquals(MusicV3Gate.PLAYER, MusicV3Rules.gate(false, listOf(rohit), rohit, adding = false, signedOutLast = false))

  @Test fun addingAnAccountShowsTheQrAgain() =
      assertEquals(MusicV3Gate.ADD_ACCOUNT, MusicV3Rules.gate(true, listOf(rohit), rohit, adding = true, signedOutLast = false))

  @Test fun addingNeedsAClient() =
      assertEquals(MusicV3Gate.PLAYER, MusicV3Rules.gate(false, listOf(rohit), rohit, adding = true, signedOutLast = false))

  @Test fun aRevokedActiveAccountSignsInAgain() =
      assertEquals(MusicV3Gate.SIGN_IN, MusicV3Rules.gate(true, listOf(revoked), revoked, adding = false, signedOutLast = false))

  @Test fun successStaysOnScreenAlthoughTheAccountNowExists() =
      assertEquals(MusicV3Gate.SIGN_IN, MusicV3Rules.gate(true, listOf(rohit), rohit, adding = false,
          signedOutLast = false, showingSuccess = true))

  @Test fun upToFourAccounts() {
    assertTrue(MusicV3Rules.canAddAccount(3))
    assertFalse(MusicV3Rules.canAddAccount(4))
  }

  @Test fun showingCodeCountsDown() {
    val view = MusicV3Rules.signInView(
        YtSignInState.ShowingCode("KQWT-ZMHR", "https://www.google.com/device", expiresAtMs = 900_000), nowMs = 27_500)
    assertEquals(MusicV3SignInView.Code("KQWT-ZMHR", "https://www.google.com/device", "google.com/device", "14:33"), view)
  }

  @Test fun pollingPastExpiryReadsExpired() = assertEquals(MusicV3SignInView.Expired,
      MusicV3Rules.signInView(YtSignInState.Polling("A", "https://www.google.com/device", 1_000), nowMs = 1_000))

  @Test fun terminalStatesMapOneToOne() {
    assertEquals(MusicV3SignInView.Starting, MusicV3Rules.signInView(YtSignInState.Idle, 0))
    assertEquals(MusicV3SignInView.Denied, MusicV3Rules.signInView(YtSignInState.Denied, 0))
    assertEquals(MusicV3SignInView.Expired, MusicV3Rules.signInView(YtSignInState.Expired, 0))
    assertEquals(MusicV3SignInView.Offline, MusicV3Rules.signInView(YtSignInState.Offline, 0))
    assertEquals(MusicV3SignInView.NotConfigured, MusicV3Rules.signInView(YtSignInState.NotConfigured, 0))
    assertEquals(MusicV3SignInView.Failed, MusicV3Rules.signInView(YtSignInState.Failed(500), 0))
    assertEquals(MusicV3SignInView.Success("Rohit"), MusicV3Rules.signInView(YtSignInState.SignedIn(rohit), 0))
  }

  @Test fun timesFormat() {
    assertEquals("0:00", MusicV3Rules.formatTime(-5))
    assertEquals("4:12", MusicV3Rules.formatPosition(252_900))
    assertEquals("1:02:03", MusicV3Rules.formatPosition(3_723_000))
  }

  @Test fun searchHintOnlyAtTenOrFewer() {
    assertNull(MusicV3Rules.searchHint(11))
    assertEquals("10 searches left today", MusicV3Rules.searchHint(10))
    assertEquals("1 search left today", MusicV3Rules.searchHint(1))
    assertNull(MusicV3Rules.searchHint(0))
    assertTrue(MusicV3Rules.searchResting(0))
  }

  @Test fun submitNeedsTwoCharactersAndBudgetOrASavedSearch() {
    assertFalse(MusicV3Rules.canSubmit(" a ", 50, emptyList()))
    assertTrue(MusicV3Rules.canSubmit("ab", 50, emptyList()))
    assertFalse(MusicV3Rules.canSubmit("abba", 0, emptyList()))
    assertTrue(MusicV3Rules.canSubmit("ABBA", 0, listOf("abba")))
  }

  @Test fun recentSearchesAreNewestFirstAndDeduplicated() {
    val recent = listOf("one", "two", "three", "four", "five", "six")
    assertEquals(listOf("Two", "one", "three", "four", "five", "six"), MusicV3Rules.withRecent(recent, "  Two "))
    assertEquals(listOf("new", "one", "two", "three", "four", "five"), MusicV3Rules.withRecent(recent, "new"))
    assertEquals(recent, MusicV3Rules.withRecent(recent, "x"))
  }

  @Test fun tappedTrackIsFoundInThePlayableList() {
    val a = YtDataTrack("aaaaaaaaaaa", "A", "x", null)
    val b = YtDataTrack("bbbbbbbbbbb", "B", "x", null)
    assertEquals(0, MusicV3Rules.startIndex(b, listOf(b.copy(durationMs = 1))))
    assertNull(MusicV3Rules.startIndex(a, listOf(b)))
  }

  @Test fun dataTrackMapsToTheQueueTrack() {
    val mapped = YtDataTrack("aaaaaaaaaaa", "Low tide", "Marrow", null, 252_000).toQueueTrack()
    assertEquals(YtTrack("aaaaaaaaaaa", "Low tide", "Marrow", "", 252_000), mapped)
    assertEquals("u", YtDataTrack("aaaaaaaaaaa", "t", "a", "u").toQueueTrack().thumbnailUrl)
  }

  @Test fun skipToastExplainsEmbedBlocks() {
    assertEquals("Skipped “Slow coals”. The artist doesn't allow playing it outside YouTube.",
        MusicV3Rules.skipToast(YtSkipNotice(1, "Slow coals", YtSkipPolicy.reasonFor(150)!!)))
    assertEquals("Skipped “X”. Removed or private.", MusicV3Rules.skipToast(YtSkipNotice(2, "X", "Removed or private")))
  }

  @Test fun signOutCopySaysWhenMusicStops() {
    assertFalse(MusicV3Rules.signOutBody("Maya", stopsMusic = false).contains("stops"))
    assertTrue(MusicV3Rules.signOutBody("Maya", stopsMusic = true).endsWith("Music playing from this account stops."))
  }

  @Test fun countsAndInitials() {
    assertEquals("1 song", MusicV3Rules.upNextCount(1))
    assertEquals("9 songs", MusicV3Rules.upNextCount(9))
    assertEquals("Playlist", MusicV3Rules.songCount(null))
    assertEquals("R", MusicV3Rules.initial(" rohit"))
    assertEquals("?", MusicV3Rules.initial(""))
  }
}
