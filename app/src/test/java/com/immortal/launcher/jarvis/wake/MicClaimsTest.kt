/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hearthpane's own mic users: listening steps aside while any claim is open, and only the edges notify. */
class MicClaimsTest {
  private var changes = 0

  @After fun reset() { MicClaims.onChange = {} }

  @Test fun anOpenClaimHoldsTheMicUntilEveryClaimCloses() {
    MicClaims.onChange = { changes++ }
    val memo = MicClaims.claim()
    val intercom = MicClaims.claim()
    assertTrue(MicClaims.active)
    memo.close()
    assertTrue(MicClaims.active)
    intercom.close()
    assertFalse(MicClaims.active)
    assertEquals("free → claimed, claimed → free", 2, changes)
  }

  @Test fun closingTwiceGivesTheMicBackOnce() {
    val other = MicClaims.claim()
    val claim = MicClaims.claim()
    claim.close()
    claim.close()
    assertTrue("the second close must not release someone else's claim", MicClaims.active)
    other.close()
    assertFalse(MicClaims.active)
  }
}
