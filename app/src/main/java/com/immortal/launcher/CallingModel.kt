/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Hearthpane Calling, the shared vocabulary. Pure Kotlin (no Android) so the state machine and
 * policies are JVM-tested.
 *
 * Phase 1 is Portal↔Portal inside one home over the LAN. The model already separates *who* (a
 * [CallContact] in a household) from *which device* answers, and *how* bytes travel
 * ([CallTransport]), so cross-home calls, phones and groups slot in later without reshaping state.
 */

/** Where a contact lives. Phase 1 only knows [HOME]; other households arrive with the relay. */
internal object CallHousehold {
  const val HOME = "home"
}

/** Someone (or some room) you can call: a household plus the device that rings. */
@Immutable
internal data class CallContact(
    val deviceId: String,
    val name: String,
    val household: String = CallHousehold.HOME,
    val kind: Kind = Kind.ROOM,
) {
  /** [PHONE]: a paired phone running Portal Toolkit; it signals over this Portal's HTTP routes. */
  enum class Kind { ROOM, PERSON, PHONE }

  val phone: Boolean get() = kind == Kind.PHONE
}

/** How a call's signalling reaches the other side. Phase 1: [LAN]. A relay transport comes later. */
internal enum class CallTransport { LAN }

/** A call rings; a drop-in chimes and (if allowed) joins by itself. */
internal enum class CallKind(val wire: String) {
  CALL("c"), DROP_IN("d");

  companion object {
    fun fromWire(raw: String?): CallKind? = entries.firstOrNull { it.wire == raw }
  }
}

internal enum class CallDirection { OUTGOING, INCOMING }

/** What a room says about itself in the Calls app. */
internal enum class CallPresence(val label: String) {
  AVAILABLE("Available"), IN_CALL("In a call"), QUIET("Quiet hours"), OFFLINE("Offline");
}

/** This Portal's rule for drop-ins *from* one other room (the mock's grid cell). */
internal enum class DropInPolicy(val wire: String, val label: String) {
  OFF("o", "Off"), ASK("a", "Ask"), CHIME("c", "Chime, join");

  /** The grid cycles Off → Ask → Chime, join on each tap. */
  fun next(): DropInPolicy = entries[(ordinal + 1) % entries.size]

  companion object {
    fun fromWire(raw: String?): DropInPolicy? = entries.firstOrNull { it.wire == raw }
  }
}

/** How the callee answered a ring (bus `call_reply`). */
internal enum class CallReply(val wire: String) {
  /** It is ringing there (normal call or an Ask drop-in). */
  RINGING("r"),
  /** Chime-then-join: the banner countdown is running there. */
  COUNTDOWN("cd"),
  QUIET("q"),
  BUSY("b"),
  /** "Who can call" is Nobody there. */
  CALLS_OFF("x"),
  /** Drop-ins from this room are Off there (or drop-ins are off on that Portal). */
  DROP_INS_OFF("dx"),
  DECLINED("d");

  companion object {
    fun fromWire(raw: String?): CallReply? = entries.firstOrNull { it.wire == raw }
  }
}

/** Why a call ended. [wire] travels in `call_end` for the reasons the other side should hear. */
internal enum class CallEndReason(val wire: String) {
  HUNG_UP("h"),
  CANCELLED("c"),
  DECLINED("d"),
  NO_ANSWER("n"),
  /** The callee's view of a ring that stopped before anyone answered. */
  MISSED("m"),
  OFFLINE("o"),
  BUSY("b"),
  QUIET("q"),
  CALLS_OFF("x"),
  DROP_INS_OFF("dx"),
  NETWORK_LOST("l"),
  /** The call screen went away (Back, Home, screen off): privacy closes everything. */
  LEFT("v"),
  CAP_REACHED("cap"),
  FAILED("f"),
  /** No camera/mic permission and the user declined to grant it. */
  NO_PERMISSION("p"),
  /** Calling can't run on this Portal (no WebRTC for this CPU). */
  UNAVAILABLE("u"),
  /**
   * Phase 2b, local only: this device handed the call to another one ("Move to phone"). On the wire
   * the mover sends [HUNG_UP] plus an optional `mv` naming the device that took over, so older
   * Portals still close the leg cleanly.
   */
  MOVED("mv");

  companion object {
    fun fromWire(raw: String?): CallEndReason? = entries.firstOrNull { it.wire == raw }
  }
}

/** Why the camera isn't sending, shown honestly in the call. */
internal enum class CameraIssue {
  /** Another client (Portal's camera service) holds it or it sent no frames. */
  BUSY,
  /** The privacy shutter is closed (black frames / camera disabled). */
  SHUTTER,
  /** CAMERA permission not granted. */
  PERMISSION,
}

/** Quality preference (Settings › Calls › Call quality). */
internal enum class CallQuality(val wire: String, val label: String, val width: Int, val height: Int,
                                val fps: Int, val maxKbps: Int) {
  AUTO("auto", "Auto", 1280, 720, 30, 2_500),
  SAVE("save", "Save data", 640, 360, 30, 700),
  BEST("best", "Best", 1280, 720, 30, 4_000);

  companion object {
    fun fromWire(raw: String?): CallQuality = entries.firstOrNull { it.wire == raw } ?: AUTO
    /** What a low-memory Portal steps down to, whatever was chosen. */
    val LOW_MEMORY = SAVE
  }
}

/** Ringtones (Settings › Calls › Ringtone), synthesised on device by [CallingRingtones]. */
internal enum class CallRingtone(val wire: String, val label: String, val help: String) {
  HEARTH("hearth", "Hearth chime", "Warm, two notes. Our default."),
  WIND("wind", "Wind bells", "Gentle, good for bedrooms"),
  MARIMBA("marimba", "Soft marimba", "Wooden, rounded"),
  CLASSIC("classic", "Classic ring", "Like an old phone");

  companion object {
    fun fromWire(raw: String?): CallRingtone = entries.firstOrNull { it.wire == raw } ?: HEARTH
  }
}

/** Who may ring this Portal (phase 1: the rooms of this home, or nobody). */
internal enum class CallWhoCanCall(val wire: String, val label: String) {
  HOME("home", "Rooms in this home"), NOBODY("nobody", "Nobody");

  companion object {
    fun fromWire(raw: String?): CallWhoCanCall = entries.firstOrNull { it.wire == raw } ?: HOME
  }
}

/** Quiet hours behaviour: block (mock default) or a silent, visual-only ring. */
internal enum class CallQuietMode(val wire: String, val label: String) {
  BLOCK("block", "Don’t ring, tell the caller"), VISUAL("visual", "Show the call silently");

  companion object {
    fun fromWire(raw: String?): CallQuietMode = entries.firstOrNull { it.wire == raw } ?: BLOCK
  }
}

/** Named timings from the approved mock (Decisions and notes). */
internal object CallTimings {
  /** The caller waits this long for any `call_reply` before calling the Portal offline. */
  const val DIAL_ACK_MS = 4_000L
  /** Ring timeout → "didn’t answer". */
  const val RING_MS = 45_000L
  /** Drop-in banner countdown before camera and mic go live. */
  const val DROP_IN_COUNTDOWN_MS = 3_000L
  /** Signalling + ICE must finish within this after Accept. */
  const val CONNECT_MS = 15_000L
  /** A dropped connection may recover for this long before the call ends. */
  const val RECONNECT_MS = 10_000L
  /** Drop-ins end after 15 minutes unless someone taps Keep going (+15 each time). */
  const val DROP_IN_CAP_MS = 15 * 60_000L
  /** A room not heard from for this long shows as Offline. */
  const val OFFLINE_AFTER_MS = 90_000L
  /** The ended screen closes itself after this (mock: back to Photos in 5 s). */
  const val ENDED_AUTO_CLOSE_MS = 5_000L
  /** Grandma mode: auto-answer connects after this countdown (chime, Not now, Answer now). */
  const val AUTO_ANSWER_MS = 10_000L
  /** Move to phone / Portal: once the new device is connected, the old one leaves after at most this. */
  const val MOVE_SETTLE_MS = 8_000L
  /** "Move it back here": the phone's move ring is taken without a second tap for this long. */
  const val PULL_BACK_MS = 20_000L
  /** The phone a call moved from may ask for it back only this soon after the move. */
  const val PULL_WINDOW_MS = 120_000L
  /** History is kept on this Portal for 30 days. */
  const val HISTORY_KEEP_MS = 30L * 24 * 60 * 60_000L
}
