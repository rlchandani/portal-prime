/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import org.junit.Test
import org.mockito.Mockito

/** A datagram can never stop the household bus listener (a dead listener is a silent DoS). */
class HouseholdBusResilienceTest {

  @Test fun aPacketWhoseHandlingThrowsIsDroppedNotPropagated() {
    // A Context whose prefs are unavailable makes key lookup itself throw, the path that used to
    // escape the per-handler catch and end the listener loop for good.
    val context = Mockito.mock(Context::class.java)
    Mockito.mockStatic(Log::class.java).use {
      HouseholdBus.handlePacket(context, "IMMORTAL_HH/1|{}|00")
      HouseholdBus.handlePacket(context, "")
      HouseholdBus.handlePacket(context, "\u0000".repeat(5_600))
    }
  }

  @Test fun workAHandlerDefersIsContainedToo() {
    var ran = false
    Mockito.mockStatic(Log::class.java).use {
      HouseholdBus.contained("share_note") { ran = true; error("bug triggered by a peer message") }.run()
    }
    org.junit.Assert.assertTrue(ran)
  }

  @Test fun theListSyncPeerMapStaysBoundedUnderClaimedSenderIds() {
    var s = HouseholdListSyncState()
    for (i in 1..500) s = s.withPeer("dev$i", HouseholdListSyncState.PeerInfo("Room $i", true, i.toLong()))
    org.junit.Assert.assertEquals(HouseholdListSyncState.MAX_PEERS, s.peers.size)
    org.junit.Assert.assertTrue("keeps the most recently heard", "dev500" in s.peers && "dev1" !in s.peers)
    val updated = s.withPeer("dev500", HouseholdListSyncState.PeerInfo("Kitchen", false, 999))
    org.junit.Assert.assertEquals("Kitchen", updated.peers["dev500"]!!.name)
  }
}
