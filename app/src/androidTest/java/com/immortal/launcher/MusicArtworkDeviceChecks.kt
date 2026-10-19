/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Native artwork acceptance only: real shipped loaders/codecs/cache, synthetic test-APK
 * images and loopback HTTP. No UI, settings, household media, cancellation or audio proof.
 * Assets are encoded off-device; never allocate a source-sized 4096 px bitmap here.
 */
internal object MusicArtworkDeviceChecks {
  private const val BODY_LIMIT = 8 * 1024 * 1024
  private const val HEADER_LIMIT = 32 * 1024
  private const val CACHE_LIMIT = 16 * 1024 * 1024
  private const val RUN_MS = 90_000L
  private const val SOCKET_MS = 6_000L

  fun run(probe: Instrumentation): Bundle {
    val report = Bundle().apply {
      putBoolean("musicArtworkCompleted", false)
      putBoolean("runtimeDecodeCacheProof", false)
      putBoolean("cancellationProof", false)
      putBoolean("runtimeAudioProof", false)
      putInt("payloadLimitBytes", BODY_LIMIT)
      putInt("overflowPayloadBytes", BODY_LIMIT + 1)
      putInt("eofOverflowProbeBytes", 1)
      putInt("headerLimitBytes", HEADER_LIMIT)
      putLong("runDeadlineMs", RUN_MS)
      putLong("socketDeadlineMs", SOCKET_MS)
    }
    val began = SystemClock.elapsedRealtime()
    var stage = 0
    var root: File? = null
    var server: FixtureServer? = null
    var cache: LruCache<Any, Bitmap>? = null
    val syntheticKeys = mutableListOf<Any>()
    var assertions = 0
    var passed = false
    fun demand(key: String, condition: Boolean) {
      report.putBoolean(key, condition)
      assertions++
      check(condition)
    }
    fun before(nextStage: Int) {
      stage = nextStage
      check(SystemClock.elapsedRealtime() - began < RUN_MS)
    }
    fun dimensions(prefix: String, bitmap: Bitmap?, edge: Int): Bitmap {
      demand("${prefix}Decoded", bitmap != null)
      val value = requireNotNull(bitmap)
      report.putInt("${prefix}Width", value.width)
      report.putInt("${prefix}Height", value.height)
      report.putInt("${prefix}AllocationBytes", value.allocationByteCount)
      demand("${prefix}Bounded", value.width in 1..edge && value.height in 1..edge &&
          value.allocationByteCount.toLong() <= edge.toLong() * edge * 4)
      return value
    }
    try {
      before(1)
      demand("offMainThread", Looper.myLooper() != Looper.getMainLooper())
      val high = probe.context.assets.open("music-artwork/high-4096.png").use { it.readBytes() }
      val covers = (0..7).map {
        probe.context.assets.open("music-artwork/cover-$it.png").use { input -> input.readBytes() }
      }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(high, 0, high.size, bounds)
      report.putInt("sourceWidth", bounds.outWidth)
      report.putInt("sourceHeight", bounds.outHeight)
      report.putInt("sourceEncodedBytes", high.size)
      demand("highResolutionFixture", bounds.outWidth == 4096 && bounds.outHeight == 4096)
      // Only create/read/delete our UUID directory; instrumentation runs with target UID.
      val directory = File(probe.targetContext.cacheDir, "music-artwork-${UUID.randomUUID()}")
      root = directory
      check(directory.mkdirs())
      val local = File(directory, "high.png").apply { writeBytes(high) }
      before(2)
      dimensions("local384", MediaArt.resolveUri(probe.targetContext, Uri.fromFile(local).toString()), 384)

      before(3)
      val fixtureServer = FixtureServer(high, covers)
      server = fixtureServer
      // Inspect only accounting and keys we construct ourselves; never snapshot cache contents.
      val cacheField = MultiRoomArt::class.java.getDeclaredField("cache").apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST")
      val actualCache = cacheField.get(MultiRoomArt) as LruCache<Any, Bitmap>
      cache = actualCache
      val keyClass = Class.forName("com.immortal.launcher.MultiRoomArt\$ArtKey")
      val keyConstructor = keyClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
          .apply { isAccessible = true }
      fun ownKey(path: String, edge: Int): Any =
          keyConstructor.newInstance(fixtureServer.url(path), edge).also { syntheticKeys.add(it) }
      ownKey("/high-thumb", 384)
      ownKey("/high-cover", 1024)
      report.putInt("cacheMaxBytes", actualCache.maxSize())
      demand("cacheConfiguredWithin16MiB", actualCache.maxSize() in 1..CACHE_LIMIT)
      fixtureServer.start()
      dimensions("http384", MediaArt.resolveUri(probe.targetContext, fixtureServer.url("/high-thumb")), 384)
      dimensions("http1024", MultiRoomArt.fetch(fixtureServer.url("/high-cover"), 4096), 1024)
      demand("httpHighRequested", fixtureServer.requests("/high-cover") == 1)

      before(4)
      // A valid PNG prefix + padding is decoded at the exact payload cap. This control makes
      // the subsequent rejection attributable to transport bounds, not an invalid image.
      ownKey("/exact-eof", 384)
      dimensions("exactEof", MultiRoomArt.fetch(fixtureServer.url("/exact-eof")), 384)
      report.putInt("exactEofPayloadBytes", BODY_LIMIT)
      ownKey("/exact-chunked", 384)
      dimensions("exactChunked", MultiRoomArt.fetch(fixtureServer.url("/exact-chunked")), 384)
      report.putInt("exactChunkedPayloadBytes", BODY_LIMIT)
      report.putInt("exactChunkFramingBytes", BODY_LIMIT.toString(16).length + 9)
      report.putInt("exactChunkHttpHeaderBytes", fixtureServer.chunkHeaderBytes)

      before(5)
      for (path in listOf("/oversized-eof", "/oversized-length", "/oversized-chunk", "/oversized-header")) {
        ownKey(path, 384)
      }
      demand("oversizedEofRejected", MultiRoomArt.fetch(fixtureServer.url("/oversized-eof")) == null)
      demand("oversizedDeclaredBodyRejected",
          MultiRoomArt.fetch(fixtureServer.url("/oversized-length")) == null)
      demand("oversizedChunkBodyRejected",
          MultiRoomArt.fetch(fixtureServer.url("/oversized-chunk")) == null)
      demand("oversizedHeaderRejected", MultiRoomArt.fetch(fixtureServer.url("/oversized-header")) == null)
      for (path in listOf("/oversized-eof", "/oversized-length", "/oversized-chunk", "/oversized-header")) {
        demand("rejectionRequest${assertions}", fixtureServer.requests(path) == 1)
      }
      report.putInt("oversizedHeaderWireBytes", fixtureServer.oversizedHeaderBytes)
      report.putInt("eofHttpHeaderBytes", fixtureServer.eofHeaderBytes)
      report.putBoolean("payloadExcludesHttpHeadersAndChunkFraming", true)
      val oversizedLocal = File(directory, "oversized.png")
      oversizedLocal.outputStream().use { writePayload(it, high, BODY_LIMIT + 1) }
      demand("oversizedLocalRejected",
          MediaArt.resolveUri(probe.targetContext, Uri.fromFile(oversizedLocal).toString()) == null)

      before(6)
      val evictionStart = actualCache.evictionCount()
      var peakBytes = actualCache.size()
      var insertedBytes = 0L
      var first: Bitmap? = null
      var firstKey: Any? = null
      var last: Bitmap? = null
      val colors = mutableSetOf<Int>()
      for (index in covers.indices) {
        before(6)
        val path = "/cover-$index"
        val key = ownKey(path, 1024)
        val bitmap = dimensions("cacheCover$index", MultiRoomArt.fetch(fixtureServer.url(path), 1024), 1024)
        demand("cacheCover${index}FullSize", bitmap.width == 1024 && bitmap.height == 1024)
        demand("cacheCover${index}Stored", actualCache.get(key) === bitmap)
        colors.add(bitmap.getPixel(0, 0))
        if (index == 0) { first = bitmap; firstKey = key }
        last = bitmap
        insertedBytes += bitmap.allocationByteCount
        peakBytes = maxOf(peakBytes, actualCache.size())
        demand("cacheCover${index}Budget", actualCache.size() in 0..CACHE_LIMIT &&
            actualCache.size() <= actualCache.maxSize())
      }
      report.putInt("distinctCovers", colors.size)
      report.putLong("insertedCoverAllocationBytes", insertedBytes)
      report.putInt("cachePeakBytes", peakBytes)
      report.putInt("cacheEvictions", actualCache.evictionCount() - evictionStart)
      demand("eightDistinctDecodedCovers", colors.size == covers.size)
      demand("insertionsExceedCacheBudget", insertedBytes > actualCache.maxSize())
      demand("oldestSyntheticCoverEvicted", actualCache.get(requireNotNull(firstKey)) == null)
      demand("evictionObserved", actualCache.evictionCount() > evictionStart)
      demand("evictionDoesNotRecycleBorrowedBitmap", first?.isRecycled == false)
      val lastRequests = fixtureServer.requests("/cover-7")
      demand("cacheHitReturnsSharedBitmap", MultiRoomArt.fetch(fixtureServer.url("/cover-7"), 1024) === last)
      demand("cacheHitAvoidsRequest", fixtureServer.requests("/cover-7") == lastRequests)
      val oldRequests = fixtureServer.requests("/cover-0")
      dimensions("evictedReload", MultiRoomArt.fetch(fixtureServer.url("/cover-0"), 1024), 1024)
      demand("evictedCoverReloaded", fixtureServer.requests("/cover-0") == oldRequests + 1)
      demand("reloadWithinCacheBudget", actualCache.size() <= actualCache.maxSize() &&
          actualCache.size() <= CACHE_LIMIT)
      demand("successfulResponsesFullySent", fixtureServer.successfulWriteFailures.get() == 0)
      demand("noSocketDeadlineExpired", fixtureServer.socketDeadlinesExpired.get() == 0)
      demand("serverLifetimeDidNotExpire", !fixtureServer.lifetimeExpired.get())
      report.putBoolean("runtimeDecodeCacheProof", true)
      passed = true
    } catch (_: Throwable) {
      // Numeric stage only: no exception text, URLs, file paths, cache keys or private diagnostics.
      report.putInt("failureStage", stage)
    } finally {
      // Remove only known synthetic entries. Shared bitmaps are never recycled, even on failure.
      var cacheCleaned = true
      for (key in syntheticKeys) {
        try { cache?.remove(key) } catch (_: Throwable) { cacheCleaned = false }
      }
      report.putBoolean("syntheticCacheEntriesRemoved", cacheCleaned)
      report.putBoolean("serverThreadsStopped", try { server?.close() ?: true } catch (_: Throwable) { false })
      report.putInt("socketDeadlinesExpired", server?.socketDeadlinesExpired?.get() ?: 0)
      report.putBoolean("serverLifetimeExpired", server?.lifetimeExpired?.get() ?: false)
      report.putBoolean("syntheticFilesRemoved", try { root?.deleteRecursively() ?: true } catch (_: Throwable) { false })
      report.putInt("assertionCount", assertions)
      report.putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
      report.putBoolean("musicArtworkCompleted", passed &&
          report.getBoolean("syntheticCacheEntriesRemoved") &&
          report.getBoolean("serverThreadsStopped") && report.getBoolean("syntheticFilesRemoved") &&
          report.getInt("socketDeadlinesExpired") == 0 && !report.getBoolean("serverLifetimeExpired"))
    }
    return report
  }

  /** Stream padding in 8 KiB blocks; no body-sized fixture allocation in the server. */
  private fun writePayload(output: OutputStream, png: ByteArray, bytes: Int) {
    require(bytes >= png.size)
    output.write(png)
    val padding = ByteArray(8192)
    var remaining = bytes - png.size
    while (remaining > 0) {
      val count = minOf(remaining, padding.size)
      output.write(padding, 0, count)
      remaining -= count
    }
  }

  private class FixtureServer(private val high: ByteArray, private val covers: List<ByteArray>) {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    private val active = AtomicReference<Socket?>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "music-artwork-deadlines").apply { isDaemon = true }
    }
    val successfulWriteFailures = AtomicInteger()
    val socketDeadlinesExpired = AtomicInteger()
    val lifetimeExpired = java.util.concurrent.atomic.AtomicBoolean()
    private val eofHeader = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nConnection: close\r\n\r\n"
    private val chunkHeader = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
    private val oversizedHeader = "HTTP/1.1 200 OK\r\nX-Synthetic: " + "a".repeat(HEADER_LIMIT) +
        "\r\nContent-Length: ${covers[0].size}\r\nConnection: close\r\n\r\n"
    val eofHeaderBytes get() = eofHeader.length
    val chunkHeaderBytes get() = chunkHeader.length
    val oversizedHeaderBytes get() = oversizedHeader.length
    private val worker = Thread({ serve() }, "music-artwork-loopback").apply { isDaemon = true }

    fun url(path: String) = "http://127.0.0.1:${listener.localPort}$path"
    fun requests(path: String) = counts[path]?.get() ?: 0
    fun start() {
      deadlines.schedule({ lifetimeExpired.set(true); stopSockets() }, RUN_MS, TimeUnit.MILLISECONDS)
      worker.start()
    }
    private fun stopSockets() {
      runCatching { listener.close() }
      runCatching { active.getAndSet(null)?.close() }
    }
    fun close(): Boolean {
      stopSockets()
      deadlines.shutdownNow()
      worker.join(2_000)
      return !worker.isAlive && deadlines.awaitTermination(2_000, TimeUnit.MILLISECONDS)
    }
    private fun serve() {
      var accepted = 0
      while (!listener.isClosed && accepted < 32) {
        val socket = try { listener.accept() } catch (_: java.net.SocketTimeoutException) {
          continue
        } catch (_: Exception) { break }
        accepted++
        active.set(socket)
        val deadline = try {
          deadlines.schedule({
            socketDeadlinesExpired.incrementAndGet()
            runCatching { socket.close() }
          }, SOCKET_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
          runCatching { socket.close() }
          break
        }
        var valid = false
        try {
          socket.use {
            it.soTimeout = 2_000
            val path = readRequest(it.getInputStream())
            counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val output = it.getOutputStream()
            fun header(value: String) { output.write(value.toByteArray(Charsets.ISO_8859_1)) }
            when (path) {
              "/exact-eof", "/oversized-eof" -> {
                valid = path == "/exact-eof"
                header(eofHeader)
                writePayload(output, covers[0], BODY_LIMIT + if (valid) 0 else 1)
              }
              "/exact-chunked", "/oversized-chunk" -> {
                valid = path == "/exact-chunked"
                header(chunkHeader)
                header((BODY_LIMIT + if (valid) 0 else 1).toString(16) + "\r\n")
                // Offer the entire valid PNG + padding, so truncation cannot mimic rejection.
                // A correct loader closes early; the server tolerates that synthetic write failure.
                writePayload(output, covers[0], BODY_LIMIT + if (valid) 0 else 1)
                header("\r\n0\r\n\r\n")
              }
              "/oversized-length" -> {
                header("HTTP/1.1 200 OK\r\nContent-Length: ${BODY_LIMIT + 1}\r\nConnection: close\r\n\r\n")
                writePayload(output, covers[0], BODY_LIMIT + 1)
              }
              "/oversized-header" -> { header(oversizedHeader); output.write(covers[0]) }
              else -> {
                val png = when (path) {
                  "/high-thumb", "/high-cover" -> high
                  else -> covers[path.removePrefix("/cover-").toInt()]
                }
                valid = true
                header("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n")
                output.write(png)
              }
            }
            output.flush()
          }
        } catch (_: Exception) {
          if (valid) successfulWriteFailures.incrementAndGet()
        } finally {
          deadline.cancel(false)
          active.compareAndSet(socket, null)
          runCatching { socket.close() }
        }
      }
    }
    private fun readRequest(input: InputStream): String {
      val buffer = StringBuilder()
      val until = SystemClock.elapsedRealtime() + 2_000
      while (buffer.length < 4096 && SystemClock.elapsedRealtime() < until) {
        val byte = input.read()
        check(byte >= 0)
        buffer.append(byte.toChar())
        if (buffer.endsWith("\r\n\r\n")) {
          val first = buffer.toString().substringBefore("\r\n").split(' ')
          check(first.size == 3 && first[0] == "GET")
          return first[1]
        }
      }
      error("Synthetic request limit")
    }
  }
}
