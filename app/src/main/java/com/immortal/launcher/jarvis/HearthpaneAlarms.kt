/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.AlarmConfig
import com.immortal.launcher.ReminderDue
import com.immortal.launcher.ReminderTimeText
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.alarms` for Jarvis: set, read and turn off the Portal's wake-up alarm (Tools > Alarm), one-off or
 * repeating on chosen days. It rings on this Portal over the lock screen and the screensaver, quiet hours or not
 * (an alarm is asked for), with Dismiss and a 9-minute Snooze. Hearthpane keeps ONE alarm, so setting a different
 * time while one is on asks first (`needs_replace`) instead of silently moving it. Arguments are untrusted (model
 * output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneAlarms(
    private val load: () -> AlarmConfig.Config,
    /** Saves the whole alarm and re-arms it (cancelling any pending snooze). */
    private val save: (AlarmConfig.Config) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val use24: () -> Boolean = { false },
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching {
      when (args.optString("action").trim().lowercase()) {
        "set", "add" -> set(args)
        "list", "get", "read" -> read()
        "cancel", "off", "delete", "remove" -> cancel()
        else -> error("action must be set, list or cancel")
      }
    }.getOrElse { error("alarms unavailable") }
  }

  private fun set(args: JSONObject): JSONObject {
    val timeRaw = (args.opt("time") as? String)?.trim().orEmpty()
    if (timeRaw.isEmpty()) return error("time is required, in 24-hour form, e.g. 07:00")
    val clock = ReminderTimeText.parse(timeRaw) ?: return error("time must look like 07:00")
    val days = AlarmDays.parse(args.opt("repeat") as? String) ?: return error("repeat must be once, weekdays, weekends, " +
        "every day or weekday names such as monday and friday")
    val old = load()
    val label = (args.opt("label") as? String)?.trim()?.take(MAX_LABEL)?.ifEmpty { null } ?: old.label.ifEmpty { DEFAULT_LABEL }
    val wanted = AlarmConfig.Config(true, clock.hour, clock.minute, label, days)
    val replace = (args.opt("replace") as? Boolean) == true
    if (old.enabled && (old.hour != wanted.hour || old.minute != wanted.minute || old.days != wanted.days) && !replace) {
      return JSONObject().put("ok", false).put("needs_replace", true).put("message", "There's already an alarm " +
          "${describe(old)}. The Portal keeps one alarm: ask the user whether to change it to ${describe(wanted)}, then " +
          "call again with replace true.")
    }
    save(wanted)
    val next = nextText(wanted) ?: return error("couldn't schedule that alarm")
    return JSONObject().put("ok", true).put("message", "Alarm set ${describe(wanted)}. Next one: $next.")
  }

  private fun read(): JSONObject {
    val c = load()
    if (!c.enabled) return JSONObject().put("ok", true).put("set", false).put("message", "No alarm is on.")
    val next = nextText(c)
    return JSONObject().put("ok", true).put("set", true)
        .put("message", "The alarm is on ${describe(c)}." + (next?.let { " Next one: $it." } ?: ""))
  }

  private fun cancel(): JSONObject {
    val c = load()
    if (!c.enabled) return JSONObject().put("ok", true).put("message", "There's no alarm on.")
    save(c.copy(enabled = false))
    return JSONObject().put("ok", true).put("message", "Turned off the alarm ${describe(c)}.")
  }

  /** "for 7 am on weekdays", "for 6:30 am" (one-off). */
  private fun describe(c: AlarmConfig.Config): String {
    val days = AlarmDays.spoken(c.days)
    return "for ${clockText(c.hour, c.minute)}" + if (days.isEmpty()) "" else " $days"
  }

  private fun clockText(hour: Int, minute: Int): String {
    val cal = Calendar.getInstance(zone()).apply {
      timeInMillis = now(); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
    }
    return ReminderDue.clock(cal.timeInMillis, zone(), use24())
  }

  /** "today at 6 pm", "tomorrow at 7 am", "Friday at 7 am". */
  private fun nextText(c: AlarmConfig.Config): String? {
    val from = Calendar.getInstance(zone()).apply { timeInMillis = now() }
    val at = AlarmConfig.nextTrigger(c, from) ?: return null
    val atCal = Calendar.getInstance(zone()).apply { timeInMillis = at }
    val clock = ReminderDue.clock(at, zone(), use24())
    return when (daysBetween(from, atCal)) {
      0 -> "today at $clock"
      1 -> "tomorrow at $clock"
      else -> "${AlarmDays.name(atCal.get(Calendar.DAY_OF_WEEK))} at $clock"
    }
  }

  private fun daysBetween(a: Calendar, b: Calendar): Int {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((midnight(b) - midnight(a)) / DAY_MS).toInt()
  }

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.alarms"
    private const val MAX_LABEL = 30
    private const val DEFAULT_LABEL = "Alarm"
    private const val DAY_MS = 86_400_000.0
  }
}
