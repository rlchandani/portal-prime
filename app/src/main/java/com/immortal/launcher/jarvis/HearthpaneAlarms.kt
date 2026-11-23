/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.AlarmConfig
import com.immortal.launcher.AlarmRinging
import com.immortal.launcher.AlarmSnooze
import com.immortal.launcher.MoreAlarms
import com.immortal.launcher.ReminderDue
import com.immortal.launcher.ReminderTimeText
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The Portal's alarms and the ring under way, as [HearthpaneAlarms] sees them. */
internal interface AlarmBook {
  /** The main alarm (id [MoreAlarms.MAIN_ID]) first, then the others. */
  fun all(): List<MoreAlarms.Alarm>
  /** Saves [alarm] in its slot and re-arms it. */
  fun save(alarm: MoreAlarms.Alarm)
  /** Turns the main alarm off, or deletes another one. */
  fun remove(id: Int)
  fun ringing(): AlarmRinging.Ring?
  fun snooze(minutes: Int): AlarmRinging.Ring?
  fun dismiss(): AlarmRinging.Ring?
}

/**
 * `hearthpane.alarms` for Jarvis: the Portal's alarms (Tools › Alarm is the main one, Jarvis can add up to
 * [MoreAlarms.MAX] more), one-off or repeating on chosen days, and the ring under way ("snooze", "stop"). Each rings
 * on this Portal over the lock screen and the screensaver, quiet hours or not (an alarm is asked for). A new time is
 * added beside the others; moving one needs `replace` (and `from` when several are on). Arguments are untrusted
 * (model output). Pure apart from [book], so every rule is JVM-tested.
 */
internal class HearthpaneAlarms(
    private val book: AlarmBook,
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
        "cancel", "off", "delete", "remove" -> cancel(args)
        "snooze" -> snooze(args)
        "stop", "dismiss" -> stop()
        else -> error("action must be set, list, cancel, snooze or stop")
      }
    }.getOrElse { error("alarms unavailable") }
  }

  private fun set(args: JSONObject): JSONObject {
    val timeRaw = (args.opt("time") as? String)?.trim().orEmpty()
    if (timeRaw.isEmpty()) return error("time is required, in 24-hour form, e.g. 07:00")
    val clock = ReminderTimeText.parse(timeRaw) ?: return error("time must look like 07:00")
    val days = AlarmDays.parse(args.opt("repeat") as? String) ?: return error("repeat must be once, weekdays, weekends, " +
        "every day or weekday names such as monday and friday")
    val label = (args.opt("label") as? String)?.trim()?.take(MAX_LABEL)?.ifEmpty { null }
    val all = book.all()
    val on = all.filter { it.config.enabled }
    val same = all.firstOrNull { it.config.hour == clock.hour && it.config.minute == clock.minute && it.config.days == days }
    val slot: MoreAlarms.Alarm? = when {
      same != null -> same
      (args.opt("replace") as? Boolean) == true -> when (val picked = pick(on, args.opt("from") as? String, null)) {
        is Pick.One -> picked.alarm
        is Pick.None -> return picked.reply
        is Pick.Many -> return needsWhich(on, "Which alarm should move?")
      }
      else -> null
    }
    val target = slot ?: freeSlot(all) ?: return JSONObject().put("ok", false).put("message",
        "The Portal keeps up to ${MoreAlarms.MAX + 1} alarms and they're all in use. Ask the user which one to turn off.")
    val wanted = MoreAlarms.Alarm(target.id, AlarmConfig.Config(true, clock.hour, clock.minute,
        label ?: target.config.label.takeIf { slot != null || target.id == MoreAlarms.MAIN_ID }?.ifEmpty { null } ?: DEFAULT_LABEL,
        days))
    book.save(wanted)
    val next = nextText(wanted.config) ?: return error("couldn't schedule that alarm")
    val count = book.all().count { it.config.enabled }
    return JSONObject().put("ok", true).put("message", "Alarm set ${describe(wanted.config)}. Next one: $next." +
        if (count > 1) " You have $count alarms on." else "")
  }

  /** The main slot when it's off, else the lowest free other slot (as an off alarm); null when all are taken. */
  private fun freeSlot(all: List<MoreAlarms.Alarm>): MoreAlarms.Alarm? {
    all.firstOrNull { it.id == MoreAlarms.MAIN_ID && !it.config.enabled }?.let { return it }
    val others = all.filter { it.id != MoreAlarms.MAIN_ID }
    val free = others.firstOrNull { !it.config.enabled }?.id ?: MoreAlarms.freeId(others) ?: return null
    return MoreAlarms.Alarm(free, AlarmConfig.Config(false, 0, 0, DEFAULT_LABEL, emptySet()))
  }

  private fun read(): JSONObject {
    val on = sorted(book.all().filter { it.config.enabled })
    if (on.isEmpty()) return JSONObject().put("ok", true).put("set", false).put("message", "No alarm is on.")
    val next = nextText(on.first().config)?.let { " Next one: $it." }.orEmpty()
    val message = if (on.size == 1) "The alarm is on ${describe(on.single().config)}.$next"
    else "You have ${on.size} alarms on: ${HearthpaneLists.spoken(on.map { short(it.config) })}.$next"
    return JSONObject().put("ok", true).put("set", true).put("message", message).put("alarms", json(on))
  }

  private fun cancel(args: JSONObject): JSONObject {
    val time = (args.opt("time") as? String)?.trim()?.ifEmpty { null } ?: (args.opt("from") as? String)?.trim()?.ifEmpty { null }
    val label = (args.opt("label") as? String)?.trim()?.ifEmpty { null }
    val all = (args.opt("all") as? Boolean) == true
    if (time == null && label == null && !all && book.ringing() != null) return stop()
    val on = book.all().filter { it.config.enabled }
    if (on.isEmpty()) return JSONObject().put("ok", true).put("message", "There's no alarm on.")
    if (all) {
      on.forEach { book.remove(it.id) }
      return JSONObject().put("ok", true).put("message",
          if (on.size == 1) "Turned off the alarm ${describe(on.single().config)}." else "Turned off all ${on.size} alarms.")
    }
    val gone = when (val picked = pick(on, time, label)) {
      is Pick.One -> listOf(picked.alarm)
      is Pick.Many -> if (time == null && label == null) return needsWhich(on, "Which alarm should go off?") else picked.alarms
      is Pick.None -> return picked.reply
    }
    gone.forEach { book.remove(it.id) }
    return JSONObject().put("ok", true).put("message",
        if (gone.size == 1) "Turned off the alarm ${describe(gone.single().config)}."
        else "Turned off ${gone.size} alarms: ${HearthpaneLists.spoken(gone.map { short(it.config) })}.")
  }

  private fun snooze(args: JSONObject): JSONObject {
    val minutes = if (args.has("minutes")) args.optInt("minutes", -1) else AlarmSnooze.MINUTES
    if (minutes !in AlarmSnooze.ALLOWED_MINUTES) return error("minutes must be 1 to 30")
    book.snooze(minutes) ?: return notRinging()
    return JSONObject().put("ok", true).put("message", "Snoozed for $minutes minute${if (minutes == 1) "" else "s"}.")
  }

  private fun stop(): JSONObject {
    book.dismiss() ?: return notRinging()
    return JSONObject().put("ok", true).put("message", "Stopped the alarm.")
  }

  private fun notRinging() = JSONObject().put("ok", false).put("message", "No alarm is ringing right now.")

  private sealed interface Pick {
    data class One(val alarm: MoreAlarms.Alarm) : Pick
    data class Many(val alarms: List<MoreAlarms.Alarm>) : Pick
    data class None(val reply: JSONObject) : Pick
  }

  /** The alarms among [on] at [time] and/or named [label]; all of them when neither is given. */
  private fun pick(on: List<MoreAlarms.Alarm>, time: String?, label: String?): Pick {
    if (on.isEmpty()) return Pick.None(JSONObject().put("ok", false).put("message", "There's no alarm on."))
    val clock = time?.let { ReminderTimeText.parse(it) ?: return Pick.None(error("time must look like 07:00")) }
    val words = label?.lowercase()
    val hits = on.filter { a ->
      (clock == null || (a.config.hour == clock.hour && a.config.minute == clock.minute)) &&
          (words == null || a.config.label.lowercase().contains(words) || words.contains(a.config.label.lowercase()))
    }
    return when (hits.size) {
      0 -> Pick.None(JSONObject().put("ok", false).put("message", "There's no alarm like that. The alarms on are " +
          "${HearthpaneLists.spoken(sorted(on).map { short(it.config) })}."))
      1 -> Pick.One(hits.single())
      else -> Pick.Many(hits)
    }
  }

  private fun needsWhich(on: List<MoreAlarms.Alarm>, question: String) = JSONObject().put("ok", false)
      .put("needs_which", true).put("alarms", json(sorted(on)))
      .put("message", "$question The alarms on are ${HearthpaneLists.spoken(sorted(on).map { short(it.config) })}. " +
          "Ask the user, then call again with from (the old time) or label.")

  private fun sorted(alarms: List<MoreAlarms.Alarm>): List<MoreAlarms.Alarm> {
    val from = Calendar.getInstance(zone()).apply { timeInMillis = now() }
    return alarms.sortedBy { AlarmConfig.nextTrigger(it.config, from) ?: Long.MAX_VALUE }
  }

  private fun json(alarms: List<MoreAlarms.Alarm>) = JSONArray().apply {
    alarms.forEach { a ->
      put(JSONObject().put("time", "%02d:%02d".format(a.config.hour, a.config.minute))
          .put("repeat", AlarmDays.spoken(a.config.days).ifEmpty { "once" }).put("label", a.config.label))
    }
  }

  /** When the next alarm rings ("tomorrow at 7 am"), null when none is on; for the Good night routine. */
  fun nextSpoken(): String? = runCatching {
    sorted(book.all().filter { it.config.enabled }).firstOrNull()?.let { nextText(it.config) }
  }.getOrNull()

  /** "for 7 am on weekdays", "for 6:30 am" (one-off). */
  private fun describe(c: AlarmConfig.Config): String {
    val days = AlarmDays.spoken(c.days)
    return "for ${clockText(c.hour, c.minute)}" + if (days.isEmpty()) "" else " $days"
  }

  /** "6:30 am on weekdays (Gym)": the label only when it isn't the default. */
  private fun short(c: AlarmConfig.Config): String {
    val days = AlarmDays.spoken(c.days)
    return clockText(c.hour, c.minute) + (if (days.isEmpty()) "" else " $days") +
        if (c.label.isBlank() || c.label == DEFAULT_LABEL) "" else " (${c.label})"
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
