/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable live code snapshot used only by the on-Portal pairing display. */
internal data class PairingDisplayPin(val value: String, val expiresAt: Long)

/**
 * Pairing + session auth for the phone remote ([RemoteRoutes]). The fleet bearer token
 * ([FleetConfig]) is a 32-char secret meant for the laptop CLI — miserable to type on a
 * phone — so the remote uses a **PIN/QR pairing** instead:
 *
 *  1. The Portal shows a short-lived 6-digit PIN ([RemotePairActivity], via [newPin]).
 *  2. The phone opens `/remote/ui` and submits the PIN to `/remote/pair`.
 *  3. On a match we mint a long-lived **session token**, persist it, and hand it back;
 *     the phone stores it and sends it as `Authorization: Bearer …` on every call.
 *
 * The PIN is the security gate: redeeming it requires physically reading the Portal's
 * screen, so only someone in the room can pair. Sessions persist across reboots (so a
 * paired phone keeps working), and [revokeAll] drops them all. PINs live in memory only
 * and expire quickly. The remote feature is opt-in per device ([isEnabled]) in the same
 * spirit as the agent itself.
 */
object RemotePairing {
  private const val PREFS = "remote_pairing"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_SESSIONS = "sessions" // newline-separated session tokens
  private const val KEY_TEST_SESSIONS = "test_sessions"
  private const val AUDIT_TAG = "ImmortalRemote"
  internal const val PIN_TTL_MS = 5 * 60_000L
  /** Wrong guesses that burn the PIN. A 6-digit PIN is 10^6, but with no lockout the whole
   *  5-min window is brute-forceable on a fast LAN; a hard cap makes online guessing infeasible. */
  internal const val MAX_PIN_ATTEMPTS = 5

  @Volatile private var pin: String? = null
  @Volatile private var pinExpiresAt = 0L
  @Volatile private var pinAttempts = 0

  private val mutablePinChanges = MutableStateFlow(0L)
  internal val pinChanges = mutablePinChanges.asStateFlow()

  /** A card must never display a replaced, consumed, locked-out or expired code. */
  @Synchronized
  internal fun displayPin(candidate: String?, now: Long = System.currentTimeMillis()): PairingDisplayPin? =
      currentPin(now)?.takeIf { it == candidate }?.let { PairingDisplayPin(it, pinExpiresAt) }

  private fun publishPinChange() { mutablePinChanges.value += 1L }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // --- opt-in -----------------------------------------------------------------

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

  fun setEnabled(c: Context, on: Boolean) {
    prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
  }

  // --- PIN (in-memory, short-lived) -------------------------------------------

  /** Generate, store, and return a fresh 6-digit PIN to show on the Portal. */
  @Synchronized
  fun newPin(now: Long = System.currentTimeMillis()): String {
    val fresh = randomDigits(6)
    pin = fresh
    pinExpiresAt = now + PIN_TTL_MS
    pinAttempts = 0 // fresh PIN → reset the lockout counter
    publishPinChange()
    return fresh
  }

  /** The current PIN if still valid, else null (for the on-Portal display). */
  @Synchronized
  fun currentPin(now: Long = System.currentTimeMillis()): String? =
      if (pinMatches(pin, pinExpiresAt, pin ?: "", now)) pin else null

  /**
   * Redeem a candidate PIN. On a valid, unexpired match: clear the PIN (single use),
   * mint + persist a session token, and return it. Otherwise return null.
   */
  @Synchronized
  fun redeem(c: Context, candidate: String, now: Long = System.currentTimeMillis()): String? {
    if (!pinMatches(pin, pinExpiresAt, candidate, now)) {
      // Count wrong guesses only while a live PIN exists (an expired/absent PIN already can't be
      // redeemed). After too many, burn the PIN so the rest of the window is dead — the user must
      // request a fresh one, defeating online brute force.
      if (pin != null && now < pinExpiresAt) {
        pinAttempts++
        if (pinAttempts >= MAX_PIN_ATTEMPTS) {
          pin = null
          pinExpiresAt = 0L
          publishPinChange()
        }
      }
      return null
    }
    pin = null
    pinExpiresAt = 0L
    pinAttempts = 0
    lastRedeemMs = now
    val token = randomToken()
    val sessions = loadSessions(c).toMutableSet().apply { add(token) }
    prefs(c).edit().putString(KEY_SESSIONS, sessions.joinToString("\n")).apply()
    publishPinChange()
    return token
  }

  /**
   * The household key may be shared with or set by a phone only shortly after someone paired in
   * person (PIN on this screen). Session tokens travel in cleartext on the LAN, so a sniffed token
   * must not be enough to read the key that signs calls (Calling 2a review). In memory only: after
   * a restart, pair again to re-provision.
   */
  @Volatile private var lastRedeemMs = 0L
  const val HOUSEHOLD_WINDOW_MS = 10 * 60_000L

  fun householdWindowOpen(now: Long = System.currentTimeMillis()): Boolean =
      lastRedeemMs > 0 && now - lastRedeemMs in 0..HOUSEHOLD_WINDOW_MS

  // --- sessions ---------------------------------------------------------------

  fun isValidSession(c: Context, token: String): Boolean =
      token.isNotBlank() && loadSessions(c).any { FleetRoutes.constantTimeEquals(it, token) }

  /**
   * Operator verification only (Fleet `phone_test_session`, fleet token): a session marked as a
   * test so [revokeTestSessions] removes it alone, leaving the household's real phones paired.
   */
  @Synchronized
  fun mintTestSession(c: Context): String {
    val token = randomToken()
    val p = prefs(c)
    val tests = p.getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty() + token
    p.edit().putString(KEY_SESSIONS, (loadSessions(c) + token).joinToString("\n")).putStringSet(KEY_TEST_SESSIONS, tests).apply()
    android.util.Log.i(AUDIT_TAG, "operator minted a test phone session (${tests.size} test sessions)")
    return token
  }

  /** Removes every test session; returns how many. Real pairings are untouched. */
  @Synchronized
  fun revokeTestSessions(c: Context): Int {
    val p = prefs(c)
    val tests = p.getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty()
    val kept = loadSessions(c) - tests
    p.edit().putString(KEY_SESSIONS, kept.joinToString("\n")).remove(KEY_TEST_SESSIONS).apply()
    android.util.Log.i(AUDIT_TAG, "operator revoked ${tests.size} test phone sessions")
    return tests.size
  }

  fun revokeAll(c: Context) {
    prefs(c).edit().remove(KEY_SESSIONS).apply()
  }

  private fun loadSessions(c: Context): Set<String> =
      prefs(c).getString(KEY_SESSIONS, null)?.split("\n")?.filter { it.isNotBlank() }?.toSet()
          ?: emptySet()

  // --- pure helpers (unit-tested) ---------------------------------------------

  /**
   * Whether [candidate] matches the [stored] PIN and the window hasn't expired. A null
   * or blank stored PIN never matches (so "no PIN issued" can't be redeemed with "").
   */
  internal fun pinMatches(stored: String?, expiresAt: Long, candidate: String, now: Long): Boolean {
    if (stored.isNullOrBlank()) return false
    if (now >= expiresAt) return false
    return FleetRoutes.constantTimeEquals(stored, candidate.trim())
  }

  private val rng = SecureRandom()

  private fun randomDigits(n: Int): String =
      buildString { repeat(n) { append(rng.nextInt(10)) } }

  private fun randomToken(): String {
    val bytes = ByteArray(16)
    rng.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
