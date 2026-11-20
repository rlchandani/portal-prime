/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure phone-session lifetime rules (Finding 3: sessions never expired). */
class RemoteSessionPolicyTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 1_000L * day

  private fun record(token: String, created: Long, seen: Long) =
      RemoteSessionRecord(RemoteSessionPolicy.idOf(token), created, seen, "Pixel")

  @Test fun idIsStableOpaqueAndNeverTheToken() {
    val id = RemoteSessionPolicy.idOf("abc")
    assertEquals(id, RemoteSessionPolicy.idOf("abc"))
    assertNotEquals(id, RemoteSessionPolicy.idOf("abd"))
    assertEquals(16, id.length)
    assertFalse(id.contains("abc"))
  }

  @Test fun expiresOnlyAfter180IdleDays() {
    val r = record("t", 0L, now)
    assertFalse(RemoteSessionPolicy.isExpired(r, now + 180 * day))
    assertTrue(RemoteSessionPolicy.isExpired(r, now + 180 * day + 1))
    // A clock that jumped backwards never expires anybody.
    assertFalse(RemoteSessionPolicy.isExpired(r, now - 400 * day))
  }

  @Test fun useRefreshesLastSeenAtMostHourly() {
    val r = record("t", 0L, now)
    assertNull(RemoteSessionPolicy.touched(r, now + 59 * 60_000L))
    assertEquals(now + 60 * 60_000L, RemoteSessionPolicy.touched(r, now + 60 * 60_000L)!!.lastSeenMs)
    // Daily use keeps a session alive indefinitely (refreshed on use, no hard cap).
    var current = r
    for (d in 1..400) current = RemoteSessionPolicy.touched(current, now + d * day) ?: current
    assertFalse(RemoteSessionPolicy.isExpired(current, now + 400 * day))
  }

  @Test fun upgradeStartsTheClockForEveryExistingPairing() {
    // A 1.271 Portal stored tokens only; nobody may be expired by the upgrade.
    val result = RemoteSessionPolicy.reconcile(listOf("old-a", "old-b"), emptyMap(), now)
    assertEquals(listOf("old-a", "old-b"), result.tokens)
    assertTrue(result.changed)
    result.records.values.forEach {
      assertEquals(now, it.createdMs)
      assertEquals(now, it.lastSeenMs)
      assertEquals(RemoteSessionPolicy.LEGACY_LABEL, it.label)
    }
  }

  @Test fun reconcileDropsExpiredAndOrphanedRecords() {
    val fresh = record("fresh", 0L, now - day)
    val stale = record("stale", 0L, now - 181 * day)
    val orphan = record("gone", 0L, now)
    val result = RemoteSessionPolicy.reconcile(listOf("fresh", "stale"),
        mapOf(fresh.id to fresh, stale.id to stale, orphan.id to orphan), now)
    assertEquals(listOf("fresh"), result.tokens)
    assertEquals(setOf(fresh.id), result.records.keys)
    assertTrue(result.changed)
  }

  @Test fun reconcileIsAQuietNoOpWhenNothingChanged() {
    val fresh = record("fresh", 0L, now)
    val result = RemoteSessionPolicy.reconcile(listOf("fresh"), mapOf(fresh.id to fresh), now)
    assertFalse(result.changed)
  }

  @Test fun recordsRoundTripAndCorruptStorageFallsBackToFreshRecords() {
    val r = record("t", 5L, 9L).copy(test = true)
    assertEquals(mapOf(r.id to r), RemoteSessionPolicy.decode(RemoteSessionPolicy.encode(mapOf(r.id to r))))
    assertEquals(emptyMap<String, RemoteSessionRecord>(), RemoteSessionPolicy.decode("{not json"))
    assertEquals(emptyMap<String, RemoteSessionRecord>(), RemoteSessionPolicy.decode("""{"x":{"created":-1}}"""))
    // Corrupt records never lock a phone out: its token gets a fresh record.
    assertEquals(listOf("t"), RemoteSessionPolicy.reconcile(listOf("t"), RemoteSessionPolicy.decode("garbage"), now).tokens)
  }

  @Test fun labelsAreShortPrintableAndNeverBlank() {
    assertEquals("Pixel 8", RemoteSessionPolicy.cleanLabel("  Pixel\n 8 "))
    assertEquals(RemoteSessionPolicy.LEGACY_LABEL, RemoteSessionPolicy.cleanLabel("\u0000\u0007 "))
    assertEquals(RemoteSessionPolicy.LEGACY_LABEL, RemoteSessionPolicy.cleanLabel(null))
    assertEquals(40, RemoteSessionPolicy.cleanLabel("x".repeat(200)).length)
  }

  @Test fun pairedPhoneCopyIsHonestAboutHourlyGranularity() {
    assertEquals("Used in the last hour", PairedPhoneCopy.lastSeen(now, now + 30 * 60_000L))
    assertEquals("Used 1 h ago", PairedPhoneCopy.lastSeen(now, now + 90 * 60_000L))
    assertEquals("Used 5 h ago", PairedPhoneCopy.lastSeen(now, now + 5 * 60 * 60_000L))
    assertEquals("Used 1 day ago", PairedPhoneCopy.lastSeen(now, now + day + 1))
    assertEquals("Used 12 days ago", PairedPhoneCopy.lastSeen(now, now + 12 * day))
    assertEquals("Used in the last hour", PairedPhoneCopy.lastSeen(now, now - day)) // clock went back
    assertEquals("pairing ends in 180 days unless used", PairedPhoneCopy.expiresIn(now, now))
    assertEquals("pairing ends in 1 day unless used", PairedPhoneCopy.expiresIn(now, now + 179 * day))
    assertEquals("pairing ends in 0 days unless used", PairedPhoneCopy.expiresIn(now, now + 200 * day))
  }
}
