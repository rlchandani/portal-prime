/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Blocking Tools feeds have their own bounded worker, independent of photo-source scans. */
internal object ToolsWork {
    private val io = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task ->
            Thread(task, "ImmortalToolsIO").apply { isDaemon = true }
        })
    private val cleanup = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task ->
            Thread(task, "ImmortalToolsCleanup").apply { isDaemon = true }
        }, ThreadPoolExecutor.DiscardOldestPolicy())
    private val current = ThreadLocal<Request>()

    private class Request {
        val cancelled = AtomicBoolean()
        val connections = CopyOnWriteArraySet<HttpURLConnection>()

        fun check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted)
                throw CancellationException("Tools work cancelled")
        }

        fun close() {
            connections.forEach { connection ->
                // Cancellation and finally can race. Claim before a disconnect can block.
                if (connections.remove(connection)) runCatching { connection.disconnect() }
            }
        }
    }

    /** Completion waits for cleanup; cancellation releases the caller immediately.
     * A full queue fails before [block] runs. */
    suspend fun <T> load(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request()
        val task = FutureTask<Unit> {
            current.set(request)
            try {
                val result = try {
                    request.check()
                    block()
                } finally {
                    request.close()
                }
                request.check()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                current.remove()
            }
        }
        continuation.invokeOnCancellation {
            request.cancelled.set(true)
            task.cancel(true)
            io.remove(task)
            if (request.connections.isNotEmpty()) cleanup.execute { request.close() }
        }
        if (continuation.isActive) {
            try {
                io.execute(task)
                if (!continuation.isActive) io.remove(task)
            } catch (error: RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    /** Default callers have no Tools request, so their existing blocking behavior is unchanged. */
    fun checkpoint() { current.get()?.check() }
    fun ownsCurrentRequest(): Boolean = current.get() != null

    /** Existing HTTP finally blocks share the cancellation cleanup's claim. */
    fun disconnect(connection: HttpURLConnection) {
        val request = current.get()
        if (request == null || request.connections.remove(connection)) connection.disconnect()
    }

    fun track(connection: HttpURLConnection): HttpURLConnection {
        val request = current.get() ?: return connection
        request.connections += connection
        request.check()
        return connection
    }
}
