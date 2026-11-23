/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.SharedTimer
import com.immortal.launcher.SharedTimerAction
import com.immortal.launcher.SharedTimerMerge
import com.immortal.launcher.SharedTimerState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * `hearthpane.timers`: Jarvis's countdown timers are the household's ([com.immortal.launcher.SharedTimers]), so a
 * timer asked for by voice shows on every Portal and the phone, rings in every room, and is in the briefing. Start,
 * list, cancel, pause, resume, a minute more, and stop a ringing one; a change picks the only timer it applies to,
 * the one named, or asks which (`needs_which`). Arguments are untrusted (model output). Pure apart from its seams.
 */
internal class HearthpaneTimers(
    /** Every retained timer (live and recent tombstones). */
    private val timers: () -> List<SharedTimer>,
    /** Starts a household timer here; null when refused (too many, no household id yet). */
    private val start: (name: String, durationMs: Long) -> SharedTimer?,
    /** True when the change was applied. */
    private val act: (SharedTimerAction, id: String) -> Boolean,
    /** This Portal's room name, so the list says where the other rooms' timers are. */
    private val here: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching {
      when (args.optString("action").trim().lowercase()) {
        "start", "set", "add" -> start(args)
        "list", "get", "read" -> list()
        "cancel", "delete", "remove" -> change(args, SharedTimerAction.CANCEL)
        "pause" -> change(args, SharedTimerAction.PAUSE)
        "resume", "continue" -> change(args, SharedTimerAction.RESUME)
        "add_minute", "add minute", "more" -> change(args, SharedTimerAction.ADD)
        "stop", "dismiss" -> change(args, SharedTimerAction.STOP)
        else -> error("action must be start, list, cancel, pause, resume, add_minute or stop")
      }
    }.getOrElse { error("timers unavailable") }
  }

  private fun start(args: JSONObject): JSONObject {
    val seconds = args.optDouble("duration_seconds", Double.NaN)
    if (seconds.isNaN() || seconds < 1 || seconds > MAX_SECONDS) return error("duration_seconds must be 1 to $MAX_SECONDS")
    val ms = (seconds.roundToLong() * 1_000L).coerceIn(SharedTimer.MIN_DURATION_MS, SharedTimer.MAX_DURATION_MS)
    val label = (args.opt("label") as? String)?.trim()?.take(MAX_LABEL)?.ifEmpty { null }
    val timer = start(label ?: SharedTimer.DEFAULT_NAME, ms)
        ?: return error("couldn't start the timer: the household may already have ${SharedTimer.MAX_LIVE} running")
    return JSONObject().put("ok", true).put("id", timer.id).put("label", timer.name).put("duration_seconds", ms / 1_000)
        .put("message", "${title(timer)} set for ${length(ms)}. It shows in every room.")
  }

  private fun list(): JSONObject {
    val at = now()
    val live = SharedTimerMerge.display(timers(), at)
    if (live.isEmpty()) return JSONObject().put("ok", true).put("message", "No timers are running.")
    val room = here()
    val spoken = live.joinToString("; ") { t ->
      val where = if (t.room.isNotBlank() && !t.room.equals(room, ignoreCase = true)) " in the ${t.room}" else ""
      val left = length(t.remaining(at))
      t.name + where + when (t.state) {
        SharedTimerState.PAUSED -> ", paused with $left left"
        SharedTimerState.RINGING -> ", ringing now"
        else -> ", $left left"
      }
    }
    val json = JSONArray().apply {
      live.forEach { t ->
        put(JSONObject().put("id", t.id).put("label", t.name).put("room", t.room).put("state", stateName(t.state))
            .put("remaining_seconds", t.remaining(at) / 1_000))
      }
    }
    return JSONObject().put("ok", true).put("timers", json)
        .put("message", "${live.size} timer${if (live.size == 1) "" else "s"}: $spoken.")
  }

  private fun change(args: JSONObject, action: SharedTimerAction): JSONObject {
    val live = SharedTimerMerge.display(timers(), now())
    val applies = live.filter { applies(action, it.state) }
    if (applies.isEmpty()) return JSONObject().put("ok", false).put("message", nothing(action, live.isEmpty()))
    val id = (args.opt("id") as? String)?.trim()?.ifEmpty { null }
    val label = (args.opt("label") as? String)?.let(::words)?.ifEmpty { null }
    val all = (args.opt("all") as? Boolean) == true
    val chosen = when {
      id != null -> applies.filter { it.id == id }
      label != null -> applies.filter { words(it.name).let { n -> n.contains(label) || label.contains(n) } }
      all || action == SharedTimerAction.STOP || applies.size == 1 -> applies
      else -> return JSONObject().put("ok", false).put("needs_which", true).put("message",
          "Which timer? There are ${HearthpaneLists.spoken(applies.map { it.name })}. Ask the user, then call again " +
              "with its label.")
    }
    if (chosen.isEmpty()) return JSONObject().put("ok", false).put("message",
        "There's no timer like that. The timers are ${HearthpaneLists.spoken(applies.map { it.name })}.")
    val done = chosen.filter { act(action, it.id) }
    if (done.isEmpty()) return JSONObject().put("ok", false).put("message", "That timer already changed in another room.")
    val verb = VERBS.getValue(action)
    return JSONObject().put("ok", true).put("message",
        if (done.size == 1) "$verb the ${subject(done.single())}."
        else if (all && action == SharedTimerAction.CANCEL) "$verb all ${done.size} timers." else "$verb ${done.size} timers.")
  }

  /** "Pasta timer" in the label's own case; the default is just "timer". */
  private fun subject(t: SharedTimer): String = if (t.name == SharedTimer.DEFAULT_NAME) "timer" else "${t.name} timer"

  /** The start reply's subject: "Timer", or "pasta timer" as the user named it. */
  private fun title(t: SharedTimer): String = if (t.name == SharedTimer.DEFAULT_NAME) "Timer" else subject(t)

  private fun applies(action: SharedTimerAction, state: SharedTimerState): Boolean = when (action) {
    SharedTimerAction.PAUSE -> state == SharedTimerState.RUNNING
    SharedTimerAction.RESUME -> state == SharedTimerState.PAUSED
    SharedTimerAction.STOP -> state == SharedTimerState.RINGING
    SharedTimerAction.ADD, SharedTimerAction.CANCEL -> state.live
  }

  private fun nothing(action: SharedTimerAction, none: Boolean): String = when {
    action == SharedTimerAction.STOP -> "No timer is ringing."
    none -> "No timers are running."
    action == SharedTimerAction.PAUSE -> "No timer is running to pause."
    action == SharedTimerAction.RESUME -> "No timer is paused."
    else -> "No timers are running."
  }

  private fun stateName(s: SharedTimerState) = when (s) {
    SharedTimerState.PAUSED -> "paused"
    SharedTimerState.RINGING -> "ringing"
    else -> "running"
  }

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.timers"
    private const val MAX_SECONDS = 86_400
    private const val MAX_LABEL = 30
    private val VERBS = mapOf(SharedTimerAction.CANCEL to "Cancelled", SharedTimerAction.PAUSE to "Paused",
        SharedTimerAction.RESUME to "Resumed", SharedTimerAction.ADD to "Added a minute to", SharedTimerAction.STOP to "Stopped")

    /** "the eggs timer" → "eggs". */
    fun words(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ')
        .filter { it.isNotBlank() && it != "the" && it != "timer" && it != "my" }.joinToString(" ")

    /** "1 hour 30 minutes", "4 minutes 10 seconds", "45 seconds" (rounded to the second). */
    fun length(ms: Long): String {
      val total = (ms + 500) / 1_000
      val h = total / 3_600
      val m = total % 3_600 / 60
      val s = total % 60
      fun unit(n: Long, name: String) = if (n == 0L) null else "$n $name${if (n == 1L) "" else "s"}"
      return listOfNotNull(unit(h, "hour"), unit(m, "minute"), unit(s, "second").takeIf { h == 0L })
          .joinToString(" ").ifEmpty { "0 seconds" }
    }
  }
}
