/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Notification
import org.junit.Assert.*
import org.junit.Test

class NotificationBadgeSnapshotTest {
  @Test fun seedCountsEligibleKeysPerPackage() {
    val cache = NotificationBadgeSnapshot()
    val epoch = cache.beginSeed()
    assertFalse(cache.seeded)
    assertTrue(cache.completeSeed(epoch, listOf(
        entry("chat-1", "fixture.chat"), entry("chat-2", "fixture.chat"),
        entry("music", "fixture.music"), entry("excluded", "fixture.chat", false),
    )))
    assertTrue(cache.seeded)
    assertEquals(mapOf("fixture.chat" to 2, "fixture.music" to 1), cache.snapshot())
  }

  @Test fun initialDuplicateKeysCountOnce() {
    val cache = seeded(entry("same", "fixture.chat"), entry("same", "fixture.chat"))
    assertEquals(mapOf("fixture.chat" to 1), cache.snapshot())
  }

  @Test fun replacingTheSameKeyMovesItsCountToTheNewPackage() {
    val cache = seeded(entry("moving", "fixture.chat"), entry("remaining", "fixture.chat"))
    cache.upsert(entry("moving", "fixture.music"))
    assertEquals(mapOf("fixture.chat" to 1, "fixture.music" to 1), cache.snapshot())
    cache.upsert(entry("remaining", "fixture.music"))
    assertEquals(mapOf("fixture.music" to 2), cache.snapshot())
  }

  @Test fun duplicateUpdatesDoNotDoubleCountOrRepublish() {
    val cache = seeded(entry("chat", "fixture.chat"))
    val published = cache.snapshot()
    repeat(3) {
      cache.upsert(entry("chat", "fixture.chat"))
      assertSame(published, cache.snapshot())
    }
    assertEquals(mapOf("fixture.chat" to 1), cache.snapshot())
  }

  @Test fun ineligibleUpdatesAndRemovalsReduceCountsAndRemoveZeroPackages() {
    val cache = seeded(entry("first", "fixture.chat"), entry("second", "fixture.chat"))
    cache.upsert(entry("first", "fixture.chat", false))
    assertEquals(mapOf("fixture.chat" to 1), cache.snapshot())
    cache.remove("second")
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
    cache.remove("first")
    cache.remove("unknown")
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
  }

  @Test fun eligibleUpdateCanRestoreAnIneligibleKey() {
    val cache = seeded(entry("chat", "fixture.chat", false))
    cache.upsert(entry("chat", "fixture.chat"))
    assertEquals(mapOf("fixture.chat" to 1), cache.snapshot())
  }

  @Test fun twentyWarmReadsReturnTheSamePublishedMap() {
    val cache = seeded(entry("chat", "fixture.chat"), entry("music", "fixture.music"))
    val published = cache.snapshot()
    repeat(20) { assertSame(published, cache.snapshot()) }
  }

  @Test fun publishedSnapshotsRemainUnchangedAfterLaterEventsAndDisconnect() {
    val cache = seeded(entry("chat", "fixture.chat"), entry("music", "fixture.music"))
    val published = cache.snapshot()
    cache.remove("chat")
    cache.upsert(entry("music", "fixture.download"))
    cache.clear()
    assertEquals(mapOf("fixture.chat" to 1, "fixture.music" to 1), published)
  }

  @Test fun callersCannotMutateThePublishedSnapshot() {
    val cache = seeded(entry("chat", "fixture.chat"), entry("music", "fixture.music"))
    val published = cache.snapshot()
    try {
      @Suppress("UNCHECKED_CAST")
      (published as MutableMap<String, Int>)["fixture.injected"] = 99
      fail("Published badge snapshots must be immutable")
    } catch (_: UnsupportedOperationException) {
      // Java-backed immutable maps reject writes.
    } catch (_: ClassCastException) {
      // Kotlin immutable maps may reject the mutable interface itself.
    }
    assertEquals(mapOf("fixture.chat" to 1, "fixture.music" to 1), cache.snapshot())
  }

  @Test fun disconnectClearsPublishedCountsAndSeededStatus() {
    val cache = seeded(entry("chat", "fixture.chat"))
    cache.clear()
    assertFalse(cache.seeded)
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
  }

  @Test fun oldSeedIsRejectedAfterConnectionReset() {
    val cache = NotificationBadgeSnapshot()
    val oldEpoch = cache.beginSeed()
    cache.upsert(entry("old-pending", "fixture.old"))
    val currentEpoch = cache.beginSeed()
    assertNotEquals(oldEpoch, currentEpoch)
    assertFalse(cache.completeSeed(oldEpoch, listOf(entry("old", "fixture.old"))))
    assertFalse(cache.seeded)
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
    assertTrue(cache.completeSeed(currentEpoch, listOf(entry("new", "fixture.new"))))
    assertEquals(mapOf("fixture.new" to 1), cache.snapshot())
  }

  @Test fun disconnectRejectsAnOutstandingSeed() {
    val cache = NotificationBadgeSnapshot()
    val epoch = cache.beginSeed()
    cache.upsert(entry("pending", "fixture.chat"))
    cache.clear()
    assertFalse(cache.completeSeed(epoch, listOf(entry("initial", "fixture.chat"))))
    assertFalse(cache.seeded)
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
  }

  @Test fun duplicateSeedCompletionCannotOverwriteNewerEvents() {
    val cache = NotificationBadgeSnapshot()
    val epoch = cache.beginSeed()
    assertTrue(cache.completeSeed(epoch, listOf(entry("initial", "fixture.chat"))))
    cache.upsert(entry("later", "fixture.music"))
    val published = cache.snapshot()
    assertFalse(cache.completeSeed(epoch, emptyList()))
    assertTrue(cache.seeded)
    assertSame(published, cache.snapshot())
    assertEquals(mapOf("fixture.chat" to 1, "fixture.music" to 1), cache.snapshot())
  }

  @Test fun postsAndRemovalsDuringInitialReadOverrideTheSeed() {
    val cache = NotificationBadgeSnapshot()
    val epoch = cache.beginSeed()
    cache.upsert(entry("moving", "fixture.updated"))
    cache.upsert(entry("posted", "fixture.new"))
    cache.remove("removed")
    cache.upsert(entry("excluded", "fixture.chat", false))
    assertFalse(cache.seeded)
    assertEquals(emptyMap<String, Int>(), cache.snapshot())
    assertTrue(cache.completeSeed(epoch, listOf(
        entry("moving", "fixture.old"), entry("removed", "fixture.chat"),
        entry("excluded", "fixture.chat"), entry("unchanged", "fixture.chat"),
    )))
    assertEquals(mapOf("fixture.updated" to 1, "fixture.new" to 1, "fixture.chat" to 1), cache.snapshot())
  }

  @Test fun latestPendingEventPerKeyWins() {
    val cache = NotificationBadgeSnapshot()
    val epoch = cache.beginSeed()
    cache.upsert(entry("present", "fixture.old"))
    cache.remove("present")
    cache.upsert(entry("present", "fixture.final"))
    cache.remove("absent")
    cache.upsert(entry("absent", "fixture.temporary"))
    cache.remove("absent")
    assertTrue(cache.completeSeed(epoch, listOf(
        entry("present", "fixture.seed"), entry("absent", "fixture.seed"),
    )))
    assertEquals(mapOf("fixture.final" to 1), cache.snapshot())
  }

  @Test fun eligibilityExcludesNonclearableAndGroupSummariesWithoutRejectingOtherFlags() {
    assertTrue(notificationBadgeEligible(clearable = true, flags = 0))
    assertTrue(notificationBadgeEligible(clearable = true, flags = Notification.FLAG_ONGOING_EVENT))
    assertFalse(notificationBadgeEligible(clearable = false, flags = 0))
    assertFalse(notificationBadgeEligible(clearable = true, flags = Notification.FLAG_GROUP_SUMMARY))
    assertFalse(notificationBadgeEligible(clearable = true,
        flags = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT))
    assertFalse(notificationBadgeEligible(clearable = false, flags = Notification.FLAG_GROUP_SUMMARY))
  }

  private fun entry(key: String, packageName: String, eligible: Boolean = true) =
      NotificationBadgeSnapshot.Entry(key, packageName, eligible)

  private fun seeded(vararg entries: NotificationBadgeSnapshot.Entry): NotificationBadgeSnapshot =
      NotificationBadgeSnapshot().also { cache ->
        assertTrue(cache.completeSeed(cache.beginSeed(), entries.toList()))
      }
}
