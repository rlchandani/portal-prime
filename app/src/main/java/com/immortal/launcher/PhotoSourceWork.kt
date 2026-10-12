/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.net.SocketFactory

/** Home owns one blocking scan. Cancellation releases its coroutine immediately and closes IO. */
internal object PhotoSourceWork {
    private val io = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
    private val cleanup = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), ThreadPoolExecutor.DiscardOldestPolicy())
    private val current = ThreadLocal<Request>()

    private class Request {
        val cancelled = AtomicBoolean()
        val connections = CopyOnWriteArraySet<HttpURLConnection>()
        val closers = CopyOnWriteArraySet<() -> Unit>()

        fun check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted)
                throw InterruptedIOException("Photo source cancelled")
        }

        fun close() {
            connections.forEach { runCatching { it.disconnect() } }
            connections.clear()
            // Close the raw socket before protocol teardown can wait for a reply.
            closers.toList().asReversed().forEach { runCatching { it() } }
            closers.clear()
        }
    }

    /** Only immutable in-memory snapshots may be checked outside the blocking photo worker. */
    suspend fun <T> cachedOrLoad(cached: () -> T?, block: () -> T?): T? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val snapshot = cached()
        caller.ensureActive()
        return snapshot ?: load(block)
    }

    suspend fun <T> load(block: () -> T?): T? = suspendCancellableCoroutine { continuation ->
        val request = Request()
        val task = FutureTask<Unit> {
            current.set(request)
            try {
                request.check()
                val result = block()
                request.check()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                current.remove()
                request.close()
            }
        }
        continuation.invokeOnCancellation {
            request.cancelled.set(true)
            task.cancel(true)
            io.remove(task)
            if (request.connections.isNotEmpty() || request.closers.isNotEmpty())
                cleanup.execute { request.close() }
        }
        if (continuation.isActive) {
            try {
                io.execute(task)
                if (!continuation.isActive) io.remove(task)
            } catch (_: RejectedExecutionException) {
                // Rapid source changes retain the previous image and retry later.
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    fun checkpoint() { current.get()?.check() }
    fun ownsCurrentRequest(): Boolean = current.get() != null

    fun track(connection: HttpURLConnection): HttpURLConnection {
        val request = current.get() ?: return connection
        request.connections += connection
        request.check()
        return connection
    }

    fun onClose(close: () -> Unit) {
        current.get()?.let { request ->
            request.closers += close
            request.check()
        }
    }
}

/** Other callers keep their existing behavior; a Home request registers transport ownership. */
internal fun URL.openPhotoSourceConnection(): HttpURLConnection =
    PhotoSourceWork.track(openConnection() as HttpURLConnection)

/** Register the unconnected SMB socket before its blocking connect/authentication begins. */
internal class PhotoSourceSocketFactory(private val newSocket: () -> Socket = { Socket() }) : SocketFactory() {
    override fun createSocket(): Socket = newSocket().also { socket ->
        PhotoSourceWork.onClose { socket.close() }
    }

    private fun connected(remote: SocketAddress, local: SocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            local?.let { socket.bind(it) }
            socket.connect(remote, 8_000)
            socket.soTimeout = 8_000
            PhotoSourceWork.checkpoint()
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
}
