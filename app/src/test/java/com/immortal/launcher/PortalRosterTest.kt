/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import com.immortal.launcher.youtube.YtSecureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

/** The Portals pinned in person: public keys only, nothing but a pin adds one, bad files start empty. */
class PortalRosterTest {
  private val random = SecureRandom()
  private val me = HomeLinkIdentity.generate(random).publicKey
  private val kitchen = HomeLinkIdentity.generate(random).publicKey

  @Test fun aPinIsKeyedByTheKeysFingerprint() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen), me, 5L)!!
    val kid = HomeLinkIdentity.fingerprint(kitchen)
    assertEquals(kitchen, r.keyFor(kid))
    assertEquals(PortalPin(kid, "Kitchen", kitchen, 5L), r.pins[kid])
    assertNull(r.keyFor("0000000000000000"))
  }

  @Test fun pinningTheSameKeyAgainKeepsTheFirstPin() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen), me, 5L)!!
    assertSame(r, r.pin(HomeLinkPeer("Renamed", kitchen), me, 9L))
  }

  @Test fun ourOwnKeyBadKeysAndBadNamesAreRefused() {
    assertNull(PortalRoster().pin(HomeLinkPeer("Me", me), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("Bad", "not-a-key"), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("", kitchen), me, 1L))
  }

  @Test fun aFullRosterRefusesMore() {
    var r = PortalRoster()
    repeat(PortalRoster.MAX_PINS) { r = r.pin(HomeLinkPeer("P$it", HomeLinkIdentity.generate(random).publicKey), me, 1L)!! }
    assertNull(r.pin(HomeLinkPeer("One more", kitchen), me, 1L))
  }

  @Test fun unpinForgetsThePortal() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen), me, 5L)!!
    assertTrue(r.unpin(HomeLinkIdentity.fingerprint(kitchen)).isEmpty)
  }

  @Test fun theCodecRoundTripsAndDropsBadPins() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen), me, 5L)!!
    assertEquals(r, PortalRosterCodec.parse(PortalRosterCodec.encode(r)))
    val o = JSONObject(PortalRosterCodec.encode(r))
    o.getJSONArray("pins").put(JSONObject().put("key", "garbage").put("name", "X").put("at", 1))
    assertEquals(r, PortalRosterCodec.parse(o.toString()))
    assertNull(PortalRosterCodec.parse(JSONObject(o.toString()).put("v", 9).toString()))
    assertNull(PortalRosterCodec.parse("nope"))
    assertNull(PortalRosterCodec.parse(null))
  }

  @Test fun theStoreKeepsPinsAndStartsEmptyWhenUnreadable() {
    val disk = MemorySecureStore()
    val store = PortalRosterStore(disk) {}
    assertTrue(store.pin(HomeLinkPeer("Kitchen", kitchen), me, 5L))
    assertEquals(store.roster, PortalRosterStore(disk) {}.roster)
    assertFalse(disk.text!!.contains("private"))
    val broken = object : YtSecureStore {
      override fun load(): String? = throw IOException("key gone")
      override fun save(text: String) = throw IOException("disk full")
      override fun clear() {}
    }
    val warnings = ArrayList<String>()
    val empty = PortalRosterStore(broken) { warnings += it }
    assertTrue(empty.roster.isEmpty)
    assertFalse(empty.pin(HomeLinkPeer("Kitchen", kitchen), me, 5L))
    assertTrue(empty.roster.isEmpty)
    assertEquals(2, warnings.size)
  }
}
