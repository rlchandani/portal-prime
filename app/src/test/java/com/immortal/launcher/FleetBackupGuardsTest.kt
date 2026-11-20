/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetBackupKeyStore.Outcome
import java.security.KeyPair
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rate limits, first-use key enrolment and the device binding of the fleet backup routes. */
class FleetBackupGuardsTest {
  private var now = 0L
  private val limiter = FleetBackupLimiter(maxPerWindow = 3, windowMs = 60_000, minGapMs = 2_000, clock = { now })

  @Test fun callsTooCloseTogetherWait() {
    assertNull(limiter.acquire("/backup/export"))
    now = 500
    assertEquals(2L, limiter.acquire("/backup/export"))
    now = 2_000
    assertNull(limiter.acquire("/backup/export"))
  }

  @Test fun theWindowCapsCallsPerRoute() {
    repeat(3) { now = it * 3_000L; assertNull(limiter.acquire("/backup/export")) }
    now = 9_000
    assertEquals(51L, limiter.acquire("/backup/export"))
    assertNull(limiter.acquire("/backup/key")) // routes are counted separately
    now = 60_000
    assertNull(limiter.acquire("/backup/export"))
  }

  @Test fun refusedCallsAreNotCounted() {
    assertNull(limiter.acquire("r"))
    now = 100
    repeat(10) { assertEquals(2L, limiter.acquire("r")) }
    now = 2_000
    assertNull(limiter.acquire("r"))
  }

  @Test fun aFleetEnrolmentOnlyProposesAKeyAndTheActiveKeyIsUnchanged() {
    assertEquals(Outcome.PENDING, FleetBackupKeyStore.decide(null, A.public.encoded))
    assertEquals(Outcome.UNCHANGED, FleetBackupKeyStore.decide(A.public.encoded, A.public.encoded.copyOf()))
  }

  @Test fun anotherKeyIsRefusedOnceAKeyIsEnrolled() {
    assertEquals(Outcome.MISMATCH, FleetBackupKeyStore.decide(A.public.encoded, B.public.encoded))
  }

  @Test fun theDeviceIdIsAHashNotTheRawId() {
    val id = FleetBackupRoutes.deviceIdOf("8c3b5f0a1d2e4f67")
    assertEquals(32, id.length)
    assertEquals(id, FleetBackupRoutes.deviceIdOf("8c3b5f0a1d2e4f67"))
    assertNotEquals(id, FleetBackupRoutes.deviceIdOf("8c3b5f0a1d2e4f68"))
    assert(!id.contains("8c3b5f0a"))
  }

  @Test fun errorCodesMapToStatuses() {
    assertEquals(413, FleetBackupRoutes.statusFor("backup_too_large"))
    assertEquals(409, FleetBackupRoutes.statusFor("different_device"))
    assertEquals(422, FleetBackupRoutes.statusFor("seal_broken"))
    assertEquals(422, FleetBackupRoutes.statusFor("wrong_key"))
    assertEquals(400, FleetBackupRoutes.statusFor("bad_archive"))
  }

  @Test fun theRoutesAreServedOnlyBehindTheFleetToken() {
    // FleetRoutes checks the bearer token before dispatching any path outside /remote/.
    FleetBackupRoutes.PATHS.forEach { assert(!it.startsWith("/remote/")) }
  }

  companion object {
    private val A: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    private val B: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
  }
}
