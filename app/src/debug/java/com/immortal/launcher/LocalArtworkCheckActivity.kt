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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Real MusicLocalArtwork.fetch probe using only this process's synthetic provider. */
class LocalArtworkCheckActivity : Activity() {
  private lateinit var status: TextView
  private var coordinator: Thread? = null
  @Volatile private var workers: ExecutorService? = null
  @Volatile private var source: LocalArtworkCheckProvider.Session? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    status = TextView(this).apply {
      text = "Local artwork: checking a held provider and queued cancellation"
      textSize = 20f
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.rgb(28, 27, 25))
      setPadding(24, 24, 24, 24)
    }
    setContentView(status)
    val runId = intent.getStringExtra("run_id")
        ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        ?: UUID.randomUUID().toString().replace("-", "")
    coordinator = Thread({ runChecks(runId) }, "LocalArtworkCheck").apply {
      isDaemon = true
      start()
    }
  }

  override fun onDestroy() {
    source?.close()
    workers?.shutdownNow()
    coordinator?.interrupt()
    super.onDestroy()
  }

  private fun runChecks(runId: String) {
    val started = SystemClock.elapsedRealtime()
    val testDeadline = started + 6_000
    val totalDeadline = started + 8_000
    val checks = JSONArray()
    val failures = ConcurrentLinkedQueue<JSONObject>()
    val report = JSONObject().put("complete", false).put("success", false)
        .put("runId", runId).put("api", Build.VERSION.SDK_INT)
        .put("syntheticProviderOnly", true).put("syntheticPngOnly", true)
        .put("maxWaitMs", 8_000).put("checks", checks)
    var local: LocalArtworkCheckProvider.Session? = null
    var executor: ExecutorService? = null
    fun demand(name: String, passed: Boolean) {
      checks.put(JSONObject().put("name", name).put("passed", passed))
    }
    fun remaining(cap: Long = Long.MAX_VALUE): Long =
        minOf(cap, testDeadline - SystemClock.elapsedRealtime()).also {
          if (it <= 0) throw TimeoutException()
        }

    try {
      check(Build.VERSION.SDK_INT == 34 && application.javaClass == Application::class.java)
      demand("isolated_api34_base_application", true)
      check(LocalArtworkCheckProvider.session == null)
      val bitmap = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
      val png = try {
        bitmap.eraseColor(Color.rgb(40, 140, 210))
        ByteArrayOutputStream().use {
          check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
          it.toByteArray()
        }
      } finally {
        bitmap.recycle() // Only our generated source; fetch results remain borrowed.
      }
      // A solid PNG fits one minimum-sized pipe buffer, so a broken reader cannot
      // strand the producer in a write syscall after the held byte is released.
      check(png.size in 2..4_096)
      report.put("sourceWidth", 768).put("sourceHeight", 512).put("pngBytes", png.size)
      val provider = LocalArtworkCheckProvider.Session(runId, png, testDeadline, failures)
      local = provider
      source = provider
      LocalArtworkCheckProvider.session = provider
      val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "LocalArtworkFetch").apply { isDaemon = true }
      }
      executor = pool
      workers = pool
      fun fetch(stage: String): Probe {
        val probe = Probe()
        probe.future = pool.submit(Callable {
          probe.thread.set(Thread.currentThread())
          probe.started.countDown()
          try {
            MusicLocalArtwork.fetch(contentResolver,
                "content://$packageName.local-artwork-check/$runId/$stage")
          } catch (failure: Throwable) {
            if (stage != "cancelled" || failure !is InterruptedException) {
              failures.add(LocalArtworkCheckProvider.workerFailure("fetch_$stage", failure))
            }
            throw failure
          } finally {
            probe.finished.countDown()
          }
        })
        return probe
      }
      fun awaitBlocked(probe: Probe): Boolean {
        check(probe.started.await(remaining(500), TimeUnit.MILLISECONDS))
        val until = SystemClock.elapsedRealtime() + remaining(500)
        while (SystemClock.elapsedRealtime() < until && !probe.future.isDone) {
          val state = probe.thread.get()?.state
          if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING ||
              state == Thread.State.BLOCKED) return true
          Thread.sleep(5)
        }
        return false
      }

      val first = fetch("first")
      check(provider.firstByte.await(remaining(1_500), TimeUnit.MILLISECONDS))
      demand("first_provider_held_after_first_byte",
          provider.opens.get() == 1 && !first.future.isDone &&
              provider.releaseFirst.count == 1L)
      val cancelled = fetch("cancelled")
      demand("second_fetch_queued_before_cancellation", awaitBlocked(cancelled) &&
          provider.count("cancelled") == 0)
      demand("queued_future_cancelled", cancelled.future.cancel(true))
      demand("cancelled_worker_returned_before_release",
          cancelled.finished.await(remaining(500), TimeUnit.MILLISECONDS))
      val otherOne = fetch("other_one")
      val otherTwo = fetch("other_two")
      val oneBlocked = awaitBlocked(otherOne)
      val twoBlocked = awaitBlocked(otherTwo)
      report.put("opensBeforeRelease", provider.opens.get())
      demand("two_other_fetches_queued_before_release", oneBlocked && twoBlocked)
      demand("one_provider_open_before_release", provider.opens.get() == 1)
      demand("cancelled_path_not_opened_before_release", provider.count("cancelled") == 0)
      demand("first_body_still_held_before_release",
          provider.releaseFirst.count == 1L && !first.future.isDone &&
              provider.firstWriterFinished.count == 1L)
      provider.releaseFirst.countDown()
      for ((stage, probe) in listOf("first" to first,
          "other_one" to otherOne, "other_two" to otherTwo)) {
        val decoded = probe.future.get(remaining(), TimeUnit.MILLISECONDS)
        demand("${stage}_decoded_at_most_384", decoded != null && !decoded.isRecycled &&
            decoded.width in 1..384 && decoded.height in 1..384)
        report.put("${stage}Width", decoded?.width ?: 0)
            .put("${stage}Height", decoded?.height ?: 0)
      }
      demand("first_producer_sent_complete_png", provider.firstBodySent.get())
      demand("first_producer_finished",
          provider.firstWriterFinished.await(remaining(500), TimeUnit.MILLISECONDS))
      demand("cancelled_path_never_opened", provider.count("cancelled") == 0)
      demand("exactly_three_provider_opens", provider.opens.get() == 3)
    } catch (failure: Throwable) {
      report.put("failure", LocalArtworkCheckProvider.failureKind(failure))
      demand("fixture_completed_without_coordinator_exception", false)
    } finally {
      Thread.interrupted()
      local?.close() // Release the latch and close both ends, even on a failed assertion.
      executor?.shutdownNow()
      val fetchTerminated = try {
        executor?.awaitTermination(
            (totalDeadline - SystemClock.elapsedRealtime()).coerceIn(0, 1_000),
            TimeUnit.MILLISECONDS) ?: true
      } catch (_: InterruptedException) {
        Thread.interrupted()
        false
      }
      val writersTerminated = try {
        local?.awaitTermination(totalDeadline) ?: true
      } catch (_: InterruptedException) {
        Thread.interrupted()
        false
      }
      demand("fetch_workers_terminated", fetchTerminated)
      demand("provider_workers_terminated", writersTerminated)
      demand("first_producer_terminated",
          local != null && local.firstWriterFinished.count == 0L && writersTerminated)
      demand("all_pipe_descriptors_closed", local?.descriptorsClosed() ?: true)
      demand("worker_exceptions_absent", failures.isEmpty())
      report.put("workerFailures", JSONArray(failures.toList()))
          .put("providerOpens", local?.opens?.get() ?: 0)
          .put("cancelledOpens", local?.count("cancelled") ?: 0)
          .put("elapsedMs", SystemClock.elapsedRealtime() - started)
      demand("total_wait_budget_at_most_8_seconds",
          SystemClock.elapsedRealtime() <= totalDeadline)
      val passed = (0 until checks.length()).count { checks.getJSONObject(it).getBoolean("passed") }
      report.put("passed", passed).put("total", checks.length())
          .put("complete", true).put("success", passed == checks.length())
      if (LocalArtworkCheckProvider.session === local) LocalArtworkCheckProvider.session = null
      source = null
      workers = null
      try {
        val receipt = AtomicFile(File(filesDir, "local-artwork-check.json"))
        val output = receipt.startWrite()
        try {
          output.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
          receipt.finishWrite(output)
        } catch (failure: Throwable) {
          receipt.failWrite(output)
          throw failure
        }
        runOnUiThread {
          if (!isFinishing && !isDestroyed) status.text =
              "${if (report.getBoolean("success")) "PASS" else "FAIL"}: " +
              "$passed/${checks.length()} local artwork checks\n" +
              "Receipt: files/local-artwork-check.json"
        }
      } catch (_: Throwable) {
        runOnUiThread {
          if (!isFinishing && !isDestroyed) status.text = "FAIL: local artwork receipt write"
        }
      }
    }
  }

  private class Probe {
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val thread = AtomicReference<Thread?>()
    lateinit var future: Future<Bitmap?>
  }
}
