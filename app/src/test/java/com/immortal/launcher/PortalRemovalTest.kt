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
 * Removing a Portal from the home (calling-phase-2.md, "Removing a Portal"): the remover unpins it,
 * signs a revisioned removal marker into the vault so every other Portal that pinned the remover
 * unpins it too (idempotent), and rotates the household identity so the removed Portal's copy is
 * useless for future links; linked homes verified under the old key need checking again.
 */
class PortalRemovalTest {
  private val random = SecureRandom()
  private var now = 1_700_000_000_000L
  private val perms = HomeLinkPermissions.defaults(listOf("Kitchen"), emptySet())

  /** One Portal: its device key, household id, roster and vault. */
  private inner class Portal(val id: String) {
    val device = HomeLinkIdentity.generate(random)
    val kid = HomeLinkIdentity.fingerprint(device.publicKey)
    val roster = PortalRosterStore(MemorySecureStore()) {}
    val repo = HomeLinkRepository(MemorySecureStore(), random, { now }, {})
    fun pin(other: Portal) =
        assertEquals(PortalPinOutcome.PINNED, roster.pin(HomeLinkPeer(other.id, other.device.publicKey, other.id), device.publicKey, now, id))
    fun remover() = PortalRemover(roster, repo, device, { now })
    /** What [HomeLinkSync] does with a pulled vault: removals first, then the merge. */
    fun receive(from: Portal) {
      PortalRemover.apply(roster, from.repo.vault.value, device.publicKey)
      repo.merge(from.repo.vault.value)
    }
  }

  private val family = Portal("family-1")
  private val office = Portal("office-1")
  private val sold = Portal("sold-1")

  init {
    for (a in listOf(family, office, sold)) for (b in listOf(family, office, sold)) if (a !== b) a.pin(b)
    family.repo.identity()
    family.repo.link(HomeLinkPeer("Lakeview", HomeLinkIdentity.generate(random).publicKey), perms)
    office.receive(family)
    sold.receive(family)
    now += 1_000
  }

  @Test fun removingAPortalUnpinsItHereAndRotatesTheIdentity() {
    val before = family.repo.vault.value.identity!!
    assertTrue(family.remover().remove(sold.kid))
    assertNull(family.roster.roster.pins[sold.kid])
    val after = family.repo.vault.value.identity!!
    assertNotEquals(before.publicKey, after.publicKey)
    assertEquals(before.generation + 1, after.generation)
    assertEquals(sold.kid, family.repo.vault.value.removals.getValue(sold.kid).kid)
    // The removed Portal's copy is the old key: nothing it holds is this home's identity any more.
    assertNotEquals(after.publicKey, sold.repo.vault.value.identity!!.publicKey)
    assertFalse(family.remover().remove(sold.kid))
  }

  @Test fun theRemovalReachesEveryPinnedPortalAndIsIdempotent() {
    family.remover().remove(sold.kid)
    office.receive(family)
    assertNull(office.roster.roster.pins[sold.kid])
    assertNotNull(office.roster.roster.pins[family.kid])
    assertEquals(family.repo.vault.value.identity, office.repo.vault.value.identity)
    val once = office.repo.vault.value to office.roster.roster
    office.receive(family)
    assertEquals(once, office.repo.vault.value to office.roster.roster)
    // The removed Portal can no longer pull from either.
    val asking = HomeLinkSyncWire.ask(sold.device, "0".repeat(24), now, "0".repeat(32), back = false, random = random)
    assertNull(HomeLinkSyncWire.admit(asking.pull, office.roster.roster, office.kid, now))
    assertNull(HomeLinkSyncWire.admit(asking.pull, family.roster.roster, family.kid, now))
  }

  @Test fun theRemovedPortalsOldVaultCantBringTheOldIdentityBack() {
    family.remover().remove(sold.kid)
    val rotated = family.repo.vault.value.identity
    office.repo.merge(sold.repo.vault.value)
    office.receive(family)
    office.repo.merge(sold.repo.vault.value)
    assertEquals(rotated, office.repo.vault.value.identity)
  }

  @Test fun linkedHomesVerifiedUnderTheOldKeyNeedCheckingAgain() {
    val lakeview = family.repo.vault.value.book.links.single()
    assertTrue(family.repo.vault.value.reverifyIds.isEmpty())
    family.remover().remove(sold.kid)
    assertEquals(setOf(lakeview.id), family.repo.vault.value.reverifyIds)
    office.receive(family)
    assertEquals(setOf(lakeview.id), office.repo.vault.value.reverifyIds)
    // Checking again (a new link under the new key) clears it; a home linked after rotation never needs it.
    family.repo.link(HomeLinkPeer("Lakeview", lakeview.publicKey), perms)
    val fresh = family.repo.link(HomeLinkPeer("Maple", HomeLinkIdentity.generate(random).publicKey), perms)!!
    assertTrue(family.repo.vault.value.reverifyIds.isEmpty())
    assertEquals(HomeLinkIdentity.fingerprint(family.repo.vault.value.identity!!.publicKey), fresh.verifiedAs)
  }

  @Test fun aMarkerSignedByAnUnpinnedKeyOrTamperedChangesNothing() {
    val stranger = HomeLinkIdentity.generate(random)
    val pin = office.roster.roster.pins.getValue(sold.kid)
    val forged = PortalRemovals.sign(stranger, pin, now, 99)
    assertTrue(PortalRemovals.toUnpin(office.roster.roster, mapOf(sold.kid to forged), office.device.publicKey).isEmpty())
    val real = PortalRemovals.sign(family.device, pin, now, 99)
    assertEquals(setOf(sold.kid), PortalRemovals.toUnpin(office.roster.roster, mapOf(sold.kid to real), office.device.publicKey))
    for (bad in listOf(real.copy(at = now + 1), real.copy(rev = 100), real.copy(deviceId = "kitchen-1"), real.copy(kid = family.kid)))
      assertTrue(bad.toString(), PortalRemovals.toUnpin(office.roster.roster, mapOf(bad.kid to bad), office.device.publicKey).isEmpty())
  }

  @Test fun aPortalNeverUnpinsItselfAndARePairAfterTheRemovalSurvives() {
    val selfPin = family.roster.roster.pins.getValue(office.kid)
    val aboutMe = PortalRemovals.sign(family.device, selfPin, now, 5)
    assertTrue(PortalRemovals.toUnpin(office.roster.roster, mapOf(office.kid to aboutMe), office.device.publicKey).isEmpty())
    // Re-paired in person after the removal: the old marker no longer applies to the newer pin.
    val old = PortalRemovals.sign(family.device, office.roster.roster.pins.getValue(sold.kid), now - 10_000, 5)
    assertTrue(PortalRemovals.toUnpin(office.roster.roster, mapOf(sold.kid to old), office.device.publicKey).isEmpty())
  }

  @Test fun twoRemovalsAtOnceConvergeOnOneIdentityMadeAfterBoth() {
    val spare = Portal("spare-1")
    for (p in listOf(family, office)) { p.pin(spare); spare.pin(p) }
    // family removes sold while office removes spare, before either hears of the other.
    family.remover().remove(sold.kid)
    now += 1_000
    office.remover().remove(spare.kid)
    now += 1_000
    family.receive(office)
    office.receive(family)
    family.receive(office)
    val a = family.repo.vault.value
    val b = office.repo.vault.value
    assertEquals(a.identity, b.identity)
    assertEquals(setOf(sold.kid, spare.kid), a.removals.keys)
    assertTrue(a.removals.values.all { it.at <= a.identity!!.createdAt })
    assertFalse(a.identityStale(now))
    assertNull(family.roster.roster.pins[spare.kid])
    assertNull(office.roster.roster.pins[sold.kid])
  }

  @Test fun removalsMergeCommutativelyAndBumpTheClock() {
    val pinA = office.roster.roster.pins.getValue(sold.kid)
    val r = PortalRemovals.sign(family.device, pinA, now, 7)
    val v = HomeLinkVault().withRemoval(r)
    assertEquals(7L, v.clock)
    assertEquals(v, v.merge(v))
    val other = HomeLinkVault().withRemoval(PortalRemovals.sign(office.device, family.roster.roster.pins.getValue(sold.kid), now, 3))
    assertEquals(v.merge(other), other.merge(v))
    assertEquals(7L, v.merge(other).removals.getValue(sold.kid).rev)
  }

  @Test fun theVaultCodecKeepsRemovalsGenerationsAndVerifiedKeys() {
    family.remover().remove(sold.kid)
    val v = family.repo.vault.value
    assertEquals(v, HomeLinkVaultCodec.parse(HomeLinkVaultCodec.encode(v)))
    assertEquals(1, HomeLinkVaultCodec.parse(HomeLinkVaultCodec.encode(v))!!.identity!!.generation)
    // A vault from before removals (no "rm", no "g", no "as") still parses as generation 0.
    val legacy = HomeLinkVaultCodec.encode(HomeLinkVault(v.identity!!.let { HomeLinkStoredIdentity.parse(it.publicKey, it.privateKey, 1L)!! }))
    assertEquals(0, HomeLinkVaultCodec.parse(legacy)!!.identity!!.generation)
    assertFalse(legacy.contains("\"rm\""))
  }

  @Test fun theHigherGenerationWinsThenTheOlder() {
    val a = HomeLinkStoredIdentity.of(HomeLinkIdentity.generate(random), 10L)
    val b = HomeLinkStoredIdentity.of(HomeLinkIdentity.generate(random), 20L, generation = 1)
    assertSame(b, HomeLinkStoredIdentity.pick(a, b))
    assertSame(b, HomeLinkStoredIdentity.pick(b, a))
    val c = HomeLinkStoredIdentity.of(HomeLinkIdentity.generate(random), 5L)
    assertSame(c, HomeLinkStoredIdentity.pick(a, c))
  }

  /** A Portal whose clock is behind the remover's rotates once, dated at the marker, and is then settled. */
  @Test fun aSlowClockRotatesOnceNotOnEveryMerge() {
    val ahead = PortalRemovals.sign(office.device, family.roster.roster.pins.getValue(sold.kid), now + 3_600_000, 1)
    family.repo.merge(HomeLinkVault().withRemoval(ahead))
    val once = family.repo.vault.value.identity!!
    assertEquals(now + 3_600_000, once.createdAt)
    assertFalse(family.repo.vault.value.identityStale(now))
    family.repo.merge(HomeLinkVault().withRemoval(ahead))
    assertEquals(once, family.repo.vault.value.identity)
  }

  @Test fun aMarkerFromTheFarFutureDoesntRotateForever() {
    val r = PortalRemovals.sign(family.device, office.roster.roster.pins.getValue(sold.kid), now + 365L * 24 * 3_600_000, 9)
    val v = family.repo.vault.value.withRemoval(r)
    assertFalse(v.identityStale(now))
  }

  @Test fun nothingSecretIsInAMarkerOrItsText() {
    family.remover().remove(sold.kid)
    val r = family.repo.vault.value.removals.getValue(sold.kid)
    assertFalse(r.toString().contains(r.sig))
    assertEquals(family.kid, r.by)
  }
}
