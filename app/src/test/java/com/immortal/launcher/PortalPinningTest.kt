/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Two Portals pin each other's device keys in person (the linking protocol with device keys),
 * after which — and only after which — one can pull the other's vault. Software keys stand in for
 * the Keystore device keys.
 */
class PortalPinningTest {
  private val random = SecureRandom()
  private val familyKey = HomeLinkIdentity.generate(random)
  private val officeKey = HomeLinkIdentity.generate(random)
  private val family = PortalRosterStore(MemorySecureStore()) {}
  private val office = PortalRosterStore(MemorySecureStore()) {}
  private val invite = HomeLinkInvite(HomeLinkCode.parse("PK7M2Q9X4A", HomeLinkDomain.PORTAL_PIN)!!, createdAt = 0L)
  private val familySelf = PortalPinning.self(familyKey, "Family Area", "family-1")
  private val officeSelf = PortalPinning.self(officeKey, "Office", "office-1")
  private val now = 1_000L

  private fun ceremony(typed: HomeLinkCode = invite.code) = HomeLinkLoopback.exchange(invite, typed,
      familySelf, officeSelf, random, now)

  @Test fun matchingPicturesPinEachOthersDeviceKey() {
    val r = ceremony()
    val i = r.inviter as HomeLinkInviterState.Comparing
    val j = r.joiner as HomeLinkJoinerState.Comparing
    assertEquals(i.safety, j.safety)
    val linkedI = HomeLinkInviter.confirm(i, matched = true) as HomeLinkInviterState.Linked
    val linkedJ = HomeLinkJoiner.confirm(j, matched = true) as HomeLinkJoinerState.Linked
    assertEquals("office-1", linkedI.peer.deviceId)
    assertEquals("family-1", linkedJ.peer.deviceId)
    assertEquals(PortalPinOutcome.PINNED, PortalPinning.confirm(family, linkedI.peer, familySelf, now))
    assertEquals(PortalPinOutcome.PINNED, PortalPinning.confirm(office, linkedJ.peer, officeSelf, now))
    assertEquals(officeKey.publicKey, family.roster.byDevice("office-1")!!.publicKey)
    assertEquals(officeKey.publicKey, family.roster.keyFor(HomeLinkIdentity.fingerprint(officeKey.publicKey)))
    assertEquals(familyKey.publicKey, office.roster.keyFor(HomeLinkIdentity.fingerprint(familyKey.publicKey)))
  }

  @Test fun aWrongCodePinsNothing() {
    val r = ceremony(HomeLinkCode.parse("PK7M2Q9X4B", HomeLinkDomain.PORTAL_PIN)!!)
    assertFalse(r.inviter is HomeLinkInviterState.Comparing)
    assertFalse(r.joiner is HomeLinkJoinerState.Comparing)
    assertTrue(family.roster.isEmpty && office.roster.isEmpty)
  }

  @Test fun onlyAfterPinningCanOnePortalOpenTheOthersVault() {
    val vault = HomeLinkRepository(MemorySecureStore(), random, { now }, {}).apply { identity() }.vault.value
    val officeKid = HomeLinkIdentity.fingerprint(officeKey.publicKey)
    val familyKid = HomeLinkIdentity.fingerprint(familyKey.publicKey)
    fun pull(): HomeLinkVault? {
      val asking = HomeLinkSyncWire.ask(officeKey, "0".repeat(24), now, "0".repeat(32), back = false, random = random)
      val admitted = HomeLinkSyncWire.admit(asking.pull, family.roster, familyKid, now) ?: return null
      assertEquals(officeKid, admitted.kid)
      return HomeLinkSyncWire.receive(asking, HomeLinkSyncWire.give(familyKey, asking.pull, vault, random), office.roster, officeKid)
    }
    assertFalse(HomeLinkGate.syncs(family.roster, buildSwitch = true))
    assertNull(pull())
    val r = ceremony()
    PortalPinning.confirm(family, (r.inviter as HomeLinkInviterState.Comparing).peer, familySelf, now)
    PortalPinning.confirm(office, (r.joiner as HomeLinkJoinerState.Comparing).peer, officeSelf, now)
    assertTrue(HomeLinkGate.syncs(family.roster, buildSwitch = true))
    assertEquals(vault, pull())
  }
}
