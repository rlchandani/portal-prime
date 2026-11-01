/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowSourceDiscoveryTest {
    private class Connection : HttpURLConnection(URL("http://127.0.0.1/unused")) {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() {
            closes.incrementAndGet()
            closed.countDown()
        }
    }

    private class Owner : Closeable {
        val root = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val failed = CountDownLatch(1)
        private val scope = CoroutineScope(root + Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                failures.add(error)
                failed.countDown()
            })
        val discovery = SlideshowSourceDiscovery(scope)

        fun awaitIdle() = runBlocking {
            withTimeout(4_000) { root.children.toList().forEach { it.join() } }
            drainPhotoWork()
        }

        override fun close() {
            try {
                discovery.close()
            } finally {
                root.cancel()
                runBlocking { withTimeout(4_000) { root.join() } }
            }
        }
    }

    /** Models a connected body read that ignores interruption until disconnect releases it. */
    private class NonInterruptibleConnection :
        HttpURLConnection(URL("http://127.0.0.1/unused")) {
        val reading = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val disconnectThreads = CopyOnWriteArrayList<Thread>()
        private val body = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    while (closed.count > 0) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) throw IOException("Test body was not disconnected")
                        try {
                            if (!closed.await(remaining, TimeUnit.NANOSECONDS))
                                throw IOException("Test body was not disconnected")
                        } catch (_: InterruptedException) {
                            interrupted.set(true)
                        }
                    }
                    if (Thread.currentThread().isInterrupted) interrupted.set(true)
                    return -1
                } finally {
                    readFinished.countDown()
                }
            }
        }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getInputStream(): InputStream = body
        override fun disconnect() {
            disconnectThreads.add(Thread.currentThread())
            closed.countDown()
        }
    }

    @Test(timeout = 20_000)
    fun successfulDiscoveryPublishesOnceAndClosesTrackedConnections() {
        Owner().use { owner ->
            val connection = Connection()
            val delivered = CountDownLatch(1)
            val results = CopyOnWriteArrayList<String?>()
            owner.discovery.load(
                block = { PhotoSourceWork.track(connection); "album" },
                onResult = { results.add(it); delivered.countDown() })

            await(delivered, "Successful discovery was not delivered")
            owner.awaitIdle()
            await(connection.closed, "Successful discovery leaked its connection")
            assertEquals(listOf("album"), results.toList())
            assertEquals(1, connection.closes.get())
            assertTrue(owner.failures.isEmpty())
        }
    }

    @Test(timeout = 20_000)
    fun recoverableFailurePublishesNullClosesItsConnectionAndAllowsRetry() {
        Owner().use { owner ->
            val connection = Connection()
            val failedResult = CountDownLatch(1)
            val retriedResult = CountDownLatch(1)
            val results = CopyOnWriteArrayList<String?>()
            owner.discovery.load<String>(
                block = {
                    PhotoSourceWork.track(connection)
                    throw IOException("Loopback listing unavailable")
                },
                onResult = { results.add(it); failedResult.countDown() })

            await(failedResult, "Recoverable failure did not publish its fallback")
            owner.awaitIdle()
            await(connection.closed, "Failed discovery leaked its connection")
            assertEquals(listOf<String?>(null), results.toList())
            assertEquals(1, connection.closes.get())
            assertTrue(owner.failures.isEmpty())

            owner.discovery.load(
                block = { "retry" },
                onResult = { results.add(it); retriedResult.countDown() })
            await(retriedResult, "Discovery did not recover after failure")
            owner.awaitIdle()
            assertEquals(listOf(null, "retry"), results.toList())
        }
    }

    @Test(timeout = 20_000)
    fun anEmptyDiscoveryStillDeliversItsNullResult() {
        Owner().use { owner ->
            val delivered = CountDownLatch(1)
            val results = CopyOnWriteArrayList<String?>()
            owner.discovery.load<String>(
                block = { null },
                onResult = { results.add(it); delivered.countDown() })

            await(delivered, "An empty source did not finish discovery")
            owner.awaitIdle()
            assertEquals(listOf<String?>(null), results.toList())
            assertTrue(owner.failures.isEmpty())
        }
    }

    @Test(timeout = 20_000)
    fun replacementCancelsTheOldTransportAndSuppressesItsLateResult() {
        val owner = Owner()
        val connection = Connection()
        val started = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldReturned = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val results = CopyOnWriteArrayList<String?>()
        try {
            owner.discovery.load(
                block = {
                    PhotoSourceWork.track(connection)
                    started.countDown()
                    awaitIgnoringInterrupts(releaseOld)
                    oldReturned.countDown()
                    "obsolete"
                },
                onResult = { results.add(it) })
            await(started, "Old discovery did not start")
            val oldRequest = owner.root.children.first()

            owner.discovery.load(
                block = { "current" },
                onResult = { results.add(it); delivered.countDown() })
            await(connection.closed, "Replacing discovery did not cancel the old transport")
            releaseOld.countDown()
            await(oldReturned, "Old blocking source did not return its late result")
            await(delivered, "Replacement remained blocked behind the old discovery")
            runBlocking { withTimeout(4_000) { oldRequest.join() } }
            owner.awaitIdle()
            assertEquals(listOf("current"), results.toList())
            assertTrue(owner.failures.isEmpty())
        } finally {
            releaseOld.countDown()
            try { owner.close() } finally { drainPhotoWork() }
        }
    }

    @Test(timeout = 20_000)
    fun closeDisconnectsANonInterruptibleBodyOffTheCallerAndUnblocksThePhotoWorker() {
        val owner = Owner()
        val connection = NonInterruptibleConnection()
        val caller = Thread.currentThread()
        val results = CopyOnWriteArrayList<String?>()
        try {
            owner.discovery.load(
                block = {
                    PhotoSourceWork.track(connection)
                    connection.inputStream.use { it.read() }
                    "obsolete"
                },
                onResult = { results.add(it) })
            await(connection.reading, "Discovery did not reach the noninterruptible body read")
            assertEquals("The body read must remain blocked until disconnect", 1L,
                connection.readFinished.count)

            owner.discovery.close()
            await(connection.closed, "Stopping discovery did not disconnect the transport")
            await(connection.readFinished, "Stopping discovery left the body read blocked")
            assertEquals("next photo", runBlocking {
                withTimeout(4_000) { PhotoSourceWork.load { "next photo" } }
            })
            owner.awaitIdle()
            assertTrue("The body must ignore worker interruption until disconnect",
                connection.interrupted.get())
            assertTrue(connection.disconnectThreads.isNotEmpty())
            assertTrue("Disconnect must never run on the lifecycle caller",
                connection.disconnectThreads.all { it !== caller })
            assertTrue("Closed discovery published a stale result or fallback", results.isEmpty())
            assertTrue(owner.failures.isEmpty())
        } finally {
            // Release the test body even if cancellation failed, then drain the shared worker.
            connection.closed.countDown()
            try { owner.close() } finally { drainPhotoWork() }
        }
    }

    @Test(timeout = 20_000)
    fun closedOwnerNeverStartsAnotherDiscoveryOrDeliversItsResult() {
        Owner().use { owner ->
            val started = AtomicInteger()
            val published = AtomicInteger()
            owner.discovery.close()
            owner.discovery.load(
                block = { started.incrementAndGet(); "too late" },
                onResult = { published.incrementAndGet() })

            owner.awaitIdle()
            assertEquals(0, started.get())
            assertEquals(0, published.get())
            assertTrue(owner.failures.isEmpty())
        }
    }

    @Test(timeout = 20_000)
    fun loaderCancellationCancelsTheRequestWithoutPublishingAFailureFallback() {
        val owner = Owner()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        try {
            owner.discovery.load<String>(
                block = {
                    started.countDown()
                    awaitIgnoringInterrupts(release)
                    throw CancellationException("Discovery source cancelled")
                },
                onResult = { published.incrementAndGet() })
            await(started, "Cancellable discovery did not start")
            val request = owner.root.children.first()
            release.countDown()
            runBlocking { withTimeout(4_000) { request.join() } }
            owner.awaitIdle()

            assertTrue("Loader cancellation must escape as request cancellation", request.isCancelled)
            assertEquals(0, published.get())
            assertTrue(owner.failures.isEmpty())
        } finally {
            release.countDown()
            try { owner.close() } finally { drainPhotoWork() }
        }
    }

    @Test(timeout = 20_000)
    fun fatalErrorEscapesToTheScopeAndClosesItsTrackedConnection() {
        Owner().use { owner ->
            val connection = Connection()
            val expected = AssertionError("Discovery invariant failed")
            val published = AtomicInteger()
            owner.discovery.load<String>(
                block = { PhotoSourceWork.track(connection); throw expected },
                onResult = { published.incrementAndGet() })

            await(owner.failed, "Fatal discovery error was swallowed")
            owner.awaitIdle()
            await(connection.closed, "Fatal discovery error leaked its connection")
            assertEquals(1, owner.failures.size)
            val actual = owner.failures.single()
            assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
            assertEquals(0, published.get())
            assertEquals(1, connection.closes.get())
        }
    }

    companion object {
        private fun await(latch: CountDownLatch, message: String) {
            assertTrue(message, latch.await(4, TimeUnit.SECONDS))
        }

        /** A blocked source may ignore interruption, but the test itself has a deadline. */
        private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (latch.count > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("Test source was not released")
                try {
                    if (!latch.await(remaining, TimeUnit.NANOSECONDS))
                        throw IOException("Test source was not released")
                } catch (_: InterruptedException) {}
            }
        }

        private fun drainPhotoWork() = runBlocking {
            withTimeout(4_000) { PhotoSourceWork.load { Unit } }
        }
    }
}
