/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Independent implementation of the documented Messenger contract; no receiver implementation. */
internal object MusicReceiverProtocol {
  const val RECEIVER_PACKAGE = "com.immortal.musicreceiver"
  const val SERVICE_CLASS = "de.badaix.snapcast.FocusControlService"
  const val BIND_ACTION = "com.immortal.musicreceiver.FOCUS_CONTROL"
  const val FRESH_MS = 1_000L
  private const val MAX_TEXT = 4096

  sealed interface Action {
    data object Query : Action
    data object Suppress : Action
    data object UserStop : Action
    /** A new user gesture, bound to a fresh receiver observation, never an automatic recovery. */
    data class UserStart(val observed: Observation, val host: String, val port: Int = 1704) : Action
  }

  data class Observation(
      val requestId: Long, val instance: String, val epoch: Long, val observedAtElapsedMs: Long,
      val clientId: String, val outputState: String, val reason: String, val processAlive: Boolean,
      val focusHeld: Boolean, val playbackRequested: Boolean,
      val rendererActive: Boolean = processAlive, val pcmFramesDrained: Long = 0,
  ) {
    /** Confirms only reported local output stop, not decoder termination or speaker silence. */
    val stoppedConfirmed: Boolean get() = !rendererActive && !focusHeld && !playbackRequested &&
        (outputState == "stopped_confirmed" ||
          (outputState == "connection_running_unverified" && processAlive))
  }

  sealed interface Reply {
    val requestId: Long
    data class State(val observation: Observation, val error: String? = null) : Reply {
      override val requestId: Long get() = observation.requestId
    }
    data class Error(override val requestId: Long, val error: String) : Reply
  }

  data class Request(val what: Int, val requestId: Long, val data: Map<String, Any>,
      val generation: Long, val sentAtElapsedMs: Long)

  enum class Rejection { MALFORMED, INSTANCE_CHANGED, SOURCE_CHANGED, EPOCH_REGRESSED,
    COUNTER_REGRESSED, STALE_TIME }
  sealed interface Acceptance {
    data object Ignored : Acceptance
    data class Rejected(val reason: Rejection) : Acceptance
    data class Accepted(val reply: Reply) : Acceptance
  }

  private val stateKeys = setOf("protocol", "requestId", "instance", "epoch", "observedAtElapsedMs",
      "clientId", "outputState", "reason", "processAlive", "focusHeld", "playbackRequested",
      "rendererActive", "pcmFramesDrained")
  private val outputs = setOf("stopped_confirmed", "starting_unverified",
      "process_running_unverified", "connection_running_unverified", "termination_unconfirmed")
  private val reasons = setOf("idle", "launcher_suppressed", "user_stopped", "user_start",
      "service_destroyed", "renderer_gate_failed", "renderer_exited", "pcm_output_failed", "focus_denied",
      "start_failed", "termination_unconfirmed", "restart_failed", "focus_loss_-1", "focus_loss_-2", "focus_loss_-3")

  /** Bundle values must retain their actual runtime types; coercing getters are not used. */
  fun parse(what: Int, fields: Map<String, Any?>): Reply {
    require(what in 1..4) { "Unsupported receiver command." }
    val id = long(fields, "requestId", 1)
    if (fields.keys == setOf("requestId", "error")) {
      val error = text(fields, "error")
      require(error == "not_ready" || error == "forbidden") { "Invalid receiver error." }
      return Reply.Error(id, error)
    }
    val error = if (fields.containsKey("error")) text(fields, "error") else null
    require(fields.keys == stateKeys + (if (error == null) emptySet() else setOf("error"))) {
      "Invalid receiver fields."
    }
    require(fields["protocol"] is Int && fields["protocol"] == 2) { "Unsupported receiver protocol." }
    require(error == null || error in setOf("stale", "invalid_endpoint", "unsupported")) {
      "Invalid receiver error."
    }
    val output = text(fields, "outputState")
    val reason = text(fields, "reason")
    require(output in outputs && reason in reasons) { "Invalid receiver state." }
    val processAlive = bool(fields, "processAlive")
    val rendererActive = bool(fields, "rendererActive")
    require(output != "connection_running_unverified" || (processAlive && !rendererActive)) {
      "Invalid connection-only receiver state."
    }
    return Reply.State(Observation(id, text(fields, "instance"), long(fields, "epoch", 0),
        long(fields, "observedAtElapsedMs", 0), text(fields, "clientId"), output, reason,
        processAlive, bool(fields, "focusHeld"), bool(fields, "playbackRequested"),
        rendererActive, long(fields, "pcmFramesDrained", 0)), error)
  }

  private fun text(fields: Map<String, Any?>, key: String): String {
    val raw = fields[key]
    require(raw is String && raw.isNotBlank() && raw.length <= MAX_TEXT) { "Invalid receiver text: $key." }
    return raw
  }
  private fun long(fields: Map<String, Any?>, key: String, min: Long): Long {
    val raw = fields[key]
    require(raw is Long && raw >= min) { "Invalid receiver integer: $key." }
    return raw
  }
  private fun bool(fields: Map<String, Any?>, key: String): Boolean =
      fields[key] as? Boolean ?: throw IllegalArgumentException("Invalid receiver boolean: $key.")

  private fun endpoint(host: String, port: Int): Boolean = host.isNotBlank() && host.length <= 253 &&
      port in 1..65535 && host.none { it <= ' ' || it == '\u007f' || it in "/\\@?#" }

  /** The asynchronous client owns this ledger on one scheduler. It never queues or retries Start. */
  class Session(private val replyTimeoutMs: Long = FRESH_MS) {
    init { require(replyTimeoutMs in 1..5_000) }
    var generation: Long = 0
      private set
    var observation: Observation? = null
      private set
    var pending: Request? = null
      private set
    private var connected = false
    private var nextId = 0L
    private var instance: String? = null
    private var clientId: String? = null
    private var epoch = -1L
    private var observedAt = -1L
    // A live service keeps draining across bindings; only a new service instance resets its counter.
    private var frameInstance: String? = null
    private var framesDrained = -1L
    // Real receipts only, current binding only; normal 500 ms polling needs at most three.
    private val recentObservations = ArrayDeque<Observation>()

    // Drain progress is telemetry, not a new user-visible state or Start admission epoch.
    // Receipt membership below still compares the complete, unmodified Observation.
    private fun sameReportedState(a: Observation, b: Observation): Boolean =
        a.copy(requestId = b.requestId, observedAtElapsedMs = b.observedAtElapsedMs,
            pcmFramesDrained = b.pcmFramesDrained) == b

    fun connect() { reset(); connected = true }
    fun disconnect() { reset(); connected = false }
    private fun reset() {
      generation++
      observation = null; pending = null; instance = null; clientId = null; epoch = -1; observedAt = -1
      recentObservations.clear()
    }

    fun issue(action: Action, nowElapsedMs: Long): Request? {
      if (!connected || nowElapsedMs < 0 || nextId == Long.MAX_VALUE) return null
      expireObservation(nowElapsedMs)
      if (action == Action.Query && pending != null) return null
      if (action is Action.UserStart && (observation == null || action.observed !in recentObservations ||
          !sameReportedState(action.observed, observation!!) || nowElapsedMs < action.observed.observedAtElapsedMs ||
          nowElapsedMs - action.observed.observedAtElapsedMs >= FRESH_MS ||
          !endpoint(action.host, action.port))) return null
      val id = ++nextId
      val data = linkedMapOf<String, Any>("requestId" to id)
      val what = when (action) {
        Action.Query -> 1
        Action.Suppress -> 2
        Action.UserStop -> 4
        is Action.UserStart -> {
          data["instance"] = action.observed.instance
          data["epoch"] = action.observed.epoch
          data["host"] = action.host
          data["port"] = action.port
          3
        }
      }
      if (action != Action.Query) {
        observation = null
        recentObservations.clear()
      }
      return Request(what, id, data.toMap(), generation, nowElapsedMs).also { pending = it }
    }

    fun accept(bindingGeneration: Long, what: Int, fields: Map<String, Any?>,
        nowElapsedMs: Long): Acceptance {
      val request = pending ?: return Acceptance.Ignored
      if (!connected || bindingGeneration != generation || request.generation != generation ||
          what != request.what) return Acceptance.Ignored
      val id = fields["requestId"]
      if (id is Long && id != request.requestId) return Acceptance.Ignored
      fun reject(reason: Rejection): Acceptance {
        pending = null; observation = null; recentObservations.clear()
        return Acceptance.Rejected(reason)
      }
      val reply = try { parse(what, fields) } catch (_: IllegalArgumentException) {
        return reject(Rejection.MALFORMED)
      }
      if (nowElapsedMs < request.sentAtElapsedMs ||
          nowElapsedMs - request.sentAtElapsedMs >= replyTimeoutMs) return reject(Rejection.STALE_TIME)
      if (reply is Reply.State) {
        val s = reply.observation
        if (instance != null && instance != s.instance) return reject(Rejection.INSTANCE_CHANGED)
        if (clientId != null && clientId != s.clientId) return reject(Rejection.SOURCE_CHANGED)
        if (s.epoch < epoch) return reject(Rejection.EPOCH_REGRESSED)
        if (frameInstance == s.instance && s.pcmFramesDrained < framesDrained)
          return reject(Rejection.COUNTER_REGRESSED)
        if (s.observedAtElapsedMs < observedAt || s.observedAtElapsedMs < request.sentAtElapsedMs ||
            s.observedAtElapsedMs > nowElapsedMs ||
            nowElapsedMs - s.observedAtElapsedMs >= FRESH_MS) return reject(Rejection.STALE_TIME)
        instance = s.instance; clientId = s.clientId; epoch = s.epoch; observedAt = s.observedAtElapsedMs
        frameInstance = s.instance; framesDrained = s.pcmFramesDrained
        if (observation == null || !sameReportedState(observation!!, s) || reply.error != null)
          recentObservations.clear()
        observation = s.takeIf { reply.error == null }
        if (observation != null) {
          while (recentObservations.isNotEmpty() &&
              nowElapsedMs - recentObservations.first().observedAtElapsedMs >= FRESH_MS)
            recentObservations.removeFirst()
          if (recentObservations.size == 4) recentObservations.removeFirst()
          recentObservations.addLast(s)
        }
      } else {
        observation = null
        recentObservations.clear()
      }
      pending = null
      return Acceptance.Accepted(reply)
    }

    fun timeout(nowElapsedMs: Long): Boolean {
      val request = pending ?: return false
      if (nowElapsedMs >= request.sentAtElapsedMs &&
          nowElapsedMs - request.sentAtElapsedMs < replyTimeoutMs) return false
      pending = null; observation = null; recentObservations.clear()
      return true
    }

    fun expireObservation(nowElapsedMs: Long): Boolean {
      val s = observation ?: return false
      if (nowElapsedMs >= s.observedAtElapsedMs && nowElapsedMs - s.observedAtElapsedMs < FRESH_MS)
        return false
      observation = null; recentObservations.clear()
      return true
    }
  }
}
