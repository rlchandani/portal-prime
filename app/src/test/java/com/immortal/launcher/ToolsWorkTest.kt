/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ToolsWorkTest {
    private class Connection(private val closing: () -> Unit = {}) :
        HttpURLConnection(URL("https://example.invalid")) {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val closeThread = AtomicReference<Thread>()
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() {
            closeThread.set(Thread.currentThread())
            closes.incrementAndGet()
            closed.countDown()
            closing()
        }
    }

    /** Model a transport that ignores interrupts until its owner disconnects it. */
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) {
            try { latch.await(50, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) {}
        }
    }

    private suspend fun drain() {
        withTimeout(2_000) { ToolsWork.load { Unit } }
    }

    @Test fun cancellationDisconnectsBlockingIoAndPreventsFollowUpWork() = runBlocking {
        val connection = Connection()
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val followUps = AtomicInteger()
        val published = AtomicBoolean()
        val caller = Thread.currentThread()
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                ToolsWork.track(connection)
                started.countDown()
                while (connection.closed.count > 0) {
                    try { connection.closed.await(50, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted.set(true) }
                }
                if (Thread.currentThread().isInterrupted) interrupted.set(true)
                ToolsWork.checkpoint()
                followUps.incrementAndGet()
                "obsolete"
            }
            published.set(true)
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
            drain()
            assertTrue(interrupted.get())
            assertTrue(connection.closeThread.get() !== caller)
            assertTrue(connection.closeThread.get().isDaemon)
            assertEquals(0, followUps.get())
            assertFalse(published.get())
        } finally {
            connection.closed.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun cancellationReleasesTheCallerWhileDisconnectIsStillBlocked() = runBlocking {
        val allowClose = CountDownLatch(1)
        val connection = Connection { awaitUninterruptibly(allowClose) }
        val started = CountDownLatch(1)
        val published = AtomicBoolean()
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                ToolsWork.track(connection)
                started.countDown()
                awaitUninterruptibly(connection.closed)
                ToolsWork.disconnect(connection)
                "obsolete"
            }
            published.set(true)
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, allowClose.count)
        } finally {
            allowClose.countDown()
            connection.closed.countDown()
            job.cancelAndJoin()
            drain()
        }
        assertFalse(published.get())
        assertEquals(1, connection.closes.get())
    }

    @Test fun lateTransportRegistrationAfterCancellationIsStillClosed() = runBlocking {
        val connection = Connection()
        val started = CountDownLatch(1)
        val register = CountDownLatch(1)
        val followUps = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                started.countDown()
                awaitUninterruptibly(register)
                ToolsWork.track(connection)
                followUps.incrementAndGet()
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            register.countDown()
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
            drain()
            assertEquals(0, followUps.get())
        } finally {
            register.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun repeatedTrackingAndCancellationClaimCleanupBeforeDisconnecting() = runBlocking {
        val allowClose = CountDownLatch(1)
        val connection = Connection { awaitUninterruptibly(allowClose) }
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                ToolsWork.track(connection)
                ToolsWork.track(connection)
                started.countDown()
                awaitUninterruptibly(connection.closed)
                "obsolete"
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            job.cancel()
            job.cancel()
            withTimeout(1_000) { job.join() }
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
            // The worker's finally must not call disconnect again while cleanup owns it.
            drain()
            assertEquals(1, connection.closes.get())
        } finally {
            allowClose.countDown()
            connection.closed.countDown()
            job.cancelAndJoin()
            drain()
        }
    }

    @Test fun normalCompletionWaitsForTransportCleanup() = runBlocking {
        val allowClose = CountDownLatch(1)
        val connection = Connection { awaitUninterruptibly(allowClose) }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            ToolsWork.load { ToolsWork.track(connection); "value" }
        }
        try {
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
            yield()
            assertFalse("A normal result must wait for cleanup to finish", result.isCompleted)
        } finally {
            allowClose.countDown()
        }
        assertEquals("value", withTimeout(2_000) { result.await() })
        assertEquals(1, connection.closes.get())
    }

    @Test fun explicitDisconnectAndWorkerCleanupDoNotCloseTheSameTransportTwice() = runBlocking {
        val success = Connection()
        assertEquals("value", ToolsWork.load {
            ToolsWork.track(success)
            try { "value" } finally { ToolsWork.disconnect(success) }
        })
        assertEquals(1, success.closes.get())
    }

    @Test fun exceptionalCompletionReleasesTheTransportAndPreservesTheFailure() = runBlocking {
        val failure = Connection()
        val expected = IllegalStateException("feed failed")
        val actual = runCatching {
            ToolsWork.load { ToolsWork.track(failure); throw expected }
        }.exceptionOrNull()
        assertTrue(failure.closed.await(2, TimeUnit.SECONDS))
        // Coroutine debug stack-trace recovery may copy an exception across suspension.
        assertEquals(IllegalStateException::class.java, actual?.javaClass)
        assertEquals("feed failed", actual?.message)
        assertEquals(1, failure.closes.get())
    }

    @Test fun oneWorkerAndTwoQueuedRequestsRejectExtraActionsAndReleaseCancelledSlots() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val actions = AtomicInteger()
        val workerThread = AtomicReference<Thread>()
        val worker = async(Dispatchers.Default) {
            ToolsWork.load {
                workerThread.set(Thread.currentThread())
                started.countDown()
                awaitUninterruptibly(release)
                "first"
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            ToolsWork.load { actions.incrementAndGet(); "second" }
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            ToolsWork.load { actions.incrementAndGet(); "cancelled" }
        }
        try {
            val error = runCatching {
                withTimeout(1_000) { ToolsWork.load { actions.incrementAndGet(); "rejected" } }
            }.exceptionOrNull()
            assertTrue("Extra work must fail before running its block: $error",
                error is RejectedExecutionException)
            assertEquals(0, actions.get())
            cancelled.cancelAndJoin()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                ToolsWork.load { actions.incrementAndGet(); "replacement" }
            }
            release.countDown()
            assertEquals("first", withTimeout(2_000) { worker.await() })
            assertEquals("second", withTimeout(2_000) { queued.await() })
            assertEquals("replacement", withTimeout(2_000) { replacement.await() })
            assertEquals(2, actions.get())
            assertTrue(workerThread.get().isDaemon)
        } finally {
            release.countDown()
            worker.cancelAndJoin()
            queued.cancelAndJoin()
            cancelled.cancelAndJoin()
            drain()
        }
    }

    @Test fun toolsWorkRunsWhileThePhotoSourceWorkerIsBusy() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val photo = launch(Dispatchers.Default) {
            PhotoSourceWork.load {
                started.countDown()
                awaitUninterruptibly(release)
                "photo"
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals("tools", withTimeout(1_000) { ToolsWork.load { "tools" } })
        } finally {
            release.countDown()
            photo.cancelAndJoin()
        }
    }

    @Test fun cancellationIsRethrownBeforeSharedHttpCanStartAnotherRequest() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancellation = AtomicBoolean()
        val followUps = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                started.countDown()
                awaitUninterruptibly(proceed)
                try {
                    Http.getString("invalid URL")
                    followUps.incrementAndGet()
                } catch (_: CancellationException) {
                    cancellation.set(true)
                } finally {
                    finished.countDown()
                }
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            proceed.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            drain()
            assertTrue(cancellation.get())
            assertEquals(0, followUps.get())
        } finally {
            proceed.countDown()
            job.cancelAndJoin()
        }
    }

    private fun cancellationAfterCoordinates(load: (Context) -> Unit) = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val followUps = AtomicInteger()
        val context = Mockito.mock(Context::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer {
                if (it.getArgument<String>(0) != "immortal_weather") followUps.incrementAndGet()
                prefs
            }
        Mockito.`when`(prefs.getLong("resolved_at", 0L)).thenReturn(System.currentTimeMillis())
        Mockito.`when`(prefs.getString("lat", null)).thenReturn("53.35")
        Mockito.`when`(prefs.getString("lon", null)).thenAnswer {
            started.countDown()
            awaitUninterruptibly(proceed)
            "-6.26"
        }
        val cancellation = AtomicBoolean()
        val job = launch(Dispatchers.Default) {
            ToolsWork.load {
                try {
                    load(context)
                    followUps.incrementAndGet()
                } catch (_: CancellationException) {
                    cancellation.set(true)
                } finally {
                    finished.countDown()
                }
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            proceed.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            drain()
            assertTrue(cancellation.get())
            assertEquals(0, followUps.get())
        } finally {
            proceed.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun cancelledIssCoordinatesNeverStartOrbitLoading() =
        cancellationAfterCoordinates { IssPasses.predict(it) }

    @Test fun cancelledAuroraCoordinatesNeverStartKpFetching() =
        cancellationAfterCoordinates { Aurora.status(it) }

    @Test fun callersOutsideToolsWorkKeepOwnershipOfTheirConnections() {
        val connection = Connection()
        Thread.currentThread().interrupt()
        try {
            ToolsWork.checkpoint()
            assertSame(connection, ToolsWork.track(connection))
            assertEquals(0, connection.closes.get())
        } finally {
            Thread.interrupted()
        }
    }
}
