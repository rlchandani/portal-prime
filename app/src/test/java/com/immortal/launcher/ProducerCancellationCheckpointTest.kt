/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/** Exercises public producer cancellation boundaries without configured accounts or network. */
class ProducerCancellationCheckpointTest {
  private fun assertCancelled(expected: CancellationException, work: () -> Unit) {
    try {
      work()
      fail("Cancellation must escape the producer")
    } catch (actual: CancellationException) {
      assertSame(expected, actual)
    }
  }

  @Test
  fun `cancelled options scan escapes before configuration or fetching`() {
    val context = mock(Context::class.java)
    val cancelled = CancellationException("Stopped")
    assertCancelled(cancelled) {
      OptionsScanner.scan(context, listOf("AAPL"), "call", 0.2, 0.3, 30,
          checkCancelled = { throw cancelled })
    }
  }

  @Test
  fun `cancelled HA fetch escapes before configuration or fetching`() {
    val context = mock(Context::class.java)
    val cancelled = CancellationException("Stopped")
    assertCancelled(cancelled) {
      HaClient.fetchStates(context, listOf("sensor.example"),
          checkCancelled = { throw cancelled })
    }
  }

  @Test
  fun `HA cancellation during a failed entity read prevents returning partial states`() {
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    var stopped = false
    `when`(context.getSharedPreferences("immortal_ha", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("base_url", "")).thenAnswer { stopped = true; "" }
    val cancelled = CancellationException("Stopped during read")
    assertCancelled(cancelled) {
      HaClient.fetchStates(context, listOf("sensor.example"),
          checkCancelled = { if (stopped) throw cancelled })
    }
  }

  @Test
  fun `options cancellation during configuration prevents returning an empty result`() {
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    var stopped = false
    `when`(context.getSharedPreferences("immortal_alpaca", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("key_id", "")).thenAnswer { stopped = true; "" }
    val cancelled = CancellationException("Stopped during configuration")
    assertCancelled(cancelled) {
      OptionsScanner.scan(context, listOf("AAPL"), "call", 0.2, 0.3, 30,
          checkCancelled = { if (stopped) throw cancelled })
    }
  }

  @Test
  fun `cancelled option chain escapes before configuration or pagination`() {
    val cancelled = CancellationException("Stopped")
    assertCancelled(cancelled) {
      AlpacaClient.fetchOptionChain(mock(Context::class.java), "AAPL", "call", "2026-10-04", "2026-11-04",
          checkCancelled = { throw cancelled })
    }
  }

  @Test
  fun `option chain checks cancellation after failed configuration`() {
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    var stopped = false
    `when`(context.getSharedPreferences("immortal_alpaca", Context.MODE_PRIVATE)).thenReturn(prefs)
    `when`(prefs.getString("key_id", "")).thenAnswer { stopped = true; "" }
    val cancelled = CancellationException("Stopped during configuration")
    assertCancelled(cancelled) {
      AlpacaClient.fetchOptionChain(context, "AAPL", "call", "2026-10-04", "2026-11-04",
          checkCancelled = { if (stopped) throw cancelled })
    }
  }

  @Test
  fun `default callbacks retain empty input behavior without configuration`() {
    val context = mock(Context::class.java)
    assertTrue(OptionsScanner.scan(context, emptyList(), "call", 0.2, 0.3, 30).isEmpty())
    assertTrue(HaClient.fetchStates(context, emptyList()).isEmpty())
  }
}
