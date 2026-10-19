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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android query-only producer probe. Never sends Start, Stop, Suppress or reads credentials. */
internal object MusicReceiverDeviceChecks {
  fun run(instrumentation: Instrumentation, options: Bundle): Bundle {
    if (options.getString("rendererOnly") == "1") return runRendererFixture(instrumentation, options)
    if (options.getString("client") == "1") return runProductionClient(instrumentation, options)
    val requested = options.getString("queryOnly") == "1"
    val result = Bundle()
    val started = SystemClock.elapsedRealtime()
    var cycles = 0
    var requests = 0L
    var previousClient: String? = null
    var previousInstance: String? = null
    var previousFramesDrained = -1L
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
          val reply = MusicReceiverProtocol.parse(1, data.keySet().associateWith { data.get(it) })
              as MusicReceiverProtocol.Reply.State
          val state = reply.observation
          check(reply.error == null && state.stoppedConfirmed) { "Query must not start local output." }
          check(previousInstance != state.instance || state.pcmFramesDrained >= previousFramesDrained) {
            "Drain counter regressed in the same receiver instance."
          }
          previousInstance = state.instance
          previousFramesDrained = state.pcmFramesDrained
          val client = state.clientId
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

  /** Exercises the actual signed Binder transport; default-denied Start never reaches the receiver. */
  private fun runProductionClient(instrumentation: Instrumentation, options: Bundle): Bundle {
    val result = Bundle()
    val started = SystemClock.elapsedRealtime()
    var cycles = 0
    var deniedStarts = 0
    var previousIdentity: String? = null
    var stage = "opt_in"
    try {
      check(options.getString("queryOnly") == "1") { "Opt-in required." }
      repeat(30) {
        val observed = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val publications = AtomicInteger()
        var observation: MusicReceiverProtocol.Observation? = null
        var terminalFailure = false
        val client = MusicReceiverClient(instrumentation.targetContext, onChanged = { view ->
          publications.incrementAndGet()
          if (view.connection == MusicReceiverClient.Connection.DETACHED) detached.countDown()
          if (view.connection == MusicReceiverClient.Connection.UNTRUSTED ||
              view.connection == MusicReceiverClient.Connection.UNAVAILABLE) {
            terminalFailure = true
            observed.countDown()
          } else if (view.observation != null) {
            observation = view.observation
            observed.countDown()
          }
        })
        try {
          stage = "client_attach"
          client.attach()
          check(observed.await(4, TimeUnit.SECONDS) && !terminalFailure) { "Client observation incomplete." }
          stage = "client_stopped_state"
          val state = requireNotNull(observation)
          check(state.stoppedConfirmed) { "Attaching must not start output." }
          check(previousIdentity == null || previousIdentity == state.clientId)
          previousIdentity = state.clientId
          val age = SystemClock.elapsedRealtime() - state.observedAtElapsedMs
          check(age in 0 until MusicReceiverProtocol.FRESH_MS)
          stage = "default_start_denied"
          val denied = CountDownLatch(1)
          var commandResult: MusicReceiverClient.Result? = null
          client.execute(MusicReceiverProtocol.Action.UserStart(state, "127.0.0.1")) {
            commandResult = it
            denied.countDown()
          }
          check(denied.await(1, TimeUnit.SECONDS))
          check(commandResult == MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.NOT_ADMITTED))
          deniedStarts++
          check(client.view.observation?.stoppedConfirmed == true)
        } finally {
          val precedingStage = stage
          stage = "client_detach"
          client.detach()
          check(detached.await(1, TimeUnit.SECONDS)) { "Client detach incomplete." }
          check(client.view.connection == MusicReceiverClient.Connection.DETACHED &&
              client.view.observation == null && client.view.pendingWhat == null)
          val afterDetach = publications.get()
          // Check post-detach callbacks without waiting for or altering any household state.
          SystemClock.sleep(80)
          check(publications.get() == afterDetach) { "Client published after detach." }
          stage = precedingStage
        }
        cycles++
      }
      result.putBoolean("musicReceiverQueryPassed", true)
    } catch (_: Throwable) {
      result.putBoolean("musicReceiverQueryPassed", false)
      result.putString("failedStage", stage)
    } finally {
      result.putBoolean("productionClient", true)
      result.putInt("clientCycles", cycles)
      result.putInt("defaultStartsDenied", deniedStarts)
      // Successful denial proves the reviewed pre-send branch; failure leaves transmission unknown.
      if (result.getBoolean("musicReceiverQueryPassed")) result.putBoolean("startsSent", false)
      result.putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
    }
    return result
  }

  /** Local dummy endpoint: native execution/focus checks, no music server or audible playback claim. */
  private fun runRendererFixture(instrumentation: Instrumentation, options: Bundle): Bundle {
    val result = Bundle()
    val started = SystemClock.elapsedRealtime()
    val context = instrumentation.targetContext
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sockets = CopyOnWriteArrayList<Socket>()
    var endpoint: ServerSocket? = null
    var acceptor: Thread? = null
    var client: MusicReceiverClient? = null
    var heldFocus: AudioFocusRequest? = null
    var starts = 0
    var terminations = 0
    var noResumeChecks = 0
    val reasons = ArrayList<String>()
    var stage = "renderer_opt_in"
    var lastClientFailure = "none"
    var lastProtocolError = "none"
    var startAdmissionChecked = false
    var startAudioMode = -1
    var startMusicActive = false
    var cleaned = false
    try {
      check(options.getString("rendererOnly") == "1" && options.getString("queryOnly") == "1")
      check(audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive) { "Local audio is occupied." }
      stage = "dummy_endpoint"
      val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
      endpoint = server
      acceptor = Thread({
        while (!server.isClosed) {
          try {
            val socket = server.accept()
            if (server.isClosed || sockets.size >= 16) socket.close() else sockets.add(socket)
          } catch (_: SocketTimeoutException) {
            // Bounded accept allows teardown; no fixture protocol or household data is read.
          } catch (_: Exception) { break }
        }
      }, "ReceiverLocalFixture").apply { isDaemon = true; start() }
      val control = MusicReceiverClient(context, onChanged = {}, canStart = {
        startAdmissionChecked = true
        startAudioMode = audio.mode
        startMusicActive = audio.isMusicActive
        it.stoppedConfirmed && startAudioMode == AudioManager.MODE_NORMAL && !startMusicActive
      })
      client = control
      fun awaitState(predicate: (MusicReceiverProtocol.Observation) -> Boolean): MusicReceiverProtocol.Observation {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
          val state = control.view.observation
          if (state != null && predicate(state)) return state
          check(control.view.connection != MusicReceiverClient.Connection.UNTRUSTED)
          SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IllegalStateException("Receiver state deadline.")
      }
      fun command(action: MusicReceiverProtocol.Action): MusicReceiverProtocol.Reply {
        val done = CountDownLatch(1)
        var response: MusicReceiverClient.Result? = null
        control.execute(action) { response = it; done.countDown() }
        check(done.await(2, TimeUnit.SECONDS)) { "Receiver command deadline." }
        lastClientFailure = (response as? MusicReceiverClient.Result.Failed)?.failure?.name ?: "none"
        val reply = (response as? MusicReceiverClient.Result.Received)?.reply
            ?: throw IllegalStateException("Receiver command rejected.")
        lastProtocolError = when (reply) {
          is MusicReceiverProtocol.Reply.State -> reply.error ?: "none"
          is MusicReceiverProtocol.Reply.Error -> reply.error
        }
        return reply
      }
      control.attach()
      stage = "initial_stopped"
      awaitState { it.stoppedConfirmed }
      // Explicit stop paths plus the three Android focus requests; never connects to household MA.
      for (kind in listOf("suppress", "user_stop", "focus_transient", "focus_duck", "focus_permanent")) {
        stage = "start_" + kind
        val before = awaitState { it.stoppedConfirmed }
        val beforeConnections = sockets.size
        val reply = command(MusicReceiverProtocol.Action.UserStart(before, "127.0.0.1", server.localPort))
        check(reply is MusicReceiverProtocol.Reply.State && reply.error == null)
        starts++
        awaitState { it.processAlive && it.focusHeld && it.playbackRequested &&
            it.outputState == "process_running_unverified" }
        // Allow shell exec/native startup to complete before exercising termination.
        SystemClock.sleep(700)
        awaitState { it.processAlive && it.focusHeld && it.playbackRequested }
        check(sockets.size > beforeConnections) { "Native client did not reach the local endpoint." }
        stage = "terminate_" + kind
        if (kind == "suppress" || kind == "user_stop") {
          val stopped = command(if (kind == "suppress") MusicReceiverProtocol.Action.Suppress
              else MusicReceiverProtocol.Action.UserStop)
          check(stopped is MusicReceiverProtocol.Reply.State && stopped.error == null)
        } else {
          val gain = when (kind) {
            "focus_transient" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            "focus_duck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN
          }
          val request = AudioFocusRequest.Builder(gain)
              .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
              .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
          heldFocus = request
          var granted = false
          instrumentation.runOnMainSync {
            granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
          }
          check(granted) { "Fixture focus not granted." }
        }
        val expectedReason = when (kind) {
          "suppress" -> "launcher_suppressed"
          "user_stop" -> "user_stopped"
          "focus_transient" -> "focus_loss_-2"
          "focus_duck" -> "focus_loss_-3"
          else -> "focus_loss_-1"
        }
        val stopped = awaitState { it.stoppedConfirmed && it.epoch > before.epoch && it.reason == expectedReason }
        reasons.add(stopped.reason)
        terminations++
        heldFocus?.let { request -> instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(request) } }
        heldFocus = null
        stage = "no_auto_resume_" + kind
        val holdUntil = SystemClock.elapsedRealtime() + 3_250
        do {
          val current = requireNotNull(control.view.observation)
          check(current.stoppedConfirmed && current.epoch == stopped.epoch &&
              SystemClock.elapsedRealtime() - current.observedAtElapsedMs in 0 until MusicReceiverProtocol.FRESH_MS)
          SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < holdUntil)
        val last = awaitState { it.observedAtElapsedMs >= holdUntil }
        check(last.stoppedConfirmed && last.epoch == stopped.epoch)
        noResumeChecks++
      }
      result.putBoolean("musicReceiverQueryPassed", true)
    } catch (_: Throwable) {
      result.putBoolean("musicReceiverQueryPassed", false)
      result.putString("failedStage", stage)
    } finally {
      // Emergency suppression is attempted even if startup/fixture assertions fail.
      var rendererStopped = client == null
      client?.let { control ->
        val stopped = CountDownLatch(1)
        var acknowledgedStop = false
        control.execute(MusicReceiverProtocol.Action.Suppress) { response ->
          val reply = (response as? MusicReceiverClient.Result.Received)?.reply as? MusicReceiverProtocol.Reply.State
          acknowledgedStop = reply?.error == null && reply?.observation?.stoppedConfirmed == true
          stopped.countDown()
        }
        rendererStopped = stopped.await(2, TimeUnit.SECONDS) && acknowledgedStop
        control.detach()
      }
      heldFocus?.let { request -> instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(request) } }
      runCatching { endpoint?.close() }
      acceptor?.join(500)
      sockets.forEach { runCatching { it.close() } }
      instrumentation.runOnMainSync { cleaned = rendererStopped && (client == null || client?.view?.connection == MusicReceiverClient.Connection.DETACHED) &&
          (endpoint == null || endpoint?.isClosed == true) && acceptor?.isAlive != true && sockets.all { it.isClosed } }
      result.putBoolean("rendererStopAcknowledged", rendererStopped)
      result.putBoolean("fixtureCleaned", cleaned)
      if (!cleaned) {
        result.putBoolean("musicReceiverQueryPassed", false)
        result.putString("failedStage", "fixture_cleanup")
      }
      result.putBoolean("localRendererFixture", true)
      result.putString("lastClientFailure", lastClientFailure)
      result.putString("lastProtocolError", lastProtocolError)
      result.putBoolean("startAdmissionChecked", startAdmissionChecked)
      if (startAdmissionChecked) {
        result.putInt("lastStartAudioMode", startAudioMode)
        result.putBoolean("lastStartMusicActive", startMusicActive)
      }
      result.putInt("explicitStartsAccepted", starts)
      result.putInt("terminationsConfirmed", terminations)
      result.putInt("noAutomaticResumeChecks", noResumeChecks)
      result.putInt("localConnectionsAccepted", sockets.size)
      result.putStringArrayList("observedStopReasons", reasons)
      result.putBoolean("householdServerUsed", false)
      result.putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
    }
    return result
  }
}
