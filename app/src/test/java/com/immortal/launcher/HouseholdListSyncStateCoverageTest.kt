/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListSyncState.Arrival
import com.immortal.launcher.HouseholdListSyncState.PeerInfo
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Peer cap, status inputs, arrival lines and change counting of the list's sync state. */
class HouseholdListSyncStateCoverageTest {
  private val now = 1_800_000_000_000L
  private val zone = TimeZone.getTimeZone("UTC")

  private fun list(vararg texts: Pair<String, String>): HouseholdListState =
      texts.foldIndexed(HouseholdListState()) { i, s, (t, by) -> HouseholdListOps.add(s, t, by, now - 1_000 + i).state }

  @Test fun peerMapIsCappedKeepingTheMostRecentlySeen() {
    var s = HouseholdListSyncState()
    for (i in 0 until HouseholdListSyncState.MAX_PEERS) s = s.withPeer("p$i", PeerInfo("Room $i", true, now + i))
    assertEquals(HouseholdListSyncState.MAX_PEERS, s.peers.size)
    val more = s.withPeer("new", PeerInfo("New", true, now + 1_000))
    assertEquals(HouseholdListSyncState.MAX_PEERS, more.peers.size)
    assertTrue("new" in more.peers)
    assertTrue("oldest evicted", "p0" !in more.peers)
    val update = s.withPeer("p0", PeerInfo("Renamed", false, now))
    assertEquals("Renamed", update.peers.getValue("p0").name)
  }

  @Test fun firstSyncNamesThePeerOrAFallback() {
    val busy = HouseholdListSyncState(busy = 1)
    assertEquals("Getting the list from the other Portal…", busy.status(now, HouseholdListState(), zone).long)
    val named = busy.withPeer("p", PeerInfo("Den", true, now))
    assertEquals("Getting the list from Den…", named.status(now, HouseholdListState(), zone).long)
    assertEquals(HouseholdListSyncStatus.Kind.BUSY, named.status(now, list("Milk" to "Den"), zone).kind)
  }

  @Test fun offlineUsesLastUpOrNow() {
    val never = HouseholdListSyncState(online = false)
    assertEquals(HouseholdListSyncStatus.Kind.OFFLINE, never.status(now, HouseholdListState(), zone).kind)
    assertTrue(never.status(now, HouseholdListState(), zone).long.endsWith(HouseholdListSyncStatus.clock(now, zone)))
    val seen = HouseholdListSyncState(online = false, lastUpAt = now - 3_600_000)
    assertTrue(seen.status(now, HouseholdListState(), zone).long.endsWith(HouseholdListSyncStatus.clock(now - 3_600_000, zone)))
  }

  @Test fun pastRetryTimesAreIgnored() {
    assertEquals(HouseholdListSyncStatus.Kind.FAILING,
        HouseholdListSyncState(retryAt = now + 5_000).status(now, HouseholdListState(), zone).kind)
    assertEquals(HouseholdListSyncStatus.Kind.OK,
        HouseholdListSyncState(retryAt = now).status(now, HouseholdListState(), zone).kind)
  }

  @Test fun phonesSeenOutsideTheWindowAreNotCounted() {
    val s = HouseholdListSyncState(phones = mapOf("a" to now - HouseholdListSyncState.PHONE_WINDOW_MS, "b" to now - 1 - HouseholdListSyncState.PHONE_WINDOW_MS))
    assertEquals("Up to date with 1 phone", s.status(now, HouseholdListState(), zone).long)
  }

  @Test fun arrivalLineVariants() {
    val l = list("Milk" to "Den")
    val item = l.live().single()
    val base = HouseholdListSyncState()
    assertNull("no arrival", base.arrivalLine(item, now, zone))
    val offline = base.copy(arrivals = mapOf(item.id to Arrival(now, whileOffline = true)))
    assertEquals("While offline, Den", offline.arrivalLine(item, now, zone))
    val late = base.copy(arrivals = mapOf(item.id to Arrival(item.added.at + 90_001, whileOffline = false)))
    assertEquals("Added ${HouseholdListSyncStatus.clock(item.added.at, zone)}, Den", late.arrivalLine(item, item.added.at + 90_001, zone))
    val prompt = base.copy(arrivals = mapOf(item.id to Arrival(item.added.at + 90_000, whileOffline = false)))
    assertEquals("Just now, Den", prompt.arrivalLine(item, item.added.at + 90_000, zone))
    val edge = base.copy(arrivals = mapOf(item.id to Arrival(now, false)))
    assertTrue(edge.arrivalLine(item, now + HouseholdListSyncState.FRESH_MS, zone) != null)
    assertNull(edge.arrivalLine(item, now + HouseholdListSyncState.FRESH_MS + 1, zone))
  }

  @Test fun newArrivalsSkipOwnItemsUnchangedOnesButIncludeRevivals() {
    val before = list("Milk" to "Den", "Bread" to "Kitchen")
    val milk = before.liveByText("Milk")!!.id
    val bread = before.liveByText("Bread")!!.id
    val after = HouseholdListOps.add(before, "Eggs", "Den", now).state
    val eggs = after.liveByText("Eggs")!!.id
    assertEquals(setOf(eggs), HouseholdListSyncState.newArrivals(before, after, "Kitchen", now, false).keys)
    val removedBefore = HouseholdListOps.remove(before, milk, "Den", now).state
    val arrivals = HouseholdListSyncState.newArrivals(removedBefore, after, "Kitchen", now, true)
    assertEquals(setOf(milk, eggs), arrivals.keys)
    assertEquals(Arrival(now, true), arrivals.getValue(milk))
    assertTrue(bread !in HouseholdListSyncState.newArrivals(HouseholdListState(), after, "Kitchen", now, false))
  }

  @Test fun changedCountCountsAddsEditsAndDrops() {
    val a = list("Milk" to "Den")
    val b = HouseholdListOps.add(a, "Eggs", "Den", now).state
    assertEquals(0, HouseholdListSyncState.changedCount(a, a))
    assertEquals(1, HouseholdListSyncState.changedCount(a, b))
    assertEquals(1, HouseholdListSyncState.changedCount(b, a))
    val ticked = HouseholdListOps.setDone(b, a.live().single().id, true, "Den", now + 1).state
    assertEquals(2, HouseholdListSyncState.changedCount(a, ticked))
  }

  @Test fun retryDelayIsClampedAtBothEnds() {
    assertEquals(HouseholdListSyncState.RETRY_MS.first(), HouseholdListSyncState.retryDelay(-3))
    assertEquals(HouseholdListSyncState.RETRY_MS[2], HouseholdListSyncState.retryDelay(2))
    assertEquals(HouseholdListSyncState.RETRY_MS.last(), HouseholdListSyncState.retryDelay(99))
  }

  @Test fun defaultZoneOverloadsAndAuxiliaryRecordsWork() {
    val l = list("Milk" to "Den")
    val item = l.live().single()
    val s = HouseholdListSyncState(arrivals = mapOf(item.id to Arrival(now, whileOffline = true)),
        catchUp = HouseholdListSyncState.CatchUp(since = now - 1, arrived = 2, sent = 1),
        notice = HouseholdListSyncState.Notice("Back online.", now + HouseholdListSyncState.NOTICE_MS, 1))
    assertEquals("While offline, Den", s.arrivalLine(item, now))
    assertEquals(HouseholdListSyncStatus.Kind.OK, s.status(now, l).kind)
    assertEquals(2, s.catchUp!!.arrived)
    assertEquals(0, HouseholdListSyncState.CatchUp(now).sent)
  }
}
