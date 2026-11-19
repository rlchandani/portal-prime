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

class InstallConfirmGateTest {
  private var now = 10_000L
  private val gate = InstallConfirmGate(clock = { now })
  private val ready = ConfirmButtonSnapshot(found = true, enabled = true, visibleToUser = true, windowFocused = true)

  @Test fun tapsAnEnabledVisibleButtonInAFocusedWindow() {
    assertEquals(ConfirmDecision.TAP, gate.decide(true, ready))
  }

  @Test fun neverTapsADisabledButton() {
    // Android 10 disables Install while paused and a tap then closes the dialog unaccepted.
    assertEquals(ConfirmDecision.DISABLED, gate.decide(true, ready.copy(enabled = false)))
  }

  @Test fun waitsWhileTheDreamOrHomeHoldsFocus() {
    assertEquals(ConfirmDecision.NOT_FOCUSED, gate.decide(true, ready.copy(windowFocused = false)))
  }

  @Test fun waitsForAnOffscreenButton() {
    assertEquals(ConfirmDecision.NOT_VISIBLE, gate.decide(true, ready.copy(visibleToUser = false)))
  }

  @Test fun reportsMissingDialogAndButton() {
    assertEquals(ConfirmDecision.NO_DIALOG, gate.decide(false, ready))
    assertEquals(ConfirmDecision.NO_BUTTON, gate.decide(true, ready.copy(found = false)))
  }

  @Test fun retriesAStillShowingDialogOnlyAfterTheInterval() {
    assertEquals(ConfirmDecision.TAP, gate.decide(true, ready))
    now += InstallConfirmGate.MIN_TAP_INTERVAL_MS - 1
    assertEquals(ConfirmDecision.THROTTLED, gate.decide(true, ready))
    now += 1
    assertEquals(ConfirmDecision.TAP, gate.decide(true, ready))
  }

  @Test fun waitingDecisionsDoNotStartTheThrottle() {
    assertEquals(ConfirmDecision.DISABLED, gate.decide(true, ready.copy(enabled = false)))
    assertEquals(ConfirmDecision.TAP, gate.decide(true, ready))
  }

  @Test fun affirmativeLabelsAreButtonsNotQuestionText() {
    listOf("Install", "INSTALL", " Update ", "Continue", "Install anyway").forEach {
      assertTrue(it, InstallConfirmLabels.isAffirmative(it))
    }
    listOf("Do you want to install an update to this existing application?", "Cancel", "OK", "Done",
        "Installer", "", null).forEach {
      assertFalse(it.toString(), InstallConfirmLabels.isAffirmative(it))
    }
  }
}
