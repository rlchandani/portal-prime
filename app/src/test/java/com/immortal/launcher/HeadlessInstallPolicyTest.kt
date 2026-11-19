/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessInstallPolicyTest {
  private val mb = 1024L * 1024L

  @Test fun resultTimeoutGrowsWithApkSizeWithinBounds() {
    assertEquals(HeadlessInstallPolicy.MIN_RESULT_TIMEOUT_MS, HeadlessInstallPolicy.resultTimeoutMs(0))
    assertEquals(HeadlessInstallPolicy.MIN_RESULT_TIMEOUT_MS, HeadlessInstallPolicy.resultTimeoutMs(-5))
    // 1.257 is 52 MB: the old flat 120 s wait now becomes 284 s.
    assertEquals(284_000L, HeadlessInstallPolicy.resultTimeoutMs(52 * mb))
    assertTrue(HeadlessInstallPolicy.resultTimeoutMs(52 * mb) > HeadlessInstallPolicy.resultTimeoutMs(38 * mb))
    assertEquals(HeadlessInstallPolicy.MAX_RESULT_TIMEOUT_MS, HeadlessInstallPolicy.resultTimeoutMs(2_000 * mb))
  }

  @Test fun partialMegabyteRoundsUp() {
    assertEquals(HeadlessInstallPolicy.MIN_RESULT_TIMEOUT_MS + HeadlessInstallPolicy.RESULT_TIMEOUT_PER_MB_MS,
        HeadlessInstallPolicy.resultTimeoutMs(1))
  }

  @Test fun representsOnlyAnUntappedShownDialogWithinTheCap() {
    assertTrue(HeadlessInstallPolicy.shouldRepresent(havePendingIntent = true, tapped = false, presentations = 1))
    assertFalse("never shown: nothing to re-show",
        HeadlessInstallPolicy.shouldRepresent(havePendingIntent = false, tapped = false, presentations = 0))
    assertFalse("a tapped session may be installing; re-showing could re-run its commit",
        HeadlessInstallPolicy.shouldRepresent(havePendingIntent = true, tapped = true, presentations = 1))
    assertFalse(HeadlessInstallPolicy.shouldRepresent(true, false, HeadlessInstallPolicy.MAX_PRESENTATIONS))
    assertTrue(HeadlessInstallPolicy.shouldRepresent(true, false, HeadlessInstallPolicy.MAX_PRESENTATIONS - 1))
  }

  @Test fun orphansAreOwnedSessionsWithoutALiveWaiter() {
    assertEquals(listOf(303252533, 9), HeadlessInstallPolicy.orphans(listOf(303252533, 37, 9, 9, -1), setOf(37)))
    assertEquals(emptyList<Int>(), HeadlessInstallPolicy.orphans(listOf(37), setOf(37)))
    assertEquals(emptyList<Int>(), HeadlessInstallPolicy.orphans(emptyList(), emptySet()))
  }

  @Test fun onlyAcceptanceProblemsCountAsConfirmationFailures() {
    assertTrue(HeadlessInstallOutcome.ConfirmTimeout(120_000, 1, tapped = true).confirmationFailed)
    assertTrue(HeadlessInstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_ABORTED, null).confirmationFailed)
    assertFalse(HeadlessInstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "sig").confirmationFailed)
    assertFalse(HeadlessInstallOutcome.Error("IOException").confirmationFailed)
    assertFalse(HeadlessInstallOutcome.Installed.confirmationFailed)
    assertTrue(HeadlessInstallOutcome.Installed.ok)
    assertFalse(HeadlessInstallOutcome.ConfirmTimeout(1, 1, tapped = true).ok)
  }

  @Test fun descriptionsNeverClaimSuccessWithoutASessionResult() {
    val tappedButNothing = HeadlessInstallPolicy.describe(HeadlessInstallOutcome.ConfirmTimeout(284_000, 2, tapped = true))
    assertTrue(tappedButNothing, tappedButNothing.contains("never accepted"))
    assertTrue(tappedButNothing.contains("284s"))
    assertFalse(tappedButNothing.contains("confirmed"))
    assertTrue(HeadlessInstallPolicy.describe(HeadlessInstallOutcome.ConfirmTimeout(1_000, 1, tapped = false))
        .contains("never tapped"))
    assertEquals("failed: session status 4 (INSTALL_FAILED_INVALID_APK)",
        HeadlessInstallPolicy.describe(HeadlessInstallOutcome.Failed(4, "INSTALL_FAILED_INVALID_APK")))
    assertEquals("failed: session status 3",
        HeadlessInstallPolicy.describe(HeadlessInstallOutcome.Failed(3, " ")))
    assertTrue(HeadlessInstallPolicy.describe(HeadlessInstallOutcome.Installed).contains("session reported success"))
  }
}
