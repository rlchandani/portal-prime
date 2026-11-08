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

class GuestPasswordStoreTest {
  private class Fake(var text: String? = null, var broken: Boolean = false) : YtSecureStore {
    override fun load(): String? = if (broken) throw IOException("key gone") else text
    override fun save(text: String) { if (broken) throw IOException("no space"); this.text = text }
    override fun clear() { text = null }
  }

  private val fake = Fake()
  private val store = GuestPasswordStore(fake)

  @Test fun writesAndReadsBack() {
    assertTrue(store.write("correct horse"))
    assertEquals("correct horse", store.read())
    assertTrue(store.isSet())
  }

  @Test fun blankClears() {
    store.write("x1234567")
    assertTrue(store.write(""))
    assertNull(fake.text)
    assertFalse(store.isSet())
  }

  @Test fun rejectsTooLongOrControlCharacters() {
    assertFalse(store.write("a".repeat(GuestPasswordStore.MAX_LENGTH + 1)))
    assertFalse(store.write("abc\ndef"))
    assertNull(fake.text)
  }

  @Test fun anUnreadableStoreReadsAsNoPassword() {
    fake.broken = true
    assertEquals("", store.read())
    assertFalse(store.isSet())
    assertFalse(store.write("abcdefgh"))
  }
}
