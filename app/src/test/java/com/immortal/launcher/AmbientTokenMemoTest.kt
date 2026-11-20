/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class AmbientTokenMemoTest {
  private class Disk(var encoded: String? = null, var writeOk: Boolean = true) {
    var reads = 0
    var writes = 0
    fun read(): SavedPortalAmbient? { reads++; return encoded?.let(::decodePortalAmbient) }
    fun write(e: String): Boolean { writes++; if (writeOk) encoded = e; return writeOk }
    fun memo() = AmbientTokenMemo(::read, ::write)
  }

  @Test fun everyResumeAfterTheFirstReadsNoDisk() {
    val disk = Disk("-")
    val memo = disk.memo()
    repeat(5) { assertNull(memo.saved()) }
    assertEquals(1, disk.reads)
  }

  @Test fun aSuccessfulWriteIsWhatTheNextReadSees() {
    val disk = Disk()
    val memo = disk.memo()
    assertTrue(memo.persist(encodePortalAmbient("1")))
    assertEquals(SavedPortalAmbient("1"), memo.saved())
    assertTrue(memo.persist(encodePortalAmbient(null)))
    assertEquals(SavedPortalAmbient(null), memo.saved())
    assertTrue(memo.persist("-"))
    assertNull(memo.saved())
    assertEquals(0, disk.reads)
  }

  @Test fun aFailedWriteKeepsThePreviousToken() {
    val disk = Disk("12")
    val memo = disk.memo()
    assertEquals(SavedPortalAmbient("2"), memo.saved())
    disk.writeOk = false
    assertFalse(memo.persist("-"))
    assertEquals(SavedPortalAmbient("2"), memo.saved())
    assertEquals("12", disk.encoded)
  }

  @Test fun aCorruptTokenIsNotRememberedSoItFailsClosedEveryTime() {
    val disk = Disk("broken")
    val memo = disk.memo()
    repeat(2) { assertThrows(IllegalStateException::class.java) { memo.saved() } }
    assertEquals(2, disk.reads)
  }

  @Test fun theHandoffRestoresTheOriginalThroughTheMemo() {
    val disk = Disk("-")
    val memo = disk.memo()
    var setting: String? = "1"
    val handoff = PortalAmbientHandoff(object : PortalAmbientBackend {
      override fun saved() = memo.saved()
      override fun save(original: String?) = memo.persist(encodePortalAmbient(original))
      override fun clear() = memo.persist("-")
      override fun current() = setting
      override fun write(value: String?): Boolean { setting = value; return true }
    })
    assertTrue(handoff.begin())
    assertEquals("0", setting)
    assertTrue(handoff.restore())
    assertEquals("1", setting)
    assertEquals("-", disk.encoded)
    assertTrue(handoff.restore()) // the common Home resume: nothing pending, no disk
    assertEquals(1, disk.reads)
  }
}
