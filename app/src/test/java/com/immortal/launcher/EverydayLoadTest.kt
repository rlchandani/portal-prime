/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EverydayLoadTest {
  @Test fun initialStateHasNoValueErrorOrRequest() {
    val load = EverydayLoad<String>()

    assertNull(load.value)
    assertNull(load.error)
    assertFalse(load.loading)
    assertEquals(0, load.retry)
  }

  @Test fun initialFailureShowsLoadErrorAndSuccessfulRefreshRecovers() = runBlocking {
    val load = EverydayLoad<String>()

    load.refresh {
      assertTrue(load.loading)
      throw IllegalStateException("Unavailable")
    }

    assertNull(load.value)
    assertEquals("Couldn’t load your saved data.", load.error)
    assertFalse(load.loading)

    load.refresh {
      assertTrue(load.loading)
      "loaded"
    }

    assertEquals("loaded", load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }

  @Test fun cachedFailureKeepsLastLoadedValueAndSuccessReplacesIt() = runBlocking {
    val load = EverydayLoad<List<String>>()
    load.refresh { listOf("saved") }

    load.refresh { throw IllegalStateException("Unavailable") }

    assertEquals(listOf("saved"), load.value)
    assertEquals(
        "Couldn’t refresh your saved data. Showing the last loaded values.", load.error)
    assertFalse(load.loading)

    load.refresh { listOf("fresh") }

    assertEquals(listOf("fresh"), load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }

  @Test fun successfullyLoadedEmptyCollectionIsStillACachedValue() = runBlocking {
    val load = EverydayLoad<List<String>>()
    load.refresh { emptyList() }

    assertEquals(emptyList<String>(), load.value)
    assertNull(load.error)

    load.refresh { throw IllegalStateException("Unavailable") }

    assertEquals(emptyList<String>(), load.value)
    assertEquals(
        "Couldn’t refresh your saved data. Showing the last loaded values.", load.error)
    assertFalse(load.loading)

    load.refresh { emptyList() }

    assertEquals(emptyList<String>(), load.value)
    assertNull(load.error)
  }

  @Test fun duplicateRefreshDoesNotInvokeLoaderOrEndTheInFlightRequest() = runBlocking {
    val load = EverydayLoad<String>()
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<String>()
    var duplicateCalls = 0
    val request = launch {
      load.refresh {
        started.complete(Unit)
        result.await()
      }
    }

    try {
      withTimeout(3000) { started.await() }
      assertTrue(load.loading)

      withTimeout(3000) {
        load.refresh {
          duplicateCalls++
          "duplicate"
        }
      }

      assertEquals(0, duplicateCalls)
      assertTrue(load.loading)
      assertNull(load.value)
      assertNull(load.error)
    } finally {
      result.complete("first")
      withTimeout(3000) { request.join() }
    }

    assertEquals("first", load.value)
    assertFalse(load.loading)
    load.refresh { "next" }
    assertEquals("next", load.value)
  }

  @Test fun cancellationRetainsCacheRethrowsAndAllowsAnotherRefresh() = runBlocking {
    val load = EverydayLoad<String>()
    load.refresh { "saved" }
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<String>()
    val observed = CompletableDeferred<CancellationException>()
    val cancellation = CancellationException("Pane departed")
    val request = launch {
      try {
        load.refresh {
          started.complete(Unit)
          release.await()
        }
      } catch (error: CancellationException) {
        observed.complete(error)
        throw error
      }
    }

    try {
      withTimeout(3000) { started.await() }
      assertTrue(load.loading)
    } finally {
      request.cancel(cancellation)
      withTimeout(3000) { request.join() }
    }

    assertTrue("Refresh must propagate cancellation to its caller", observed.isCompleted)
    val propagated = observed.await()
    assertEquals(cancellation.message, propagated.message)
    // Coroutine stack-trace recovery may copy the exception while retaining its original cause.
    assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === cancellation })
    assertEquals("saved", load.value)
    assertNull(load.error)
    assertFalse(load.loading)

    // A loader's own cancellation must also escape an otherwise active caller.
    val loaderCancellation = CancellationException("Loader cancelled")
    try {
      load.refresh { throw loaderCancellation }
      fail("Refresh must rethrow a loader cancellation")
    } catch (error: CancellationException) {
      assertSame(loaderCancellation, error)
    }
    assertEquals("saved", load.value)
    assertNull(load.error)
    assertFalse(load.loading)

    load.refresh { "fresh" }
    assertEquals("fresh", load.value)
    assertNull(load.error)
  }

  @Test fun newestExplicitValueWinsWhenAnOlderRefreshFailsLate() = runBlocking {
    val load = EverydayLoad<String>()
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val request = launch {
      load.refresh {
        started.complete(Unit)
        release.await()
        throw IllegalStateException("Old request failed")
      }
    }

    try {
      withTimeout(3000) { started.await() }
      val generation = load.generation
      load.value = "earlier edit"
      load.value = "newest edit"
      assertEquals(generation + 2, load.generation)
      assertTrue(load.loading)
    } finally {
      release.complete(Unit)
      withTimeout(3000) { request.join() }
    }

    assertEquals("newest edit", load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }

  @Test fun newestExplicitValueWinsWhenAnOlderRefreshSucceedsLate() = runBlocking {
    val load = EverydayLoad<String>()
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<String>()
    val request = launch {
      load.refresh {
        started.complete(Unit)
        result.await()
      }
    }

    try {
      withTimeout(3000) { started.await() }
      val generation = load.generation
      load.value = "earlier edit"
      load.value = "newest edit"
      assertEquals(generation + 2, load.generation)
      assertTrue(load.loading)
    } finally {
      result.complete("stale result")
      withTimeout(3000) { request.join() }
    }

    assertEquals("newest edit", load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }

  @Test fun failedRetriesKeepTheErrorAndRemainRetryableUntilSuccess() = runBlocking {
    val load = EverydayLoad<String>()
    load.refresh { throw IllegalStateException("First failure") }
    assertEquals("Couldn’t load your saved data.", load.error)

    load.retry++
    assertEquals(1, load.retry)
    assertEquals("Couldn’t load your saved data.", load.error)
    load.refresh { throw IllegalStateException("Retry failed") }

    assertEquals(1, load.retry)
    assertNull(load.value)
    assertEquals("Couldn’t load your saved data.", load.error)
    assertFalse(load.loading)

    load.retry++
    assertEquals(2, load.retry)
    assertEquals("Couldn’t load your saved data.", load.error)
    load.refresh { "recovered" }

    assertEquals("recovered", load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }

  @Test fun explicitWritesIncludingSameValueAndNullAdvanceGenerationAndClearErrors() = runBlocking {
    val load = EverydayLoad<String>()
    load.refresh { throw IllegalStateException("Unavailable") }
    assertEquals("Couldn’t load your saved data.", load.error)
    val generation = load.generation

    load.value = null
    assertEquals(generation + 1, load.generation)
    assertNull(load.value)
    assertNull(load.error)

    load.value = "saved"
    assertEquals(generation + 2, load.generation)
    load.value = "saved"
    assertEquals(generation + 3, load.generation)

    load.refresh { throw IllegalStateException("Refresh failed") }
    assertEquals(
        "Couldn’t refresh your saved data. Showing the last loaded values.", load.error)

    load.value = null
    assertEquals(generation + 4, load.generation)
    assertNull(load.value)
    assertNull(load.error)
    assertFalse(load.loading)
  }
}
