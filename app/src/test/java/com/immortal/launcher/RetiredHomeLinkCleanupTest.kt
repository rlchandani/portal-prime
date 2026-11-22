/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetiredHomeLinkCleanupTest {
  @get:Rule val tmp = TemporaryFolder()

  private class FakeKeys(initial: Set<String>, private val stuck: Set<String> = emptySet()) : RetiredHomeLinkCleanup.Keys {
    val aliases = initial.toMutableSet()
    var lookups = 0
    override fun contains(alias: String): Boolean { lookups++; return alias in aliases }
    override fun delete(alias: String) { if (alias in stuck) throw IllegalStateException("keystore busy"); aliases.remove(alias) }
  }

  private val noWarn: (String, Throwable) -> Unit = { m, t -> fail("unexpected warning: $m $t") }

  private fun plantLeftovers(noBackup: File) {
    RetiredHomeLinkCleanup.DIRS.forEach { File(noBackup, it).apply { mkdirs() }.resolve("vault.bin").writeBytes(byteArrayOf(1, 2, 3)) }
  }

  @Test fun deletesLeftoverFoldersAndKeystoreAliasesThenMarksDone() {
    val noBackup = tmp.newFolder("no_backup")
    plantLeftovers(noBackup)
    val other = File(noBackup, "hearthpane_call_messages").apply { mkdirs() }.resolve("keep.bin").apply { writeText("x") }
    val keys = FakeKeys(RetiredHomeLinkCleanup.ALIASES.toSet() + "hearthpane.calling.messages")

    assertTrue(RetiredHomeLinkCleanup.run(noBackup, keys, noWarn))

    RetiredHomeLinkCleanup.DIRS.forEach { assertFalse(it, File(noBackup, it).exists()) }
    assertEquals(setOf("hearthpane.calling.messages"), keys.aliases)
    assertTrue("other stores are untouched", other.isFile)
    assertTrue(File(noBackup, RetiredHomeLinkCleanup.MARKER).isFile)
  }

  @Test fun nothingLeftOverStillMarksDone() {
    val noBackup = tmp.newFolder("no_backup")
    assertTrue(RetiredHomeLinkCleanup.run(noBackup, FakeKeys(emptySet()), noWarn))
    assertTrue(File(noBackup, RetiredHomeLinkCleanup.MARKER).isFile)
  }

  @Test fun createsMissingNoBackupFolderForTheMarker() {
    val noBackup = File(tmp.root, "never_created")
    assertTrue(RetiredHomeLinkCleanup.run(noBackup, FakeKeys(emptySet()), noWarn))
    assertTrue(File(noBackup, RetiredHomeLinkCleanup.MARKER).isFile)
  }

  @Test fun onceMarkedLaterStartsSkipTheKeystore() {
    val noBackup = tmp.newFolder("no_backup")
    assertTrue(RetiredHomeLinkCleanup.run(noBackup, FakeKeys(emptySet()), noWarn))
    val later = FakeKeys(setOf("hearthpane.portal.device.v1"))

    assertTrue(RetiredHomeLinkCleanup.run(noBackup, later, noWarn))
    assertEquals(0, later.lookups)
  }

  @Test fun aStuckKeyIsWarnedLeavesNoMarkerAndDoesNotStopTheRest() {
    val noBackup = tmp.newFolder("no_backup")
    plantLeftovers(noBackup)
    val stuck = "hearthpane.calling.homelinks"
    val keys = FakeKeys(RetiredHomeLinkCleanup.ALIASES.toSet(), stuck = setOf(stuck))
    val warnings = mutableListOf<String>()

    assertFalse(RetiredHomeLinkCleanup.run(noBackup, keys) { m, _ -> warnings += m })

    assertEquals(1, warnings.size)
    assertEquals(setOf(stuck), keys.aliases)
    RetiredHomeLinkCleanup.DIRS.forEach { assertFalse(it, File(noBackup, it).exists()) }
    assertFalse("retried next start", File(noBackup, RetiredHomeLinkCleanup.MARKER).exists())

    val retry = FakeKeys(keys.aliases)
    assertTrue(RetiredHomeLinkCleanup.run(noBackup, retry, noWarn))
    assertTrue(retry.aliases.isEmpty())
  }

  @Test fun coversEveryRetiredStore() {
    assertEquals(listOf("hearthpane_homelinks", "hearthpane_portal_roster"), RetiredHomeLinkCleanup.DIRS)
    assertEquals(setOf("hearthpane.calling.homelinks", "hearthpane.portal.roster", "hearthpane.portal.device.v1"),
        RetiredHomeLinkCleanup.ALIASES.toSet())
  }
}
