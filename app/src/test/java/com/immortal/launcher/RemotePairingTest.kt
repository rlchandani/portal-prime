/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test
  fun redeem_burnsPinAfterMaxWrongAttempts() {
    val pin = RemotePairing.newPin(now = 0L)
    // MAX_PIN_ATTEMPTS wrong guesses, all within the window.
    repeat(RemotePairing.MAX_PIN_ATTEMPTS) {
      assertNull(RemotePairing.redeem(ctx, "000000", now = 1_000L))
    }
    // PIN is now burned: even the CORRECT pin no longer redeems, and it's no longer displayed.
    assertNull("correct PIN must fail after lockout", RemotePairing.redeem(ctx, pin, now = 1_000L))
    assertNull("burned PIN must not display", RemotePairing.currentPin(now = 1_000L))
  }

  @Test
  fun redeem_wrongGuessesBelowCapDoNotBurnPin() {
    val pin = RemotePairing.newPin(now = 0L)
    repeat(RemotePairing.MAX_PIN_ATTEMPTS - 1) {
      assertNull(RemotePairing.redeem(ctx, "000000", now = 1_000L))
    }
    // Still live below the cap — the PIN is still displayable.
    assertNotNull("PIN must survive below the attempt cap", RemotePairing.currentPin(now = 1_000L))
  }

  @Test
  fun newPin_resetsTheAttemptCounter() {
    RemotePairing.newPin(now = 0L)
    repeat(RemotePairing.MAX_PIN_ATTEMPTS - 1) { RemotePairing.redeem(ctx, "000000", now = 1_000L) }
    // A fresh PIN clears the counter, so it can absorb a full new run of wrong guesses.
    val fresh = RemotePairing.newPin(now = 2_000L)
    repeat(RemotePairing.MAX_PIN_ATTEMPTS - 1) {
      assertNull(RemotePairing.redeem(ctx, "000000", now = 3_000L))
    }
    assertNotNull(RemotePairing.currentPin(now = 3_000L))
    assertTrue(fresh.length == 6)
  }
}
