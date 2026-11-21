/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/** The vault every Portal in one home shares: identity, linked homes, removals that stick. */
class HomeLinkVaultTest {
  private val random = SecureRandom()
  private val rooms = listOf("Living room", "Kitchen")
  private fun link(name: String, at: Long = 1L) =
      HomeLink.verified(HomeLinkPeer(name, HomeLinkIdentity.generate(random).publicKey), at, HomeLinkPermissions.defaults(rooms, emptySet()))
  private fun identity(at: Long) = HomeLinkStoredIdentity.of(HomeLinkIdentity.generate(random), at)
  private val lake = link("Lakeview")
  private val priya = link("Priya & June’s", at = 2L)

  @Test fun putAddsAndEditsWithRisingRevisions() {
    val v1 = HomeLinkVault().put(lake)
    assertEquals(listOf(lake), v1.book.links)
    val edited = HomeLinkPermissionEdit.call(lake, false)
    val v2 = v1.put(edited)
    assertEquals(edited, v2.book.links.single())
    assertTrue(v2.entries.getValue(lake.id).rev > v1.entries.getValue(lake.id).rev)
    assertSame(v2, v2.put(edited))
  }

  @Test fun removeLeavesATombstoneWithoutTheKey() {
    val v = HomeLinkVault().put(lake).remove(lake.id)
    assertTrue(v.book.links.isEmpty())
    assertTrue(v.entries.getValue(lake.id).removed)
    assertFalse(HomeLinkVaultCodec.encode(v).contains(lake.publicKey))
    assertSame(v, v.remove(lake.id))
  }

  @Test fun aRemovalOnOnePortalWipesTheHomeOnTheOther() {
    val shared = HomeLinkVault().put(lake).put(priya)
    val kitchen = shared.remove(lake.id)
    val den = shared.merge(kitchen)
    assertEquals(listOf(priya), den.book.links)
    assertFalse(HomeLinkVaultCodec.encode(den).contains(lake.publicKey))
  }

  @Test fun aPortalThatWasOffCantBringARemovedHomeBack() {
    val old = HomeLinkVault().put(lake)
    val removed = old.remove(lake.id)
    assertTrue(removed.merge(old).book.links.isEmpty())
    assertTrue(old.merge(removed).book.links.isEmpty())
  }

  @Test fun removalWinsATie() {
    val a = HomeLinkVault(entries = mapOf(lake.id to HomeLinkEntry(lake.id, 5, lake)))
    val b = HomeLinkVault(entries = mapOf(lake.id to HomeLinkEntry(lake.id, 5, null)))
    assertTrue(a.merge(b).book.links.isEmpty())
    assertTrue(b.merge(a).book.links.isEmpty())
  }

  @Test fun relinkingAfterRemovalWorksWithAHigherRevision() {
    val v = HomeLinkVault().put(lake).remove(lake.id).put(lake)
    assertEquals(listOf(lake), v.book.links)
  }

  @Test fun mergeIsCommutativeAndIdempotent() {
    val a = HomeLinkVault(identity(10)).put(lake)
    val b = HomeLinkVault(identity(20)).put(priya).put(HomeLinkPermissionEdit.call(priya, false))
    assertEquals(a.merge(b), b.merge(a))
    assertEquals(a.merge(b), a.merge(b).merge(b))
  }

  @Test fun bothPortalsKeepTheOlderIdentity() {
    val older = identity(10)
    val newer = identity(20)
    assertEquals(older, HomeLinkVault(newer).merge(HomeLinkVault(older)).identity)
    assertEquals(older, HomeLinkVault(older).merge(HomeLinkVault(newer)).identity)
    assertEquals(older, HomeLinkVault().merge(HomeLinkVault(older)).identity)
  }

  @Test fun theCapHoldsAcrossAMerge() {
    val a = (1..HomeLinkBook.MAX_LINKS).fold(HomeLinkVault()) { v, i -> v.put(link("A$i", at = i.toLong())) }
    val b = (1..3).fold(HomeLinkVault()) { v, i -> v.put(link("B$i", at = 100L + i)) }
    val merged = a.merge(b)
    assertEquals(HomeLinkBook.MAX_LINKS, merged.book.links.size)
    assertTrue(merged.book.links.none { it.name.startsWith("B") })
    assertSame(a, a.put(link("C")))
  }

  @Test fun oldTombstonesArePruned() {
    val v = (1..HomeLinkVault.MAX_TOMBSTONES + 5).fold(HomeLinkVault()) { acc, i -> link("H$i").let { acc.put(it).remove(it.id) } }
    assertEquals(HomeLinkVault.MAX_TOMBSTONES, v.entries.size)
  }

  @Test fun codecRoundTripsAndNeverPrintsThePrivateKey() {
    val id = identity(7)
    val v = HomeLinkVault(id).put(lake).put(priya).remove(priya.id)
    assertEquals(v, HomeLinkVaultCodec.parse(HomeLinkVaultCodec.encode(v)))
    assertFalse(v.toString().contains(id.privateKey))
    assertFalse(id.toString().contains(id.privateKey))
    assertNotNull(HomeLinkVaultCodec.parse(HomeLinkVaultCodec.encode(v))!!.identity!!.restore())
  }

  @Test fun codecRejectsAnythingOff() {
    val good = JSONObject(HomeLinkVaultCodec.encode(HomeLinkVault(identity(1)).put(lake)))
    assertNull(HomeLinkVaultCodec.parse(null))
    assertNull(HomeLinkVaultCodec.parse("garbage"))
    assertNull(HomeLinkVaultCodec.parse(JSONObject(good.toString()).put("v", 2).toString()))
    // A private key that isn't the public key's.
    val other = identity(1)
    val swapped = JSONObject(good.toString()).apply { getJSONObject("id").put("priv", other.privateKey) }
    assertNull(HomeLinkVaultCodec.parse(swapped.toString()))
    // An entry whose id isn't its key's fingerprint.
    val badId = JSONObject(good.toString()).apply { getJSONArray("e").getJSONObject(0).put("id", "0123456789abcdef") }
    assertNull(HomeLinkVaultCodec.parse(badId.toString()))
    val badRev = JSONObject(good.toString()).apply { getJSONArray("e").getJSONObject(0).put("r", 0) }
    assertNull(HomeLinkVaultCodec.parse(badRev.toString()))
  }
}
