/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The photo frame's HTTP transports. Every request belongs to a [generation]; cancelling bumps it
 * synchronously (so late answers are dropped) and closes the open connections and streams on
 * [cancelIo], away from the UI thread. [stopped] is the frame's own stop flag.
 */
internal class PhotoFrameTransport(private val stopped: () -> Boolean) {
  val generation = AtomicLong()
  private val lock = Any()
  private val connections = HashSet<HttpURLConnection>()
  private val streams = HashSet<InputStream>()
  /** Physical closes, serialized off the UI thread; also used by the engine's transports. */
  val cancelIo = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "slideshow-close-transports").apply { isDaemon = true }
  }
  /** The video prefetch's open download, so suspending the host can cut it. */
  @Volatile private var videoPrefetch: HttpURLConnection? = null
  /** The prefetch download [cancelVideoPrefetch] cut: its stream may end in a clean EOF, which is not a whole file. */
  @Volatile private var cutPrefetch: HttpURLConnection? = null

  /** A request of [generation] is still wanted: not stopped, not cancelled, not interrupted. */
  fun current(generation: Long): Boolean =
      !stopped() && generation == this.generation.get() && !Thread.currentThread().isInterrupted

  /** Tracks [input] for cancellation while [current]; false (not tracked) once superseded. */
  fun register(input: InputStream, current: () -> Boolean): Boolean =
      synchronized(lock) { if (!current()) false else { streams.add(input); true } }

  fun unregister(input: InputStream) { synchronized(lock) { streams.remove(input) } }

  /** Epoch invalidation is synchronous; physical closes are serialized away from the UI. */
  fun cancelAll() {
    val closing = synchronized(lock) {
      generation.incrementAndGet()
      val snapshot = connections.toList() to streams.toList()
      connections.clear()
      streams.clear()
      snapshot
    }
    if (closing.first.isEmpty() && closing.second.isEmpty()) return
    cancelIo.execute {
      closing.first.forEach { runCatching { it.disconnect() } }
      closing.second.forEach { runCatching { it.close() } }
    }
  }

  fun cancelVideoPrefetch() {
    videoPrefetch?.let { connection ->
      cutPrefetch = connection
      cancelIo.execute { runCatching { connection.disconnect() } }
    }
  }

  fun <T> withConnection(
      spec: String, headers: Map<String, String>, timeout: Int, generation: Long,
      read: (InputStream) -> T,
  ): T {
    if (!current(generation)) throw InterruptedIOException("Photo request canceled")
    val c = PhotoSourceRedirects.open(spec, headers, readTimeoutMs = timeout,
        configure = { it.setRequestProperty("User-Agent", PhotoFrameController.USER_AGENT) },
        opened = { connection ->
          synchronized(lock) {
            if (!current(generation))
              throw InterruptedIOException("Photo request canceled")
            connections.add(connection)
          }
        },
        closed = { connection ->
          synchronized(lock) { connections.remove(connection) }
        })
    var input: InputStream? = null
    try {
      val stream = c.inputStream
      input = stream
      synchronized(lock) {
        if (!current(generation)) throw InterruptedIOException("Photo request canceled")
        streams.add(stream)
      }
      return read(stream)
    } finally {
      synchronized(lock) {
        connections.remove(c)
        input?.let { streams.remove(it) }
      }
      runCatching { c.disconnect() }
      runCatching { input?.close() }
    }
  }

  fun httpGet(spec: String, generation: Long): String =
      withConnection(spec, emptyMap(), 8000, generation) { input ->
        val bytes = SlideshowDecoder.readEncoded(input, 2 * 1024 * 1024)
            ?: throw java.io.IOException("Photo manifest unavailable")
        if (!current(generation)) throw InterruptedIOException("Photo request canceled")
        bytes.toString(Charsets.UTF_8)
      }

  /** A decoded download; a photo that lands after its request was superseded goes to [discard]. */
  fun <B : Any> download(
      spec: String, headers: Map<String, String>, generation: Long,
      decode: (InputStream) -> B?, discard: (B) -> Unit,
  ): B? = withConnection(spec, headers, 12000, generation) { input ->
    val bitmap = decode(input)
    if (current(generation)) bitmap else { bitmap?.let(discard); null }
  }

  /** Raw bytes of an authed HTTP GET (for caching before decode). Null on failure. */
  fun downloadBytes(spec: String, headers: Map<String, String>, generation: Long): ByteArray? =
      withConnection(spec, headers, 12000, generation) { input ->
        SlideshowDecoder.readEncoded(input)?.takeIf { current(generation) }
      }

  /** Stream an authed HTTP GET straight to [dst] (the video prefetch) while [keepGoing]. */
  fun downloadToFile(spec: String, headers: Map<String, String>, dst: java.io.File, keepGoing: () -> Boolean): Boolean =
      runCatching {
            if (!keepGoing()) return@runCatching false
            val c = PhotoSourceRedirects.open(spec, headers, readTimeoutMs = 20000,
                configure = { it.setRequestProperty("User-Agent", PhotoFrameController.USER_AGENT) },
                opened = { connection ->
                  videoPrefetch = connection
                  if (!keepGoing())
                    throw InterruptedIOException("Video prefetch canceled")
                },
                closed = { connection ->
                  if (videoPrefetch === connection) videoPrefetch = null
                })
            try {
              val declared = c.contentLengthLong // unknown (-1) or 0 trusts the clean EOF
              val copied = c.inputStream.use { input ->
                dst.outputStream().use { output -> copyWhile(input, output, keepGoing) }
              }
              if (copied != null && declared > 0L && copied != declared) {
                // The server closed early (or overran): a partial video must never be cached.
                dst.delete()
                return@runCatching false
              }
              copied != null && copied > 0L && cutPrefetch !== c && keepGoing()
            } finally {
              if (videoPrefetch === c) videoPrefetch = null
              if (cutPrefetch === c) cutPrefetch = null
              c.disconnect()
            }
          }
          .getOrDefault(false)

  /** Bytes copied up to a clean EOF, or null when [keepGoing] or an interrupt stopped it first. */
  private fun copyWhile(input: InputStream, output: java.io.OutputStream, keepGoing: () -> Boolean): Long? {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (keepGoing() && !Thread.currentThread().isInterrupted) {
      val count = input.read(buffer)
      if (count < 0) return total
      output.write(buffer, 0, count)
      total += count
    }
    return null
  }

  /** Drain the owned closes; never wait for a network read on the UI thread. */
  fun shutdown() = cancelIo.shutdown()
}
