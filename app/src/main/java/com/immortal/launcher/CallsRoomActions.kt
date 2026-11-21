/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What a room card can do right now. */
internal enum class CallRoomAction { VIDEO, DROP_IN, AUDIO }

/**
 * A room card shows only the actions that room will take (mock: compact round actions, no greyed
 * "Not allowed" pill). Calls off means nothing to offer; drop-ins off leaves video and audio. The
 * status note says why something is missing (pure, unit tested).
 */
internal object CallRoomActions {
  fun of(acceptsCalls: Boolean, drop: DropInPolicy): List<CallRoomAction> = when {
    !acceptsCalls -> emptyList()
    drop == DropInPolicy.OFF -> listOf(CallRoomAction.VIDEO, CallRoomAction.AUDIO)
    else -> listOf(CallRoomAction.VIDEO, CallRoomAction.DROP_IN, CallRoomAction.AUDIO)
  }

  /** The drop-in button's words for an allowed policy. */
  fun dropInLabel(drop: DropInPolicy): String = if (drop == DropInPolicy.ASK) "Ask to join" else "Drop in"

  /**
   * A card action's screen-reader name. Every room and phone card has the same buttons, so the name says
   * which card it is on: "Video call Living Area", "Drop in Kitchen". The visible words come first.
   */
  fun spoken(label: String, name: String): String = "$label $name"

  /** The star button (icon only): "Show Kitchen on Home" / "Remove Kitchen from Home". */
  fun favouriteSpoken(favourite: Boolean, name: String): String =
      if (favourite) "Remove $name from Home" else "Show $name on Home"

  /** A phone card's message button: "Leave a message for Priya". */
  fun leaveMessageSpoken(name: String): String = "${CallMessageCopy.LEAVE} for $name"

  /** The card's status note under the presence line. */
  enum class Note(val text: String) { CALLS_OFF("Calls off"), DROP_INS_ON("Drop-ins on"), ASKS_FIRST("Asks first"), DROP_INS_OFF("Drop-ins off") }

  fun note(acceptsCalls: Boolean, drop: DropInPolicy): Note = when {
    !acceptsCalls -> Note.CALLS_OFF
    drop == DropInPolicy.CHIME -> Note.DROP_INS_ON
    drop == DropInPolicy.ASK -> Note.ASKS_FIRST
    else -> Note.DROP_INS_OFF
  }
}
