/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Fleet logcat: every household broadcast during a Wi-Fi drop logged a warning with a full stack
 * trace ("sendto failed: ENETUNREACH"). An unreachable network is expected and harmless, so it is a
 * rate-limited debug line; anything else still warns with its stack.
 */
class HouseholdBusSendLogTest {
  private val unreachable = IOException("sendto failed: ENETUNREACH (Network is unreachable)",
      Exception("sendto failed: ENETUNREACH (Network is unreachable)"))

  @Test fun unreachableNetworksAreRecognisedThroughTheCauseChain() {
    assertTrue(HouseholdBus.isNetworkUnreachable(unreachable))
    assertTrue(HouseholdBus.isNetworkUnreachable(IOException("wrapped", SocketException("connect failed: EHOSTUNREACH"))))
    assertTrue(HouseholdBus.isNetworkUnreachable(java.net.NoRouteToHostException()))
    assertFalse(HouseholdBus.isNetworkUnreachable(IOException("sendto failed: EPERM")))
    assertFalse(HouseholdBus.isNetworkUnreachable(IllegalStateException()))
  }

  @Test fun unreachableIsOneDebugLinePerIntervalAndOtherFailuresWarn() {
    val throttle = MemoryHealthPolicy.LogThrottle(HouseholdBus.UNREACHABLE_LOG_INTERVAL_MS)
    val other = IOException("sendto failed: EPERM")
    Mockito.mockStatic(Log::class.java).use { log ->
      HouseholdBus.logSendFailure(unreachable, throttle, nowMs = 1_000)
      HouseholdBus.logSendFailure(unreachable, throttle, nowMs = 2_000)
      HouseholdBus.logSendFailure(unreachable, throttle, nowMs = 1_000 + HouseholdBus.UNREACHABLE_LOG_INTERVAL_MS)
      HouseholdBus.logSendFailure(other, throttle, nowMs = 3_000)

      log.verify({ Log.d("ImmortalHouseholdBus", "household send skipped: network unreachable") }, Mockito.times(2))
      log.verify({ Log.w("ImmortalHouseholdBus", "household send failed", other) }, Mockito.times(1))
      log.verify({ Log.w(Mockito.anyString(), Mockito.anyString(), Mockito.eq(unreachable)) }, Mockito.never())
    }
    assertEquals(10L * 60 * 1000, HouseholdBus.UNREACHABLE_LOG_INTERVAL_MS)
  }
}
