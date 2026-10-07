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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ConverterCurrencyWorkTest {
  @Test fun freshCachedRateSupportsInstantAmountChanges() {
    val (context, prefs) = cachedContext()
    Mockito.`when`(prefs.getString("rates_EUR", null)).thenReturn("""{"rates":{"USD":1.25}}""")
    assertEquals(1.25, Converter.exchangeRate(context, "EUR", "USD")!!, 0.0)
    assertEquals(12.5, Converter.convertCurrency(context, "EUR", "USD", 10.0)!!, 0.0)
    assertEquals(25.0, Converter.convertCurrency(context, "EUR", "USD", 20.0)!!, 0.0)
  }

  @Test fun sameCurrencyNeedsNeitherPreferencesNorNetwork() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
        .thenThrow(AssertionError("Identity conversion must not access a cache"))
    assertEquals(1.0, Converter.exchangeRate(context, "JPY", "JPY")!!, 0.0)
    assertEquals(42.0, Converter.convertCurrency(context, "JPY", "JPY", 42.0)!!, 0.0)
  }

  @Test fun cancelledCacheReadCannotReturnARateOrContinueWithAnotherRequest() = runBlocking {
    val (context, prefs) = cachedContext()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val cancellation = AtomicBoolean()
    val returned = AtomicBoolean()
    Mockito.`when`(prefs.getString("rates_EUR", null)).thenAnswer {
      started.countDown()
      // Model a blocking dependency that requires an explicit cancellation checkpoint.
      while (release.count > 0) {
        try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
      }
      """{"rates":{"USD":1.25}}"""
    }
    val job = launch(Dispatchers.Default) {
      ToolsWork.load {
        try {
          Converter.exchangeRate(context, "EUR", "USD")
          returned.set(true)
        } catch (_: CancellationException) {
          cancellation.set(true)
        } finally {
          finished.countDown()
        }
      }
    }
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS))
      withTimeout(2_000) { job.cancelAndJoin() }
      release.countDown()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      withTimeout(2_000) { ToolsWork.load { Unit } }
      assertTrue(cancellation.get())
      assertFalse(returned.get())
    } finally {
      release.countDown()
      job.cancelAndJoin()
    }
  }

  private fun cachedContext(): Pair<Context, SharedPreferences> {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.getSharedPreferences("immortal_converter", Context.MODE_PRIVATE)).thenReturn(prefs)
    Mockito.`when`(prefs.getLong("rates_EUR_ts", 0L)).thenReturn(System.currentTimeMillis())
    return context to prefs
  }
}
