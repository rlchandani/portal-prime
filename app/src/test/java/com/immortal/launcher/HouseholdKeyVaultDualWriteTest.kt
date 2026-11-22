/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdKeyVaultTest.FakePlain
import com.immortal.launcher.HouseholdKeyVaultTest.FakeSealed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Today's rollback-safe phase ([HouseholdKey.RETIRE_PLAINTEXT] = false): the key is sealed AND
 * kept in the plain prefs entry, so an older official build installed over this one (bench, OTA
 * timing tests) still finds it, and whatever that older build changes is picked up again.
 */
class HouseholdKeyVaultDualWriteTest {
  private val key = ByteArray(32) { (it * 7 + 1).toByte() }
  private val hex = HouseholdKey.toHex(key)
  private val other = ByteArray(32) { 9 }

  private val plain = FakePlain()
  private val sealed = FakeSealed()
  private val vault = HouseholdKeyVault(plain, sealed, {})

  /** Exactly what an older build's HouseholdKey.get does: read `k` from prefs. */
  private fun olderBuildReads(): ByteArray? = plain.value?.let(HouseholdKey::fromHex)?.takeIf(HouseholdKey::isValidKey)

  @Test fun shipsInTheRollbackSafePhase() {
    assertFalse(HouseholdKey.RETIRE_PLAINTEXT)
  }

  @Test fun anOlderBuildStillFindsAMigratedKey() {
    plain.value = hex

    assertArrayEquals(key, vault.get())

    assertEquals(hex, sealed.text)
    assertArrayEquals(key, olderBuildReads())
  }

  @Test fun anOlderBuildStillFindsAKeySetByThisBuild() {
    vault.set(key)
    assertEquals(hex, sealed.text)
    assertArrayEquals(key, olderBuildReads())
  }

  @Test fun readsComeFromTheSealedCopyOnceAndThenTheCache() {
    plain.value = hex
    sealed.text = hex
    repeat(5) { assertArrayEquals(key, vault.get()) }
    assertEquals(1, sealed.loads)
  }

  @Test fun aKeyAnOlderBuildSetWinsOverTheStaleSealedCopy() {
    vault.set(key)
    plain.value = HouseholdKey.toHex(other) // the older build was installed and re-provisioned

    assertArrayEquals(other, vault.get())
    assertEquals(HouseholdKey.toHex(other), sealed.text)
  }

  @Test fun aKeyAnOlderBuildRemovedStaysRemoved() {
    vault.set(key)
    plain.value = null // e.g. an older build's restore without a key

    assertNull(vault.get())
    assertNull(sealed.text)
  }

  @Test fun theKeystoreBeingDownKeepsWorkingFromThePlainEntry() {
    plain.value = hex
    sealed.broken = true
    repeat(3) { assertArrayEquals(key, vault.get()) }
    assertEquals(hex, plain.value)
    sealed.broken = false
    assertArrayEquals(key, vault.get())
    assertEquals(hex, sealed.text)
  }

  @Test fun anUnreadableSealedCopyFallsBackToThePlainEntry() {
    vault.set(key)
    val fresh = HouseholdKeyVault(plain, sealed, {}) // a new process: nothing cached
    sealed.broken = true
    assertArrayEquals(key, fresh.get())
  }

  @Test fun aSetThatCannotReachThePlainEntryFailsRatherThanDiverging() {
    plain.value = HouseholdKey.toHex(other)
    plain.commits = false
    assertFalse(vault.set(key))
    assertArrayEquals(other, vault.get())
  }

  @Test fun aRestoreWithoutAKeyLeavesNoKey() {
    vault.set(key)
    plain.value = null
    vault.adoptRestored()
    assertNull(vault.get())
    assertNull(sealed.text)
  }
}
