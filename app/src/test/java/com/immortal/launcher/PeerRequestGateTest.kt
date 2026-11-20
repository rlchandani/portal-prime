/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito

/**
 * Every household-signed peer route refuses an unsigned, forged, stale or wrong-feature request
 * from its header alone — before reading (or hashing, or parsing) a byte of the body.
 */
class PeerRequestGateTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 7).toByte() }
  private val now = System.currentTimeMillis()

  /** A body that fails the test if a route reads it. */
  private class Untouchable : InputStream() {
    override fun read(): Int = throw AssertionError("body read before the header was verified")
    override fun read(b: ByteArray, off: Int, len: Int): Int = read()
  }

  private fun context(): Context {
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(prefs.getString(anyString(), isNull())).thenReturn(HouseholdKey.toHex(key))
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
    Mockito.`when`(context.applicationContext).thenReturn(context)
    return context
  }

  private fun header(type: String, signWith: ByteArray = key, ts: Long = now): String =
      HouseholdMessage.encode(HouseholdMessage(type, "intruder", ts, "n${ts}x", JSONObject().put("s", "00")), signWith)

  private fun request(path: String, header: String?): FleetHttpServer.Request =
      FleetHttpServer.Request("POST", path, "", if (header == null) emptyMap() else mapOf("x-immortal-household" to header),
          Untouchable(), 1000)

  private data class PeerRoute(val path: String, val type: String, val handle: (Context, FleetHttpServer.Request) -> FleetHttpServer.Response)

  private val routes = listOf(
      PeerRoute(ChoresPeerRoute.PATH, ChorePeerAuth.REQUEST_TYPE, ChoresPeerRoute::handle),
      PeerRoute(HouseholdListPeerRoute.PATH, HouseholdListPeerAuth.REQUEST_TYPE, HouseholdListPeerRoute::handle),
      PeerRoute(RemindersPeerRoute.PATH, ReminderPeerAuth.REQUEST_TYPE, RemindersPeerRoute::handle),
      PeerRoute(CallSignalAuth.PATH, CallSignalAuth.REQUEST_TYPE, CallPeerRoute::handle),
      PeerRoute("/remote/settingspeer", SettingsSyncWire.REQUEST_TYPE, SettingsSyncNet::handle),
      PeerRoute("/remote/musicpeer", MusicHandoffAuth.FETCH_TYPE, MusicHandoffNet::handle),
  )

  @Test fun theHeaderIsHouseholdListPeerAuthsHeader() {
    assertEquals("x-immortal-household", HouseholdListPeerAuth.HEADER.lowercase())
  }

  @Test fun everyPeerRouteRefusesABadHeaderWithoutReadingTheBody() {
    val context = context()
    for (route in routes) {
      val cases = mapOf(
          "no header" to null,
          "garbage" to "IMMORTAL_HH/1|{}|00",
          "forged key" to header(route.type, signWith = other),
          "stale" to header(route.type, ts = now - 10 * 60_000),
          "another feature's request" to header(if (route.type == "chores_sync") "list_sync" else "chores_sync"),
      )
      for ((name, h) in cases) {
        val response = route.handle(context, request(route.path, h))
        assertEquals("${route.path}: $name", 401, response.status)
      }
    }
  }

  @Test fun theGateChecksSignatureFreshnessAndTypeButLeavesTheReplayDecisionToTheRoute() {
    val wire = header(ChorePeerAuth.REQUEST_TYPE)
    assertTrue(PeerRequestGate.admits(wire, ChorePeerAuth.REQUEST_TYPE, key, now))
    assertTrue("gate doesn't burn the nonce", PeerRequestGate.admits(wire, ChorePeerAuth.REQUEST_TYPE, key, now))
    assertFalse(PeerRequestGate.admits(wire, HouseholdListPeerAuth.REQUEST_TYPE, key, now))
    assertFalse(PeerRequestGate.admits(wire, ChorePeerAuth.REQUEST_TYPE, other, now))
    assertFalse(PeerRequestGate.admits(wire, ChorePeerAuth.REQUEST_TYPE, key, now + 5 * 60_000))
    assertFalse(PeerRequestGate.admits(null, ChorePeerAuth.REQUEST_TYPE, key, now))
    assertFalse(PeerRequestGate.admits("x".repeat(100_000), ChorePeerAuth.REQUEST_TYPE, key, now))
    // decode still decides replays after the gate has looked.
    val replay = HouseholdMessage.ReplayGuard()
    assertNotNull(HouseholdMessage.decode(wire, key, now, replay))
    assertEquals(null, HouseholdMessage.decode(wire, key, now, replay))
  }

  @Test fun aGenuineSignedRequestStillVerifiesAfterTheGate() {
    val body = """{"name":"Kitchen","state":{}}"""
    val wire = ChorePeerAuth.requestHeader(key, "kitchen", "nonce1", now, body)!!
    assertTrue(PeerRequestGate.admits(wire, ChorePeerAuth.REQUEST_TYPE, key, now))
    assertNotNull(ChorePeerAuth.verifyRequest(wire, body, key, now, HouseholdMessage.ReplayGuard(), "living"))
  }
}
