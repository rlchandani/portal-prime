package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncStoreTest {
  private val known = setOf("theme", "level")
  private val wellFormed: (String, Any?) -> Any? = { k, v -> v.takeIf { k in known } }

  private fun rec(key: String, value: Any, counter: Long) = SyncRecord(key, value, SyncVersion(counter, "dev-A"), 5L)

  @Test
  fun theLedgerRoundTrips() {
    val ledger = SyncLedger(
        phase = SyncPhase.SYNCED, clock = 9, records = mapOf("theme" to rec("theme", "dark", 9)),
        lastKnown = mapOf("theme" to "dark", "level" to 3),
        peers = mapOf("dev-B" to SyncPeer("dev-B", "Family", 100, 90)),
        pending = PendingAdopt("dev-B", "Family", mapOf("level" to rec("level", 4, 2))),
        lastSyncAt = 90, adoptedFrom = "Office")
    assertEquals(ledger, SettingsSyncStore.decode(SettingsSyncStore.encode(ledger).toString(), wellFormed))
  }

  @Test
  fun keysThatAreNoLongerHouseholdAreDroppedOnLoad() {
    val ledger = SyncLedger(phase = SyncPhase.SYNCED, records = mapOf("gone" to rec("gone", 1, 1), "theme" to rec("theme", "x", 2)),
        lastKnown = mapOf("gone" to 1))
    val back = SettingsSyncStore.decode(SettingsSyncStore.encode(ledger).toString(), wellFormed)!!
    assertEquals(setOf("theme"), back.records.keys)
    assertTrue(back.lastKnown.isEmpty())
  }

  @Test
  fun anUnreadableLedgerStartsOver() {
    assertNull(SettingsSyncStore.decode("{", wellFormed))
    assertEquals(SyncPhase.JOINING, SettingsSyncStore.decode("{}", wellFormed)!!.phase)
  }
}
