/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.FleetSecretPaths
import com.immortal.launcher.youtube.YtSecureStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryProfileStore] over a stand-in for the Keystore file store: one sealed file per scope,
 * nothing readable at rest, and every failure reads as an empty profile instead of throwing.
 */
class MemoryProfileStoreTest {
  private val root = File(Files.createTempDirectory("app").toFile(), "no_backup").apply { mkdirs() }
  private val dir = File(root, MemoryProfileStore.DIR)
  private val logged = mutableListOf<String>()
  private var broken = false
  private val store = MemoryProfileStore(dir, { SealingFake(it) }, logged::add)

  private val person = MemoryScope.Person("p1")
  private val warriors = MemoryFact("team:nba.gsw", MemoryFact.Kind.TEAM, "Golden State Warriors",
      evidence = listOf(Evidence(Evidence.Source.UTTERANCE, 2L, "family", "c1", 0.3)),
      confidence = 0.6, state = MemoryFact.State.ACTIVE, firstSeenMs = 1L, lastSeenMs = 2L)

  @Test fun aSavedProfileLoadsBackPerScope() {
    assertTrue(store.save(MemoryProfile(person, listOf(warriors))))
    assertTrue(store.save(MemoryProfile(MemoryScope.Household)))

    assertEquals(listOf(warriors), store.load(person).facts)
    assertTrue(store.load(MemoryScope.Household).facts.isEmpty())
    assertEquals(listOf(MemoryScope.Household, person), store.scopes())
    assertTrue(File(dir, "person-p1.bin").isFile && File(dir, "household.bin").isFile)
  }

  @Test fun nothingReadableIsWrittenToDisk() {
    store.save(MemoryProfile(person, listOf(warriors)))
    val bytes = dir.walkTopDown().filter { it.isFile }.joinToString { it.readText(Charsets.ISO_8859_1) }
    assertFalse(bytes.contains("Warriors"))
    assertFalse(bytes.contains("team:nba"))
  }

  @Test fun aMissingProfileIsEmpty() {
    assertEquals(MemoryProfile(person), store.load(person))
  }

  @Test fun anUnreadableFileLoadsEmptyAndIsKept() {
    store.save(MemoryProfile(person, listOf(warriors)))
    broken = true

    assertEquals(MemoryProfile(person), store.load(person))
    assertTrue(File(dir, "person-p1.bin").isFile)

    broken = false
    assertEquals(listOf(warriors), store.load(person).facts)
  }

  @Test fun anotherScopesFileLoadsEmpty() {
    store.save(MemoryProfile(person, listOf(warriors)))
    File(dir, "person-p1.bin").copyTo(File(dir, "person-p2.bin"))
    assertTrue(store.load(MemoryScope.Person("p2")).facts.isEmpty())
  }

  @Test fun aFailedSaveReportsFalseAndKeepsTheOldProfile() {
    store.save(MemoryProfile(person, listOf(warriors)))
    broken = true
    assertFalse(store.save(MemoryProfile(person)))
    broken = false
    assertEquals(listOf(warriors), store.load(person).facts)
  }

  @Test fun forgetRemovesOneScopeAndForgetAllRemovesEverything() {
    store.save(MemoryProfile(person, listOf(warriors)))
    store.save(MemoryProfile(MemoryScope.Household))

    assertTrue(store.forget(person))
    assertEquals(listOf<MemoryScope>(MemoryScope.Household), store.scopes())

    File(dir, "stray.tmp").writeText("x")
    assertTrue(store.forgetAll())
    assertTrue(store.scopes().isEmpty())
    assertTrue(dir.listFiles().isNullOrEmpty())
  }

  @Test fun theStoreIsBlockedFromFleetFileRoutes() {
    store.save(MemoryProfile(person))
    assertTrue(FleetSecretPaths.isBlocked(File(dir, "person-p1.bin")))
    assertTrue(FleetSecretPaths.isBlocked(dir))
  }

  @Test fun logsNeverCarryProfileContent() {
    store.save(MemoryProfile(person, listOf(warriors)))
    broken = true
    store.load(person)
    store.save(MemoryProfile(person, listOf(warriors)))
    assertTrue(logged.isNotEmpty())
    assertTrue(logged.toString(), logged.none { it.contains("Warriors") || it.contains("nba") })
  }

  /** Stands in for YtKeystoreSecureStore: writes a reversible scramble, never the plaintext. */
  private inner class SealingFake(private val file: File) : YtSecureStore {
    override fun load(): String? {
      if (broken) throw IOException("keystore unavailable")
      if (!file.isFile) return null
      return String(file.readBytes().map { (it.toInt() xor MASK).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    override fun save(text: String) {
      if (broken) throw IOException("keystore unavailable")
      file.parentFile?.mkdirs()
      file.writeBytes(text.toByteArray(Charsets.UTF_8).map { (it.toInt() xor MASK).toByte() }.toByteArray())
    }

    override fun clear() {
      file.delete()
    }
  }

  private companion object {
    const val MASK = 0x5a
  }
}
