/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListSyncStatus.Inputs
import com.immortal.launcher.HouseholdListSyncStatus.Kind
import com.immortal.launcher.HouseholdListSyncStatus.Peer
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HouseholdListSyncStatusTest {
  private val zone = TimeZone.getTimeZone("America/Los_Angeles")
  private val at804 = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 7, 8, 4) }.timeInMillis
  private val now = at804 + 30 * 60_000

  private fun status(i: Inputs) = HouseholdListSyncStatus.compute(i, now, zone)
  private val base = Inputs(online = true, lastUpAt = now, peers = listOf(Peer("Living Area", true)), phones = 2)

  @Test fun upToDate_namesWhoIsInSync() {
    val s = status(base)
    assertEquals(Kind.OK, s.kind)
    assertEquals("Up to date", s.short)
    assertEquals("Up to date with Living Area and 2 phones", s.long)
    assertEquals("Up to date with Living Area", status(base.copy(phones = 0)).long)
  }

  @Test fun offline_showsWhenTheListIsFrom() {
    val s = status(base.copy(online = false, lastUpAt = at804))
    assertEquals(Kind.OFFLINE, s.kind)
    assertEquals("Not synced. Showing the list from 8:04.", s.short)
  }

  @Test fun failing_countsDownToTheRetry() {
    val s = status(base.copy(retryAt = now + 7_200))
    assertEquals(Kind.FAILING, s.kind)
    assertEquals("Couldn’t sync. Trying again in 8 s.", s.short)
    assertEquals("Couldn’t reach the other devices. Trying again in 8 s.", s.long)
  }

  @Test fun peerDown_isUpToDateHereWithAWord() {
    val s = status(base.copy(peers = listOf(Peer("Living Area", false))))
    assertEquals(Kind.PARTIAL, s.kind)
    assertEquals("Up to date here. Living Area is offline and will catch up.", s.long)
  }

  @Test fun busyAndFirstSync() {
    assertEquals("Syncing…", status(base.copy(busy = true)).short)
    val first = status(base.copy(firstSyncFrom = "Family Area"))
    assertEquals(Kind.FIRST, first.kind)
    assertEquals("Getting the list from Family Area…", first.long)
  }

  @Test fun noPeersKnownYet_isJustUpToDateHere() {
    assertEquals("Up to date on this Portal", status(base.copy(peers = emptyList(), phones = 0)).long)
  }

  @Test fun catchUpMessage_matchesThePrototype() {
    assertEquals("Back online. 1 change arrived from while this Portal was offline. Your 1 sent.",
        HouseholdListSyncStatus.catchUpMessage(arrived = 1, sent = 1))
    assertEquals("Back online. Nothing changed elsewhere.", HouseholdListSyncStatus.catchUpMessage(0, 0))
    assertEquals("Back online. 3 changes arrived from while this Portal was offline.", HouseholdListSyncStatus.catchUpMessage(3, 0))
  }

  @Test fun wireStates_matchTheToolkitContract() {
    assertEquals(listOf("ok", "partial", "syncing", "offline", "failing", "first"),
        Kind.values().map(HouseholdListRoute::wireState))
  }
}
