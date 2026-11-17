/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bus bodies carry durations (clock-skew safe), fit one datagram, and reject junk from peers. */
class SharedTimerWireTest {
  private val kitchen = "11111111-1111-1111-1111-111111111111"
  private val sentAt = 1_000_000L
  private val pasta = SharedTimerOps.create("a1b2c3d4e5f6", "Pasta", "Kitchen", kitchen, 600_000L, sentAt, kitchen)!!

  @Test fun runningTimerTravelsAsTimeLeftAndReanchorsToTheReceiversClock() {
    val body = SharedTimerWire.encode(pasta, sentAt + 100_000L)
    assertEquals(500_000L, body.getLong("rem"))
    assertFalse("no wall clock on the wire", body.toString().contains((sentAt + 600_000L).toString()))
    // The receiver's clock is two hours behind the sender's; it still has 500 s to go.
    val receiverNow = sentAt - 7_200_000L
    val back = SharedTimerWire.decode(body, receiverNow)!!
    assertEquals(receiverNow + 500_000L, back.endAtMs)
    assertEquals(500_000L, back.remaining(receiverNow))
    assertEquals(listOf(pasta.id, pasta.name, pasta.room, pasta.origin, pasta.seq, pasta.by, pasta.state),
        listOf(back.id, back.name, back.room, back.origin, back.seq, back.by, back.state))
  }

  @Test fun pausedAndRingingKeepTheirMeaning() {
    val paused = SharedTimerOps.pause(pasta, sentAt + 60_000L, kitchen)!!
    val back = SharedTimerWire.decode(SharedTimerWire.encode(paused, sentAt + 999_000L), 5L)!!
    assertEquals(SharedTimerState.PAUSED, back.state)
    assertEquals(540_000L, back.remaining(123L))
    val ringing = SharedTimerOps.ring(pasta, sentAt, kitchen)!!
    assertEquals(SharedTimerState.RINGING, SharedTimerWire.decode(SharedTimerWire.encode(ringing, sentAt), 0L)!!.state)
  }

  @Test fun longestTimerFitsOneSignedDatagram() {
    val long = pasta.copy(name = "N".repeat(DinnerWire.MAX_NAME), room = "R".repeat(DinnerWire.MAX_NAME),
        origin = "o".repeat(64), by = "b".repeat(64), seq = Long.MAX_VALUE / 4, durationMs = SharedTimer.MAX_DURATION_MS)
    val body = SharedTimerWire.encode(long, sentAt)
    val wire = HouseholdMessage.encodeOrNull(HouseholdMessage(SharedTimerWire.DONE, kitchen, 1L, "n".repeat(24), body), ByteArray(32))
    assertNotNull("a timer must fit the bus limit", wire)
  }

  @Test fun messageTypesAreValidBusTypes() {
    (SharedTimerWire.TIMER_TYPES + SharedTimerWire.ASK).forEach { type ->
      assertNotNull(type, HouseholdMessage.encodeOrNull(HouseholdMessage(type, kitchen, 1L, "nonce1", JSONObject()), ByteArray(32)))
    }
  }

  @Test fun decodingRejectsJunkFromPeers() {
    val good = SharedTimerWire.encode(pasta, sentAt)
    fun with(key: String, value: Any?) = JSONObject(good.toString()).put(key, value)
    assertNotNull(SharedTimerWire.decode(good, 0L))
    assertNull(SharedTimerWire.decode(with("i", "../../x"), 0L))
    assertNull(SharedTimerWire.decode(with("n", ""), 0L))
    assertNull(SharedTimerWire.decode(with("r", 7), 0L))
    assertNull(SharedTimerWire.decode(with("o", "bad id with spaces"), 0L))
    assertNull(SharedTimerWire.decode(with("s", "explode"), 0L))
    assertNull(SharedTimerWire.decode(with("d", 0), 0L))
    assertNull(SharedTimerWire.decode(with("d", "600000"), 0L))
    assertNull(SharedTimerWire.decode(with("rem", -1), 0L))
    assertNull(SharedTimerWire.decode(with("rem", 1.5), 0L))
    assertNull(SharedTimerWire.decode(with("q", 0), 0L))
    assertNull(SharedTimerWire.decode(JSONObject(), 0L))
  }

  @Test fun phoneOriginIsAccepted() {
    val fromPhone = pasta.copy(origin = SharedTimer.PHONE_ORIGIN)
    assertEquals(SharedTimer.PHONE_ORIGIN, SharedTimerWire.decode(SharedTimerWire.encode(fromPhone, sentAt), 0L)!!.origin)
  }

  @Test fun routeJsonIsDurationsOnly() {
    val json = SharedTimerWire.toRouteJson(pasta, sentAt + 10_000L, kitchen)
    assertEquals(590_000L, json.getLong("remainingMs"))
    assertEquals("run", json.getString("state"))
    assertTrue(json.getBoolean("startedHere"))
    assertFalse(json.getBoolean("paused"))
    assertFalse(json.has("endAtMs"))
    assertFalse(SharedTimerWire.toRouteJson(pasta, sentAt, "someone-else").getBoolean("startedHere"))
  }

  @Test fun storeRoundTripsExactlyAndSurvivesCorruption() {
    val paused = SharedTimerOps.pause(pasta.copy(id = "bbbbbbbb"), sentAt + 5, kitchen)!!
    val saved = listOf(pasta, paused)
    assertEquals(saved, SharedTimerWire.decodeStore(SharedTimerWire.encodeStore(saved)))
    assertEquals(emptyList<SharedTimer>(), SharedTimerWire.decodeStore("{not json"))
    assertEquals(emptyList<SharedTimer>(), SharedTimerWire.decodeStore(null))
    assertEquals(1, SharedTimerWire.decodeStore("""[{"i":"aaaaaaaa","s":"run"},{"i":"!!","s":"run"},{"i":"cccccccc","s":"?"}]""").size)
  }

  @Test fun legacyKitchenTimerIdsAreAccepted() {
    assertTrue(SharedTimerWire.isId("mw069oc0-102"))
    assertFalse(SharedTimerWire.isId("-leading"))
    assertFalse(SharedTimerWire.isId("UPPER"))
  }
}
