/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The group half of [CallMachine]: adding people, joining a mesh, members leaving, and the first
 * member handing over to the next. Pure. [reduce] returns null for events that are not about a
 * leg, so the 1:1 rules (unchanged since phase 1) handle them.
 *
 * Mesh rules: the inviter rings the newcomer with the member list; the newcomer answers with an
 * offer to the inviter (as in 1:1) and, on Accept, offers to every other member. When the inviter
 * receives the newcomer's verified offer it tells each other connected member (`call_join`), and
 * a member accepts an offer from a newcomer only after a connected member vouched for it.
 *
 * Phase 2b: one paired phone may be a member. A phone has no server, so it always offers: a
 * Portal joining a call that has a phone waits for the phone's offer (the inviter tells the phone
 * with `call_join`). A move ("To phone") is an add whose ring says so; once the new device is
 * connected the mover leaves, and everyone else carries on with the new device.
 */
internal object CallGroupMachine {
  fun reduce(state: CallState, call: ActiveCall, event: CallEvent): CallStep? = when (event) {
    is CallEvent.AddPerson -> addPerson(state, call, event.peer, event.now, move = false)
    is CallEvent.MoveTo -> addPerson(state, call, event.target, event.now, move = true)
    is CallEvent.RemoteMoved -> if (event.id == call.id && call.move?.to == event.from && targetLive(call))
      completeMove(state, call, event.now) else CallStep(state)
    // Only the phone we took the call over from may ask for it back; its tap there is the consent.
    is CallEvent.RemotePull -> if (event.id == call.id && call.cameFrom == event.from && CallPhoneId.isPhone(event.from))
      addPerson(state, call, CallContact(event.from, call.cameFromName ?: "Phone", kind = CallContact.Kind.PHONE), event.now, move = true)
    else CallStep(state)
    is CallEvent.RemoteJoin -> remoteJoin(state, call, event)
    is CallEvent.Replied -> call.leg(event.from)?.takeIf { event.id == call.id }?.let { replied(state, call, it, event) }
    is CallEvent.RemoteAccepted -> call.leg(event.from)?.takeIf { event.id == call.id }?.let { offered(state, call, it, event.now) }
    is CallEvent.MediaUp -> legOf(call, event.id, event.peer)?.let { mediaUp(state, call, it, event.now) }
    is CallEvent.MediaDown -> legOf(call, event.id, event.peer)?.let { leg ->
      if (leg.phase == LegPhase.LIVE) step(state, call.replaceLeg(leg.copy(phase = LegPhase.RECONNECTING, sinceMs = event.now)))
      else CallStep(state)
    }
    is CallEvent.MediaFailed -> legOf(call, event.id, event.peer)?.let { drop(state, call, it, event.reason, event.now, tell = true) }
        ?: if (event.id == call.id && primary(call, event.peer) && call.hasConnectedLeg()) {
          handOver(state, call, event.reason, event.now, CallEffect.SendEnd(call.id, call.peer.deviceId, event.reason))
        } else null
    is CallEvent.RemoteEnded -> {
      val reason = if (event.movedTo != null) CallEndReason.MOVED else CallEndReason.HUNG_UP
      val to = event.movedTo?.let { id -> call.members.firstOrNull { it.deviceId == id }?.name }
      when {
        event.id != call.id -> null
        call.leg(event.from) != null -> drop(state, call, call.leg(event.from)!!, reason, event.now, tell = false, to = to)
        event.from == call.peer.deviceId && call.phase.connected && call.hasConnectedLeg() ->
          handOver(state, call, reason, event.now, null, to = to)
        else -> null
      }
    }
    is CallEvent.RemoteMedia -> call.leg(event.from)?.takeIf { event.id == call.id }?.let {
      step(state, call.replaceLeg(it.copy(remoteCameraOn = event.cameraOn, remoteMicOn = event.micOn)))
    }
    else -> null
  }

  /** Leg timeouts, then the first member's own reconnect handover; null when nothing changed. */
  fun tick(state: CallState, call: ActiveCall, now: Long): CallStep? {
    for (leg in call.legs) {
      val age = now - leg.sinceMs
      val step = when (leg.phase) {
        LegPhase.RINGING -> when {
          !leg.acked && age >= CallTimings.DIAL_ACK_MS -> drop(state, call, leg, CallEndReason.OFFLINE, now, tell = false)
          age >= CallTimings.RING_MS -> drop(state, call, leg, CallEndReason.NO_ANSWER, now, tell = true)
          else -> null
        }
        LegPhase.JOINING -> if (age >= CallTimings.CONNECT_MS) drop(state, call, leg, CallEndReason.FAILED, now, tell = true) else null
        LegPhase.RECONNECTING -> if (age >= CallTimings.RECONNECT_MS) drop(state, call, leg, CallEndReason.NETWORK_LOST, now, tell = true) else null
        LegPhase.LIVE -> null
      }
      if (step != null) return step
    }
    val live = call.move?.targetLiveMs
    if (live != null && targetLive(call) && now - live >= CallTimings.MOVE_SETTLE_MS) return completeMove(state, call, now)
    if (call.phase == CallPhase.RECONNECTING && now - call.phaseSinceMs >= CallTimings.RECONNECT_MS && call.hasConnectedLeg()) {
      return handOver(state, call, CallEndReason.NETWORK_LOST, now, CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.NETWORK_LOST))
    }
    return null
  }

  /**
   * On Accept of a group ring: join every member already in the call. We offer to each Portal; a
   * phone member can't take an offer, so we wait for its own (the inviter tells it we joined).
   */
  fun joinInvited(call: ActiveCall, now: Long): Pair<ActiveCall, List<CallEffect>> {
    if (call.invited.isEmpty()) return call to emptyList()
    val legs = call.invited.filter { it.contact.deviceId != call.peer.deviceId }.take(CallGroup.MAX_LEGS)
        .map { CallLeg(it.contact, LegPhase.JOINING, now, offerer = !it.contact.phone, host = it.host, port = it.port) }
    val next = call.copy(legs = legs, invited = emptyList())
    return next to legs.filter { it.offerer }.map { CallEffect.StartLeg(call.id, it) }
  }

  /** Effects for everyone beyond the first member when the call ends here. */
  fun endEffects(call: ActiveCall, reason: CallEndReason, movedTo: String? = null): List<CallEffect> = call.legs.map {
    if (it.phase == LegPhase.RINGING) CallEffect.SendEnd(call.id, it.peer.deviceId, CallEndReason.CANCELLED)
    else CallEffect.SendEnd(call.id, it.peer.deviceId, reason, movedTo)
  }

  /** Our camera/mic state goes to every member who is (being) connected. */
  fun mediaEffects(call: ActiveCall): List<CallEffect> = call.legs.filter { it.phase != LegPhase.RINGING }
      .map { CallEffect.SendMediaState(call.id, it.peer.deviceId, call.cameraOn, call.micOn, call.autoAnswered) }

  /**
   * The move target's side, after every event: once it is connected to everyone (its first member
   * and every leg live), it tells the device the call is moving from, once.
   */
  fun settleMove(step: CallStep): CallStep {
    val call = step.state.call ?: return step
    val move = call.move ?: return step
    val from = move.from ?: return step
    if (move.announced || call.phase != CallPhase.LIVE || call.legs.any { it.phase != LegPhase.LIVE }) return step
    val next = call.copy(move = move.copy(announced = true))
    return CallStep(step.state.copy(call = next), step.effects + CallEffect.SendMoved(call.id, from))
  }

  /** Whether [target] may be rung into this call now (Add person, or a move when [move]). */
  fun canAdd(call: ActiveCall, target: CallContact, move: Boolean): Boolean =
      call.phase == CallPhase.LIVE && call.kind == CallKind.CALL && !call.full && !call.locked && call.move == null &&
          !call.isMember(target.deviceId) && (!target.phone || call.phones < CallGroup.MAX_PHONES) &&
          // Only a phone takes a call over from this Portal; a move never targets another room.
          (!move || target.phone)

  private fun addPerson(state: CallState, call: ActiveCall, target: CallContact, now: Long, move: Boolean): CallStep {
    if (!canAdd(call, target, move)) return CallStep(state)
    val leg = CallLeg(target, LegPhase.RINGING, now, offerer = false, moving = move)
    val next = call.copy(legs = call.legs + leg, move = if (move) CallMove(to = target.deviceId) else call.move)
    return CallStep(state.copy(call = next), listOf(CallEffect.SendGroupRing(call.id, target, call.video, call.members, move)))
  }

  private fun remoteJoin(state: CallState, call: ActiveCall, e: CallEvent.RemoteJoin): CallStep {
    // An auto-answered (Grandma mode) call is never widened: only the chosen caller sees this camera.
    val vouched = e.id == call.id && call.phase.connected && call.kind == CallKind.CALL && !call.locked &&
        connectedMember(call, e.from)
    val phoneFull = e.newcomer.phone && call.phones >= CallGroup.MAX_PHONES
    if (!vouched || phoneFull || call.isMember(e.newcomer.deviceId) || call.full) return CallStep(state)
    val leg = CallLeg(e.newcomer, LegPhase.JOINING, e.now, offerer = false)
    return step(state, call.copy(legs = call.legs + leg))
  }

  private fun replied(state: CallState, call: ActiveCall, leg: CallLeg, e: CallEvent.Replied): CallStep {
    if (leg.phase != LegPhase.RINGING) return CallStep(state)
    return when (e.reply) {
      CallReply.RINGING, CallReply.COUNTDOWN -> step(state, call.replaceLeg(leg.copy(acked = true)))
      CallReply.QUIET -> drop(state, call, leg, CallEndReason.QUIET, e.now, tell = false)
      CallReply.BUSY -> drop(state, call, leg, CallEndReason.BUSY, e.now, tell = false)
      CallReply.DECLINED -> drop(state, call, leg, CallEndReason.DECLINED, e.now, tell = false)
      CallReply.CALLS_OFF, CallReply.DROP_INS_OFF -> drop(state, call, leg, CallEndReason.CALLS_OFF, e.now, tell = false)
    }
  }

  /** The newcomer's verified offer arrived: it is joining; vouch for it to every other connected member. */
  private fun offered(state: CallState, call: ActiveCall, leg: CallLeg, now: Long): CallStep {
    if (leg.phase != LegPhase.RINGING) return CallStep(state)
    val next = call.replaceLeg(leg.copy(phase = LegPhase.JOINING, sinceMs = now))
    val vouch = connectedIds(call).filter { it != leg.peer.deviceId }.map { CallEffect.SendJoin(call.id, it, leg.peer) }
    return CallStep(state.copy(call = next), vouch + CallEffect.SendMediaState(call.id, leg.peer.deviceId, call.cameraOn, call.micOn))
  }

  private fun mediaUp(state: CallState, call: ActiveCall, leg: CallLeg, now: Long): CallStep {
    if (leg.phase != LegPhase.JOINING && leg.phase != LegPhase.RECONNECTING) return CallStep(state)
    val live = call.replaceLeg(leg.copy(phase = LegPhase.LIVE, sinceMs = now, connectedMs = leg.connectedMs ?: now))
    val move = call.move?.takeIf { leg.moving && it.to == leg.peer.deviceId && it.targetLiveMs == null }
    return step(state, if (move != null) live.copy(move = move.copy(targetLiveMs = now)) else live)
  }

  /** The device we are moving to is connected: we leave, telling everyone where the call went. */
  private fun completeMove(state: CallState, call: ActiveCall, now: Long): CallStep {
    val to = call.move?.to ?: return CallStep(state)
    return CallMachine.end(state, call, CallEndReason.MOVED, now,
        CallEffect.SendEnd(call.id, call.peer.deviceId, CallEndReason.HUNG_UP, movedTo = to))
  }

  private fun targetLive(call: ActiveCall): Boolean {
    val to = call.move?.to ?: return false
    return call.leg(to)?.phase == LegPhase.LIVE || (call.peer.deviceId == to && call.phase == CallPhase.LIVE)
  }

  private fun drop(state: CallState, call: ActiveCall, leg: CallLeg, reason: CallEndReason, now: Long, tell: Boolean,
                   to: String? = null): CallStep {
    // The device we were moving to didn't come: the move is off and the call stays here.
    val moveOff = leg.moving && call.move?.to == leg.peer.deviceId
    val next = call.copy(legs = call.legs - leg, notice = CallNotice(leg.peer.name, reason, now, to = to, move = moveOff),
        move = if (moveOff) null else call.move?.takeUnless { it.from == leg.peer.deviceId },
        cameFrom = if (call.move?.from == leg.peer.deviceId) leg.peer.deviceId else call.cameFrom,
        cameFromName = if (call.move?.from == leg.peer.deviceId) leg.peer.name else call.cameFromName)
    val effects = buildList {
      if (tell) add(CallEffect.SendEnd(call.id, leg.peer.deviceId, reason))
      if (leg.phase != LegPhase.RINGING) add(CallEffect.ClosePeer(leg.peer.deviceId))
    }
    return CallStep(state.copy(call = next), effects)
  }

  /** The first member left; the next connected member takes its place and the call carries on. */
  private fun handOver(state: CallState, call: ActiveCall, reason: CallEndReason, now: Long, first: CallEffect?,
                       to: String? = null): CallStep {
    val heir = call.legs.first { it.phase != LegPhase.RINGING }
    val phase = when (heir.phase) {
      LegPhase.LIVE -> CallPhase.LIVE
      LegPhase.RECONNECTING -> CallPhase.RECONNECTING
      else -> CallPhase.CONNECTING
    }
    val next = call.copy(peer = heir.peer, phase = phase, phaseSinceMs = now, peerHost = heir.host, peerPort = heir.port,
        remoteCameraOn = heir.remoteCameraOn, remoteMicOn = heir.remoteMicOn, legs = call.legs - heir,
        notice = CallNotice(call.peer.name, reason, now, to = to),
        // The first member moved away from us, so the move (if we were its target) is complete for us.
        move = call.move?.takeUnless { it.from == call.peer.deviceId },
        cameFrom = if (call.move?.from == call.peer.deviceId) call.peer.deviceId else call.cameFrom,
        cameFromName = if (call.move?.from == call.peer.deviceId) call.peer.name else call.cameFromName)
    return CallStep(state.copy(call = next), listOfNotNull(first, CallEffect.ClosePeer(call.peer.deviceId)))
  }

  private fun legOf(call: ActiveCall, id: String, peer: String?): CallLeg? =
      if (id != call.id || peer == null) null else call.leg(peer)

  private fun primary(call: ActiveCall, peer: String?) = peer == null || peer == call.peer.deviceId

  private fun connectedMember(call: ActiveCall, id: String) = id in connectedIds(call)

  private fun connectedIds(call: ActiveCall): List<String> = buildList {
    if (call.phase.connected) add(call.peer.deviceId)
    call.legs.filter { it.phase.connected }.forEach { add(it.peer.deviceId) }
  }

  private fun ActiveCall.hasConnectedLeg() = legs.any { it.phase != LegPhase.RINGING }

  private fun ActiveCall.replaceLeg(leg: CallLeg) = copy(legs = legs.map { if (it.peer.deviceId == leg.peer.deviceId) leg else it })

  private fun step(state: CallState, call: ActiveCall) = CallStep(state.copy(call = call))
}
