/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Cloudflare's existing speed-test protocol with an explicit lifetime for its blocking sockets. */
internal object ApprovedToolsSpeedRunner {
  private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "ImmortalSpeedTest").apply { isDaemon = true } }

  suspend fun run(result: SpeedTest.Result, onPhase: (SpeedTest.Phase) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
    val socket = AtomicReference<HttpURLConnection?>(null)
    val future = AtomicReference<Future<*>?>(null)
    fun checkActive() { if (!continuation.isActive || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException() }
    fun open(path: String): HttpURLConnection {
      checkActive()
      val connection = URL("https://speed.cloudflare.com/$path").openConnection() as HttpURLConnection
      connection.connectTimeout = 6000
      connection.readTimeout = 8000
      socket.set(connection)
      if (!continuation.isActive) { connection.disconnect(); checkActive() }
      return connection
    }
    fun close(connection: HttpURLConnection) { socket.compareAndSet(connection, null); connection.disconnect() }
    fun phase(next: SpeedTest.Phase) { checkActive(); onPhase(next) }
    continuation.invokeOnCancellation { socket.getAndSet(null)?.disconnect(); future.get()?.cancel(true) }
    val submitted = workers.submit {
      try {
        runCatching {
          val connection = open("cdn-cgi/trace")
          try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val values = text.lineSequence().mapNotNull { line -> val split = line.indexOf('='); if (split > 0) line.substring(0, split) to line.substring(split + 1) else null }.toMap()
            checkActive()
            result.server = listOfNotNull("Cloudflare", values["colo"], values["loc"]).joinToString(" · ")
          } finally { close(connection) }
        }
        phase(SpeedTest.Phase.PING)
        runCatching {
          val samples = mutableListOf<Double>()
          repeat(6) { index ->
            val connection = open("__down?bytes=0")
            try {
              val started = System.nanoTime()
              connection.inputStream.use { it.read() }
              checkActive()
              if (index > 0) samples += (System.nanoTime() - started) / 1_000_000.0
            } finally { close(connection) }
          }
          if (samples.isNotEmpty()) {
            val average = samples.average()
            checkActive()
            result.pingMs = average
            result.jitterMs = samples.map { kotlin.math.abs(it - average) }.average()
          }
        }
        phase(SpeedTest.Phase.DOWNLOAD)
        runCatching {
          val started = System.nanoTime()
          var bytes = 0L
          var lastEmit = 0L
          val buffer = ByteArray(65_536)
          fun elapsed() = (System.nanoTime() - started) / 1_000_000_000.0
          while (elapsed() < 10.0) {
            val connection = open("__down?bytes=52428800")
            try {
              connection.inputStream.use { input ->
                while (elapsed() < 10.0) {
                  checkActive()
                  val read = input.read(buffer)
                  if (read < 0) break
                  bytes += read
                  val now = System.nanoTime()
                  if (now - lastEmit >= 200_000_000L) { checkActive(); result.downMbps = bytes * 8.0 / 1_000_000.0 / elapsed().coerceAtLeast(.001); lastEmit = now }
                }
              }
            } finally { close(connection) }
          }
          checkActive()
          result.downMbps = bytes * 8.0 / 1_000_000.0 / elapsed().coerceAtLeast(.001)
        }
        phase(SpeedTest.Phase.UPLOAD)
        runCatching {
          val connection = open("__up")
          try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setChunkedStreamingMode(0)
            val chunk = ByteArray(65_536).also { java.util.Random().nextBytes(it) }
            val started = System.nanoTime()
            var sent = 0L
            var lastEmit = 0L
            fun elapsed() = (System.nanoTime() - started) / 1_000_000_000.0
            connection.outputStream.use { output ->
              while (elapsed() < 10.0) {
                checkActive()
                output.write(chunk)
                sent += chunk.size
                val now = System.nanoTime()
                if (now - lastEmit >= 200_000_000L) { checkActive(); result.upMbps = sent * 8.0 / 1_000_000.0 / elapsed().coerceAtLeast(.001); lastEmit = now }
              }
              output.flush()
            }
            checkActive()
            result.upMbps = sent * 8.0 / 1_000_000.0 / elapsed().coerceAtLeast(.001)
            runCatching { connection.responseCode }
          } finally { close(connection) }
        }
        checkActive()
        continuation.resume(Unit)
      } catch (failure: Throwable) {
        if (continuation.isActive) continuation.resumeWithException(failure)
      } finally { socket.getAndSet(null)?.disconnect() }
    }
    future.set(submitted)
    if (!continuation.isActive) submitted.cancel(true)
  }
}
