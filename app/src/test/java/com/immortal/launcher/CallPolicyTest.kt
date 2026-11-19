/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Ring, quiet hours, drop-in and permission rules, plus presence, grid and quality policy. */
class CallPolicyTest {
  private val base = IncomingContext(CallKind.CALL, CallWhoCanCall.HOME, quiet = false, quietMode = CallQuietMode.BLOCK,
      dropInsOn = true, dropInFromCaller = DropInPolicy.ASK, busy = false)

  @Test fun normalCallRings() = assertEquals(IncomingDecision.Ring(false), CallPolicy.decide(base))

  @Test fun busyWinsOverEverything() =
      assertEquals(IncomingDecision.Reject(CallReply.BUSY), CallPolicy.decide(base.copy(busy = true, quiet = true)))

  @Test fun nobodyCanCallTurnsCallsAndDropInsAway() {
    assertEquals(IncomingDecision.Reject(CallReply.CALLS_OFF), CallPolicy.decide(base.copy(whoCanCall = CallWhoCanCall.NOBODY)))
    assertEquals(IncomingDecision.Reject(CallReply.CALLS_OFF),
        CallPolicy.decide(base.copy(whoCanCall = CallWhoCanCall.NOBODY, kind = CallKind.DROP_IN)))
  }

  @Test fun quietHoursBlockByDefaultOrRingSilently() {
    assertEquals(IncomingDecision.Reject(CallReply.QUIET), CallPolicy.decide(base.copy(quiet = true)))
    assertEquals(IncomingDecision.Ring(true), CallPolicy.decide(base.copy(quiet = true, quietMode = CallQuietMode.VISUAL)))
  }

  @Test fun dropInsFollowThePairPolicy() {
    val drop = base.copy(kind = CallKind.DROP_IN)
    assertEquals(IncomingDecision.Ring(false), CallPolicy.decide(drop.copy(dropInFromCaller = DropInPolicy.ASK)))
    assertEquals(IncomingDecision.Countdown, CallPolicy.decide(drop.copy(dropInFromCaller = DropInPolicy.CHIME)))
    assertEquals(IncomingDecision.Reject(CallReply.DROP_INS_OFF), CallPolicy.decide(drop.copy(dropInFromCaller = DropInPolicy.OFF)))
    assertEquals(IncomingDecision.Reject(CallReply.DROP_INS_OFF),
        CallPolicy.decide(drop.copy(dropInsOn = false, dropInFromCaller = DropInPolicy.CHIME)))
  }

  @Test fun dropInsNeverJoinInQuietHours() {
    val drop = base.copy(kind = CallKind.DROP_IN, dropInFromCaller = DropInPolicy.CHIME, quiet = true)
    assertEquals(IncomingDecision.Reject(CallReply.QUIET), CallPolicy.decide(drop))
    assertEquals("becomes a (silent) ring request, never a join", IncomingDecision.Ring(true),
        CallPolicy.decide(drop.copy(quietMode = CallQuietMode.VISUAL)))
  }

  @Test fun presenceFromHello() {
    val p = CallPeer("a", "Kitchen", seenMs = 1_000)
    assertEquals(CallPresence.AVAILABLE, CallPolicy.presence(p, 1_000 + CallTimings.OFFLINE_AFTER_MS))
    assertEquals(CallPresence.OFFLINE, CallPolicy.presence(p, 1_001 + CallTimings.OFFLINE_AFTER_MS))
    assertEquals(CallPresence.IN_CALL, CallPolicy.presence(p.copy(inCall = true, quiet = true), 1_000))
    assertEquals(CallPresence.QUIET, CallPolicy.presence(p.copy(quiet = true), 1_000))
  }

  @Test fun dropInOnUsesWhatThatRoomAnnouncedForUs() {
    val p = CallPeer("a", "Kitchen", 0, dropInsOn = true, dropInPolicies = mapOf("me" to DropInPolicy.CHIME))
    assertEquals(DropInPolicy.CHIME, CallPolicy.dropInOn(p, "me"))
    assertEquals("rooms default to Ask", DropInPolicy.ASK, CallPolicy.dropInOn(p, "other"))
    assertEquals(DropInPolicy.OFF, CallPolicy.dropInOn(p.copy(dropInsOn = false), "me"))
  }

  @Test fun gridEncodesDecodesAndCycles() {
    val map = mapOf("bbb" to DropInPolicy.CHIME, "aaa" to DropInPolicy.OFF)
    assertEquals("aaa:o,bbb:c", DropInGrid.encode(map))
    assertEquals(map, DropInGrid.decode("aaa:o,bbb:c"))
    assertEquals(emptyMap<String, DropInPolicy>(), DropInGrid.decode(""))
    assertNull(DropInGrid.decode("aaa:z"))
    assertNull(DropInGrid.decode("no colon"))
    assertNull(DropInGrid.decode("bad id!:a"))
    assertNull(DropInGrid.decode((1..17).joinToString(",") { "id$it:a" }))
    assertEquals(DropInPolicy.CHIME, DropInGrid.cycle(emptyMap(), "x")["x"])
    assertEquals(DropInPolicy.OFF, DropInGrid.cycle(mapOf("x" to DropInPolicy.CHIME), "x")["x"])
    assertEquals(DropInPolicy.ASK, DropInPolicy.OFF.next())
  }

  @Test fun peersMergeKeepFavouritesAndCap() {
    var list = listOf(CallPeer("a", "Kitchen", 1, favourite = true))
    list = CallPeers.merge(list, CallPeer("a", "Kitchen", 5))
    assertTrue(list.single().favourite)
    assertEquals(5L, list.single().seenMs)
    repeat(10) { list = CallPeers.merge(list, CallPeer("p$it", "Room $it", it.toLong())) }
    assertEquals(CallPeers.CAPACITY, list.size)
  }

  @Test fun learnAddsUnknownRoomsWithoutOverwritingFresherOnes() {
    val list = listOf(CallPeer("a", "Kitchen", 100, inCall = true))
    assertEquals(list, CallPeers.learn(list, "a", "Old name", 50))
    assertEquals(200L, CallPeers.learn(list, "a", "Kitchen", 200).single().seenMs)
    assertEquals(2, CallPeers.learn(list, "b", "Den", 0).size)
  }

  @Test fun homeOrderIsFavouritesFirstThenByName() {
    val list = listOf(CallPeer("1", "Zed", 0), CallPeer("2", "Den", 0), CallPeer("3", "Kitchen", 0, favourite = true))
    assertEquals(listOf("Kitchen", "Den"), CallPeers.forHome(list, 2).map { it.name })
    assertEquals(listOf("3"), CallPeers.forHome(CallPeers.setFavourite(list, "3", true), 1).map { it.id })
  }

  @Test fun qualityStepsDownOnLowMemoryOrCpuStarvation() {
    assertEquals(CallQuality.AUTO, CallingQualityPolicy.pick(CallQuality.AUTO, 300L * 1024 * 1024, false))
    assertEquals(CallQuality.LOW_MEMORY, CallingQualityPolicy.pick(CallQuality.BEST, 100L * 1024 * 1024, false))
    assertEquals(CallQuality.LOW_MEMORY, CallingQualityPolicy.pick(CallQuality.AUTO, 900L * 1024 * 1024, true))
    assertTrue(CallingQualityPolicy.shouldDegrade(CallStats(0, sendFps = 14.0, limitedBy = "cpu")))
    assertFalse(CallingQualityPolicy.shouldDegrade(CallStats(0, sendFps = 29.0, limitedBy = "cpu")))
    assertFalse(CallingQualityPolicy.shouldDegrade(CallStats(0, sendFps = 14.0, limitedBy = "bandwidth")))
    assertFalse("no frames yet is not starvation", CallingQualityPolicy.shouldDegrade(CallStats(0, sendFps = 0.0, limitedBy = "cpu")))
  }

  @Test fun shutterIsAllBlackOnly() {
    assertTrue(CallShutter.isClosed(3.0))
    assertFalse("a dark room still has some light", CallShutter.isClosed(20.0))
  }

  @Test fun enumsFallBackToSafeDefaults() {
    assertEquals(CallQuality.AUTO, CallQuality.fromWire("junk"))
    assertEquals(CallRingtone.HEARTH, CallRingtone.fromWire(null))
    assertEquals(CallWhoCanCall.HOME, CallWhoCanCall.fromWire("x"))
    assertEquals(CallQuietMode.BLOCK, CallQuietMode.fromWire("x"))
    CallEndReason.entries.forEach { assertEquals(it, CallEndReason.fromWire(it.wire)) }
    CallReply.entries.forEach { assertEquals(it, CallReply.fromWire(it.wire)) }
  }
}
