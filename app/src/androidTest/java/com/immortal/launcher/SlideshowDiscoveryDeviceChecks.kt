/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Real controller.start/stop and HTTP listings, using isolated preferences and loopback only. */
internal object SlideshowDiscoveryDeviceChecks {
  fun run(instrumentation: Instrumentation): JSONObject {
    val started = SystemClock.elapsedRealtime()
    val checks = JSONArray()
    val measurements = JSONObject()
    val root = File(instrumentation.targetContext.cacheDir, "source-discovery-${UUID.randomUUID()}")
    fun check(name: String, pass: Boolean) { checks.put(JSONObject().put("name", name).put("pass", pass)) }
    check("offMain", Looper.myLooper() != Looper.getMainLooper())
    check("ownedDirectory", root.mkdirs())
    try {
      for (backend in listOf("immich", "dav")) for (body in listOf(false, true)) {
        val label = backend + if (body) "Body" else "Headers"
        val fixture = StalledListing(body)
        var controller: PhotoFrameController? = null
        var stopped = false
        try {
          val prefs = FixturePreferences(
              instrumentation.targetContext.getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE),
              mapOf("source" to backend, "mode" to ScreensaverConfig.MODE_WHOLE,
                  "cache_enabled" to true, "include_video" to false,
                  "photo_motion" to false, "keep_people_in_frame" to false,
                  "immich_url" to fixture.base, "immich_key" to "synthetic-fixture-key",
                  "dav_url" to fixture.base + "/dav/", "dark_room_clock" to false))
          val storage = File(root, label).apply { mkdirs() }
          val scoped = object : ContextWrapper(instrumentation.targetContext) {
            override fun getCacheDir() = storage
            override fun getFilesDir() = storage
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "immortal_screensaver") prefs else super.getSharedPreferences(name, mode)
          }
          main {
            val frame = PhotoFrameController(scoped, photosOnly = true)
            controller = frame // Register before construction/start so partial setup is still owned.
            frame.view
            frame.start()
          }
          check("${label}RequestReachedRealAdapter", fixture.blocked.await(5, TimeUnit.SECONDS))
          check("${label}ExpectedMethodAndAuthentication", fixture.headersCorrect.get())
          val began = SystemClock.elapsedRealtime()
          main { controller!!.stop(); stopped = true }
          measurements.put("${label}MainStopRendezvousMs", SystemClock.elapsedRealtime() - began)
          val closed = fixture.peerClosed.await(2, TimeUnit.SECONDS)
          check("${label}DisconnectedBeforeReadTimeout", closed)
          measurements.put("${label}DisconnectMs", if (closed) fixture.closedAt - began else JSONObject.NULL)
          check("${label}NextDiscoveryWorkerAvailable", closed && runBlocking {
            withTimeout(3_000) { PhotoSourceWork.load { "next" } } == "next"
          })
          check("${label}NoLateRemoteAdmission", main { !(field(controller!!, "remoteMode") as Boolean) })
        } finally {
          fixture.close()
          if (!stopped) controller?.let { frame -> main { frame.stop() } }
          controller?.let { frame ->
            val executors = main {
              listOf("io", "metaIo", "transcodeIo", "photoCancelIo")
                  .map { field(frame, it) as ExecutorService }.toMutableList().also { list ->
                    val loader = field(frame, "nativeLoaderDelegate") as Lazy<*>
                    if (loader.isInitialized()) list +=
                        field(field(loader.value!!, "work"), "executor") as ExecutorService
                  }
            }
            check("${label}ControllerWorkersRetired", executors.all { it.awaitTermination(3, TimeUnit.SECONDS) })
            check("${label}PixelsReleased", main { (field(frame, "pixelBudget") as SlideshowPixelBudget).used() == 0L })
          }
          check("${label}FixtureWorkerRetired", fixture.finished.await(3, TimeUnit.SECONDS))
        }
      }
      check("NextPhotoSourceRequestUnblocked", runBlocking {
        withTimeout(3_000) { PhotoSourceWork.load { "next" } } == "next"
      })
    } catch (error: Throwable) {
      check("ProbeCompleted", false)
      measurements.put("failureClass", error.javaClass.simpleName)
    } finally {
      check("OwnedDirectoryRemoved", !root.exists() || root.deleteRecursively())
    }
    measurements.put("probeDurationMs", SystemClock.elapsedRealtime() - started)
    return JSONObject().put("pass", (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("pass") })
        .put("assertions", checks).put("measurements", measurements)
        .put("preferencesWritten", false).put("householdOrExternalMediaUsed", false)
        .put("attachedWindowOrGpuTested", false).put("sharedAlbumDiscoveryTested", false)
  }

  private fun field(value: Any, name: String): Any =
      value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)!!

  private fun <T> main(block: () -> T): T {
    val done = CountDownLatch(1)
    var result: Result<T>? = null
    val state = java.util.concurrent.atomic.AtomicInteger()
    val task = Runnable {
      if (state.compareAndSet(0, 1)) result = runCatching(block)
      done.countDown()
    }
    val handler = Handler(Looper.getMainLooper())
    handler.post(task)
    if (!done.await(5, TimeUnit.SECONDS)) {
      if (state.compareAndSet(0, 2)) handler.removeCallbacks(task)
      error("Bounded Main rendezvous expired")
    }
    return result!!.getOrThrow()
  }

  /** Read-only test snapshot: absent fields use load() defaults; writes cannot reach real prefs. */
  private class FixturePreferences(
      delegate: SharedPreferences, private val values: Map<String, Any>,
  ) : SharedPreferences by delegate {
    override fun getAll(): Map<String, *> = values
    override fun contains(key: String) = key in values
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun edit(): SharedPreferences.Editor = error("Fixture settings are read-only")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
  }

  private class StalledListing(private val body: Boolean) {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val blocked = CountDownLatch(1)
    val peerClosed = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val headersCorrect = AtomicBoolean()
    @Volatile var closedAt = 0L
    @Volatile private var socket: java.net.Socket? = null
    private val closing = AtomicBoolean()
    init {
      Thread({
        try {
          server.soTimeout = 6_000
          server.accept().use { client ->
            socket = client
            client.soTimeout = 200
            val input = client.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
              val next = input.read()
              check(next >= 0 && header.length < 16_384)
              header.append(next.toChar())
            }
            val request = header.toString()
            headersCorrect.set(
                (request.startsWith("POST /api/search/metadata ") &&
                    request.contains("x-api-key: synthetic-fixture-key", ignoreCase = true)) ||
                (request.startsWith("PROPFIND /dav/ ") && request.contains("Depth: 1", ignoreCase = true)))
            val length = Regex("(?i)content-length:\\s*(\\d+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0
            repeat(length) { check(input.read() >= 0) }
            if (body) {
              client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n{".toByteArray())
                flush()
              }
            }
            blocked.countDown()
            val until = SystemClock.elapsedRealtime() + 12_000
            while (!closing.get() && SystemClock.elapsedRealtime() < until) {
              try {
                if (input.read() < 0) {
                  closedAt = SystemClock.elapsedRealtime()
                  peerClosed.countDown()
                  break
                }
              } catch (_: SocketTimeoutException) {
              } catch (_: java.net.SocketException) {
                if (!closing.get()) {
                  closedAt = SystemClock.elapsedRealtime()
                  peerClosed.countDown()
                }
                break
              }
            }
          }
        } catch (_: Exception) {
          // Closing our fixture is cleanup, not evidence of client cancellation.
        } finally { finished.countDown() }
      }, "source-discovery-fixture").apply { isDaemon = true; start() }
    }
    fun close() {
      closing.set(true)
      runCatching { socket?.close() }
      runCatching { server.close() }
    }
  }
}
