/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Real artwork API probe. Only generated PNGs, loopback sockets and its own JSON receipt. */
class ArtworkCacheCheckActivity : Activity() {
  private lateinit var status: TextView
  private var coordinator: Thread? = null
  @Volatile private var server: LocalArtServer? = null
  @Volatile private var workers: ExecutorService? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    status = TextView(this).apply {
      text = "Artwork cache: checking a warm fetch during a held localhost response"
      textSize = 20f
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.rgb(28, 27, 25))
      setPadding(24, 24, 24, 24)
    }
    setContentView(status)
    val runId = intent.getStringExtra("run_id")
        ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        ?: UUID.randomUUID().toString().replace("-", "")
    coordinator = Thread({ runChecks(runId) }, "ArtworkCacheCheck").apply {
      isDaemon = true
      start()
    }
  }

  override fun onDestroy() {
    server?.close()
    workers?.shutdownNow()
    coordinator?.interrupt()
    super.onDestroy()
  }

  private fun runChecks(runId: String) {
    val started = SystemClock.elapsedRealtime()
    val testDeadline = started + 6_500
    val totalDeadline = started + 8_000
    val checks = JSONArray()
    val failures = ConcurrentLinkedQueue<JSONObject>()
    val report = JSONObject()
        .put("complete", false).put("success", false).put("runId", runId)
        .put("api", Build.VERSION.SDK_INT).put("localhostOnly", true)
        .put("syntheticPngOnly", true).put("maxWaitMs", 8_000)
        .put("warmWaitLimitMs", 1_200).put("checks", checks)
    var local: LocalArtServer? = null
    var executor: ExecutorService? = null
    fun demand(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
    }
    fun remaining(deadline: Long, cap: Long = Long.MAX_VALUE): Long =
        minOf(cap, deadline - SystemClock.elapsedRealtime()).also {
          if (it <= 0) throw TimeoutException()
        }
    fun <T> result(future: Future<T>, cap: Long = Long.MAX_VALUE): T =
        future.get(remaining(testDeadline, cap), TimeUnit.MILLISECONDS)

    try {
      // Older Android versions do not support the per-process Application override.
      check(Build.VERSION.SDK_INT == 34 && application.javaClass == Application::class.java)
      demand("isolated_api34_base_application", true)
      val source = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
      val png = try {
        source.eraseColor(Color.rgb(40, 140, 210))
        ByteArrayOutputStream().use {
          check(source.compress(Bitmap.CompressFormat.PNG, 100, it))
          it.toByteArray()
        }
      } finally {
        source.recycle()
      }
      report.put("sourceWidth", 768).put("sourceHeight", 512).put("pngBytes", png.size)
      val pool = Executors.newCachedThreadPool { task ->
        Thread(task, "ArtworkCacheWorker").apply { isDaemon = true }
      }
      executor = pool
      workers = pool
      val origin = LocalArtServer(pool, png, runId, testDeadline, failures)
      local = origin
      server = origin
      origin.start()
      fun fetch(stage: String, url: String): Future<Bitmap?> = pool.submit(Callable {
        try {
          MultiRoomArt.fetch(url)
        } catch (failure: Throwable) {
          failures.add(workerFailure(stage, failure))
          throw failure
        }
      })

      val initial = checkNotNull(result(fetch("initial_warm_fetch", origin.warmUrl)))
      demand("initial_warm_fetch_success", !initial.isRecycled)
      demand("warm_url_requested_once_before_cold_fetch", origin.warmRequests.get() == 1)
      val cold = fetch("cold_fetch", origin.coldUrl)
      check(origin.bodyHeld.await(remaining(testDeadline, 2_000), TimeUnit.MILLISECONDS))
      demand("cold_fetch_has_open_socket_and_held_body",
          origin.bodyIsHeld() && !cold.isDone)
      check(origin.bodyIsHeld() && !cold.isDone)

      val warmStarted = CountDownLatch(1)
      val warm = pool.submit(Callable {
        warmStarted.countDown()
        try {
          MultiRoomArt.fetch(origin.warmUrl)
        } catch (failure: Throwable) {
          failures.add(workerFailure("cached_warm_fetch", failure))
          throw failure
        }
      })
      check(warmStarted.await(remaining(testDeadline, 500), TimeUnit.MILLISECONDS))
      val warmStartedAt = SystemClock.elapsedRealtime()
      var early: Bitmap? = null
      var completedBeforeRelease = false
      try {
        early = result(warm, 1_200)
        completedBeforeRelease = origin.bodyIsHeld() && !cold.isDone
      } catch (_: TimeoutException) {
        // Expected failure on the old global load monitor. Still finish the cold check.
      } finally {
        report.put("warmWaitMs", SystemClock.elapsedRealtime() - warmStartedAt)
        demand("warm_fetch_completed_before_cold_body_release", completedBeforeRelease)
        demand("warm_fetch_before_release_has_same_bitmap_identity",
            completedBeforeRelease && early === initial)
        origin.releaseBody.countDown()
      }

      val downloaded = result(cold)
      val cached = result(warm)
      check(origin.coldResponseFinished.await(remaining(testDeadline, 500), TimeUnit.MILLISECONDS))
      demand("warm_fetch_eventually_has_same_bitmap_identity", cached === initial)
      demand("cold_fetch_eventually_success", downloaded != null && !downloaded.isRecycled)
      demand("cold_bitmap_dimensions_at_most_384",
          downloaded != null && downloaded.width in 1..384 && downloaded.height in 1..384)
      report.put("coldWidth", downloaded?.width ?: 0).put("coldHeight", downloaded?.height ?: 0)
      demand("warm_url_one_network_request", origin.warmRequests.get() == 1)
      demand("cold_url_one_network_request", origin.coldRequests.get() == 1)
      demand("cold_server_sent_complete_png", origin.coldBodySent.get())
    } catch (failure: Throwable) {
      report.put("failure", failureKind(failure))
      demand("fixture_completed_without_coordinator_exception", false)
    } finally {
      // A failed assertion, interruption or deadline must unblock the real network reader.
      Thread.interrupted()
      local?.close()
      executor?.shutdownNow()
      val cleanupTime = (totalDeadline - SystemClock.elapsedRealtime()).coerceIn(0, 1_000)
      val terminated = try {
        executor?.awaitTermination(cleanupTime, TimeUnit.MILLISECONDS) ?: true
      } catch (_: InterruptedException) {
        Thread.interrupted()
        false
      }
      demand("executors_terminated", terminated)
      demand("all_localhost_sockets_closed", local?.isClosed() ?: true)
      demand("worker_exceptions_absent", failures.isEmpty())
      report.put("workerFailures", JSONArray(failures.toList()))
          .put("warmRequests", local?.warmRequests?.get() ?: 0)
          .put("coldRequests", local?.coldRequests?.get() ?: 0)
          .put("elapsedMs", SystemClock.elapsedRealtime() - started)
      demand("total_wait_budget_at_most_8_seconds",
          SystemClock.elapsedRealtime() <= totalDeadline)
      val passed = (0 until checks.length()).count { checks.getJSONObject(it).getBoolean("passed") }
      report.put("passed", passed).put("total", checks.length())
          .put("complete", true).put("success", passed == checks.length())
      server = null
      workers = null
      try {
        val receipt = AtomicFile(File(filesDir, "artwork-cache-check.json"))
        val output = receipt.startWrite()
        try {
          output.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
          receipt.finishWrite(output)
        } catch (failure: Throwable) {
          receipt.failWrite(output)
          throw failure
        }
        runOnUiThread {
          if (!isFinishing && !isDestroyed) {
            status.text = "${if (report.getBoolean("success")) "PASS" else "FAIL"}: " +
                "$passed/${checks.length()} artwork cache checks\n" +
                "Receipt: files/artwork-cache-check.json"
          }
        }
      } catch (_: Throwable) {
        runOnUiThread {
          if (!isFinishing && !isDestroyed) status.text = "FAIL: artwork cache receipt write"
        }
      }
    }
  }

  private class LocalArtServer(
      private val executor: ExecutorService,
      private val png: ByteArray,
      runId: String,
      private val deadline: Long,
      private val failures: ConcurrentLinkedQueue<JSONObject>,
  ) {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
      soTimeout = 250
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val stopping = AtomicBoolean(false)
    private val heldSocket = java.util.concurrent.atomic.AtomicReference<Socket?>()
    private val warmPath = "/warm-$runId.png"
    private val coldPath = "/cold-$runId.png"
    val warmUrl = "http://127.0.0.1:${listener.localPort}$warmPath"
    val coldUrl = "http://127.0.0.1:${listener.localPort}$coldPath"
    val warmRequests = AtomicInteger()
    val coldRequests = AtomicInteger()
    val bodyHeld = CountDownLatch(1)
    val releaseBody = CountDownLatch(1)
    val coldBodySent = AtomicBoolean(false)
    val coldResponseFinished = CountDownLatch(1)

    fun start() {
      executor.submit {
        try {
          while (!stopping.get() && SystemClock.elapsedRealtime() < deadline) {
            val socket = try {
              listener.accept()
            } catch (_: SocketTimeoutException) {
              continue
            }
            sockets.add(socket)
            if (stopping.get()) {
              socket.close()
              sockets.remove(socket)
              break
            }
            executor.submit { serve(socket) }
          }
        } catch (failure: Throwable) {
          if (!stopping.get()) failures.add(workerFailure("localhost_accept", failure))
        }
      }
    }

    private fun serve(socket: Socket) {
      try {
        socket.use {
          check(socket.inetAddress.isLoopbackAddress)
          socket.soTimeout = 1_000
          val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
          val request = checkNotNull(reader.readLine())
          check(request.length < 256)
          var headerBytes = 0
          while (true) {
            val header = checkNotNull(reader.readLine())
            headerBytes += header.length
            check(headerBytes <= 4_096)
            if (header.isEmpty()) break
          }
          val cold = when (request) {
            "GET $warmPath HTTP/1.1" -> { warmRequests.incrementAndGet(); false }
            "GET $coldPath HTTP/1.1" -> { coldRequests.incrementAndGet(); true }
            else -> error("unexpected_localhost_path")
          }
          val output = socket.getOutputStream()
          val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
              "Content-Length: ${png.size}\r\nConnection: close\r\n\r\n"
          output.write(headers.toByteArray(Charsets.ISO_8859_1))
          output.write(png, 0, 1)
          output.flush()
          if (cold) {
            heldSocket.set(socket)
            bodyHeld.countDown()
            val waitMs = deadline - SystemClock.elapsedRealtime()
            check(waitMs > 0 && releaseBody.await(waitMs, TimeUnit.MILLISECONDS))
          }
          output.write(png, 1, png.size - 1)
          output.flush()
          if (cold) {
            coldBodySent.set(true)
            coldResponseFinished.countDown()
          }
        }
      } catch (failure: Throwable) {
        if (!stopping.get()) failures.add(workerFailure("localhost_response", failure))
      } finally {
        sockets.remove(socket)
      }
    }

    fun bodyIsHeld(): Boolean =
        bodyHeld.count == 0L && releaseBody.count == 1L &&
            heldSocket.get()?.isClosed == false && !coldBodySent.get()

    fun close() {
      stopping.set(true)
      releaseBody.countDown()
      runCatching { listener.close() }
      sockets.forEach { socket -> runCatching { socket.close() } }
    }

    fun isClosed(): Boolean = listener.isClosed && sockets.all { it.isClosed }
  }

  companion object {
    // Record fixed categories only: never exception messages, stack traces or arbitrary URLs.
    private fun failureKind(failure: Throwable): String = when (failure) {
      is ExecutionException -> failure.cause?.let { failureKind(it) } ?: "unexpected"
      is TimeoutException, is SocketTimeoutException -> "timeout"
      is InterruptedException -> "interrupted"
      is IOException -> "io"
      is IllegalStateException, is IllegalArgumentException -> "assertion"
      else -> "unexpected"
    }

    private fun workerFailure(stage: String, failure: Throwable): JSONObject =
        JSONObject().put("stage", stage).put("kind", failureKind(failure))
  }
}
