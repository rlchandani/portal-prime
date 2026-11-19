/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Silent adopt for a Portal with nothing to choose, and when the Home adopt card shows. */
class SettingsSyncAdoptTest {
  private val e = SettingsSyncEngine
  private val known = setOf("clock.face", "theme", "chime.on", "level")
  private val wellFormed: (String, Any?) -> Any? = { k, v -> v.takeIf { k in known && (v is String || v is Boolean || v is Int) } }
  private val defaults = mapOf("clock.face" to "classic", "theme" to "light", "chime.on" to false, "level" to 5)
  private fun rec(key: String, value: Any, counter: Long, device: String = "B") = SyncRecord(key, value, SyncVersion(counter, device), 1L)
  private val household = listOf(rec("theme", "dark", 4), rec("clock.face", "neon", 6))

  private fun joining(current: Map<String, Any>) =
      e.addPending(SyncLedger(lastKnown = current), "B", "Family Area", household, wellFormed)

  @Test fun untouchedDefaultsAdoptSilently() {
    assertTrue(e.canAutoAdopt(joining(defaults).pending, defaults, defaults))
  }

  @Test fun changesTheHouseholdNeverMadeStillAdoptSilentlyAndSurvive() {
    val current = defaults + ("level" to 8)
    val l = joining(current)
    assertTrue(e.canAutoAdopt(l.pending, current, defaults))
    val (adopted, writes) = e.resolveJoin(l, current, defaults, "Z", 10, keepMine = false)
    val (kept, _) = e.resolveJoin(l, current, defaults, "Z", 10, keepMine = true)
    assertEquals("nothing to choose: adopt and keep agree", kept.records, adopted.records)
    assertEquals(8, adopted.records.getValue("level").value)
    assertEquals(mapOf<String, Any>("theme" to "dark", "clock.face" to "neon"), writes)
  }

  @Test fun aChangeTheHouseholdWouldOverrideWaitsForTheOwner() {
    val current = defaults + ("theme" to "sepia")
    assertFalse(e.canAutoAdopt(joining(current).pending, current, defaults))
  }

  @Test fun aValueAlreadyMatchingTheHouseholdIsNoConflict() {
    val current = defaults + ("theme" to "dark")
    assertTrue(e.canAutoAdopt(joining(current).pending, current, defaults))
  }

  @Test fun nothingPendingNeverAdopts() {
    assertFalse(e.canAutoAdopt(null, defaults, defaults))
    assertFalse(e.canAutoAdopt(PendingAdopt("B", "Family Area", emptyMap()), defaults, defaults))
    // A peer that is itself joining offers no records, so nothing becomes pending.
    assertEquals(null, e.addPending(SyncLedger(lastKnown = defaults), "B", "Family Area", emptyList(), wellFormed).pending)
  }

  private fun status(conflicts: Boolean = true, dismissed: Boolean = false, available: Boolean = true, paused: Boolean = false,
                     phase: SyncPhase = SyncPhase.JOINING, from: String? = "Family Area") =
      SettingsSyncStatus.from(SyncLedger(phase = phase, pending = from?.let { PendingAdopt("B", it, household.associateBy { r -> r.key }) }),
          available = available, paused = paused, conflicts = conflicts, adoptCardDismissed = dismissed)

  @Test fun homeCardShowsOnlyForARealWaitingChoice() {
    assertTrue(status().showAdoptCard)
    assertFalse("silent adopt handles it", status(conflicts = false).showAdoptCard)
    assertFalse("Later hides it", status(dismissed = true).showAdoptCard)
    assertFalse("no household key", status(available = false).showAdoptCard)
    assertFalse("paused", status(paused = true).showAdoptCard)
    assertFalse("nothing pending", status(from = null).showAdoptCard)
    assertFalse("already joined", status(phase = SyncPhase.SYNCED).showAdoptCard)
  }

  @Test fun homeCardWords() {
    assertEquals("Use your household’s settings from Family Area?", SettingsSyncHomeCopy.title("Family Area"))
    assertEquals(listOf("Use", "Keep this Portal’s", "Later"),
        listOf(SettingsSyncHomeCopy.USE, SettingsSyncHomeCopy.KEEP, SettingsSyncHomeCopy.LATER))
  }
}
