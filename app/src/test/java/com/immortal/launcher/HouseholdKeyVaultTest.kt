/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The household key's move from plain prefs into Keystore-sealed storage (plan H1): the value
 * never changes, no failure loses it, and a Keystore that is down keeps the Portal working.
 */
class HouseholdKeyVaultTest {
  private val key = ByteArray(32) { (it * 7 + 1).toByte() }
  private val hex = HouseholdKey.toHex(key)
  private val other = ByteArray(32) { 9 }

  private val plain = FakePlain()
  private val sealed = FakeSealed()
  private val logged = mutableListOf<String>()
  private val vault = HouseholdKeyVault(plain, sealed, logged::add)

  // --- migration --------------------------------------------------------------------------

  @Test fun anOldPlainKeyMovesIntoSealedStorageUnchanged() {
    plain.value = hex

    assertArrayEquals(key, vault.get())

    assertEquals(hex, sealed.text)
    assertNull(plain.value)
    assertArrayEquals(key, vault.get())
  }

  @Test fun afterTheMoveReadsComeFromTheCacheNotTheKeystore() {
    plain.value = hex
    vault.get()
    repeat(5) { assertArrayEquals(key, vault.get()) }
    assertEquals(0, sealed.loads)
  }

  @Test fun aFreshProcessOpensTheSealedKeyOnce() {
    sealed.text = hex
    repeat(5) { assertArrayEquals(key, vault.get()) }
    assertEquals(1, sealed.loads)
  }

  @Test fun theFingerprintTheFleetSeesIsTheSameBeforeAndAfter() {
    plain.value = hex
    val before = HouseholdKey.fingerprint(HouseholdKey.fromHex(hex)!!)
    assertEquals(before, HouseholdKey.fingerprint(vault.get()!!))
    assertEquals(before, HouseholdKey.fingerprint(vault.get()!!))
  }

  @Test fun theKeystoreBeingDownKeepsThePlainKeyAndKeepsWorking() {
    plain.value = hex
    sealed.broken = true

    repeat(3) { assertArrayEquals(key, vault.get()) }
    assertEquals(hex, plain.value)

    sealed.broken = false // it came back: the next read finishes the move
    assertArrayEquals(key, vault.get())
    assertEquals(hex, sealed.text)
    assertNull(plain.value)
  }

  @Test fun aPlainCopyThatCannotBeRemovedStaysAndStillReads() {
    plain.value = hex
    plain.commits = false

    assertArrayEquals(key, vault.get())
    assertEquals(hex, plain.value)
    assertEquals(hex, sealed.text)
    assertArrayEquals(key, vault.get())
  }

  @Test fun anUnreadableSealedCopyReadsAsNoKeyButIsNeverDeleted() {
    sealed.text = hex
    sealed.broken = true

    assertNull(vault.get())
    assertEquals(hex, sealed.text)

    sealed.broken = false
    assertArrayEquals(key, vault.get())
  }

  @Test fun garbageInThePlainEntryIsIgnoredAsBefore() {
    plain.value = "not-hex"
    assertNull(vault.get())
    sealed.text = hex
    assertArrayEquals(key, vault.get())
  }

  @Test fun noKeyAnywhereIsNoKey() {
    assertNull(vault.get())
  }

  // --- set --------------------------------------------------------------------------------

  @Test fun setSealsAndLeavesNoPlainCopy() {
    plain.value = HouseholdKey.toHex(other)

    assertTrue(vault.set(key))

    assertEquals(hex, sealed.text)
    assertNull(plain.value)
    assertArrayEquals(key, vault.get())
  }

  @Test fun setWithTheKeystoreDownStoresPlainRatherThanLosingTheKey() {
    sealed.text = HouseholdKey.toHex(other)
    sealed.broken = true

    assertTrue(vault.set(key))

    assertEquals(hex, plain.value)
    sealed.broken = false
    assertArrayEquals(key, vault.get()) // the plain copy wins, then moves
    assertEquals(hex, sealed.text)
  }

  @Test fun setCannotLeaveAStalePlainKeyShadowingTheNewOne() {
    plain.value = HouseholdKey.toHex(other)
    plain.removable = false

    assertTrue(vault.set(key))

    assertArrayEquals(key, vault.get())
  }

  @Test fun setRejectsInvalidMaterialAndKeepsTheOldKey() {
    vault.set(key)
    assertFalse(vault.set(ByteArray(31)))
    assertArrayEquals(key, vault.get())
  }

  @Test fun callersCannotCorruptTheCachedKey() {
    vault.set(key)
    vault.get()!!.fill(0)
    assertArrayEquals(key, vault.get())
  }

  // --- backup restore ---------------------------------------------------------------------

  @Test fun aRestoredKeyIsAdoptedOverTheCurrentOne() {
    vault.set(other)
    plain.value = hex // the restore wrote the archive's prefs

    vault.adoptRestored()

    assertArrayEquals(key, vault.get())
    assertEquals(hex, sealed.text)
    assertNull(plain.value)
  }

  @Test fun aRestoreWithoutAKeyLeavesNoKeyAsReplacingThePrefsDidBefore() {
    vault.set(key)

    vault.adoptRestored()

    assertNull(vault.get())
    assertNull(sealed.text)
  }

  // --- logging ----------------------------------------------------------------------------

  @Test fun keyMaterialIsNeverLogged() {
    plain.value = hex
    sealed.broken = true
    vault.get()
    vault.set(key)
    sealed.broken = false
    vault.get()
    assertTrue(logged.isNotEmpty())
    assertTrue(logged.toString(), logged.none { it.contains(hex.take(8)) })
  }

  private class FakePlain : HouseholdKeyVault.PlainSlot {
    var value: String? = null
    var commits = true
    var removable = true

    override fun read(): String? = value
    override fun write(hex: String): Boolean = commits.also { if (it) value = hex }
    override fun remove(): Boolean = (commits && removable).also { if (it) value = null }
  }

  private class FakeSealed : YtSecureStore {
    var text: String? = null
    var broken = false
    var loads = 0

    override fun load(): String? {
      loads++
      if (broken) throw IOException("keystore unavailable")
      return text
    }

    override fun save(text: String) {
      if (broken) throw IOException("keystore unavailable")
      this.text = text
    }

    override fun clear() {
      text = null
    }
  }
}
