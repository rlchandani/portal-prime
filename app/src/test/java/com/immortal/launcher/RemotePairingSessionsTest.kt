/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Stored phone sessions end to end over in-memory prefs: expiry, refresh on use, the upgrade from
 * a token-only store, downgrade compatibility, Settings removal, and the household-key grant
 * bound to the PIN-paired session (Findings 2 and 3).
 */
class RemotePairingSessionsTest {
  private val day = 24L * 60 * 60 * 1000
  private val pairing = ScreensaverTestPrefs()
  private val household = ScreensaverTestPrefs()
  private val ctx: Context = mock(Context::class.java).also {
    `when`(it.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
    `when`(it.getSharedPreferences("immortal_household", Context.MODE_PRIVATE)).thenReturn(household)
  }

  @Before fun reset() = RemotePairing.resetForTest()

  private fun pair(now: Long, address: String = "192.168.1.20", label: String? = "Pixel 8"): String {
    val pin = RemotePairing.newPin(now)
    val result = RemotePairing.redeem(ctx, pin, address, label, now)
    return (result as RemotePairing.RedeemResult.Paired).token
  }

  @Test fun pairedSessionIsValidAndListedWithItsLabel() {
    val token = pair(now = 1_000L)
    assertTrue(RemotePairing.isValidSession(ctx, token, now = 2_000L))
    assertFalse(RemotePairing.isValidSession(ctx, token + "x", now = 2_000L))
    assertFalse(RemotePairing.isValidSession(ctx, "", now = 2_000L))
    val listed = RemotePairing.sessions(ctx, now = 2_000L).single()
    assertEquals("Pixel 8", listed.label)
    assertEquals(RemoteSessionPolicy.idOf(token), listed.id)
  }

  @Test fun unusedSessionExpiresAndIsRemovedWhileAUsedOneLives() {
    val idle = pair(now = 0L)
    val busy = pair(now = 0L, label = "Tablet")
    for (d in 1..200L step 7) assertTrue(RemotePairing.isValidSession(ctx, busy, now = d * day))
    assertFalse(RemotePairing.isValidSession(ctx, idle, now = 200 * day))
    assertEquals(listOf("Tablet"), RemotePairing.sessions(ctx, now = 200 * day).map { it.label })
    // The token list older builds read no longer carries the expired token either.
    assertFalse(pairing.getString("sessions", "")!!.contains(idle))
  }

  @Test fun upgradeFromATokenOnlyStoreKeepsEveryPairingAndStartsTheClock() {
    pairing.edit().putString("sessions", "legacy-one\nlegacy-two").putBoolean("enabled", true).apply()
    val upgradeAt = 500 * day
    assertTrue(RemotePairing.isValidSession(ctx, "legacy-one", now = upgradeAt))
    val records = RemotePairing.sessions(ctx, now = upgradeAt)
    assertEquals(2, records.size)
    records.forEach { assertEquals(upgradeAt, it.lastSeenMs) }
    // 180 idle days after the upgrade (not after the original pairing), legacy-two expires.
    assertTrue(RemotePairing.isValidSession(ctx, "legacy-two", now = upgradeAt + 179 * day))
    assertTrue(RemotePairing.isValidSession(ctx, "legacy-one", now = upgradeAt + 179 * day))
  }

  @Test fun storageStaysReadableByAnOlderBuildAfterUse() {
    val token = pair(now = 0L)
    RemotePairing.isValidSession(ctx, token, now = 2 * 60 * 60_000L)
    // 1.271 reads newline-separated tokens from "sessions" and ignores the new records key.
    assertEquals(listOf(token), pairing.getString("sessions", null)!!.split("\n"))
    val records = JSONObject(pairing.getString("session_records", null)!!)
    assertFalse("records never hold the token", records.toString().contains(token))
  }

  @Test fun removingOnePhoneLeavesTheOthers() {
    val a = pair(now = 0L, label = "A")
    val b = pair(now = 0L, label = "B")
    assertTrue(RemotePairing.revokeSession(ctx, RemoteSessionPolicy.idOf(a), now = 1L))
    assertFalse(RemotePairing.revokeSession(ctx, RemoteSessionPolicy.idOf(a), now = 1L))
    assertFalse(RemotePairing.isValidSession(ctx, a, now = 2L))
    assertTrue(RemotePairing.isValidSession(ctx, b, now = 2L))
  }

  @Test fun testSessionsAgeAndRevokeWithoutTouchingRealPairings() {
    val real = pair(now = 0L)
    val test = RemotePairing.mintTestSession(ctx, now = 0L)
    assertEquals(1, RemotePairing.ageTestSessions(ctx, 181 * day, now = 0L))
    assertFalse(RemotePairing.isValidSession(ctx, test, now = 1L))
    assertTrue(RemotePairing.isValidSession(ctx, real, now = 1L))
    RemotePairing.mintTestSession(ctx, now = 2L)
    assertEquals(2, RemotePairing.revokeTestSessions(ctx, now = 3L))
    assertEquals(listOf(RemoteSessionPolicy.idOf(real)), RemotePairing.sessions(ctx, now = 3L).map { it.id })
  }

  // --- household-key window (Finding 2) ----------------------------------------------------

  private val route = RemoteFeatureRoutes.householdKeyRoute
  private fun post(token: String, body: JSONObject) =
      route.handleFrom(ctx, "POST", body, RemoteFeatureRoutes.callerKey(token))

  @Test fun onlyTheJustPairedSessionMayReadTheKeyAndOnlyOnce() {
    HouseholdKey.set(ctx, ByteArray(32) { 7 })
    val other = RemotePairing.mintTestSession(ctx)
    val fresh = pair(now = System.currentTimeMillis())
    assertEquals(403, post(other, JSONObject().put("share", true)).status)
    val shared = post(fresh, JSONObject().put("share", true))
    assertEquals(200, shared.status)
    assertEquals(HouseholdKey.fingerprint(ByteArray(32) { 7 }),
        JSONObject(String(shared.jsonBytes!!)).getString("fingerprint"))
    assertEquals("the grant is single use", 403, post(fresh, JSONObject().put("share", true)).status)
    // Everyone may still read the fingerprint.
    assertEquals(200, route.handleFrom(ctx, "GET", JSONObject(), RemoteFeatureRoutes.callerKey(other)).status)
  }

  @Test fun aRefusedReplacementDoesNotSpendTheGrant() {
    val fresh = pair(now = System.currentTimeMillis())
    assertEquals(400, post(fresh, JSONObject().put("key", "not-hex")).status)
    assertTrue(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(fresh)))
  }

  @Test fun aNewerPairingMovesTheGrantAndTheWindowCloses() {
    val now = System.currentTimeMillis()
    val first = pair(now = now)
    val second = pair(now = now)
    assertFalse(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(first), now))
    assertTrue(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(second), now))
    assertFalse(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(second), now + 10 * 60_000L + 1))
  }

  @Test fun operatorTestGrantSimulatesAPinPairing() {
    val token = RemotePairing.mintTestSession(ctx, householdGrant = true)
    assertTrue(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(token)))
    RemotePairing.revokeTestSessions(ctx)
    assertFalse(RemotePairing.householdWindowOpen(RemoteFeatureRoutes.callerKey(token)))
  }

  @Test fun operatorVerificationCodeMintsATestSessionThatRevokeCleansUp() {
    val real = pair(now = 0L)
    val pin = RemotePairing.newPin(now = 1L, forTest = true)
    val test = (RemotePairing.redeem(ctx, pin, "192.168.1.30", "probe", 1L) as RemotePairing.RedeemResult.Paired).token
    assertTrue(RemotePairing.sessions(ctx, now = 2L).single { it.id == RemoteSessionPolicy.idOf(test) }.test)
    // The next ordinary code is not a test code.
    assertFalse(RemotePairing.sessions(ctx, now = 2L).single { it.id == RemoteSessionPolicy.idOf(real) }.test)
    assertEquals(1, RemotePairing.revokeTestSessions(ctx, now = 3L))
    assertEquals(listOf(RemoteSessionPolicy.idOf(real)), RemotePairing.sessions(ctx, now = 3L).map { it.id })
  }

  @Test fun labelIsOptionalForOlderPhones() {
    val token = pair(now = 0L, label = null)
    assertNotNull(token)
    assertEquals(RemoteSessionPolicy.LEGACY_LABEL, RemotePairing.sessions(ctx, now = 1L).single().label)
  }
}
