/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSourceWorkTest {
    private class BlockingConnection : HttpURLConnection(URL("https://example.invalid")) {
        val closed = CountDownLatch(1)
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed.countDown() }
    }

    @Test fun cancellationClosesANonInterruptibleTransportAndReleasesTheNextRequest() = runBlocking {
        val connection = BlockingConnection()
        val started = CountDownLatch(1)
        val published = AtomicBoolean()
        val job = launch(Dispatchers.Default) {
            PhotoSourceWork.load {
                PhotoSourceWork.track(connection)
                started.countDown()
                // Model an old HTTP read that ignores thread interruption until disconnected.
                while (connection.closed.count > 0) {
                    try { connection.closed.await(50, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) {}
                }
                PhotoSourceWork.checkpoint()
                "obsolete"
            }
            published.set(true)
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
        assertEquals("current", withTimeout(2_000) { PhotoSourceWork.load { "current" } })
        assertTrue(!published.get())
    }

    @Test fun normalCompletionReleasesTrackedConnectionsAndSourceHandles() = runBlocking {
        val connection = BlockingConnection()
        val sourceClosed = CountDownLatch(1)
        assertEquals("image", withTimeout(2_000) {
            PhotoSourceWork.load {
                PhotoSourceWork.track(connection)
                PhotoSourceWork.onClose { sourceClosed.countDown() }
                "image"
            }
        })
        assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
        assertTrue(sourceClosed.await(2, TimeUnit.SECONDS))
    }

    @Test fun cancellationOwnsTheSocketBeforeSmbSetupFinishes() = runBlocking {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val timeout = AtomicInteger()
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeoutMillis: Int) {
                timeout.set(timeoutMillis)
                started.countDown()
                while (closed.count > 0) {
                    try { closed.await(50, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) {}
                }
            }
            override fun setSoTimeout(value: Int) {}
            override fun close() { closed.countDown() }
        }
        val job = launch(Dispatchers.Default) {
            PhotoSourceWork.load {
                PhotoSourceSocketFactory { socket }.createSocket(InetAddress.getLoopbackAddress(), 445)
                "obsolete"
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(8_000, timeout.get())
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals("next", withTimeout(2_000) { PhotoSourceWork.load { "next" } })
    }
}
