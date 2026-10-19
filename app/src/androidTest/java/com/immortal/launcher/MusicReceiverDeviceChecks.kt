/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android query-only producer probe. Never sends Start, Stop, Suppress or reads credentials. */
internal object MusicReceiverDeviceChecks {
  fun run(instrumentation: Instrumentation, options: Bundle): Bundle {
    val requested = options.getString("queryOnly") == "1"
    val result = Bundle()
    val started = SystemClock.elapsedRealtime()
    var cycles = 0
    var requests = 0L
    var previousClient: String? = null
    var stage = "opt_in"
    try {
      check(requested) { "Opt-in required." }
      stage = "signer"
      check(instrumentation.targetContext.packageManager.checkSignatures(instrumentation.targetContext.packageName,
          "com.immortal.musicreceiver") == PackageManager.SIGNATURE_MATCH) { "Signer mismatch." }
      repeat(30) {
        val done = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var bound = false
        var active = true
        var remote: Messenger? = null
        var failed = false
        var observed: Bundle? = null
        var expectedId = 0L
        val deadline = SystemClock.elapsedRealtime() + 2_000
        lateinit var sendQuery: () -> Unit
        val replyHandler = object : Handler(Looper.getMainLooper()) {
          override fun handleMessage(message: Message) {
            if (!active || message.what != 1 || message.data.get("requestId") != expectedId) return
            val data = message.data
            if (data.get("error") == "not_ready" && SystemClock.elapsedRealtime() < deadline) {
              handler.postDelayed({ if (active) sendQuery() }, 25)
            } else {
              observed = data
              done.countDown()
            }
          }
        }
        val reply = Messenger(replyHandler)
        sendQuery = {
          if (active) {
            expectedId = ++requests
            try {
              remote?.send(Message.obtain().apply {
                what = 1
                replyTo = reply
                data = Bundle().apply { putLong("requestId", expectedId) }
              })
            } catch (_: Exception) {
              failed = true
              done.countDown()
            }
          }
        }
        val connection = object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!active) return
            remote = Messenger(service)
            sendQuery()
          }
          override fun onServiceDisconnected(name: ComponentName) {
            if (active) { failed = true; done.countDown() }
          }
        }
        try {
          stage = "bind_query"
          instrumentation.runOnMainSync {
            bound = instrumentation.targetContext.bindService(Intent().setComponent(ComponentName(
                "com.immortal.musicreceiver", "de.badaix.snapcast.FocusControlService")),
                connection, Context.BIND_AUTO_CREATE)
          }
          check(bound && done.await(3, TimeUnit.SECONDS) && !failed) { "Bind/query incomplete." }
          stage = "stopped_state"
          val data = requireNotNull(observed)
          check(!data.containsKey("error")) { "Receiver rejected query." }
          check(data.get("protocol") == 1 && data.get("epoch") is Long &&
              (data.get("epoch") as Long) >= 0 && data.get("observedAtElapsedMs") is Long)
          check(data.get("instance") is String && data.getString("instance")!!.isNotBlank())
          check(data.get("outputState") == "stopped_confirmed" &&
              data.get("processAlive") == false && data.get("focusHeld") == false &&
              data.get("playbackRequested") == false) { "Query must not start output." }
          val client = requireNotNull(data.get("clientId") as? String)
          check(client.isNotBlank() && (previousClient == null || previousClient == client))
          previousClient = client
          cycles++
        } finally {
          instrumentation.runOnMainSync {
            active = false
            handler.removeCallbacksAndMessages(null)
            replyHandler.removeCallbacksAndMessages(null)
            if (bound) instrumentation.targetContext.unbindService(connection)
            remote = null
          }
        }
      }
      result.putBoolean("musicReceiverQueryPassed", true)
    } catch (_: Throwable) {
      // Only public counters/bools are returned; no private diagnostics or account identifiers.
      result.putBoolean("musicReceiverQueryPassed", false)
      result.putString("failedStage", stage)
    } finally {
      result.putInt("queryCycles", cycles)
      result.putLong("queryRequests", requests)
      result.putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
      result.putBoolean("startsSent", false)

    }
    return result
  }
}
