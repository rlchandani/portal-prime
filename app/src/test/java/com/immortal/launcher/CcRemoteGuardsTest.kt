/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirm tickets, rate limits and the phone policy: the security surface of `/remote/controlcenter`. */
class CcRemoteGuardsTest {
  private var seed = 0
  private val confirm = CcRemoteConfirm { b -> b.fill((++seed).toByte()) }

  @Test fun ticketWorksOnceForThatCallerAndCall() {
    val t = confirm.issue("phoneA", "lock.front|unlock", 1_000)
    assertFalse(confirm.redeem(t, "phoneB", "lock.front|unlock", 1_100))
    assertFalse(confirm.redeem(t, "phoneA", "lock.back|unlock", 1_100))
    assertTrue(confirm.redeem(t, "phoneA", "lock.front|unlock", 1_200))
    assertFalse("single use", confirm.redeem(t, "phoneA", "lock.front|unlock", 1_300))
  }

  @Test fun ticketExpires() {
    val t = confirm.issue("p", "k", 0)
    assertFalse(confirm.redeem(t, "p", "k", CcRemoteConfirm.TTL_MS))
    assertFalse(confirm.redeem(null, "p", "k", 1))
    assertFalse(confirm.redeem("", "p", "k", 1))
  }

  @Test fun pendingTicketsAreBounded() {
    val first = confirm.issue("p", "k0", 0)
    repeat(CcRemoteConfirm.MAX_PENDING) { confirm.issue("p", "k${it + 1}", 1) }
    assertFalse(confirm.redeem(first, "p", "k0", 2))
  }

  @Test fun limiterAllowsBurstThenRefills() {
    val l = CcRemoteLimiter()
    val burst = CcRemoteLimiter.Kind.ACT.burst.toInt()
    repeat(burst) { assertEquals(0L, l.take("a", CcRemoteLimiter.Kind.ACT, 0)) }
    val wait = l.take("a", CcRemoteLimiter.Kind.ACT, 0)
    assertTrue(wait > 0)
    assertEquals(0L, l.take("b", CcRemoteLimiter.Kind.ACT, 0)) // another phone has its own bucket
    assertEquals(0L, l.take("a", CcRemoteLimiter.Kind.ACT, wait))
    assertEquals(0L, l.take("a", CcRemoteLimiter.Kind.STATE, 0)) // kinds are separate
  }

  @Test fun limiterHasAPortalWideCap() {
    val l = CcRemoteLimiter()
    val k = CcRemoteLimiter.Kind.PLAN
    var allowed = 0
    repeat(50) { i -> if (l.take("phone$i", k, 0) == 0L) allowed++ }
    assertEquals((k.burst * 3).toInt(), allowed)
  }

  @Test fun phoneIgnoresKidsModeButKeepsHouseholdAsks() {
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
    val lock = CcEntity("lock.front", "locked")
    val seen = { emptySet<String>() }
    assertNull(CcPhonePolicy.refusal(lock, CcCalls.lock(lock), kids, false, seen))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPhonePolicy.ask(lock, CcCalls.unlock(lock), kids, seen))
    assertEquals("needs confirm first", CcPhonePolicy.refusal(lock, CcCalls.unlock(lock), kids, false, seen))
    assertNull(CcPhonePolicy.refusal(lock, CcCalls.unlock(lock), kids, true, seen))
    val car = CcEntity("lock.car", "locked")
    assertNull(CcPhonePolicy.refusal(car, CcTeslaCalls.lock(car), kids, false, seen))
    assertEquals(CcPolicy.Ask.CONFIRM, CcPhonePolicy.ask(car, CcTeslaCalls.unlock(car), kids.copy(askUnlock = false), seen))
  }

  @Test fun promptsUseHouseholdWords() {
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage", "friendly_name" to "Garage door"))
    val (title, _, button) = CcPhonePolicy.prompt(garage, CcCalls.coverClose(garage), CcPolicy.Ask.CAMERA_CONFIRM, "Tesla")
    assertEquals("Close Garage door?", title)
    assertEquals("Close", button)
  }
}
