/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CurrencyRateLoaderTest {
  @Test fun freshRateBypassesDelayAndFetch() = runBlocking {
    val result = loadCurrencyRate(
        cached = { 0.85 },
        fetch = { error("A saved rate must not fetch") },
        debounce = { error("A saved rate must not wait") })
    assertEquals(0.85, result!!, 0.0)
  }

  @Test fun cacheMissWaitsBeforeFetching() = runBlocking {
    val order = mutableListOf<String>()
    val result = loadCurrencyRate(
        cached = { order += "cache"; null },
        fetch = { order += "fetch"; 1.25 },
        debounce = { order += "wait" })
    assertEquals(listOf("cache", "wait", "fetch"), order)
    assertEquals(1.25, result!!, 0.0)
  }

  @Test fun rapidPairChangesFetchOnlyFinalPair() = runBlocking {
    val waiting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val fetched = mutableListOf<String>()
    val old = launch {
      loadCurrencyRate(cached = { null }, fetch = { fetched += "GBP"; 0.85 },
          debounce = { waiting.complete(Unit); release.await() })
    }
    waiting.await()
    old.cancelAndJoin()
    val result = loadCurrencyRate(cached = { null }, fetch = { fetched += "USD"; 1.25 },
        debounce = {})
    assertEquals(listOf("USD"), fetched)
    assertEquals(1.25, result!!, 0.0)
  }

  @Test fun cancelledCacheCannotEnterDelayOrFetch() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var delayed = false
    var fetched = false
    val job = launch {
      loadCurrencyRate(cached = { entered.complete(Unit); release.await(); null },
          fetch = { fetched = true; 1.0 }, debounce = { delayed = true })
    }
    entered.await()
    job.cancelAndJoin()
    assertFalse(delayed)
    assertFalse(fetched)
  }

  @Test fun missingOfflineRateRemainsUnavailable() = runBlocking {
    assertNull(loadCurrencyRate(cached = { null }, fetch = { null }, debounce = {}))
  }
}
