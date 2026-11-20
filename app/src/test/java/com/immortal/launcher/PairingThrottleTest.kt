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

/** Pure PIN-guess rate limit (Finding 4: one LAN client could burn every code shown). */
class PairingThrottleTest {
  private val allowed = PairingThrottle.Decision.Allowed

  private fun PairingThrottle.fail(address: String, now: Long, times: Int = 1): PairingThrottle.Failure {
    var state = PairingThrottle.Failure(this, false)
    repeat(times) { state = state.throttle.onFailure(address, now) }
    return state
  }

  @Test fun anAddressWaitsAfterItsFailuresAndTheWaitDoubles() {
    var t = PairingThrottle().fail("a", 0L, PairingThrottle.IP_FAILURES).throttle
    assertEquals(PairingThrottle.Decision.Limited(30_000L, global = false), t.check("a", 0L))
    assertEquals(allowed, t.check("b", 0L))
    assertEquals(allowed, t.check("a", 30_000L))
    t = t.onNewPin().fail("a", 30_000L, PairingThrottle.IP_FAILURES).throttle
    assertEquals(PairingThrottle.Decision.Limited(60_000L, global = false), t.check("a", 30_000L))
  }

  @Test fun addressWaitIsCapped() {
    var t = PairingThrottle()
    var now = 0L
    repeat(12) {
      t = t.onNewPin().fail("a", now, PairingThrottle.IP_FAILURES).throttle
      now += PairingThrottle.IP_BLOCK_MAX_MS
    }
    val limited = t.check("a", now - PairingThrottle.IP_BLOCK_MAX_MS) as PairingThrottle.Decision.Limited
    assertEquals(PairingThrottle.IP_BLOCK_MAX_MS, limited.retryAfterMs)
  }

  @Test fun fiveWrongGuessesFromAnyMixBurnTheCodeAndCoolEveryoneDown() {
    var t = PairingThrottle()
    var burned = false
    for (i in 0 until PairingThrottle.PIN_FAILURES) {
      val f = t.onFailure("10.0.0.$i", 1_000L)
      t = f.throttle
      burned = f.burned
      if (i < PairingThrottle.PIN_FAILURES - 1) assertFalse(burned)
    }
    assertTrue(burned)
    assertEquals(PairingThrottle.Decision.Limited(30_000L, global = true), t.check("owner", 1_000L))
    assertEquals(30_000L, t.cooldownRemainingMs(1_000L))
    assertEquals(0L, t.cooldownRemainingMs(31_000L))
  }

  @Test fun repeatedBurnsEscalateButNeverPastFiveMinutes() {
    var t = PairingThrottle()
    var now = 0L
    val waits = ArrayList<Long>()
    repeat(8) {
      t = t.onNewPin()
      repeat(PairingThrottle.PIN_FAILURES) { i -> t = t.onFailure("10.0.$it.$i", now).throttle }
      waits += t.cooldownRemainingMs(now)
      now += t.cooldownRemainingMs(now)
    }
    assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L, 300_000L), waits)
  }

  @Test fun successOrLongQuietForgetsTheEscalation() {
    val burned = PairingThrottle().let { start ->
      var t = start
      repeat(PairingThrottle.PIN_FAILURES) { i -> t = t.onFailure("10.0.0.$i", 0L).throttle }
      t
    }
    assertEquals(PairingThrottle(), burned.onSuccess())
    var t = burned.onNewPin()
    val quiet = PairingThrottle.FORGET_MS + 1
    repeat(PairingThrottle.PIN_FAILURES) { i -> t = t.onFailure("10.0.1.$i", quiet).throttle }
    assertEquals("level restarted after quiet", PairingThrottle.COOLDOWN_BASE_MS, t.cooldownRemainingMs(quiet))
  }

  @Test fun newCodeKeepsEarnedWaitsButResetsItsGuessBudget() {
    val t = PairingThrottle().fail("a", 0L, PairingThrottle.IP_FAILURES).throttle.onNewPin()
    assertEquals(0, t.pinFailures)
    assertTrue(t.check("a", 1L) is PairingThrottle.Decision.Limited)
  }

  @Test fun trackedAddressesStayBounded() {
    var t = PairingThrottle()
    repeat(500) { t = t.onNewPin().onFailure("10.1.${it / 256}.${it % 256}", it.toLong()).throttle }
    assertEquals(PairingThrottle.MAX_ADDRESSES, t.addresses.size)
    assertTrue("most recent kept", "10.1.1.243" in t.addresses)
  }

  @Test fun waitSecondsRoundUp() {
    assertEquals(1L, PairingThrottle.seconds(1L))
    assertEquals(30L, PairingThrottle.seconds(29_001L))
    assertEquals(1L, PairingThrottle.seconds(0L))
  }

  @Test fun householdGrantPolicyIsBoundToOneSessionAndTenMinutes() {
    val grant = HouseholdGrant("abc", 1_000L)
    assertTrue(HouseholdGrantPolicy.allows(grant, "abc", 1_000L + HouseholdGrantPolicy.WINDOW_MS))
    assertFalse(HouseholdGrantPolicy.allows(grant, "abc", 1_001L + HouseholdGrantPolicy.WINDOW_MS))
    assertFalse(HouseholdGrantPolicy.allows(grant, "other", 2_000L))
    assertFalse(HouseholdGrantPolicy.allows(grant, "abc", 999L))
    assertFalse(HouseholdGrantPolicy.allows(null, "abc", 2_000L))
  }
}
