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
 * paired phone keeps working) but expire after [RemoteSessionPolicy.IDLE_EXPIRY_MS] unused;
 * Settings › Phone Remote lists them ([sessions]) and removes one ([revokeSession]). Wrong
 * guesses are rate limited per source address and globally ([PairingThrottle]). PINs live in
 * memory only and expire quickly. The remote feature is opt-in per device ([isEnabled]) in the
 * same spirit as the agent itself.
 */
object RemotePairing {
  private const val PREFS = "remote_pairing"
  private const val KEY_ENABLED = "enabled"
  /** Newline-separated tokens: the format every older build reads, kept so a downgrade still works. */
  private const val KEY_SESSIONS = "sessions"
  /** JSON id -> [RemoteSessionRecord] (created, last seen, label); never holds a token. */
  private const val KEY_RECORDS = "session_records"
  private const val KEY_TEST_SESSIONS = "test_sessions"
  private const val AUDIT_TAG = "ImmortalRemote"
  internal const val PIN_TTL_MS = 5 * 60_000L
  /** Wrong guesses (from any address) that burn the PIN; see [PairingThrottle] for the waits. */
  internal const val MAX_PIN_ATTEMPTS = PairingThrottle.PIN_FAILURES
  const val HOUSEHOLD_WINDOW_MS = HouseholdGrantPolicy.WINDOW_MS

  /** The result of one `/remote/pair` attempt. */
  sealed interface RedeemResult {
    data class Paired(val token: String) : RedeemResult
    data object BadPin : RedeemResult
    data class Limited(val retryAfterMs: Long, val global: Boolean) : RedeemResult
  }

  @Volatile private var pin: String? = null
  @Volatile private var pinExpiresAt = 0L
  @Volatile private var pinIsTest = false
  @Volatile private var throttle = PairingThrottle()
  /**
   * The household key may be shared with or set by a phone only by the session the latest
   * in-person PIN pairing created, within 10 minutes, once. Session tokens travel in cleartext on
   * the LAN, so a sniffed (or any other) token must not be enough to read the key that signs calls.
   * In memory only: after a restart, pair again to re-provision.
   */
  @Volatile private var householdGrant: HouseholdGrant? = null
  @Volatile private var cachedRecordsRaw: String? = null
  @Volatile private var cachedRecords: Map<String, RemoteSessionRecord> = emptyMap()

  private val mutablePinChanges = MutableStateFlow(0L)
  internal val pinChanges = mutablePinChanges.asStateFlow()

  /** A card must never display a replaced, consumed, locked-out or expired code. */
  @Synchronized
  internal fun displayPin(candidate: String?, now: Long = System.currentTimeMillis()): PairingDisplayPin? =
      currentPin(now)?.takeIf { it == candidate }?.let { PairingDisplayPin(it, pinExpiresAt) }

  /** How long every phone must wait after too many wrong codes (0 when pairing is open). */
  @Synchronized
  internal fun pairingCooldownRemainingMs(now: Long = System.currentTimeMillis()): Long =
      throttle.cooldownRemainingMs(now)

  private fun publishPinChange() { mutablePinChanges.value += 1L }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // --- opt-in -----------------------------------------------------------------

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

  fun setEnabled(c: Context, on: Boolean) {
    prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
  }

  // --- PIN (in-memory, short-lived) -------------------------------------------

  /**
   * Generate, store, and return a fresh 6-digit PIN to show on the Portal. [forTest] (operator
   * verification only) marks the session this code mints as a test session, so
   * [revokeTestSessions] cleans it up like any other.
   */
  @Synchronized
  fun newPin(now: Long = System.currentTimeMillis(), forTest: Boolean = false): String {
    val fresh = randomDigits(6)
    pin = fresh
    pinIsTest = forTest
    pinExpiresAt = now + PIN_TTL_MS
    throttle = throttle.onNewPin() // a fresh code gets its own guess budget
    publishPinChange()
    return fresh
  }

  /** The current PIN if still valid, else null (for the on-Portal display). */
  @Synchronized
  fun currentPin(now: Long = System.currentTimeMillis()): String? =
      if (pinMatches(pin, pinExpiresAt, pin ?: "", now)) pin else null

  /**
   * Redeem a candidate PIN from [address] (the phone's LAN address). A limited address or a
   * cooling-down Portal is refused before the guess is even compared. On a valid, unexpired match:
   * clear the PIN (single use), mint + persist a session labelled [label], grant that session alone
   * the household-key window, and return its token.
   */
  @Synchronized
  fun redeem(
      c: Context,
      candidate: String,
      address: String = "",
      label: String? = null,
      now: Long = System.currentTimeMillis(),
  ): RedeemResult {
    (throttle.check(address, now) as? PairingThrottle.Decision.Limited)?.let {
      return RedeemResult.Limited(it.retryAfterMs, it.global)
    }
    if (!pinMatches(pin, pinExpiresAt, candidate, now)) {
      // Count wrong guesses only while a live PIN exists (an expired/absent PIN can't be redeemed).
      if (pin != null && now < pinExpiresAt) {
        val failure = throttle.onFailure(address, now)
        throttle = failure.throttle
        if (failure.burned) {
          pin = null
          pinExpiresAt = 0L
          audit("pairing code burned after too many wrong guesses; cooling down")
          publishPinChange()
        }
      }
      return RedeemResult.BadPin
    }
    pin = null
    pinExpiresAt = 0L
    throttle = throttle.onSuccess()
    val token = randomToken()
    val id = RemoteSessionPolicy.idOf(token)
    val current = loadReconciled(c, now)
    store(c, current.tokens + token,
        current.records + (id to RemoteSessionRecord(id, now, now, RemoteSessionPolicy.cleanLabel(label), test = pinIsTest)))
    if (pinIsTest) {
      val p = prefs(c)
      p.edit().putStringSet(KEY_TEST_SESSIONS, p.getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty() + token).apply()
      pinIsTest = false
    }
    householdGrant = HouseholdGrant(id, now)
    publishPinChange()
    return RedeemResult.Paired(token)
  }

  /** Only the session the latest PIN pairing created, within the window, and not yet used. */
  @Synchronized
  fun householdWindowOpen(caller: String, now: Long = System.currentTimeMillis()): Boolean =
      HouseholdGrantPolicy.allows(householdGrant, caller, now)

  /**
   * Runs [action] only while [caller] holds the open grant, atomically, and spends the grant when
   * [succeeded] says the action worked (so two racing requests can't both use it). Null when refused.
   */
  @Synchronized
  internal fun <T> withHouseholdGrant(
      caller: String,
      now: Long = System.currentTimeMillis(),
      succeeded: (T) -> Boolean,
      action: () -> T,
  ): T? {
    if (!HouseholdGrantPolicy.allows(householdGrant, caller, now)) return null
    return action().also { if (succeeded(it)) householdGrant = null }
  }

  // --- sessions ---------------------------------------------------------------

  /**
   * True for a stored, unexpired session. A valid use refreshes its last-seen time (at most one
   * write an hour); an expired one is removed here, so the phone gets 401 and pairs again.
   */
  @Synchronized
  fun isValidSession(c: Context, token: String, now: Long = System.currentTimeMillis()): Boolean {
    if (token.isBlank()) return false
    val current = loadReconciled(c, now)
    val match = current.tokens.firstOrNull { FleetRoutes.constantTimeEquals(it, token) } ?: return false
    val id = RemoteSessionPolicy.idOf(match)
    val record = current.records[id] ?: return false
    RemoteSessionPolicy.touched(record, now)?.let { bookkeeping { store(c, current.tokens, current.records + (id to it)) } }
    return true
  }

  /** Paired phones for Settings, most recently used first. */
  @Synchronized
  internal fun sessions(c: Context, now: Long = System.currentTimeMillis()): List<RemoteSessionRecord> =
      loadReconciled(c, now).records.values.sortedByDescending { it.lastSeenMs }

  /** Removes one paired phone by its record id. Returns whether it existed. */
  @Synchronized
  fun revokeSession(c: Context, id: String, now: Long = System.currentTimeMillis()): Boolean {
    val current = loadReconciled(c, now)
    val kept = current.tokens.filterNot { RemoteSessionPolicy.idOf(it) == id }
    if (kept.size == current.tokens.size) return false
    store(c, kept, current.records - id)
    if (householdGrant?.sessionId == id) householdGrant = null
    audit("removed a paired phone (${kept.size} left)")
    return true
  }

  /**
   * Operator verification only (Fleet `phone_test_session`, fleet token): a session marked as a
   * test so [revokeTestSessions] removes it alone, leaving the household's real phones paired.
   * [householdGrant] simulates a fresh PIN pairing for that session (the fleet token already has
   * full control of this Portal, so this grants nothing new).
   */
  @Synchronized
  fun mintTestSession(c: Context, householdGrant: Boolean = false, now: Long = System.currentTimeMillis()): String {
    val token = randomToken()
    val id = RemoteSessionPolicy.idOf(token)
    val p = prefs(c)
    val tests = p.getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty() + token
    val current = loadReconciled(c, now)
    store(c, current.tokens + token,
        current.records + (id to RemoteSessionRecord(id, now, now, RemoteSessionPolicy.TEST_LABEL, test = true)))
    p.edit().putStringSet(KEY_TEST_SESSIONS, tests).apply()
    if (householdGrant) this.householdGrant = HouseholdGrant(id, now)
    audit("operator minted a test phone session (${tests.size} test sessions)")
    return token
  }

  /**
   * Debug clock for operator verification: moves every test session's last use [ms] into the past
   * (real pairings are untouched), so expiry can be checked without waiting 180 days.
   */
  @Synchronized
  fun ageTestSessions(c: Context, ms: Long, now: Long = System.currentTimeMillis()): Int {
    val tests = prefs(c).getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty().map(RemoteSessionPolicy::idOf).toSet()
    val current = loadReconciled(c, now)
    val aged = current.records.mapValues { (id, r) -> if (id in tests) r.copy(lastSeenMs = r.lastSeenMs - ms) else r }
    store(c, current.tokens, aged)
    return tests.count { it in current.records }
  }

  /** Removes every test session; returns how many. Real pairings are untouched. */
  @Synchronized
  fun revokeTestSessions(c: Context, now: Long = System.currentTimeMillis()): Int {
    val p = prefs(c)
    val tests = p.getStringSet(KEY_TEST_SESSIONS, emptySet()).orEmpty()
    val current = loadReconciled(c, now)
    val testIds = tests.map(RemoteSessionPolicy::idOf).toSet()
    store(c, current.tokens - tests, current.records - testIds)
    p.edit().remove(KEY_TEST_SESSIONS).apply()
    if (householdGrant?.sessionId?.let { it in testIds } == true) householdGrant = null
    audit("operator revoked ${tests.size} test phone sessions")
    return tests.size
  }

  @Synchronized
  fun revokeAll(c: Context) {
    prefs(c).edit().remove(KEY_SESSIONS).remove(KEY_RECORDS).apply()
    householdGrant = null
  }

  /** Stored tokens joined with their records; expired sessions are dropped and persisted. */
  private fun loadReconciled(c: Context, now: Long): ReconciledSessions {
    val p = prefs(c)
    val tokens = p.getString(KEY_SESSIONS, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
    val raw = p.getString(KEY_RECORDS, null)
    val records = if (raw == cachedRecordsRaw) cachedRecords else RemoteSessionPolicy.decode(raw)
    val result = RemoteSessionPolicy.reconcile(tokens, records, now)
    if (result.changed) bookkeeping { store(c, result.tokens, result.records) }
    else { cachedRecordsRaw = raw; cachedRecords = records }
    return result
  }

  /**
   * Upgrade stamping, expiry removal and last-seen refreshes are bookkeeping: a failed write is
   * logged and retried on the next request, and never changes whether a request is authorised.
   */
  private inline fun bookkeeping(write: () -> Unit) {
    runCatching(write).onFailure { audit("session bookkeeping write failed: ${it.javaClass.simpleName}") }
  }

  private fun store(c: Context, tokens: List<String>, records: Map<String, RemoteSessionRecord>) {
    val raw = RemoteSessionPolicy.encode(records)
    prefs(c).edit().putString(KEY_SESSIONS, tokens.joinToString("\n")).putString(KEY_RECORDS, raw).apply()
    cachedRecordsRaw = raw
    cachedRecords = records
  }

  /** Audit line; logging is unavailable in JVM tests, where it is skipped. */
  private fun audit(message: String) {
    runCatching { android.util.Log.i(AUDIT_TAG, message) }
  }

  /** Test seam: forget in-memory PIN, throttle and grant state between cases. */
  @Synchronized
  internal fun resetForTest() {
    pin = null
    pinIsTest = false
    pinExpiresAt = 0L
    throttle = PairingThrottle()
    householdGrant = null
    cachedRecordsRaw = null
    cachedRecords = emptyMap()
  }

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
