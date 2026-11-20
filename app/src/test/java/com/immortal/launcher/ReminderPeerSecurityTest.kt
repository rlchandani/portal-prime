/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ReminderFixtures.state
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Portal↔Portal reminders exchange: sealed replica, signed and replay-guarded headers. */
class ReminderPeerSecurityTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }
  private val now = 1_800_000_000_000L

  @Test fun theSealOpensOnlyWithTheSameKeyNonceAndDirection() {
    val sealed = ReminderSeal.seal(key, "n1", ReminderSeal.REQUEST, "secret")
    assertEquals("secret", ReminderSeal.open(key, "n1", ReminderSeal.REQUEST, sealed))
    assertNull("wrong key", ReminderSeal.open(other, "n1", ReminderSeal.REQUEST, sealed))
    assertNull("other exchange", ReminderSeal.open(key, "n2", ReminderSeal.REQUEST, sealed))
    assertNull("request replayed as reply", ReminderSeal.open(key, "n1", ReminderSeal.RESPONSE, sealed))
    val flipped = sealed.substring(0, sealed.length - 1) + (if (sealed.last() == '0') '1' else '0')
    assertNull("tampered", ReminderSeal.open(key, "n1", ReminderSeal.REQUEST, flipped))
    assertNull("truncated", ReminderSeal.open(key, "n1", ReminderSeal.REQUEST, sealed.take(20)))
    assertNull("not hex", ReminderSeal.open(key, "n1", ReminderSeal.REQUEST, "zz"))
    assertNull(ReminderSeal.open(key, "n1", ReminderSeal.REQUEST, null))
    assertNotEquals("random IV", sealed, ReminderSeal.seal(key, "n1", ReminderSeal.REQUEST, "secret"))
  }

  @Test fun privateTitlesNeverAppearInTheWireBody() {
    val body = ReminderPeerAuth.sealedBody(key, "n1", ReminderSeal.REQUEST, "Kitchen", state)
    assertFalse(body.contains("clinic", ignoreCase = true))
    assertFalse(body.contains(ReminderFixtures.maya.id))
    assertEquals(state, ReminderPeerAuth.openBody(key, "n1", ReminderSeal.REQUEST, body))
    assertNull(ReminderPeerAuth.openBody(other, "n1", ReminderSeal.REQUEST, body))
    assertNull(ReminderPeerAuth.openBody(key, "n1", ReminderSeal.REQUEST, "{}"))
    assertNull(ReminderPeerAuth.openBody(key, "n1", ReminderSeal.REQUEST, "not json"))
    // A body sealed with the right key but holding junk still fails validation.
    val junk = JSONObject().put("sealed", ReminderSeal.seal(key, "n1", ReminderSeal.REQUEST, "{\"v\":9}")).toString()
    assertNull(ReminderPeerAuth.openBody(key, "n1", ReminderSeal.REQUEST, junk))
  }

  @Test fun aSignedRequestVerifiesOncePinsItsBodyAndRefusesForgeries() {
    val body = ReminderPeerAuth.sealedBody(key, "nonce1", ReminderSeal.REQUEST, "Kitchen", state)
    val header = ReminderPeerAuth.requestHeader(key, "kitchen", "nonce1", now, body)!!
    val replay = HouseholdMessage.ReplayGuard()
    assertEquals(ReminderPeerAuth.Request("kitchen", "nonce1"),
        ReminderPeerAuth.verifyRequest(header, body, key, now, replay, "living"))
    assertNull("replayed", ReminderPeerAuth.verifyRequest(header, body, key, now, replay, "living"))
    assertNull("swapped body", ReminderPeerAuth.verifyRequest(header, "$body ", key, now, HouseholdMessage.ReplayGuard(), "living"))
    assertNull("forged key", ReminderPeerAuth.verifyRequest(header, body, other, now, HouseholdMessage.ReplayGuard(), "living"))
    assertNull("stale", ReminderPeerAuth.verifyRequest(header, body, key, now + 10 * 60_000, HouseholdMessage.ReplayGuard(), "living"))
    assertNull("from itself", ReminderPeerAuth.verifyRequest(header, body, key, now, HouseholdMessage.ReplayGuard(), "kitchen"))
    assertNull("no header", ReminderPeerAuth.verifyRequest(null, body, key, now, HouseholdMessage.ReplayGuard(), "living"))
  }

  @Test fun anotherFeaturesRequestCantBeReplayedAsAReminderOne() {
    val body = ReminderPeerAuth.sealedBody(key, "nonce1", ReminderSeal.REQUEST, "Kitchen", state)
    val chores = ChorePeerAuth.requestHeader(key, "kitchen", "nonce1", now, body)!!
    assertNull(ReminderPeerAuth.verifyRequest(chores, body, key, now, HouseholdMessage.ReplayGuard(), "living"))
  }

  @Test fun theReplyIsSignedForItsNonce() {
    val payload = ReminderPeerAuth.sealedBody(key, "n1", ReminderSeal.RESPONSE, "Living", state)
    val sig = ReminderPeerAuth.signResponse(key, "n1", payload)
    assertTrue(ReminderPeerAuth.verifyResponse(key, "n1", payload, sig))
    assertFalse(ReminderPeerAuth.verifyResponse(key, "n2", payload, sig))
    assertFalse(ReminderPeerAuth.verifyResponse(other, "n1", payload, sig))
    assertFalse(ReminderPeerAuth.verifyResponse(key, "n1", "$payload ", sig))
    assertFalse(ReminderPeerAuth.verifyResponse(key, "n1", payload, null))
    // Signature differs from the chores domain, so a chores reply can't stand in.
    assertNotEquals(ChorePeerAuth.signResponse(key, "n1", payload), sig)
  }

  @Test fun aPeerCantMakeUsBufferOrKeepUnboundedData() {
    assertEquals("abc", BoundedRead.utf8("abc".byteInputStream(), 3))
    assertNull(BoundedRead.utf8(ByteArray(40_000).inputStream(), 32_000))
    val many = org.json.JSONArray((1..ReminderCodec.MAX_ENTRIES + 50).map { i ->
      ReminderCodec.reminderJson(ReminderFixtures.clinic.copy(id = "rflood$i"))
    })
    val decoded = ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", many))!!
    assertEquals(ReminderCodec.MAX_ENTRIES, decoded.reminders.size)
  }
}
