/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePhotoWarmCacheTest {
    private suspend fun <T> withOccupiedWorker(body: suspend (() -> Unit) -> T): T = coroutineScope {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = async(Dispatchers.Default) {
            PhotoSourceWork.load {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                "occupied"
            }
        }
        try {
            assertTrue("worker started", started.await(2, TimeUnit.SECONDS))
            body { release.countDown() }
        } finally {
            release.countDown()
            withTimeout(2_000) {
                worker.cancelAndJoin()
                assertEquals("drained", PhotoSourceWork.load { "drained" })
            }
        }
    }

    @Test fun warmHitReturnsWhileWorkerIsOccupied() = runBlocking {
        val image = Any()
        val decodes = AtomicInteger()
        withOccupiedWorker {
            assertSame(image, withTimeout(500) {
                PhotoSourceWork.cachedOrLoad({ image }) {
                    decodes.incrementAndGet()
                    Any()
                }
            })
            assertEquals(0, decodes.get())
        }
    }

    @Test fun warmHitSurvivesAFullWorkerQueue() = runBlocking {
        val image = Any()
        val decodes = AtomicInteger()
        withOccupiedWorker {
            coroutineScope {
                val pending = List(2) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        PhotoSourceWork.load { "queued" }
                    }
                }
                try {
                    assertSame(image, withTimeout(500) {
                        PhotoSourceWork.cachedOrLoad({ image }) {
                            decodes.incrementAndGet()
                            Any()
                        }
                    })
                    assertEquals(0, decodes.get())
                } finally {
                    pending.forEach { it.cancelAndJoin() }
                }
            }
        }
    }

    @Test fun warmLookupRunsOnCallerWithoutInvokingWorker() = runBlocking {
        val caller = Thread.currentThread()
        val observed = AtomicReference<Thread>()
        val reads = AtomicInteger()
        val cold = AtomicInteger()
        assertEquals("cached", PhotoSourceWork.cachedOrLoad({
            observed.set(Thread.currentThread())
            reads.incrementAndGet()
            "cached"
        }) {
            cold.incrementAndGet()
            "cold"
        })
        assertSame(caller, observed.get())
        assertEquals(1, reads.get())
        assertEquals(0, cold.get())
    }

    @Test fun missUsesTheOwnedColdWorker() = runBlocking {
        val caller = Thread.currentThread()
        val reads = AtomicInteger()
        assertEquals("decoded", withTimeout(2_000) {
            PhotoSourceWork.cachedOrLoad({ reads.incrementAndGet(); null }) {
                assertTrue(PhotoSourceWork.ownsCurrentRequest())
                assertTrue(Thread.currentThread() !== caller)
                PhotoSourceWork.checkpoint()
                "decoded"
            }
        })
        assertEquals(1, reads.get())
    }

    @Test fun coldFailureIsPreserved() = runBlocking {
        val error = IOException("fixture decode failure")
        val actual = runCatching {
            withTimeout(2_000) {
                PhotoSourceWork.cachedOrLoad<String>({ null }) { throw error }
            }
        }.exceptionOrNull()
        assertTrue(actual is IOException)
        assertEquals(error.message, actual?.message)
    }

    @Test fun cancelledCallerCannotReadOrPublishCachedValue() = runBlocking {
        val reads = AtomicInteger()
        val published = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            PhotoSourceWork.cachedOrLoad({ reads.incrementAndGet(); "cached" }) { "cold" }
            published.incrementAndGet()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, reads.get())
        assertEquals(0, published.get())
    }

    @Test fun cacheLookupCancellationCannotPublish() = runBlocking {
        val published = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val caller = currentCoroutineContext()
            PhotoSourceWork.cachedOrLoad({ caller.cancel(); "cached" }) { "cold" }
            published.incrementAndGet()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, published.get())
    }

    @Test fun workerRecheckUsesCacheFilledDuringQueueWait() = runBlocking {
        val late = AtomicReference<String?>()
        val decodes = AtomicInteger()
        withOccupiedWorker { release ->
            coroutineScope {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    PhotoSourceWork.cachedOrLoad({ late.get() }) {
                        late.get() ?: run { decodes.incrementAndGet(); "decoded" }
                    }
                }
                late.set("late cached image")
                release()
                assertEquals("late cached image", withTimeout(2_000) { pending.await() })
                assertEquals(0, decodes.get())
            }
        }
    }
}
