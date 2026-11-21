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
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    val kid = HomeLinkIdentity.fingerprint(kitchen)
    assertEquals(kitchen, r.keyFor(kid))
    assertEquals(PortalPin(kid, "Kitchen", kitchen, 5L, "kitchen-1"), r.pins[kid])
    assertNull(r.keyFor("0000000000000000"))
  }

  @Test fun pinningTheSameKeyAgainKeepsTheFirstPin() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    assertSame(r, r.pin(HomeLinkPeer("Renamed", kitchen, "kitchen-1"), me, 9L))
  }

  @Test fun ourOwnKeyBadKeysAndBadNamesAreRefused() {
    assertNull(PortalRoster().pin(HomeLinkPeer("Me", me, "me-1"), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("Bad", "not-a-key", "bad-1"), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("", kitchen, "kitchen-1"), me, 1L))
  }

  @Test fun aPinNeedsAPortalDeviceIdThatIsntOursOrAPhones() {
    assertNull(PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "bad id!"), me, 1L))
    assertNull(PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "me-1"), me, 1L, selfDevice = "me-1"))
    assertNull(PortalRoster().pin(HomeLinkPeer("Phone", kitchen, CallPhoneId.of("pixel")), me, 1L))
  }

  /** One key per device id: a second key for an id already pinned needs an explicit re-pair. */
  @Test fun aSecondKeyForTheSameDeviceIdNeedsAnExplicitRepair() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    val newKey = HomeLinkIdentity.generate(random).publicKey
    val again = HomeLinkPeer("Kitchen", newKey, "kitchen-1")
    assertEquals(PortalPinOutcome.NEEDS_REPAIR, r.check(again, me))
    assertNull(r.pin(again, me, 9L))
    val repaired = r.pin(again, me, 9L, repair = true)!!
    assertEquals(listOf(newKey), repaired.pins.values.map { it.publicKey })
    assertEquals(newKey, repaired.byDevice("kitchen-1")!!.publicKey)
    assertNull(repaired.keyFor(HomeLinkIdentity.fingerprint(kitchen)))
  }

  /** And one device id per key: the same key showing up under another id is a re-pair too. */
  @Test fun theSameKeyUnderAnotherDeviceIdNeedsAnExplicitRepair() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    val moved = HomeLinkPeer("Kitchen", kitchen, "kitchen-2")
    assertEquals(PortalPinOutcome.NEEDS_REPAIR, r.check(moved, me))
    assertNull(r.pin(moved, me, 9L))
    assertEquals("kitchen-2", r.pin(moved, me, 9L, repair = true)!!.pins.values.single().deviceId)
  }

  @Test fun theStoreReportsWhatAPinDid() {
    val store = PortalRosterStore(MemorySecureStore()) {}
    val peer = HomeLinkPeer("Kitchen", kitchen, "kitchen-1")
    assertEquals(PortalPinOutcome.PINNED, store.pin(peer, me, 5L))
    assertEquals(PortalPinOutcome.ALREADY_PINNED, store.pin(peer, me, 6L))
    val other = HomeLinkPeer("Kitchen", HomeLinkIdentity.generate(random).publicKey, "kitchen-1")
    assertEquals(PortalPinOutcome.NEEDS_REPAIR, store.pin(other, me, 7L))
    assertEquals(kitchen, store.roster.byDevice("kitchen-1")!!.publicKey)
    assertEquals(PortalPinOutcome.PINNED, store.pin(other, me, 8L, repair = true))
    assertEquals(other.publicKey, store.roster.byDevice("kitchen-1")!!.publicKey)
    assertEquals(PortalPinOutcome.REFUSED, store.pin(HomeLinkPeer("Me", me, "me-1"), me, 9L))
  }

  @Test fun attestationIsRecordedAndSurvivesTheCodec() {
    val store = PortalRosterStore(MemorySecureStore()) {}
    store.pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)
    val kid = HomeLinkIdentity.fingerprint(kitchen)
    assertEquals(PortalAttestationLevel.UNCHECKED, store.roster.pins[kid]!!.attestation)
    assertTrue(store.attested(kid, PortalAttestationLevel.TEE))
    assertEquals(PortalAttestationLevel.TEE, PortalRosterCodec.parse(PortalRosterCodec.encode(store.roster))!!.pins[kid]!!.attestation)
    assertSame(store.roster, store.roster.attested("0000", PortalAttestationLevel.TEE))
  }

  @Test fun aFileWithTwoPinsForOneDeviceKeepsOnlyOne() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    val o = JSONObject(PortalRosterCodec.encode(r))
    o.getJSONArray("pins").put(JSONObject().put("key", HomeLinkIdentity.generate(random).publicKey).put("name", "Evil")
        .put("at", 1).put("dev", "kitchen-1"))
    assertEquals(1, PortalRosterCodec.parse(o.toString())!!.pins.size)
  }

  @Test fun anOutOfDateAttestationIsRecordedAndSurvivesTheCodec() {
    val kid = HomeLinkIdentity.fingerprint(kitchen)
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!.attested(kid, PortalAttestationLevel.TEE, outOfDate = true)
    assertTrue(r.pins[kid]!!.attestationOutOfDate)
    assertEquals(r, PortalRosterCodec.parse(PortalRosterCodec.encode(r)))
    assertFalse(r.attested(kid, PortalAttestationLevel.TEE).pins[kid]!!.attestationOutOfDate)
  }

  @Test fun aFullRosterRefusesMore() {
    var r = PortalRoster()
    repeat(PortalRoster.MAX_PINS) { r = r.pin(HomeLinkPeer("P$it", HomeLinkIdentity.generate(random).publicKey, "p-$it"), me, 1L)!! }
    assertNull(r.pin(HomeLinkPeer("One more", kitchen, "kitchen-1"), me, 1L))
  }

  @Test fun unpinForgetsThePortal() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    assertTrue(r.unpin(HomeLinkIdentity.fingerprint(kitchen)).isEmpty)
  }

  @Test fun theCodecRoundTripsAndDropsBadPins() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
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
    assertEquals(PortalPinOutcome.PINNED, store.pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L))
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
    assertEquals(PortalPinOutcome.NOT_STORED, empty.pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L))
    assertTrue(empty.roster.isEmpty)
    assertEquals(2, warnings.size)
  }
  /** Older test builds saved pins without a device id: still dropped (they can't be bound), but counted once, without keys or names. */
  @Test fun pinsWithoutADeviceIdAreDroppedAndCountedOnceInTheLog() {
    val r = PortalRoster().pin(HomeLinkPeer("Kitchen", kitchen, "kitchen-1"), me, 5L)!!
    val o = JSONObject(PortalRosterCodec.encode(r))
    val legacy = List(2) { HomeLinkIdentity.generate(random).publicKey }
    legacy.forEachIndexed { i, k -> o.getJSONArray("pins").put(JSONObject().put("key", k).put("name", "Old $i").put("at", 1)) }
    val decoded = PortalRosterCodec.decode(o.toString())!!
    assertEquals(r, decoded.roster)
    assertEquals(2, decoded.withoutDevice)
    val warnings = ArrayList<String>()
    val store = PortalRosterStore(MemorySecureStore(o.toString())) { warnings += it }
    assertEquals(r, store.roster)
    assertEquals(1, warnings.size)
    assertTrue(warnings.single().contains("2 "))
    assertTrue(warnings.none { w -> legacy.any { w.contains(it) } || w.contains("Old") || w.contains(kitchen) })
    // A clean file says nothing.
    val quiet = ArrayList<String>()
    PortalRosterStore(MemorySecureStore(PortalRosterCodec.encode(r))) { quiet += it }
    assertTrue(quiet.isEmpty())
    assertEquals(0, PortalRosterCodec.decode(PortalRosterCodec.encode(r))!!.withoutDevice)
  }
}
