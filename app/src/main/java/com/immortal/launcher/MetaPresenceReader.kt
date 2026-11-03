/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Tails Meta's presence tags in logcat and feeds verdicts to [PresenceHub] as a second,
 * camera-quality presence source ([MetaPresence] holds the pure grammar/state machine).
 *
 * The dream/sleep proxy PresenceHub already follows is blind exactly when the frame is
 * pinned always-on (PresenceState.confident == false). This signal has no such blind spot
 * — Meta's CV keeps running regardless — so the hub lets a Meta verdict override the proxy
 * whenever the proxy is unconfident, and treats the two as corroborating otherwise.
 *
 * Runs one long-lived `logcat` process on a daemon thread. Needs READ_LOGS (ADB-granted;
 * already required for the fleet /logcat endpoint). Without the grant, start() logs once
 * and does nothing — the launcher must behave identically on unprovisioned devices. The
 * logcat process dying (logd restart, buffer rotation) just respawns it with backoff.
 */
class MetaPresenceReader(private val context: Context) {

  @Volatile private var running = false
  private var worker: Thread? = null
  private val tracker = MetaPresence.Tracker()
  private val delivery = MetaPresenceDelivery(tracker) { present ->
    PresenceHub.onMetaPresence(context, present)
  }

  fun start() {
    if (running) return
    if (context.checkSelfPermission(android.Manifest.permission.READ_LOGS) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "READ_LOGS not granted; Meta presence signal unavailable")
      return
    }
    running = true
    delivery.start()
    worker =
        Thread({ loop() }, "MetaPresenceReader").apply {
          isDaemon = true
          start()
        }
  }

  fun stop() {
    running = false
    delivery.stop()
    worker?.interrupt()
    worker = null
  }

  private fun loop() {
    var backoffMs = 2_000L
    while (running) {
      val proc =
          runCatching {
            // -T 1: start from now (don't replay the buffer); tags per model generation.
            ProcessBuilder(
                    "logcat", "-T", "1", "-s",
                    "aloha.UserPresenceManager:I", "PresenceManager:I")
                .redirectErrorStream(true)
                .start()
          }.getOrNull()
      if (proc == null) {
        Log.w(TAG, "could not spawn logcat; retrying in ${backoffMs}ms")
        if (!sleepQuietly(backoffMs)) return
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        continue
      }
      backoffMs = 2_000L
      runCatching {
        BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
          while (running) {
            val line = r.readLine() ?: break // logcat died; respawn
            val signal = MetaPresence.classify(line) ?: continue
            delivery.onSignal(signal)
          }
        }
      }
      runCatching { proc.destroy() }
      if (running && !sleepQuietly(backoffMs)) return
    }
  }

  private fun sleepQuietly(ms: Long): Boolean =
      runCatching { Thread.sleep(ms); true }.getOrDefault(false)

  companion object {
    private const val TAG = "ImmortalPresence"
  }
}
