/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** Where one call is. Outgoing: DIALING → RINGING/COUNTDOWN → CONNECTING → LIVE. Incoming skips DIALING. */
internal enum class CallPhase { DIALING, RINGING, COUNTDOWN, CONNECTING, LIVE, RECONNECTING;
  val connected: Boolean get() = this == LIVE || this == RECONNECTING
  val beforeAnswer: Boolean get() = this == DIALING || this == RINGING || this == COUNTDOWN
}

/** The one call this Portal is in (phase 1 holds at most one). Immutable; the reducer copies it. */
@Immutable
internal data class ActiveCall(
    val id: String,
    val peer: CallContact,
    val direction: CallDirection,
    val kind: CallKind,
    /** The call was placed as a video call (Answer with audio only still clears [cameraOn]). */
    val video: Boolean,
    val phase: CallPhase,
    val phaseSinceMs: Long,
    val createdMs: Long,
    val cameraOn: Boolean,
    val micOn: Boolean = true,
    val connectedMs: Long? = null,
    /** Drop-ins only: the call ends at this time unless someone taps Keep going. */
    val capAtMs: Long? = null,
    val cameraIssue: CameraIssue? = null,
    val remoteCameraOn: Boolean = true,
    val remoteMicOn: Boolean = true,
    /** Incoming in quiet hours with "Show the call silently": no sound. */
    val silent: Boolean = false,
    /** Incoming only: where the caller's signalling route lives (from its signed ring). */
    val peerHost: String? = null,
    val peerPort: Int = 0,
    /** Grandma mode auto-answer: the call connects by itself at this time unless declined. */
    val autoAnswerAtMs: Long? = null,
)

/** How the last call ended, for the ended/error screens. */
@Immutable
internal data class EndedCall(
    val id: String,
    val peer: CallContact,
    val direction: CallDirection,
    val kind: CallKind,
    val reason: CallEndReason,
    val connectedMs: Long?,
    val endedMs: Long,
    /** QUIET replies: minute of day the callee's quiet hours end, when it said. */
    val quietEndMin: Int? = null,
) {
  val durationMs: Long get() = connectedMs?.let { (endedMs - it).coerceAtLeast(0) } ?: 0L
}

@Immutable
internal data class CallState(val call: ActiveCall? = null, val ended: EndedCall? = null) {
  val busy: Boolean get() = call != null
}

/** Inputs to [CallMachine.reduce]. Times are wall-clock ms from the runtime's clock. */
internal sealed interface CallEvent {
  data class Dial(val id: String, val peer: CallContact, val kind: CallKind, val video: Boolean, val now: Long) : CallEvent
  data class Replied(val id: String, val from: String, val reply: CallReply, val quietEndMin: Int?, val now: Long) : CallEvent
  data class Incoming(val id: String, val peer: CallContact, val kind: CallKind, val video: Boolean, val host: String?,
                      val port: Int, val decision: IncomingDecision, val now: Long) : CallEvent
  data class Accept(val audioOnly: Boolean, val now: Long) : CallEvent
  data class Decline(val now: Long) : CallEvent
  data class HangUp(val now: Long) : CallEvent
  /** The call screen stopped (Back, Home, screen off). Privacy: everything closes. */
  data class Left(val now: Long) : CallEvent
  data class RemoteEnded(val id: String, val from: String, val reason: CallEndReason, val now: Long) : CallEvent
  /** Caller side: the callee's signed offer arrived and verified. */
  data class RemoteAccepted(val id: String, val from: String, val now: Long) : CallEvent
  data class MediaUp(val id: String, val now: Long) : CallEvent
  data class MediaDown(val id: String, val now: Long) : CallEvent
  data class MediaFailed(val id: String, val reason: CallEndReason, val now: Long) : CallEvent
  data class Tick(val now: Long) : CallEvent
  data class KeepGoing(val now: Long) : CallEvent
  data class RemoteKeepGoing(val id: String, val from: String, val capAtMs: Long) : CallEvent
  data object ToggleMic : CallEvent
  data object ToggleCamera : CallEvent
  data class CameraProblem(val issue: CameraIssue) : CallEvent
  data class RemoteMedia(val id: String, val from: String, val cameraOn: Boolean, val micOn: Boolean) : CallEvent
  data object DismissEnded : CallEvent
}

/** Side effects the runtime performs after a transition, in order. */
internal sealed interface CallEffect {
  data class SendRing(val call: ActiveCall) : CallEffect
  data class SendReply(val id: String, val to: String, val reply: CallReply) : CallEffect
  data class SendEnd(val id: String, val to: String, val reason: CallEndReason) : CallEffect
  data class SendKeep(val id: String, val to: String, val capAtMs: Long) : CallEffect
  data class SendMediaState(val id: String, val to: String, val cameraOn: Boolean, val micOn: Boolean) : CallEffect
  /** Bring the call screen to the front and wake the screen (incoming over anything). */
  data object Present : CallEffect
  data class StartRing(val silent: Boolean) : CallEffect
  data object StopRing : CallEffect
  data object DropInChime : CallEffect
  /** Caller: open the self-preview while it rings (camera only, never the mic). */
  data class OpenPreview(val camera: Boolean) : CallEffect
  /** Callee after Accept: open camera + mic and send the signed offer. */
  data class StartMedia(val call: ActiveCall) : CallEffect
  /** Callee of a phone's call after Accept: tell the phone to open its media and send its offer. */
  data class SendGo(val id: String, val to: String) : CallEffect
  /** Grandma mode: the auto-answer chime (the countdown itself is on screen). */
  data object AutoAnswerChime : CallEffect
  data class SetCamera(val on: Boolean) : CallEffect
  data class SetMic(val on: Boolean) : CallEffect
  data object StopMedia : CallEffect
  data class Log(val entry: CallHistoryEntry) : CallEffect
  /** Tell the other rooms our presence changed (Available ↔ In a call). */
  data object AnnouncePresence : CallEffect
}

internal data class CallStep(val state: CallState, val effects: List<CallEffect> = emptyList())

/** Pure call state machine: every rule about what a call may do next lives here. */
internal object CallMachine {
  fun reduce(state: CallState, event: CallEvent): CallStep {
    val call = state.call
    return when (event) {
      is CallEvent.Dial -> dial(state, event)
      is CallEvent.Incoming -> incoming(state, event)
      is CallEvent.DismissEnded -> CallStep(state.copy(ended = null))
      is CallEvent.Tick -> if (call == null) CallStep(state) else tick(state, call, event.now)
      else -> if (call == null) CallStep(state) else active(state, call, event)
    }
  }

  private fun active(state: CallState, call: ActiveCall, event: CallEvent): CallStep = when (event) {
    is CallEvent.Replied -> replied(state, call, event)
    is CallEvent.Accept -> accept(state, call, event.audioOnly, event.now)
    is CallEvent.Decline -> if (call.direction == CallDirection.INCOMING && call.phase.beforeAnswer)
      end(state, call, CallEndReason.DECLINED, event.now, CallEffect.SendReply(call.id, call.peer.deviceId, CallReply.DECLINED))
    else CallStep(state)
    is CallEvent.HangUp -> hangUp(state, call, event.now, left = false)
    is CallEvent.Left -> hangUp(state, call, event.now, left = true)
    is CallEvent.RemoteEnded -> if (matches(call, event.id, event.from)) remoteEnded(state, call, event) else CallStep(state)
    is CallEvent.RemoteAccepted -> remoteAccepted(state, call, event)
    is CallEvent.MediaUp -> if (event.id == call.id && (call.phase == CallPhase.CONNECTING || call.phase == CallPhase.RECONNECTING)) {
      val connected = call.connectedMs ?: event.now
      val cap = call.capAtMs ?: if (call.kind == CallKind.DROP_IN) connected + CallTimings.DROP_IN_CAP_MS else null
      CallStep(state.copy(call = call.copy(phase = CallPhase.LIVE, phaseSinceMs = event.now, connectedMs = connected, capAtMs = cap)))
    } else CallStep(state)
    is CallEvent.MediaDown -> if (event.id == call.id && call.phase == CallPhase.LIVE)
      CallStep(state.copy(call = call.copy(phase = CallPhase.RECONNECTING, phaseSinceMs = event.now)))
    else CallStep(state)
    is CallEvent.MediaFailed -> if (event.id == call.id)
      end(state, call, event.reason, event.now, CallEffect.SendEnd(call.id, call.peer.deviceId, event.reason))
    else CallStep(state)
    is CallEvent.KeepGoing -> keepGoing(state, call, event.now)
    is CallEvent.RemoteKeepGoing -> if (matches(call, event.id, event.from) && call.capAtMs != null)
      CallStep(state.copy(call = call.copy(capAtMs = maxOf(call.capAtMs, event.capAtMs))))
    else CallStep(state)
    is CallEvent.ToggleMic -> toggleMic(state, call)
    is CallEvent.ToggleCamera -> toggleCamera(state, call)
    is CallEvent.CameraProblem -> CallStep(state.copy(call = call.copy(cameraOn = false, cameraIssue = event.issue)),
        listOf(CallEffect.SetCamera(false), media(call.copy(cameraOn = false))))
    is CallEvent.RemoteMedia -> if (matches(call, event.id, event.from))
      CallStep(state.copy(call = call.copy(remoteCameraOn = event.cameraOn, remoteMicOn = event.micOn)))
    else CallStep(state)
    else -> CallStep(state)
  }

  private fun matches(call: ActiveCall, id: String, from: String) = call.id == id && call.peer.deviceId == from

  private fun dial(state: CallState, e: CallEvent.Dial): CallStep {
    if (state.call != null) return CallStep(state)
    // A call shows the caller's own preview while it rings. A drop-in opens nothing until the other room's
    // reply says how it joins: Chime opens the preview for the countdown, Ask keeps everything closed until
    // they say yes (mock: "Camera off · Mic off until they say yes").
    val preview = e.video && e.kind == CallKind.CALL
    val call = ActiveCall(e.id, e.peer, CallDirection.OUTGOING, e.kind, e.video, CallPhase.DIALING, e.now, e.now,
        cameraOn = preview)
    return CallStep(CallState(call = call), listOf(CallEffect.SendRing(call), CallEffect.OpenPreview(preview),
        CallEffect.AnnouncePresence))
  }

  private fun incoming(state: CallState, e: CallEvent.Incoming): CallStep {
    val current = state.call
    if (current != null) {
      // A repeat of the ring we're already showing: answer again, change nothing.
      if (current.id == e.id && current.direction == CallDirection.INCOMING) {
        val reply = if (current.phase == CallPhase.COUNTDOWN) CallReply.COUNTDOWN else CallReply.RINGING
        return CallStep(state, if (current.phase.beforeAnswer) listOf(CallEffect.SendReply(e.id, e.peer.deviceId, reply)) else emptyList())
      }
      return CallStep(state, listOf(CallEffect.SendReply(e.id, e.peer.deviceId, CallReply.BUSY),
          CallEffect.Log(CallHistory.missed(e.id, e.peer, e.kind, e.now, CallEndReason.BUSY))))
    }
    return when (val d = e.decision) {
      is IncomingDecision.Reject -> CallStep(state, buildList {
        add(CallEffect.SendReply(e.id, e.peer.deviceId, d.reply))
        if (d.reply == CallReply.QUIET) add(CallEffect.Log(CallHistory.missed(e.id, e.peer, e.kind, e.now, CallEndReason.QUIET)))
      })
      is IncomingDecision.Ring -> {
        val call = ActiveCall(e.id, e.peer, CallDirection.INCOMING, e.kind, e.video, CallPhase.RINGING, e.now, e.now,
            cameraOn = e.video, silent = d.silent, peerHost = e.host, peerPort = e.port)
        CallStep(CallState(call = call), listOf(CallEffect.SendReply(e.id, e.peer.deviceId, CallReply.RINGING),
            CallEffect.Present, CallEffect.StartRing(d.silent), CallEffect.AnnouncePresence))
      }
      IncomingDecision.AutoAnswer -> {
        val call = ActiveCall(e.id, e.peer, CallDirection.INCOMING, CallKind.CALL, e.video, CallPhase.RINGING, e.now, e.now,
            cameraOn = e.video, peerHost = e.host, peerPort = e.port, autoAnswerAtMs = e.now + CallTimings.AUTO_ANSWER_MS)
        CallStep(CallState(call = call), listOf(CallEffect.SendReply(e.id, e.peer.deviceId, CallReply.RINGING),
            CallEffect.Present, CallEffect.AutoAnswerChime, CallEffect.AnnouncePresence))
      }
      IncomingDecision.Countdown -> {
        val call = ActiveCall(e.id, e.peer, CallDirection.INCOMING, CallKind.DROP_IN, e.video, CallPhase.COUNTDOWN, e.now, e.now,
            cameraOn = e.video, peerHost = e.host, peerPort = e.port)
        CallStep(CallState(call = call), listOf(CallEffect.SendReply(e.id, e.peer.deviceId, CallReply.COUNTDOWN),
            CallEffect.Present, CallEffect.DropInChime, CallEffect.AnnouncePresence))
      }
    }
  }

  private fun replied(state: CallState, call: ActiveCall, e: CallEvent.Replied): CallStep {
    if (!matches(call, e.id, e.from) || call.direction != CallDirection.OUTGOING || !call.phase.beforeAnswer) return CallStep(state)
    val now = e.now
    return when (e.reply) {
      CallReply.RINGING -> {
        if (call.phase != CallPhase.DIALING) CallStep(state)
        else CallStep(state.copy(call = call.copy(phase = CallPhase.RINGING, phaseSinceMs = now)))
      }
      CallReply.COUNTDOWN -> if (call.phase == CallPhase.COUNTDOWN) CallStep(state)
        else CallStep(state.copy(call = call.copy(phase = CallPhase.COUNTDOWN, phaseSinceMs = now, cameraOn = call.video)),
          if (!call.cameraOn && call.video) listOf(CallEffect.OpenPreview(true)) else emptyList())
      CallReply.QUIET -> end(state, call, CallEndReason.QUIET, now, quietEndMin = e.quietEndMin)
      CallReply.BUSY -> end(state, call, CallEndReason.BUSY, now)
      CallReply.CALLS_OFF -> end(state, call, CallEndReason.CALLS_OFF, now)
      CallReply.DROP_INS_OFF -> end(state, call, CallEndReason.DROP_INS_OFF, now)
      CallReply.DECLINED -> end(state, call, CallEndReason.DECLINED, now)
    }
  }

  private fun accept(state: CallState, call: ActiveCall, audioOnly: Boolean, now: Long): CallStep {
    if (call.direction != CallDirection.INCOMING || !call.phase.beforeAnswer) return CallStep(state)
    val next = call.copy(phase = CallPhase.CONNECTING, phaseSinceMs = now, cameraOn = call.video && !audioOnly, autoAnswerAtMs = null)
    // A phone has no server: it is always the offerer, so the Portal tells it to go and waits for the offer.
    val media = if (call.peer.phone) CallEffect.SendGo(call.id, call.peer.deviceId) else CallEffect.StartMedia(next)
    return CallStep(state.copy(call = next), listOf(CallEffect.StopRing, media))
  }

  private fun hangUp(state: CallState, call: ActiveCall, now: Long, left: Boolean): CallStep {
    val reason = when {
      call.direction == CallDirection.OUTGOING && call.phase.beforeAnswer -> CallEndReason.CANCELLED
      call.direction == CallDirection.INCOMING && call.phase.beforeAnswer -> CallEndReason.DECLINED
      left -> CallEndReason.LEFT
      else -> CallEndReason.HUNG_UP
    }
    val tell = if (reason == CallEndReason.DECLINED) CallEffect.SendReply(call.id, call.peer.deviceId, CallReply.DECLINED)
    else CallEffect.SendEnd(call.id, call.peer.deviceId, reason)
    return end(state, call, reason, now, tell)
  }

  private fun remoteEnded(state: CallState, call: ActiveCall, e: CallEvent.RemoteEnded): CallStep {
    val reason = when {
      call.direction == CallDirection.INCOMING && call.phase.beforeAnswer -> CallEndReason.MISSED
      e.reason == CallEndReason.LEFT || e.reason == CallEndReason.CANCELLED -> CallEndReason.HUNG_UP
      e.reason in REMOTE_KEEPS -> e.reason
      else -> CallEndReason.HUNG_UP
    }
    return end(state, call, reason, e.now)
  }

  private fun remoteAccepted(state: CallState, call: ActiveCall, e: CallEvent.RemoteAccepted): CallStep {
    if (!matches(call, e.id, e.from) || call.direction != CallDirection.OUTGOING || !call.phase.beforeAnswer) return CallStep(state)
    // An Ask drop-in that was accepted: the camera may open now (the call was placed with video).
    val camera = call.cameraOn || (call.kind == CallKind.DROP_IN && call.video)
    return CallStep(state.copy(call = call.copy(phase = CallPhase.CONNECTING, phaseSinceMs = e.now, cameraOn = camera)))
  }

  private fun tick(state: CallState, call: ActiveCall, now: Long): CallStep {
    val inPhase = now - call.phaseSinceMs
    val outgoing = call.direction == CallDirection.OUTGOING
    return when (call.phase) {
      CallPhase.DIALING -> if (inPhase >= CallTimings.DIAL_ACK_MS) end(state, call, CallEndReason.OFFLINE, now) else CallStep(state)
      CallPhase.RINGING -> when {
        !outgoing && call.autoAnswerAtMs != null && now >= call.autoAnswerAtMs -> accept(state, call, audioOnly = false, now)
        outgoing && inPhase >= CallTimings.RING_MS ->
          end(state, call, CallEndReason.NO_ANSWER, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.NO_ANSWER))
        !outgoing && inPhase >= CallTimings.RING_MS + RING_GRACE_MS -> end(state, call, CallEndReason.MISSED, now)
        else -> CallStep(state)
      }
      CallPhase.COUNTDOWN -> when {
        !outgoing && inPhase >= CallTimings.DROP_IN_COUNTDOWN_MS -> accept(state, call, audioOnly = false, now)
        outgoing && inPhase >= CallTimings.DROP_IN_COUNTDOWN_MS + CallTimings.CONNECT_MS ->
          end(state, call, CallEndReason.FAILED, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.FAILED))
        else -> CallStep(state)
      }
      CallPhase.CONNECTING -> if (inPhase >= CallTimings.CONNECT_MS)
        end(state, call, CallEndReason.FAILED, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.FAILED))
      else CallStep(state)
      CallPhase.RECONNECTING -> if (inPhase >= CallTimings.RECONNECT_MS)
        end(state, call, CallEndReason.NETWORK_LOST, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.NETWORK_LOST))
      else CallStep(state)
      CallPhase.LIVE -> if (call.capAtMs != null && now >= call.capAtMs)
        end(state, call, CallEndReason.CAP_REACHED, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.CAP_REACHED))
      else CallStep(state)
    }
  }

  private fun keepGoing(state: CallState, call: ActiveCall, now: Long): CallStep {
    val cap = call.capAtMs ?: return CallStep(state)
    if (!call.phase.connected) return CallStep(state)
    val next = maxOf(cap, now) + CallTimings.DROP_IN_CAP_MS
    return CallStep(state.copy(call = call.copy(capAtMs = next)), listOf(CallEffect.SendKeep(call.id, call.peer.deviceId, next)))
  }

  private fun toggleMic(state: CallState, call: ActiveCall): CallStep {
    val next = call.copy(micOn = !call.micOn)
    return CallStep(state.copy(call = next), listOf(CallEffect.SetMic(next.micOn), media(next)))
  }

  private fun toggleCamera(state: CallState, call: ActiveCall): CallStep {
    // Before an Ask drop-in is accepted nothing may open; the toggle only records the wish.
    val on = !call.cameraOn
    val next = call.copy(cameraOn = on, cameraIssue = if (on) null else call.cameraIssue)
    val blocked = call.direction == CallDirection.OUTGOING && call.kind == CallKind.DROP_IN && call.phase == CallPhase.RINGING
    val effects = buildList {
      if (!blocked) add(if (call.phase.beforeAnswer) CallEffect.OpenPreview(on) else CallEffect.SetCamera(on))
      if (call.phase.connected || call.phase == CallPhase.CONNECTING) add(media(next))
    }
    return CallStep(state.copy(call = next), effects)
  }

  private fun media(call: ActiveCall) = CallEffect.SendMediaState(call.id, call.peer.deviceId, call.cameraOn, call.micOn)

  private fun end(state: CallState, call: ActiveCall, reason: CallEndReason, now: Long, first: CallEffect? = null,
                  quietEndMin: Int? = null): CallStep {
    val ended = EndedCall(call.id, call.peer, call.direction, call.kind, reason, call.connectedMs, now, quietEndMin)
    val effects = buildList {
      first?.let(::add)
      add(CallEffect.StopRing)
      add(CallEffect.StopMedia)
      add(CallEffect.Log(CallHistory.of(ended)))
      add(CallEffect.AnnouncePresence)
    }
    return CallStep(state.copy(call = null, ended = ended), effects)
  }

  /** The incoming side waits a little longer than the caller, so the caller's end usually arrives first. */
  private const val RING_GRACE_MS = 2_000L
  private val REMOTE_KEEPS = setOf(CallEndReason.CAP_REACHED, CallEndReason.NETWORK_LOST, CallEndReason.FAILED,
      CallEndReason.NO_ANSWER, CallEndReason.HUNG_UP)
}
