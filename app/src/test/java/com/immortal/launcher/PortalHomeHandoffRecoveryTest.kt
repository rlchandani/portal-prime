/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalHomeHandoffRecoveryTest {
  @Test fun coldMetaBootstrapRedirect_hasABoundedAttemptBudgetPerCallsHandoff() {
    val recovery = ColdPortalHomeRecovery()
    assertTrue(recovery.claim(1_000, 6_000, true, true, true, false))
    assertTrue(recovery.claim(1_000, 6_100, true, true, true, false))
    assertTrue(recovery.claim(1_000, 6_200, true, true, true, false))
    assertFalse(recovery.claim(1_000, 6_300, true, true, true, false))
    assertTrue(recovery.claim(10_000, 15_000, true, true, true, false))
  }

  @Test fun hardwareHomeKey_doesNotRecoverOrConsumeThePendingAttempt() {
    val recovery = ColdPortalHomeRecovery()
    // Observed Home-key intents lack CLEAR_TOP; Android can also mark a task reset.
    assertFalse(recovery.claim(1_000, 2_000, true, true, false, false))
    assertFalse(recovery.claim(1_000, 2_000, true, true, false, true))
    assertFalse(recovery.claim(1_000, 2_000, true, true, true, true))
    assertTrue(recovery.claim(1_000, 6_000, true, true, true, false))
  }

  @Test fun explicitBackHelperReturn_doesNotRecoverOrConsumeThePendingAttempt() {
    val recovery = ColdPortalHomeRecovery()
    assertFalse(recovery.claim(1_000, 2_000, true, true, true, false, reordersTask = true))
    assertTrue(recovery.claim(1_000, 6_000, true, true, true, false))
  }

  @Test fun ordinaryReturnAndNonPortalHandoffs_areNotRedirected() {
    val recovery = ColdPortalHomeRecovery()
    assertFalse(recovery.claim(1_000, 2_000, true, false, true, false))
    assertFalse(recovery.claim(1_000, 2_000, false, true, true, false))
    assertFalse(recovery.claim(0, 2_000, true, true, true, false))
    assertTrue(recovery.claim(1_000, 6_000, true, true, true, false))
  }

  @Test fun staleOrFutureHandoffs_cannotReopenMetaHome() {
    val recovery = ColdPortalHomeRecovery()
    assertFalse(recovery.claim(1_000, 31_001, true, true, true, false))
    assertFalse(recovery.claim(1_000, 999, true, true, true, false))
    assertTrue(recovery.claim(1_000, 31_000, true, true, true, false))
  }
}
