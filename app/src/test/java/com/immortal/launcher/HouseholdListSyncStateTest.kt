/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class HouseholdListSyncStateTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val now = 1_800_000_000_000L
  private val fa = "Family Area"
  private val ph = "Rohit’s Pixel"

  @Test fun arrivals_markOnlyOtherDevicesNewItems() {
    val before = HouseholdListOps.add(HouseholdListState(), "Eggs", fa, now - 5_000).state
    val mine = HouseholdListOps.add(before, "Bread", fa, now - 100).state
    val after = HouseholdListOps.add(mine, "Milk", ph, now - 200).state
    val arrivals = HouseholdListSyncState.newArrivals(before, after, self = fa, nowMs = now, whileOffline = false)
    assertEquals(setOf(HouseholdListText.idFor("Milk")), arrivals.keys)
  }

  @Test fun arrivalLine_saysJustNow_thenFades() {
    val s = HouseholdListOps.add(HouseholdListState(), "Milk", ph, now - 1_000).state
    val milk = s.liveByText("Milk")!!
    val sync = HouseholdListSyncState(arrivals = mapOf(milk.id to HouseholdListSyncState.Arrival(now, false)))
    assertEquals("Just now, Rohit’s Pixel", sync.arrivalLine(milk, now + 1_000, zone))
    assertNull(sync.arrivalLine(milk, now + HouseholdListSyncState.FRESH_MS + 1, zone))
    val offline = sync.copy(arrivals = mapOf(milk.id to HouseholdListSyncState.Arrival(now, true)))
    assertEquals("While offline, Rohit’s Pixel", offline.arrivalLine(milk, now, zone))
  }

  @Test fun firstSync_onlyWhileEmptyAndExchanging() {
    val list = HouseholdListState()
    val s = HouseholdListSyncState(busy = 1, peers = mapOf("p1" to HouseholdListSyncState.PeerInfo(fa, true, now)))
    assertEquals("Getting the list from Family Area…", s.status(now, list, zone).long)
    assertEquals(HouseholdListSyncStatus.Kind.BUSY, s.copy(everExchanged = true).status(now, list, zone).kind)
  }

  @Test fun phonesCount_onlyRecentOnes() {
    val s = HouseholdListSyncState(lastUpAt = now, phones = mapOf(ph to now - 1_000, "Old phone" to now - 3 * 86_400_000L))
    assertEquals("Up to date with 1 phone", s.status(now, HouseholdListState(), zone).long)
  }

  @Test fun retryBackoff_isBounded() {
    assertEquals(10_000L, HouseholdListSyncState.retryDelay(0))
    assertEquals(300_000L, HouseholdListSyncState.retryDelay(42))
  }
}
