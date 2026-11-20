/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** What one group tile shows. */
@Immutable
internal sealed interface CallTile {
  /** Another member: their picture (or avatar), name, mic and camera state, or a ringing card. */
  data class Member(val contact: CallContact, val state: State, val cameraOn: Boolean, val micOn: Boolean) : CallTile
  /** This Portal: the self view, always on screen while the camera is open. */
  data class Self(val cameraOn: Boolean) : CallTile

  enum class State { RINGING, CONNECTING, LIVE, RECONNECTING }
}

/** How the tiles sit (mock `.tiles.n3` / `.n4`; upright Mini stacks the big tile on top). */
internal enum class TileArrangement {
  /** 1:1 layout (full-bleed remote and a self view). */
  SOLO,
  /** Three people: one big tile left, two stacked right (`1.25fr 1fr`). */
  THREE_SIDE,
  /** Three people upright: one big tile on top, two side by side below. */
  THREE_STACKED,
  /** Four people: 2×2. */
  FOUR_GRID,
}

/** Pure tile rules (JVM-tested): order, state and arrangement for a call. */
internal object CallTiles {
  /** Weight of the big tile against the others (mock `grid-template-columns: 1.25fr 1fr`). */
  const val BIG_SHARE = 1.25f

  /** The first member big, then the others in the order they were added, this Portal last. */
  fun of(call: ActiveCall, probe: Boolean = false): List<CallTile> = buildList {
    add(CallTile.Member(call.peer, firstState(call.phase), call.remoteCameraOn, call.remoteMicOn))
    call.legs.forEach { add(CallTile.Member(it.peer, legState(it.phase), it.remoteCameraOn, it.remoteMicOn)) }
    // Measurement builds only: the in-process load probe holds a tile like a real member would.
    if (probe && call.legs.size < CallGroup.MAX_LEGS) add(CallTile.Member(CallContact(CallMedia.PROBE, PROBE_NAME), CallTile.State.LIVE, true, false))
    add(CallTile.Self(call.cameraOn))
  }

  const val PROBE_NAME = "Test peer"


  fun arrangement(people: Int, layout: LayoutClass): TileArrangement = when {
    people <= 2 -> TileArrangement.SOLO
    people == 3 -> if (layout.compactPortrait) TileArrangement.THREE_STACKED else TileArrangement.THREE_SIDE
    else -> TileArrangement.FOUR_GRID
  }

  /** The live bar's text in a group: "Camera on · Mic on · 3 of 4". */
  fun liveBar(call: ActiveCall): String = "${CallingCopy.liveBar(call.cameraOn, call.micOn)} · ${call.people} of ${CallGroup.MAX_PEOPLE}"

  private fun firstState(phase: CallPhase) = when (phase) {
    CallPhase.LIVE -> CallTile.State.LIVE
    CallPhase.RECONNECTING -> CallTile.State.RECONNECTING
    else -> CallTile.State.CONNECTING
  }

  private fun legState(phase: LegPhase) = when (phase) {
    LegPhase.RINGING -> CallTile.State.RINGING
    LegPhase.JOINING -> CallTile.State.CONNECTING
    LegPhase.LIVE -> CallTile.State.LIVE
    LegPhase.RECONNECTING -> CallTile.State.RECONNECTING
  }
}

/** Add person: what each room in this home offers in the sheet. Pure. */
internal object CallAddPeople {
  /**
   * Phase 2b adds phones: [PHONE_CLOSED] Portal Toolkit isn't open (or ringing in the background)
   * there; [NOT_PAIRED] the phone and a Portal in this call aren't paired, so they couldn't see each
   * other; [ONE_PHONE] a phone is already in the call; [LOCKED] the call answered by itself.
   */
  enum class Option { IN_CALL, QUIET, OFFLINE, BUSY, CALLS_OFF, FULL, PHONE_CLOSED, NOT_PAIRED, ONE_PHONE, LOCKED, ADD }

  /** [phone]: a paired phone rather than a Portal ([peer] then carries its id, name and last poll). */
  @Immutable
  data class Row(val peer: CallPeer, val option: Option, val phone: Boolean = false)

  fun rows(call: ActiveCall, peers: List<CallPeer>, selfId: String?, nowMs: Long, phones: List<CallPhone> = emptyList()): List<Row> {
    val phoneInCall = call.members.firstOrNull { it.phone }?.let { m -> phones.firstOrNull { it.id == m.deviceId } }
    val rooms = peers.filter { it.id != selfId }.map { p -> Row(p, roomOption(call, p, nowMs, phoneInCall)) }
    val handsets = phones.map { ph -> Row(CallPeer(ph.id, ph.name, ph.seenMs), phoneOption(call, ph, selfId, nowMs), phone = true) }
    return (rooms + handsets).sortedWith(compareBy<Row> { it.option != Option.ADD }.thenBy { it.phone }.thenBy { it.peer.name.lowercase() })
  }

  /** What a phone row offers: Add only when it rings now and is paired with every Portal in the call. */
  fun phoneOption(call: ActiveCall, ph: CallPhone, selfId: String?, nowMs: Long): Option = when {
    call.isMember(ph.id) -> Option.IN_CALL
    call.full -> Option.FULL
    call.locked -> Option.LOCKED
    call.phones >= CallGroup.MAX_PHONES -> Option.ONE_PHONE
    !ph.reachable(nowMs) -> Option.PHONE_CLOSED
    unpaired(call, ph, selfId).isNotEmpty() -> Option.NOT_PAIRED
    else -> Option.ADD
  }

  /** Portals in the call (other than this one) the phone has no session with. */
  fun unpaired(call: ActiveCall, ph: CallPhone, selfId: String?): List<CallContact> =
      call.members.filterNot { it.phone || it.deviceId == selfId || ph.pairedWith(it.deviceId) }

  private fun roomOption(call: ActiveCall, p: CallPeer, nowMs: Long, phoneInCall: CallPhone?): Option = when {
    call.isMember(p.id) -> Option.IN_CALL
    call.full -> Option.FULL
    call.locked -> Option.LOCKED
    // The phone in this call must be able to reach the newcomer through its own session there.
    phoneInCall != null && !phoneInCall.pairedWith(p.id) -> Option.NOT_PAIRED
    else -> when (CallPolicy.presence(p, nowMs)) {
      CallPresence.OFFLINE -> Option.OFFLINE
      CallPresence.QUIET -> Option.QUIET
      CallPresence.IN_CALL -> Option.BUSY
      CallPresence.AVAILABLE -> if (p.acceptsCalls) Option.ADD else Option.CALLS_OFF
    }
  }

  /** The sheet's subtitle: "Up to 4 people · 2 in the call now" or "4 of 4 · this call is full". */
  fun subtitle(call: ActiveCall): String =
      if (call.full) "${CallGroup.MAX_PEOPLE} of ${CallGroup.MAX_PEOPLE} · this call is full"
      else "Up to ${CallGroup.MAX_PEOPLE} people · ${call.people} in the call now"

  /** Toast copy for a group event. */
  fun notice(n: CallNotice): String = if (n.move) "${n.name} didn’t pick up · the call stays here" else when (n.reason) {
    CallEndReason.MOVED -> if (n.to != null) "${n.name} moved the call to ${n.to}" else "${n.name} handed the call over"
    CallEndReason.QUIET -> "${n.name} is in quiet hours · we didn’t ring"
    CallEndReason.BUSY -> "${n.name} is in another call"
    CallEndReason.DECLINED -> "${n.name} can’t join right now"
    CallEndReason.CALLS_OFF -> "${n.name} doesn’t take calls right now"
    CallEndReason.OFFLINE -> "${n.name} is offline"
    CallEndReason.NO_ANSWER -> "${n.name} didn’t answer"
    CallEndReason.FAILED -> "${n.name} couldn’t connect"
    CallEndReason.NETWORK_LOST -> "${n.name} lost the connection"
    else -> "${n.name} left the call"
  }
}
