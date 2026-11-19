package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bus and peer-route formats: structure, size limits, forgery and replay. */
class SettingsSyncWireTest {
  private val key = ByteArray(32) { it.toByte() }
  private val otherKey = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L
  private val w = SettingsSyncWire

  private fun rec(value: Any = "dark", device: String = "dev-A") =
      SyncRecord("immortal.settingsTheme", value, SyncVersion(7, device), now)

  @Test
  fun recordsRoundTripWithTheBriefsFieldNames() {
    val json = w.recordJson(rec())
    assertEquals(setOf("key", "value", "version", "deviceId", "ts"), json.keys().asSequence().toSet())
    assertEquals(rec(), w.parseRecord(JSONObject(json.toString())))
  }

  @Test
  fun malformedRecordsAreDropped() {
    assertNull(w.parseRecord(null))
    assertNull(w.parseRecord(JSONObject().put("key", "a.b")))
    assertNull(w.parseRecord(w.recordJson(rec()).put("deviceId", "../../etc")))
    assertNull(w.parseRecord(w.recordJson(rec()).put("value", JSONObject().put("x", 1))))
    assertNull(w.parseRecord(w.recordJson(rec()).put("version", "7")))
    assertNull(w.parseRecord(w.recordJson(rec()).put("key", "x".repeat(65))))
  }

  @Test
  fun aBusChangeMustComeFromTheDeviceThatWroteIt() {
    val body = w.changedBody(rec())!!
    assertNotNull(w.parseChanged("dev-A", body))
    assertNull("a relayed or spoofed author is refused on the bus", w.parseChanged("dev-B", body))
  }

  @Test
  fun aChangeTooBigForOnePacketGoesByExchangeInstead() {
    assertNull(w.changedBody(rec(value = "u".repeat(w.MAX_CHANGED_BODY))))
    val msg = HouseholdMessage(w.CHANGED_TYPE, "dev-A", now, "abcdef012345", w.changedBody(rec("x".repeat(700)))!!)
    assertNotNull("the largest bus change still fits the bus envelope", HouseholdMessage.encodeOrNull(msg, key))
  }

  @Test
  fun signalsCarryTheJoiningFlagAndOnlyPrivateAddresses() {
    val s = SettingsSyncWire.Signal("0123456789ab", "192.168.1.20", 8723, joining = true)
    assertEquals(s, w.parseSignal(w.signalBody(s)))
    assertFalse(w.parseSignal(w.signalBody(s.copy(joining = false)))!!.joining)
    assertNull(w.parseSignal(w.signalBody(s.copy(host = "8.8.8.8"))))
    assertNull(w.parseSignal(w.signalBody(s.copy(digest = "nothex"))))
    assertNull("never aimed at a privileged service port", w.parseSignal(w.signalBody(s.copy(port = 22))))
  }

  @Test
  fun payloadsRoundTripAndAreBounded() {
    val text = w.payload("Family Area", joining = false, records = listOf(rec()))
    val p = w.parsePayload(text)!!
    assertEquals("Family Area", p.name)
    assertFalse(p.joining)
    assertEquals(listOf(rec()), p.records)
    assertTrue(w.parsePayload(w.payload("New", joining = true, records = emptyList()))!!.joining)
    assertNull(w.parsePayload("not json"))
    assertNull(w.parsePayload(null))
    val tooMany = JSONObject().put("name", "x").put("records",
        org.json.JSONArray((0..SettingsSyncEngine.MAX_RECORDS).map { w.recordJson(rec()) }))
    assertNull(w.parsePayload(tooMany.toString()))
  }

  @Test
  fun aSignedRequestIsAcceptedOnce() {
    val body = w.payload("Office", false, listOf(rec()))
    val header = w.requestHeader(key, "dev-B", "aabbccddeeff00112233aabb", now, body)
    val replay = HouseholdMessage.ReplayGuard()
    assertEquals("dev-B", w.verifyRequest(header, body, key, now, replay, self = "dev-A")!!.from)
    assertNull("a replayed request is refused", w.verifyRequest(header, body, key, now, replay, self = "dev-A"))
  }

  @Test
  fun forgedSwappedStaleOrForeignRequestsAreRefused() {
    val body = w.payload("Office", false, listOf(rec()))
    val header = w.requestHeader(key, "dev-B", "aabbccddeeff00112233aabb", now, body)
    assertNull(w.verifyRequest(header, body, otherKey, now, HouseholdMessage.ReplayGuard(), "dev-A"))
    assertNull(w.verifyRequest(header, w.payload("Office", false, listOf(rec("light"))), key, now,
        HouseholdMessage.ReplayGuard(), "dev-A"))
    assertNull(w.verifyRequest(header, body, key, now + 121_000, HouseholdMessage.ReplayGuard(), "dev-A"))
    assertNull(w.verifyRequest(header, body, key, now, HouseholdMessage.ReplayGuard(), self = "dev-B"))
    assertNull(w.verifyRequest(null, body, key, now, HouseholdMessage.ReplayGuard(), "dev-A"))
    val choresHeader = ChorePeerAuth.requestHeader(key, "dev-B", "aabbccddeeff00112233aabb", now, body)
    assertNull("a chores request can't be replayed as settings",
        w.verifyRequest(choresHeader, body, key, now, HouseholdMessage.ReplayGuard(), "dev-A"))
  }

  @Test
  fun theReplyIsBoundToTheRequestNonceAndKey() {
    val payload = w.payload("Family", false, listOf(rec()))
    val sig = w.signResponse(key, "nonce1", payload)
    assertTrue(w.verifyResponse(key, "nonce1", payload, sig))
    assertFalse(w.verifyResponse(key, "nonce2", payload, sig))
    assertFalse(w.verifyResponse(otherKey, "nonce1", payload, sig))
    assertFalse(w.verifyResponse(key, "nonce1", payload.replace("dark", "light"), sig))
    assertFalse(w.verifyResponse(key, "nonce1", payload, null))
    assertFalse("a chores reply can't pass as settings",
        w.verifyResponse(key, "nonce1", payload, ChorePeerAuth.signResponse(key, "nonce1", payload)))
  }
}
