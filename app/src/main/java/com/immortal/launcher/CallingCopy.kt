/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone

/** Every Calling sentence, from the approved mock's copy, as pure functions (JVM-tested). */
internal object CallingCopy {
  const val E2E = "E2E"
  const val SELF_LABEL = "You · Auto-framed"
  const val SELF_LABEL_SHORT = "You"
  const val CAMERA_OFF = "Camera off"
  const val NOBODY_SEES = "Nobody can see you"
  const val ANSWER_PRIVACY = "Your camera and mic stay off until you answer"
  const val ENCRYPTED = "End-to-end encrypted"
  const val DIRECT = "Direct, no relay needed"
  const val CALLS_FOOTER = "Only Portals in this home can call. Calls go straight between Portals, end-to-end encrypted."
  const val WIDGET_FOOTER = "Tap a face to call. You can cancel straight away."
  const val HISTORY_FOOTER = "History lives on this Portal only, for 30 days. No server keeps a call log."
  const val FRAMING_NOTE = "Portal’s camera frames you automatically. It can’t be switched off from Hearthpane."
  const val VOLUME_NOTE = "Hey Portal volume keys work too. Echo cancelling stays on at every level."
  const val LOW_MEMORY = "Low on memory · sending a lighter picture to keep things smooth"

  /** The live bar: always on screen during a call. */
  fun liveBar(cameraOn: Boolean, micOn: Boolean): String =
      "${if (cameraOn) "Camera on" else "Camera off"} · ${if (micOn) "Mic on" else "Mic muted"}"

  fun liveDotActive(cameraOn: Boolean, micOn: Boolean) = cameraOn || micOn

  fun outgoingTitle(kind: CallKind, ask: Boolean, name: String): String = when {
    kind == CallKind.CALL -> "Calling $name"
    ask -> "Asking $name"
    else -> "Dropping in on $name"
  }

  fun outgoingSub(call: ActiveCall): String = when {
    call.phase == CallPhase.DIALING -> "Reaching ${call.peer.name}…"
    call.kind == CallKind.CALL -> "Ringing the ${call.peer.name} Portal"
    call.phase == CallPhase.COUNTDOWN -> "${call.peer.name} is chiming and showing a banner. Anyone there can say “Not now”."
    else -> "${call.peer.name} asks first. Nothing opens until someone there taps Join."
  }

  fun outgoingLiveBar(call: ActiveCall, countdownS: Int): String = when {
    call.kind == CallKind.DROP_IN && call.phase == CallPhase.COUNTDOWN -> "${liveBar(call.cameraOn, call.micOn)} · joining in $countdownS s"
    call.kind == CallKind.DROP_IN -> "Camera off · Mic off until they say yes"
    call.cameraOn -> "Camera preview · only you can see it"
    else -> "Audio call · camera off"
  }

  fun incomingSub(kind: CallKind, video: Boolean, name: String): String = when {
    kind == CallKind.DROP_IN -> "Drop-in from $name"
    video -> "Video call from $name"
    else -> "Audio call from $name"
  }

  /**
   * The incoming line, phase 2b aware: a move ("Sam’s phone wants to move its call here · with
   * Kitchen"), a group ring (mock `phgroup`: "Living room added you to a call with Kitchen"), else
   * the 1:1 line.
   */
  fun incomingLine(call: ActiveCall): String {
    val others = call.invited.map { it.contact.name }
    val with = if (others.isEmpty()) "" else " · with ${names(others)}"
    return when {
      call.move != null -> "Moving a call here$with"
      others.isNotEmpty() -> "${call.peer.name} added you to a call with ${names(others)}"
      else -> incomingSub(call.kind, call.video, call.peer.name)
    }
  }

  /** "Kitchen", "Kitchen and Den", "Kitchen, Den and Kids". */
  fun names(list: List<String>): String = when (list.size) {
    0 -> ""
    1 -> list[0]
    else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
  }

  /** The answer button: "Pick up here" for a move. */
  fun answerLabel(call: ActiveCall) = if (call.move != null) "Pick up here" else "Answer"

  /** Group tile label (mock "Priya · phone"). */
  fun tileName(contact: CallContact) = if (contact.phone) "${contact.name} · phone" else contact.name

  fun dropInBannerTitle(ask: Boolean, name: String) = if (ask) "$name wants to drop in" else "$name is dropping in"

  fun dropInBannerSub(ask: Boolean, seconds: Int) =
      if (ask) "Your camera and mic stay off unless you say yes." else "Chime played. Camera and mic turn on in $seconds s."

  fun dropInBar(name: String, incoming: Boolean, cameraOn: Boolean, micOn: Boolean) =
      "Drop-in ${if (incoming) "from" else "with"} $name · ${liveBar(cameraOn, micOn)}"

  fun capProgress(elapsedMs: Long, capMs: Long) = "${clock(elapsedMs)} of ${clock(capMs)}"

  /** 4:12, 1:04:12. */
  fun clock(ms: Long): String {
    val s = (ms / MS_PER_S).coerceAtLeast(0)
    val h = s / S_PER_H
    val m = (s % S_PER_H) / S_PER_M
    val sec = s % S_PER_M
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
  }

  /** "24 min", "1 min", "Under a minute". */
  fun duration(ms: Long): String {
    val min = ms / MS_PER_MIN
    return when {
      min < 1 -> "Under a minute"
      min < MIN_PER_H -> "$min min"
      else -> "${min / MIN_PER_H} h ${min % MIN_PER_H} min"
    }
  }

  data class Ended(val title: String, val body: String, val retry: Boolean, val icon: String, val warn: Boolean = false)

  fun ended(e: EndedCall, timeLabel: String, quietEndLabel: String?): Ended {
    val n = e.peer.name
    return when (e.reason) {
      CallEndReason.NO_ANSWER -> Ended("$n didn’t answer", "Rang for 45 seconds. Nothing was recorded.", true, "bellOff")
      CallEndReason.QUIET -> Ended("$n is in quiet hours",
          (quietEndLabel?.let { "Their Portal won’t ring until $it. " } ?: "Their Portal won’t ring right now. ") +
              "We didn’t wake anyone.", false, "moon")
      CallEndReason.OFFLINE -> if (e.peer.phone) Ended("$n didn’t pick up the ring",
          "Portal Toolkit needs to be open on the phone, or “Ring when Portal Toolkit is closed” turned on there.",
          true, "wifiOff", warn = true)
      else Ended("Their Portal is offline",
          "$n didn’t answer the network. It may be unplugged or off Wi-Fi.", true, "wifiOff", warn = true)
      CallEndReason.MOVED -> Ended("Call moved to ${e.movedTo?.name ?: "your phone"}", MOVED_BODY, false, "handoff")
      CallEndReason.BUSY -> Ended("$n is in another call", "Try again in a little while.", true, "phone")
      CallEndReason.DECLINED -> if (e.direction == CallDirection.OUTGOING)
        Ended("$n can’t talk right now", "They tapped Not now. Nothing was recorded.", true, "phone")
      else Ended("Declined", "$n was told you can’t talk right now.", false, "phone")
      CallEndReason.CALLS_OFF -> Ended("$n doesn’t take calls", "Calls are turned off on that Portal.", false, "bellOff")
      CallEndReason.DROP_INS_OFF -> Ended("$n doesn’t take drop-ins",
          "Drop-ins are set per room. You can ring $n like a normal call. It rings, and only opens if someone answers.",
          false, "door")
      CallEndReason.NETWORK_LOST -> Ended("The call dropped",
          "The Wi-Fi connection to $n was lost${if (e.durationMs > 0) " after ${duration(e.durationMs)}" else ""}. Nothing was stored.",
          true, "wifiOff", warn = true)
      CallEndReason.FAILED -> Ended("The call couldn’t connect",
          "The two Portals couldn’t set up a direct, encrypted connection. Check both are on the home Wi-Fi.", true, "warn", warn = true)
      CallEndReason.UNAVAILABLE -> Ended("Calls aren’t ready here",
          "This Portal needs the household key from Portal Toolkit, and a home Wi-Fi address.", false, "warn", warn = true)
      CallEndReason.NO_PERMISSION -> Ended("Camera or microphone not allowed",
          "Allow the camera and microphone for Hearthpane to make calls.", false, "warn", warn = true)
      CallEndReason.MISSED -> Ended("Missed call from $n", "$timeLabel · nothing rang for long enough to answer.", true, "phone")
      CallEndReason.CAP_REACHED -> Ended("Drop-in with $n ended", "Drop-ins end after 15 minutes unless someone taps Keep going.",
          true, "door")
      CallEndReason.HUNG_UP, CallEndReason.LEFT, CallEndReason.CANCELLED -> Ended(
          "${if (e.kind == CallKind.DROP_IN) "Drop-in" else "Call"} with $n ended",
          if (e.durationMs > 0) "${duration(e.durationMs)} · $timeLabel · direct between Portals, nothing stored"
          else "$timeLabel · nothing was recorded", true, "phone")
    }
  }

  const val MOVED_BODY = "This Portal’s camera and mic are off. The call carries on, still end-to-end encrypted."
  const val MOVE_BACK = "Move it back here"

  /** History row subtitle. */
  fun historyLine(e: CallHistoryEntry, timeLabel: String): String {
    val what = when {
      e.reason == CallEndReason.QUIET && e.direction == CallDirection.INCOMING -> "quiet hours, nothing rang"
      e.missed -> "missed"
      e.kind == CallKind.DROP_IN && e.durationMs > 0 -> "drop-in · ${duration(e.durationMs)}"
      e.durationMs > 0 -> "call · ${duration(e.durationMs)}"
      e.direction == CallDirection.OUTGOING -> "didn’t connect"
      else -> "declined"
    }
    return "$timeLabel · $what"
  }

  fun historyDay(atMs: Long, nowMs: Long, zone: TimeZone): String {
    val day = dayIndex(atMs, zone)
    val today = dayIndex(nowMs, zone)
    return when (today - day) {
      0L -> "Today"
      1L -> "Yesterday"
      else -> "Earlier"
    }
  }

  /** Local calendar day number (days since the epoch in [zone]), continuous across years. */
  private fun dayIndex(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)

  fun minuteLabel(minuteOfDay: Int, use24: Boolean): String {
    val h = minuteOfDay / MIN_PER_H
    val m = minuteOfDay % MIN_PER_H
    if (use24) return "%d:%02d".format(h, m)
    val h12 = if (h % HALF_DAY == 0) HALF_DAY else h % HALF_DAY
    return "%d:%02d %s".format(h12, m, if (h < HALF_DAY) "AM" else "PM")
  }

  /** Initials for a room avatar: "Living Area" → "LA", "Kitchen" → "K". */
  fun initials(name: String): String = name.split(' ', '-', '’', '\'').filter { it.isNotBlank() }
      .take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }

  private const val MS_PER_S = 1_000L
  private const val MS_PER_MIN = 60_000L
  private const val S_PER_M = 60L
  private const val S_PER_H = 3_600L
  private const val MIN_PER_H = 60
  private const val HALF_DAY = 12
  private const val DAY_MS = 24L * 60 * 60 * 1000
}
