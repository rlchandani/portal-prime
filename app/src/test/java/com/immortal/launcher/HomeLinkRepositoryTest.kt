/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import com.immortal.launcher.youtube.YtSecureStore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

/** The sealed on-device store: stored before shown, never half-applied, survives a restart. */
class HomeLinkRepositoryTest {
  private val random = SecureRandom()
  private val warnings = ArrayList<String>()
  private val perms = HomeLinkPermissions.defaults(listOf("Kitchen"), emptySet())
  private fun peer(name: String = "Lakeview") = HomeLinkPeer(name, HomeLinkIdentity.generate(random).publicKey)
  private fun repo(store: YtSecureStore) = HomeLinkRepository(store, random, { 42L }, { warnings += it })

  private class Broken(var failSave: Boolean = false, var failLoad: Boolean = false) : YtSecureStore {
    var text: String? = null
    override fun load(): String? = if (failLoad) throw IOException("key lost") else text
    override fun save(text: String) { if (failSave) throw IOException("disk") else this.text = text }
    override fun clear() { text = null }
  }

  @Test fun theIdentityIsMadeOnceAndStored() {
    val store = MemorySecureStore()
    val r = repo(store)
    val a = r.identity()!!
    assertEquals(a.publicKey, r.identity()!!.publicKey)
    assertEquals(1, store.saves)
    assertEquals(a.publicKey, repo(store).identity()!!.publicKey)
  }

  @Test fun theStoreNeverHoldsTheKeyInPlainTextBeyondTheSealedFile() {
    // The repository only hands text to the sealed store; the codec is what it writes.
    val store = MemorySecureStore()
    val r = repo(store)
    r.identity()
    assertEquals(HomeLinkVaultCodec.encode(r.vault.value), store.text)
  }

  @Test fun linkEditRemoveSurviveARestart() {
    val store = MemorySecureStore()
    val r = repo(store)
    val lake = r.link(peer(), perms)!!
    assertEquals(42L, lake.verifiedAt)
    assertTrue(r.edit(HomeLinkPermissionEdit.call(lake, false)))
    assertFalse(repo(store).vault.value.book.links.single().permissions.canCall)
    assertTrue(r.remove(lake.id))
    assertTrue(repo(store).vault.value.book.links.isEmpty())
  }

  @Test fun anEditForARemovedHomeDoesNothing() {
    val r = repo(MemorySecureStore())
    val lake = r.link(peer(), perms)!!
    r.remove(lake.id)
    assertTrue(r.edit(lake))
    assertTrue(r.vault.value.book.links.isEmpty())
  }

  @Test fun aChangeThatCantBeStoredDoesntHappen() {
    val store = Broken(failSave = true)
    val r = repo(store)
    assertNull(r.link(peer(), perms))
    assertNull(r.identity())
    assertEquals(HomeLinkVault(), r.vault.value)
    assertTrue(warnings.any { it.contains("not stored") })
  }

  @Test fun aLostKeyOrCorruptFileStartsEmptyAndSaysSo() {
    assertEquals(HomeLinkVault(), repo(Broken(failLoad = true)).vault.value)
    assertEquals(HomeLinkVault(), repo(MemorySecureStore("{nope")).vault.value)
    assertEquals(2, warnings.size)
    assertTrue(warnings.none { it.contains("BEGIN") || it.contains("priv") })
  }

  @Test fun storedChangesTellSyncAndNoOpsDont() {
    val r = repo(MemorySecureStore())
    val seen = ArrayList<HomeLinkVault>()
    r.onChange = { seen += it }
    val lake = r.link(peer(), perms)!!
    r.edit(lake)
    assertEquals(1, seen.size)
    r.remove(lake.id)
    assertEquals(2, seen.size)
  }

  @Test fun mergingAnotherPortalsVaultAdoptsItsIdentityAndHomes() {
    val kitchen = repo(MemorySecureStore())
    val den = repo(MemorySecureStore())
    val id = kitchen.identity()!!
    kitchen.link(peer(), perms)
    assertTrue(den.merge(kitchen.vault.value))
    assertEquals(id.publicKey, den.identity()!!.publicKey)
    assertEquals(1, den.vault.value.book.links.size)
    assertFalse(den.merge(kitchen.vault.value))
  }
}
