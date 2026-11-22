/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CallPeer
import com.immortal.launcher.CallPhoneId
import com.immortal.launcher.CallPolicy
import com.immortal.launcher.CallPresence
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.call` for Jarvis: "call the Living Area" → an in-home video call to another Portal in this home, over
 * the home network only. Rooms come only from the Portals heard on the household bus; a paired phone, any internet
 * or phone-network number, and drop-ins are never reachable this way. Like the Calls screen's own buttons it dials at
 * once, and the call screen that opens lets the user hang up before anyone answers. A room in quiet hours, on
 * another call, offline or not taking calls is not dialled. Arguments are untrusted (model output). Pure apart from
 * its seams, so every rule is JVM-tested.
 */
internal class HearthpaneCall(
    private val ready: () -> Boolean,
    private val selfName: () -> String,
    private val peers: () -> List<CallPeer>,
    /** This Portal is already on a call. */
    private val busy: () -> Boolean,
    /** Places the video call to that in-home Portal and opens the call screen; false if it couldn't start. */
    private val dial: (peerId: String, video: Boolean) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { call(args) }.getOrElse { error("calls unavailable") }
  }

  private fun call(args: JSONObject): JSONObject {
    val room = (args.opt("room") as? String)?.trim().orEmpty()
    if (room.isEmpty()) return error("room is required: which room to call, e.g. Living Area")
    if (!ready()) return reply("In-home calls aren't set up on this Portal yet.")
    if (busy()) return reply("This Portal is already on a call.")
    val rooms = peers().filter { !CallPhoneId.isPhone(it.id) }
    if (RoomMatch.norm(room) == RoomMatch.norm(selfName())) return reply("That's this room. Which other room should I call?")
    val name = RoomMatch.find(room, rooms.map { it.name })
        ?: return JSONObject().put("needs_room", true).put("message", "There's no Portal called ${room.take(MAX_ROOM)} " +
            "in this home. " + if (rooms.isEmpty()) "No other Portals have been heard yet."
            else "Ask which room: ${HearthpaneLists.spoken(rooms.map { it.name })}.").put("rooms", JSONArray(rooms.map { it.name }))
    val peer = rooms.first { it.name == name }
    val why = when {
      !peer.acceptsCalls -> "The ${peer.name} isn't taking calls."
      else -> when (CallPolicy.presence(peer, now())) {
        CallPresence.OFFLINE -> "The ${peer.name} isn't answering on the home network right now."
        CallPresence.IN_CALL -> "The ${peer.name} is on another call."
        CallPresence.QUIET -> "It's quiet hours in the ${peer.name}, so calls don't ring there."
        CallPresence.AVAILABLE -> null
      }
    }
    if (why != null) return reply(why)
    val video = (args.opt("video") as? Boolean) ?: true
    if (!dial(peer.id, video)) return reply("Couldn't start the call to the ${peer.name}.")
    return JSONObject().put("ok", true).put("message", "Calling the ${peer.name}. Hang up on the screen to stop.")
  }

  private fun reply(message: String) = JSONObject().put("ok", false).put("message", message)

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.call"
    private const val MAX_ROOM = 40
  }
}
