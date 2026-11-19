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

class OtaRetryPolicyTest {
  private val minute = 60_000L

  @Test fun backoffGrowsThenCapsAtAnHour() {
    assertEquals(listOf(2, 5, 15, 30, 60, 60, 60).map { it * minute }, (1..7).map(OtaRetryPolicy::backoffMs))
  }

  @Test fun repeatedFailuresOfOneVersionBackOff() {
    val first = OtaRetryPolicy.afterFailure(null, 251, nowElapsedMs = 1_000)
    assertEquals(OtaConfirmRetry(251, 1, 1_000 + 2 * minute), first)
    val second = OtaRetryPolicy.afterFailure(first, 251, nowElapsedMs = 200_000)
    assertEquals(OtaConfirmRetry(251, 2, 200_000 + 5 * minute), second)
  }

  @Test fun aNewVersionStartsOver() {
    val old = OtaConfirmRetry(251, 4, 9_999_999)
    assertEquals(OtaConfirmRetry(252, 1, 5 + 2 * minute), OtaRetryPolicy.afterFailure(old, 252, 5))
  }

  @Test fun dueOnlyAfterTheBackoffForThatVersion() {
    val retry = OtaConfirmRetry(251, 1, 10_000)
    assertTrue(OtaRetryPolicy.due(null, 251, 0))
    assertFalse(OtaRetryPolicy.due(retry, 251, 9_999))
    assertTrue(OtaRetryPolicy.due(retry, 251, 10_000))
    assertTrue("a different published version isn't held back", OtaRetryPolicy.due(retry, 252, 0))
  }
}
