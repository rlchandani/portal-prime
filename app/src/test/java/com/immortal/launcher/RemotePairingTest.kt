/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** The pure PIN-window check + the brute-force lockout behind remote pairing. */
class RemotePairingTest {

  private val exp = 10_000L // PIN expiry instant

  @Test
  fun pinMatches_withinWindowAndEqual() {
    assertTrue(RemotePairing.pinMatches("123456", exp, "123456", now = 9_000L))
  }

  @Test
  fun pinMatches_trimsCandidate() {
    assertTrue(RemotePairing.pinMatches("123456", exp, "  123456 ", now = 9_000L))
  }

  @Test
  fun pinMatches_rejectsWrongPin() {
    assertFalse(RemotePairing.pinMatches("123456", exp, "123457", now = 9_000L))
  }

  @Test
  fun pinMatches_rejectsExpired() {
    assertFalse(RemotePairing.pinMatches("123456", exp, "123456", now = 10_000L)) // now == expiry
    assertFalse(RemotePairing.pinMatches("123456", exp, "123456", now = 99_999L))
  }

  @Test
  fun pinMatches_rejectsMissingPin() {
    assertFalse(RemotePairing.pinMatches(null, exp, "123456", now = 9_000L))
    assertFalse(RemotePairing.pinMatches("", exp, "", now = 9_000L)) // no PIN issued, blank guess
  }

  // --- brute-force lockout ----------------------------------------------------

  // The failure path of redeem() never touches prefs, so a bare mock suffices for the lockout tests.
  private val ctx: Context = mock(Context::class.java)

  @Before fun reset() = RemotePairing.resetForTest()

  /** Wrong guesses spread over distinct addresses, so only the global per-code cap applies. */
  private fun wrongFromMany(count: Int, now: Long, offset: Int = 0) = repeat(count) {
    assertEquals(RemotePairing.RedeemResult.BadPin, RemotePairing.redeem(ctx, "000000", "10.0.0.${it + offset}", now = now))
  }

  @Test
  fun redeem_burnsPinAfterMaxWrongAttemptsAndCoolsDown() {
    val pin = RemotePairing.newPin(now = 0L)
    wrongFromMany(RemotePairing.MAX_PIN_ATTEMPTS, now = 1_000L)
    // PIN is burned and every address waits, even one that never guessed.
    val limited = RemotePairing.redeem(ctx, pin, "10.0.9.9", now = 1_000L)
    assertTrue(limited is RemotePairing.RedeemResult.Limited && limited.global)
    assertNull("burned PIN must not display", RemotePairing.currentPin(now = 1_000L))
    assertEquals(PairingThrottle.COOLDOWN_BASE_MS, RemotePairing.pairingCooldownRemainingMs(now = 1_000L))
  }

  @Test
  fun redeem_wrongGuessesBelowCapDoNotBurnPin() {
    RemotePairing.newPin(now = 0L)
    wrongFromMany(RemotePairing.MAX_PIN_ATTEMPTS - 1, now = 1_000L)
    assertNotNull("PIN must survive below the attempt cap", RemotePairing.currentPin(now = 1_000L))
  }

  @Test
  fun redeem_oneNoisyAddressIsLimitedButCannotBurnTheCode() {
    val pin = RemotePairing.newPin(now = 0L)
    repeat(PairingThrottle.IP_FAILURES) { RemotePairing.redeem(ctx, "000000", "10.0.0.66", now = 1_000L) }
    val limited = RemotePairing.redeem(ctx, "000000", "10.0.0.66", now = 1_000L)
    assertTrue(limited is RemotePairing.RedeemResult.Limited && !limited.global)
    // The owner on another address still sees and can use the live code.
    assertEquals(pin, RemotePairing.currentPin(now = 1_000L))
  }

  @Test
  fun redeem_afterCooldownAFreshCodeWorksAgain() {
    RemotePairing.newPin(now = 0L)
    wrongFromMany(RemotePairing.MAX_PIN_ATTEMPTS, now = 1_000L)
    val later = 1_000L + PairingThrottle.COOLDOWN_BASE_MS
    RemotePairing.newPin(now = later)
    assertEquals(0L, RemotePairing.pairingCooldownRemainingMs(now = later))
    assertEquals(RemotePairing.RedeemResult.BadPin, RemotePairing.redeem(ctx, "000000", "10.0.9.9", now = later))
  }

  @Test
  fun newPin_resetsTheAttemptCounter() {
    RemotePairing.newPin(now = 0L)
    wrongFromMany(RemotePairing.MAX_PIN_ATTEMPTS - 1, now = 1_000L)
    // A fresh PIN clears the counter, so it can absorb a full new run of wrong guesses.
    val fresh = RemotePairing.newPin(now = 2_000L)
    wrongFromMany(RemotePairing.MAX_PIN_ATTEMPTS - 1, now = 3_000L, offset = 100)
    assertNotNull(RemotePairing.currentPin(now = 3_000L))
    assertTrue(fresh.length == 6)
  }
}
