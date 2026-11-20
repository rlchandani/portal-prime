/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/** Exercises the real singleton's display, invalidation, and change notifications. */
class RemotePairingDisplayTest {

  private val prefs = ScreensaverTestPrefs()
  private val context = mock(Context::class.java).also {
    `when`(it.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(prefs)
  }
  private var address = 0

  @Before fun reset() = RemotePairing.resetForTest()

  /** One guess from a fresh LAN address, so only the per-code cap applies; the token or null. */
  private fun guess(candidate: String, now: Long): String? =
      (RemotePairing.redeem(context, candidate, "10.9.${address / 250}.${address++ % 250}", now = now)
          as? RemotePairing.RedeemResult.Paired)?.token

  // Changing the first digit guarantees inequality, including when the issued PIN is all zeroes.
  private fun wrongCandidate(issued: String): String =
      (if (issued.first() == '0') "1" else "0") + issued.drop(1)

  // Boolean assertions avoid exposing random PINs or session tokens in failure messages.
  private fun assertCurrent(issued: String, now: Long) {
    assertTrue("issued PIN must remain current", issued == RemotePairing.currentPin(now))
  }

  private fun assertDisplay(issued: String, now: Long, expiresAt: Long) {
    val display = RemotePairing.displayPin(issued, now)
    assertNotNull("live issued PIN must display", display)
    assertTrue("display must contain the issued PIN", issued == display!!.value)
    assertEquals("display must retain the original expiry", expiresAt, display.expiresAt)
  }

  @Test
  fun newPin_emitsChangeAndMakesIssuedPinDisplayable() {
    val before = RemotePairing.pinChanges.value
    val issued = RemotePairing.newPin(now = 10_000L)

    assertTrue("issuing a PIN must advance pinChanges", RemotePairing.pinChanges.value > before)
    val afterIssue = RemotePairing.pinChanges.value
    assertCurrent(issued, now = 10_000L)
    assertDisplay(issued, now = 10_000L, expiresAt = 310_000L)
    assertEquals("reading the display must not emit a change", afterIssue, RemotePairing.pinChanges.value)
  }

  @Test
  fun regenerate_emitsSecondChangeEvenIfRandomDigitsRepeat() {
    val before = RemotePairing.pinChanges.value
    RemotePairing.newPin(now = 10_000L)
    val afterFirst = RemotePairing.pinChanges.value
    assertTrue("first generation must emit", afterFirst > before)

    val regenerated = RemotePairing.newPin(now = 20_000L)
    val afterSecond = RemotePairing.pinChanges.value
    assertTrue("explicit regeneration must emit again", afterSecond > afterFirst)
    assertCurrent(regenerated, now = 20_000L)
    assertDisplay(regenerated, now = 20_000L, expiresAt = 320_000L)
    assertEquals(afterSecond, RemotePairing.pinChanges.value)
  }

  @Test
  fun displayPin_hasExactFiveMinuteExpiryAndRejectsNullOrStaleCandidate() {
    val issued = RemotePairing.newPin(now = 10_000L)
    val afterIssue = RemotePairing.pinChanges.value

    assertDisplay(issued, now = 10_000L, expiresAt = 310_000L)
    assertDisplay(issued, now = 309_999L, expiresAt = 310_000L)
    assertNull("null candidate must not display", RemotePairing.displayPin(null, now = 11_000L))
    assertNull(
        "a candidate unequal to the current PIN must not display",
        RemotePairing.displayPin(wrongCandidate(issued), now = 11_000L),
    )
    assertCurrent(issued, now = 11_000L)
    assertEquals("display lookups must not mutate pairing state", afterIssue, RemotePairing.pinChanges.value)
  }

  @Test
  fun displayPin_rejectsExactExpiryWithoutMintingOrEmitting() {
    val issued = RemotePairing.newPin(now = 10_000L)
    val afterIssue = RemotePairing.pinChanges.value

    assertDisplay(issued, now = 309_999L, expiresAt = 310_000L)
    assertNull("expiry is exclusive", RemotePairing.displayPin(issued, now = 310_000L))
    assertNull("expired PIN must not remain current", RemotePairing.currentPin(now = 310_000L))
    assertNull(RemotePairing.displayPin(issued, now = 310_001L))
    assertNull(RemotePairing.displayPin(null, now = 310_001L))
    assertNull("expiry must not mint a replacement", RemotePairing.currentPin(now = 310_001L))
    assertNull("expired PIN must not redeem", guess(issued, now = 310_001L))
    assertEquals("expiry reads must not emit", afterIssue, RemotePairing.pinChanges.value)
  }

  @Test
  fun wrongAttemptsBelowCap_preserveDisplayAndDoNotEmit() {
    val issued = RemotePairing.newPin(now = 10_000L)
    val wrong = wrongCandidate(issued)
    val afterIssue = RemotePairing.pinChanges.value

    repeat(4) {
      assertNull("wrong candidate must not redeem", guess(wrong, now = 11_000L))
      assertCurrent(issued, now = 11_000L)
      assertDisplay(issued, now = 11_000L, expiresAt = 310_000L)
      assertEquals("a guess below the cap must not emit", afterIssue, RemotePairing.pinChanges.value)
    }
  }

  @Test
  fun maxWrongAttempts_invalidateAndEmitWithoutMintingReplacement() {
    val issued = RemotePairing.newPin(now = 10_000L)
    val wrong = wrongCandidate(issued)
    val afterIssue = RemotePairing.pinChanges.value
    assertEquals("the security cap remains five guesses", 5, RemotePairing.MAX_PIN_ATTEMPTS)

    repeat(RemotePairing.MAX_PIN_ATTEMPTS - 1) {
      assertNull(guess(wrong, now = 11_000L))
      assertCurrent(issued, now = 11_000L)
      assertEquals(afterIssue, RemotePairing.pinChanges.value)
    }
    assertNull(guess(wrong, now = 11_000L))
    val afterBurn = RemotePairing.pinChanges.value
    assertTrue("burning the PIN must emit a change", afterBurn > afterIssue)
    assertNull(RemotePairing.currentPin(now = 11_000L))
    assertNull(RemotePairing.displayPin(issued, now = 11_000L))
    assertNull(RemotePairing.displayPin(null, now = 11_000L))
    assertNull("correct PIN must fail after burn", guess(issued, now = 11_000L))
    assertNull("burn must not mint a replacement", RemotePairing.currentPin(now = 12_000L))
    assertEquals("reads and attempts after burn must not emit again", afterBurn, RemotePairing.pinChanges.value)
  }

  @Test
  fun successfulRedeem_invalidatesDisplayAndEmitsOnce() {
    val issued = RemotePairing.newPin(now = 10_000L)
    val afterIssue = RemotePairing.pinChanges.value

    val session = guess(issued, now = 11_000L)
    assertNotNull("correct live PIN must redeem", session)
    assertTrue("redeemed session must be persisted and valid", RemotePairing.isValidSession(context, session!!, now = 11_000L))
    val afterRedeem = RemotePairing.pinChanges.value
    assertTrue("successful redemption must emit", afterRedeem > afterIssue)
    assertNull(RemotePairing.currentPin(now = 11_000L))
    assertNull(RemotePairing.displayPin(issued, now = 11_000L))
    assertNull(RemotePairing.displayPin(null, now = 11_000L))
    assertNull("PIN must be single use", guess(issued, now = 11_000L))
    assertEquals("reusing a redeemed PIN must not emit", afterRedeem, RemotePairing.pinChanges.value)
  }

  @Test
  fun explicitNewPinAfterBurn_resetsAttemptBudgetAndCanRedeem() {
    val issued = RemotePairing.newPin(now = 10_000L)
    repeat(RemotePairing.MAX_PIN_ATTEMPTS) {
      assertNull(guess(wrongCandidate(issued), now = 11_000L))
    }
    assertNull("PIN must be burned before regeneration", RemotePairing.currentPin(now = 11_000L))
    // The burn starts a short cooldown for every phone; after it, a fresh code works normally.
    assertEquals(PairingThrottle.COOLDOWN_BASE_MS, RemotePairing.pairingCooldownRemainingMs(now = 11_000L))
    val afterBurn = RemotePairing.pinChanges.value

    val fresh = RemotePairing.newPin(now = 11_000L + PairingThrottle.COOLDOWN_BASE_MS)
    val afterRegeneration = RemotePairing.pinChanges.value
    assertTrue("explicit generation after burn must emit", afterRegeneration > afterBurn)
    repeat(4) {
      assertNull(guess(wrongCandidate(fresh), now = 42_000L))
      assertDisplay(fresh, now = 42_000L, expiresAt = 341_000L)
      assertEquals("fresh PIN has a reset attempt budget", afterRegeneration, RemotePairing.pinChanges.value)
    }
    val session = guess(fresh, now = 42_000L)
    assertNotNull("regenerated PIN must still redeem after four wrong guesses", session)
    assertTrue(RemotePairing.isValidSession(context, session!!, now = 42_000L))
    assertTrue("redemption of regenerated PIN must emit", RemotePairing.pinChanges.value > afterRegeneration)
    assertNull(RemotePairing.currentPin(now = 42_000L))
    assertNull(RemotePairing.displayPin(fresh, now = 42_000L))
    assertNull(guess(fresh, now = 42_000L))
  }
}
