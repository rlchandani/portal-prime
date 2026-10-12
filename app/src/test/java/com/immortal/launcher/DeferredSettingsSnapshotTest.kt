/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test

class DeferredSettingsSnapshotTest {
  @Test fun thirtyUnusedPagesAndResumeEvents_doNotLoadEitherDomain() {
    var reads = 0
    repeat(30) {
      val immortal = DeferredSettingsSnapshot { reads++; "app settings" }
      val screensaver = DeferredSettingsSnapshot { reads++; "screensaver settings" }
      immortal.refreshIfLoaded(); screensaver.refreshIfLoaded()
    }
    println("unused_snapshot_reads=" + reads)
    assertEquals(0, reads)
  }

  @Test fun usedDomain_loadsOnceWhileTheOtherDomainStaysUnread() {
    var immortalReads = 0
    var screensaverReads = 0
    val immortal = DeferredSettingsSnapshot { immortalReads++; listOf("appearance") }
    val screensaver = DeferredSettingsSnapshot { screensaverReads++; listOf("photos") }
    val first = immortal.value
    repeat(20) { assertSame(first, immortal.value) }
    screensaver.refreshIfLoaded()
    assertEquals(1, immortalReads)
    assertEquals(0, screensaverReads)
  }

  @Test fun firstReadUsesCurrentValuesAfterPreviouslyUnusedResume() {
    var stored = "before"
    val snapshot = DeferredSettingsSnapshot { stored }
    snapshot.refreshIfLoaded()
    stored = "after"
    assertEquals("after", snapshot.value)
  }

  @Test fun loadedDomainRefresh_readsFreshStateAndPublishesIt() {
    var stored = listOf("before")
    var reads = 0
    val snapshot = DeferredSettingsSnapshot { reads++; stored }
    assertSame(stored, snapshot.value)
    stored = listOf("after")
    snapshot.refreshIfLoaded()
    assertSame(stored, snapshot.value)
    assertEquals(2, reads)
  }

  @Test fun refreshFailure_preservesLastVerifiedSnapshotAndCanRetry() {
    var unavailable = false
    var stored = "before"
    val snapshot = DeferredSettingsSnapshot {
      if (unavailable) error("read unavailable") else stored
    }
    assertEquals("before", snapshot.value)
    unavailable = true
    assertThrows(IllegalStateException::class.java) { snapshot.refreshIfLoaded() }
    assertEquals("before", snapshot.value)
    unavailable = false; stored = "after"
    snapshot.refreshIfLoaded()
    assertEquals("after", snapshot.value)
  }

  @Test fun initialFailure_isDeferredUntilReadAndCanRetry() {
    var reads = 0
    val snapshot = DeferredSettingsSnapshot {
      reads++; if (reads == 1) error("first read unavailable") else "ready"
    }
    assertEquals(0, reads)
    assertThrows(IllegalStateException::class.java) { snapshot.value }
    assertEquals("ready", snapshot.value)
    assertEquals("ready", snapshot.value)
    assertEquals(2, reads)
  }

  @Test fun nullIsALoadedValue_andStillRefreshes() {
    var reads = 0
    val snapshot = DeferredSettingsSnapshot<String?> { reads++; null }
    assertNull(snapshot.value); assertNull(snapshot.value)
    assertEquals(1, reads)
    snapshot.refreshIfLoaded()
    assertEquals(2, reads)
  }

  @Test fun refreshingOneDomain_doesNotLoadOrModifyTheOther() {
    var a = "a1"; var b = "b1"
    var bReads = 0
    val first = DeferredSettingsSnapshot { a }
    val second = DeferredSettingsSnapshot { bReads++; b }
    assertEquals("a1", first.value)
    a = "a2"; b = "b2"
    first.refreshIfLoaded()
    assertEquals("a2", first.value)
    assertEquals(0, bReads)
    assertEquals("b2", second.value)
    assertEquals(1, bReads)
  }

  @Test fun loadedValuesRemainComposeObservableWhenRefreshed() {
    var stored = "before"
    val snapshot = DeferredSettingsSnapshot { stored }
    var readsObserved = 0
    var writesObserved = 0
    Snapshot.observe({ readsObserved++ }, { writesObserved++ }) {
      assertEquals("before", snapshot.value)
      stored = "after"; snapshot.refreshIfLoaded()
      assertEquals("after", snapshot.value)
    }
    assertTrue(readsObserved > 0)
    assertTrue(writesObserved > 0)
  }

  @Test fun separateOwners_keepSeparateSnapshotsOfTheSameDomain() {
    var stored = "before"
    val first = DeferredSettingsSnapshot { stored }
    assertEquals("before", first.value)
    stored = "after"
    val second = DeferredSettingsSnapshot { stored }
    assertEquals("after", second.value)
    assertEquals("before", first.value)
    first.refreshIfLoaded()
    assertEquals("after", first.value)
  }
}
