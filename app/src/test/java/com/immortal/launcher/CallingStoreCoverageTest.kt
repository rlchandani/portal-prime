/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The persisted room directory codec: every flag round-trips, and stored junk is dropped. */
class CallingStoreCoverageTest {
  private val kitchen = "22222222-2222-2222-2222-222222222222"
  private val den = "33333333-3333-3333-3333-333333333333"
  /** A generic private-range placeholder; the codec only keeps home-LAN addresses. */
  private val lanHost = "10.0.0.2"

  private fun peer(id: String = kitchen) = CallPeer(id, "Kitchen", 1_000, inCall = true, quiet = true, acceptsCalls = false,
      dropInsOn = true, dropInPolicies = mapOf(den to DropInPolicy.CHIME), favourite = true, host = lanHost, port = 8723)

  @Test fun everyFieldRoundTrips() {
    val list = listOf(peer(), CallPeer(den, "Den", 5))
    assertEquals(list, CallPeerCodec.decode(CallPeerCodec.encode(list)))
  }

  @Test fun aPeerWithoutAnAddressStoresNoHostOrPort() {
    val o = JSONArray(CallPeerCodec.encode(listOf(CallPeer(den, "Den", 5)))).getJSONObject(0)
    assertFalse(o.has("h"))
    assertFalse(o.has("pt"))
  }

  @Test fun addressesOutsideTheHomeLanOrWithoutAValidPortAreDropped() {
    val good = JSONArray(CallPeerCodec.encode(listOf(peer()))).getJSONObject(0)
    fun decodeWith(key: String, value: Any?): CallPeer =
        CallPeerCodec.decode(JSONArray().put(JSONObject(good.toString()).apply { if (value == null) remove(key) else put(key, value) }).toString()).single()
    val publicHost = decodeWith("h", "192.0.2.10")
    assertNull(publicHost.host)
    assertEquals(0, publicHost.port)
    val badPort = decodeWith("pt", 70_000)
    assertNull(badPort.host)
    assertEquals(0, badPort.port)
    val noPort = decodeWith("pt", null)
    assertNull(noPort.host)
    assertEquals(0, noPort.port)
  }

  @Test fun missingFlagsTakeTheirDefaults() {
    val minimal = JSONArray().put(JSONObject().put("id", den).put("n", "Den"))
    val p = CallPeerCodec.decode(minimal.toString()).single()
    assertEquals(CallPeer(den, "Den", 0L), p)
    assertTrue("calls are accepted unless a room said otherwise", p.acceptsCalls)
  }

  @Test fun aCorruptDropInGridFallsBackToEmpty() {
    val raw = JSONArray().put(JSONObject(JSONArray(CallPeerCodec.encode(listOf(peer()))).getJSONObject(0).toString()).put("dp", "bad grid"))
    assertEquals(emptyMap<String, DropInPolicy>(), CallPeerCodec.decode(raw.toString()).single().dropInPolicies)
  }

  @Test fun junkEntriesDuplicatesAndOverflowAreDropped() {
    val good = JSONArray(CallPeerCodec.encode(listOf(peer()))).getJSONObject(0)
    val arr = JSONArray().put(good).put(JSONObject(good.toString()).put("n", "Kitchen again"))
        .put("str").put(JSONObject().put("id", "bad id").put("n", "X")).put(JSONObject().put("id", den).put("n", ""))
    val decoded = CallPeerCodec.decode(arr.toString())
    assertEquals(listOf("Kitchen"), decoded.map { it.name })
    assertEquals(emptyList<CallPeer>(), CallPeerCodec.decode("nope"))
    assertEquals(emptyList<CallPeer>(), CallPeerCodec.decode(null))
    val many = (1..CallPeers.CAPACITY + 2).map { CallPeer("room$it", "Room $it", it.toLong()) }
    assertEquals(CallPeers.CAPACITY, CallPeerCodec.decode(CallPeerCodec.encode(many)).size)
  }
}
