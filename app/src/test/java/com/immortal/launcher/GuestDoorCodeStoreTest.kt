/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDoorCodeStoreTest {
  private class Fake(var text: String? = null, var broken: Boolean = false) : YtSecureStore {
    override fun load(): String? = if (broken) throw IOException("key gone") else text
    override fun save(text: String) { if (broken) throw IOException("no space"); this.text = text }
    override fun clear() { text = null }
  }

  private val fake = Fake()
  private val store = GuestDoorCodeStore(fake)

  @Test fun writesOnlyFourToEightDigits() {
    assertFalse(store.write("12a4"))
    assertFalse(store.write("123"))
    assertNull(fake.text)
    assertTrue(store.write("4821"))
    assertEquals("4821", store.read())
  }

  @Test fun blankClears() {
    store.write("4821")
    assertTrue(store.write(""))
    assertFalse(store.isSet())
  }

  /** The migration path: a legacy prefs value. */
  private class Legacy(var value: String?, var dropOk: Boolean = true) : GuestSecretsMigration.LegacySource {
    var dropped = false
    override fun read() = value
    override fun drop(): Boolean { dropped = dropOk; if (dropOk) value = null; return dropOk }
  }

  @Test fun migrationMovesThePlainCodeIntoTheSecretStoreAndDeletesIt() {
    val legacy = Legacy("4821#")
    assertTrue(GuestSecretsMigration.moveDoorCode(legacy, store))
    assertEquals("free-text codes from the first card are kept as they were", "4821#", store.read())
    assertTrue(legacy.dropped)
    assertNull(legacy.value)
  }

  @Test fun migrationNeverOverwritesANewerSecret() {
    store.write("1111")
    val legacy = Legacy("2222")
    assertTrue(GuestSecretsMigration.moveDoorCode(legacy, store))
    assertEquals("1111", store.read())
    assertTrue(legacy.dropped)
  }

  @Test fun migrationKeepsThePlainCopyWhenTheSecretCannotBeWritten() {
    fake.broken = true
    val legacy = Legacy("4821")
    assertFalse(GuestSecretsMigration.moveDoorCode(legacy, store))
    assertEquals("4821", legacy.value)
    assertFalse(legacy.dropped)
  }

  @Test fun blankOrMissingLegacyCodesAreJustDropped() {
    val blank = Legacy("  ")
    assertTrue(GuestSecretsMigration.moveDoorCode(blank, store))
    assertTrue(blank.dropped)
    assertFalse(store.isSet())
    assertTrue(GuestSecretsMigration.moveDoorCode(Legacy(null), store))
  }
}
