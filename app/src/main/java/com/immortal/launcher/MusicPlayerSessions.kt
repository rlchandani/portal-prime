/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal object MusicSourceRevisions {
  private val next = AtomicLong()
  fun next() = next.incrementAndGet()
}

/** One controller/socket shared by Portal layouts and paired phone/browser requests. */
internal object MusicPlayerSessions {
  private const val REMOTE_LEASE_MS = 120_000L
  private const val MAIN_REQUEST_MS = 500L
  private val main = Handler(Looper.getMainLooper())
  private val lease = MusicSessionLease(REMOTE_LEASE_MS)
  private var current: MusicPlayerController? = null
  private var observing = false
  private val idle = Runnable {
    if (!lease.shouldObserve(SystemClock.elapsedRealtime())) {
      current?.detach()
      observing = false
    } else scheduleIdle()
  }

  data class View(
      val snapshot: MusicPlayerSnapshot,
      val sourceRevision: Long,
      val observedAtElapsedMs: Long,
  )
  data class Result(
      val status: Int,
      val code: String,
      val admitted: Boolean? = false,
      val view: View? = null,
  )

  fun controller(context: Context): MusicPlayerController {
    checkMain()
    return current ?: MusicPlayerController(context.applicationContext).also { current = it }
  }

  fun retainUi(controller: MusicPlayerController) {
    checkMain()
    check(current === controller)
    lease.retainUi()
    observe(controller)
    scheduleIdle()
  }

  fun releaseUi(controller: MusicPlayerController) {
    checkMain()
    if (current !== controller) return
    lease.releaseUi()
    scheduleIdle()
  }

  /** Capture only on Main; JSON encoding stays on the HTTP worker using this immutable view. */
  fun read(context: Context): View? = onMain {
    val controller = remoteController(context)
    view(controller)
  }

  fun command(context: Context, body: JSONObject): Result = onMain {
    synchronized(com.immortal.launcher.settings.SettingsDomains.immortal) {
    val controller = remoteController(context)
    val before = view(controller)
    when (val decoded = MusicRemoteProtocol.decode(body, before.snapshot, before.sourceRevision)) {
      is MusicRemoteProtocol.Decoded.Rejected -> Result(decoded.status, decoded.code, view = before)
      MusicRemoteProtocol.Decoded.AlreadyApplied -> Result(200, "already_applied", view = before)
      is MusicRemoteProtocol.Decoded.Accepted -> {
        if (controller.send(decoded.action))
          Result(202, "admitted", admitted = true, view = view(controller))
        else Result(409, "player_changed", view = view(controller))
      }
    }
    }
  } ?: Result(503, "music_request_timeout", admitted = null)

  private fun remoteController(context: Context): MusicPlayerController {
    val controller = controller(context)
    lease.touchRemote(SystemClock.elapsedRealtime())
    observe(controller)
    controller.reconcileConnection()
    scheduleIdle()
    return controller
  }

  private fun observe(controller: MusicPlayerController) {
    if (!observing) {
      controller.attach()
      observing = true
    }
  }

  private fun view(controller: MusicPlayerController) = View(
      controller.store.current, controller.sourceRevision, SystemClock.elapsedRealtime())

  private fun scheduleIdle() {
    main.removeCallbacks(idle)
    lease.idleDelayMs(SystemClock.elapsedRealtime())?.let { main.postDelayed(idle, it) }
  }

  private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

  private fun <T> onMain(block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val deadline = SystemClock.elapsedRealtime() + MAIN_REQUEST_MS
    val task = FutureTask<T?> {
      if (SystemClock.elapsedRealtime() >= deadline) null else block()
    }
    if (!main.post(task)) return null
    return try {
      task.get(MAIN_REQUEST_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      // A started mutation may have an uncertain result. Clients must refresh, not retry.
      task.cancel(false)
      main.removeCallbacks(task)
      null
    } catch (_: InterruptedException) {
      task.cancel(false)
      main.removeCallbacks(task)
      Thread.currentThread().interrupt()
      null
    } catch (_: java.util.concurrent.ExecutionException) {
      null
    }
  }
}
