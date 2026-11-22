/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.DinnerMessage
import com.immortal.launcher.DinnerPeer
import com.immortal.launcher.DinnerPresets
import com.immortal.launcher.DinnerSend
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.announce` for Jarvis: "announce dinner's ready" goes out as a Call everyone card to every other Portal
 * in the home, or to one named room, over the signed household bus (in-home LAN only). Short text, no audio: each
 * receiving Portal chimes and speaks it with its own voice, and during its quiet hours shows the card silently.
 * Arguments are untrusted (model output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneAnnounce(
    private val ready: () -> Boolean,
    private val selfId: () -> String?,
    private val selfName: () -> String,
    private val peers: () -> List<DinnerPeer>,
    /** Sends [message] to [rooms] (null = every other Portal); null when this Portal isn't set up. */
    private val send: (message: DinnerMessage, rooms: List<String>?) -> DinnerSend?,
    /** Ask the other Portals to say who they are, so a room missing now is known next time. */
    private val hello: () -> Unit = {},
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { announce(args) }.getOrElse { error("announcements unavailable") }
  }

  private fun announce(args: JSONObject): JSONObject {
    val text = (args.opt("message") as? String)?.trim().orEmpty()
    if (text.isEmpty()) return error("message is required: what to announce, e.g. Dinner's ready")
    val message = messageFor(text)
        ?: return error("too long for an announcement: say it in ${DinnerMessage.MAX_CUSTOM} characters or fewer")
    if (!ready()) return notSetUp()
    val self = selfId() ?: return notSetUp()
    val others = peers().filter { it.id != self }
    val room = args.opt("room") as? String
    val targets = if (RoomMatch.meansAll(room)) null else when (val picked = pick(room!!, others)) {
      is Pick.One -> listOf(picked.peer)
      is Pick.Reply -> return picked.reply
    }
    if (others.isEmpty()) {
      hello()
      return JSONObject().put("ok", false).put("message", "No other Portals have answered yet, so there's nobody to " +
          "announce to. Check the other Portals are on and try again in a moment.")
    }
    send(message, targets?.map { it.id }) ?: return notSetUp()
    val reached = targets ?: others
    return JSONObject().put("ok", true).put("message", sentMessage(message, reached))
        .put("rooms", JSONArray(reached.map { it.name }))
  }

  private sealed interface Pick {
    data class One(val peer: DinnerPeer) : Pick
    data class Reply(val reply: JSONObject) : Pick
  }

  private fun pick(room: String, others: List<DinnerPeer>): Pick {
    if (RoomMatch.norm(room) == RoomMatch.norm(selfName())) return Pick.Reply(JSONObject().put("ok", false)
        .put("message", "That's this room. Name another room, or leave it out for every room."))
    val name = RoomMatch.find(room, others.map { it.name }) ?: run {
      hello()
      val known = others.map { it.name }
      return Pick.Reply(JSONObject().put("needs_room", true).put("message", "I don't know a room called " +
          "${room.trim().take(MAX_ROOM)}. " + if (known.isEmpty()) "No other Portals have answered yet."
          else "Ask which room: ${(known + "every room").joinToString(", ")}.").put("rooms", JSONArray(known)))
    }
    return Pick.One(others.first { it.name == name })
  }

  private fun sentMessage(message: DinnerMessage, rooms: List<DinnerPeer>): String {
    val where = if (rooms.size == 1) "the ${rooms[0].name}" else "${rooms.size} rooms"
    val quiet = rooms.filter { it.quiet == true }.map { it.name }
    val off = rooms.filter { it.receive == false }.map { it.name }
    val notes = buildString {
      if (quiet.isNotEmpty()) append(" Quiet hours in ${quiet.joinToString(", ")}: the card shows there without sound.")
      if (off.isNotEmpty()) append(" ${off.joinToString(", ")} turned announcements off, so it won't show there.")
    }
    return "Announced to $where: ${message.text}.$notes"
  }

  private fun notSetUp() = JSONObject().put("ok", false).put("message",
      "Announcements aren't set up on this Portal yet. Set up Call everyone from Portal Toolkit.")

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.announce"
    private const val MAX_ROOM = 40

    /** A preset when the words are one ("dinner is ready" → the Dinner's ready card); else short custom text. */
    fun messageFor(text: String): DinnerMessage? {
      val said = presetKey(text)
      val preset = DinnerPresets.all.firstOrNull { presetKey(it.text) == said } ?: ALIASES[said]?.let(DinnerPresets::byId)
      return preset?.let { DinnerMessage.preset(it.id) } ?: DinnerMessage.custom(text.trimEnd('.', '!'))
    }

    private fun presetKey(text: String) = text.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val ALIASES = mapOf("dinner is ready" to "dinner", "dinner time" to "dinner", "dinner's ready everyone" to "dinner",
        "time for bed" to "bed", "bed time" to "bed", "come down" to "down", "come downstairs please" to "down")
  }
}
