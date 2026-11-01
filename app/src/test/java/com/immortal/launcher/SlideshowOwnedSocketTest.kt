/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** A real loopback peer observes EOF; no NAS credentials or Android runtime are needed. */
class SlideshowOwnedSocketTest {
  @Test fun closingHiddenHostReleasesTheQueuedConnection() = verifyRelease(alreadyClosed = false)

  @Test fun lateCompletionReleasesItsConnectionAfterHostClose() = verifyRelease(alreadyClosed = true)

  private fun verifyRelease(alreadyClosed: Boolean) {
    val address = InetAddress.getLoopbackAddress()
    ServerSocket(0, 1, address).use { server ->
      Socket(address, server.localPort).use { connection ->
        server.accept().use { peer ->
          peer.soTimeout = 2_000
          val released = CountDownLatch(1)
          val admitted = AtomicBoolean()
          val scheduled = mutableListOf<Runnable>()
          val queue = PausableFrameCallbacks(
              clock = { 0L },
              schedule = { callback, _ -> scheduled.add(callback); true },
              cancel = { callback -> scheduled.remove(callback); Unit })
          val cleanup = Executors.newSingleThreadExecutor()
          try {
            queue.setActive(false)
            if (alreadyClosed) queue.close()
            val accepted = queue.postOwned(
                Runnable { admitted.set(true) },
                onDiscard = {
                  cleanup.execute {
                    try { connection.close() } finally { released.countDown() }
                  }
                })
            assertEquals(!alreadyClosed, accepted)
            queue.close()
            assertTrue("An unadmitted connection must be released", released.await(2, TimeUnit.SECONDS))
            assertEquals("The peer must see connection retirement", -1, peer.getInputStream().read())
            assertFalse(admitted.get())
            assertTrue(scheduled.isEmpty())
          } finally {
            queue.close()
            cleanup.shutdownNow()
            assertTrue(cleanup.awaitTermination(2, TimeUnit.SECONDS))
          }
        }
      }
    }
  }
}
