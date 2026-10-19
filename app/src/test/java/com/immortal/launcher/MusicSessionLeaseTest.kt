/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicSessionLeaseTest {
  @Test fun unopenedSessionDoesNotObserve() {
    assertFalse(MusicSessionLease(120_000).shouldObserve(0))
  }

  @Test fun oneUiCannotCloseAnotherUiOwner() {
    val lease = MusicSessionLease(120_000)
    lease.retainUi()
    lease.retainUi()
    lease.releaseUi()
    assertTrue(lease.shouldObserve(500_000))
    lease.releaseUi()
    assertFalse(lease.shouldObserve(500_000))
  }

  @Test fun closingPortalLayoutKeepsAnActiveRemoteSessionUntilItsDeadline() {
    val lease = MusicSessionLease(120_000)
    lease.retainUi()
    lease.touchRemote(100)
    lease.releaseUi()
    assertTrue(lease.shouldObserve(120_099))
    assertFalse(lease.shouldObserve(120_100))
  }

  @Test fun remoteRefreshExtendsLeaseWithoutLeakingUiOwnership() {
    val lease = MusicSessionLease(120_000)
    repeat(30) { lease.touchRemote(it * 1_000L) }
    assertTrue(lease.shouldObserve(148_999))
    assertFalse(lease.shouldObserve(149_000))
  }

  @Test fun uiOwnershipSurvivesRemoteLeaseExpiry() {
    val lease = MusicSessionLease(120_000)
    lease.touchRemote(10)
    lease.retainUi()
    assertTrue(lease.shouldObserve(500_000))
    lease.releaseUi()
    assertFalse(lease.shouldObserve(500_000))
  }

  @Test fun duplicateUiCleanupDoesNotCreateNegativeOwnership() {
    val lease = MusicSessionLease(120_000)
    lease.releaseUi()
    lease.retainUi()
    assertTrue(lease.shouldObserve(0))
    lease.releaseUi()
    lease.releaseUi()
    assertFalse(lease.shouldObserve(0))
  }
}
