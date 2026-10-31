/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MusicArtworkDeliveryTest {
  @Test fun cancelledReturnToUiDisposesTheDecodedResultExactlyOnce() = runBlocking {
    val owner = Executors.newSingleThreadExecutor()
    val dispatcher = owner.asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
    val loading = CountDownLatch(1)
    val releaseLoad = CountDownLatch(1)
    val ownerBlocked = CountDownLatch(1)
    val releaseOwner = CountDownLatch(1)
    val loaded = CountDownLatch(1)
    val disposals = AtomicInteger()
    val publications = AtomicInteger()
    val image = Any()
    val job = scope.launch {
      deliverOwnedMusicArtwork(
          load = {
            loading.countDown()
            check(releaseLoad.await(3, TimeUnit.SECONDS))
            loaded.countDown()
            image
          },
          dispose = { assertSame(image, it); disposals.incrementAndGet() },
          publish = { publications.incrementAndGet() })
    }
    try {
      assertTrue(loading.await(1, TimeUnit.SECONDS))
      // Pin the receiving thread while IO finishes, then cancel before it resumes.
      owner.execute {
        ownerBlocked.countDown()
        check(releaseOwner.await(3, TimeUnit.SECONDS))
      }
      assertTrue(ownerBlocked.await(1, TimeUnit.SECONDS))
      releaseLoad.countDown()
      assertTrue(loaded.await(1, TimeUnit.SECONDS))
      job.cancel()
      releaseOwner.countDown()
      withTimeout(2_000) { job.join() }
      assertEquals(0, publications.get())
      assertEquals(1, disposals.get())
    } finally {
      releaseLoad.countDown()
      releaseOwner.countDown()
      scope.cancel()
      withTimeout(2_000) { job.join() }
      dispatcher.close()
      assertTrue(owner.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun successfulPublicationTransfersOwnershipWithoutDisposal() = runBlocking {
    val image = Any()
    var published: Any? = null
    var disposals = 0
    deliverOwnedMusicArtwork({ image }, { disposals++ }, { published = it })
    assertSame(image, published)
    assertEquals(0, disposals)
  }

  @Test fun failedPublicationDisposesTheUntransferredResult() = runBlocking {
    val image = Any()
    val failure = IllegalStateException("synthetic publication failure")
    var disposals = 0
    try {
      deliverOwnedMusicArtwork({ image }, { assertSame(image, it); disposals++ }, { throw failure })
      fail("publication failure expected")
    } catch (actual: IllegalStateException) { assertSame(failure, actual) }
    assertEquals(1, disposals)
  }
}
