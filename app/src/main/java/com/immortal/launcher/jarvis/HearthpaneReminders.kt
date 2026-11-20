/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.Reminder
import com.immortal.launcher.ReminderDraft
import com.immortal.launcher.ReminderDue
import com.immortal.launcher.ReminderResult
import com.immortal.launcher.ReminderState
import com.immortal.launcher.ReminderTimeText
import com.immortal.launcher.ReminderVisibility
import com.immortal.launcher.WhosTherePerson
import com.immortal.launcher.WhosThereUi
import com.immortal.launcher.WhosThereView
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.reminders` for Jarvis: add, list, complete. Arguments are untrusted (model output).
 *
 * Privacy: tool results go to Jarvis's model, so a private reminder's title or id is NEVER in a
 * result — lists give a count, and completing one by its words (allowed only for its owner, alone
 * on their Home) answers without echoing it. "me" is the person whose own Home is showing now;
 * nobody recognised → "Who's this for?". Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneReminders(
    private val who: () -> WhosThereUi,
    private val state: () -> ReminderState,
    private val add: (ReminderDraft) -> ReminderResult,
    private val setDone: (id: String) -> ReminderResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    private val use24: () -> Boolean = { false },
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching {
      when (args.optString("action").trim().lowercase()) {
        "add" -> add(args)
        "list" -> list(args)
        "complete", "done" -> complete(args)
        else -> error("action must be add, list or complete")
      }
    }.getOrElse { error("reminders unavailable") }
  }

  private sealed interface Owner {
    data object Household : Owner
    data class Person(val p: WhosTherePerson) : Owner
    data class Ask(val reply: JSONObject) : Owner
  }

  private fun resolve(raw: String?, ui: WhosThereUi, defaultMe: Boolean): Owner {
    val text = raw?.trim()?.lowercase().orEmpty()
    val people = ui.people
    return when {
      text.isEmpty() && !defaultMe -> Owner.Household
      text in HOUSEHOLD_WORDS -> Owner.Household
      text.isEmpty() || text in ME_WORDS -> me(ui)?.let { Owner.Person(it) } ?: if (people.isEmpty()) Owner.Household else Owner.Ask(
          JSONObject().put("needs_person", true).put("message", "Who's this for? Ask the user: one of " +
              (people.map { it.name } + "the household").joinToString(", ") + ".").put("people", JSONArray(people.map { it.name })))
      else -> people.firstOrNull { it.name.equals(raw!!.trim(), ignoreCase = true) }?.let { Owner.Person(it) }
          ?: Owner.Ask(JSONObject().put("needs_person", true).put("message", "No one called ${raw!!.trim().take(MAX_NAME)} is set up " +
              "in Who's there?. Ask who it's for: " + (people.map { it.name } + "the household").joinToString(", ") + "."))
    }
  }

  /** The adult or teen (or kid) whose own Home is up and confirmed; null when nobody is. */
  private fun me(ui: WhosThereUi): WhosTherePerson? {
    val view = ui.view as? WhosThereView.Personal ?: return null
    return if (view.confirm) null else ui.owner
  }

  private fun add(args: JSONObject): JSONObject {
    val title = args.optString("title").trim().take(MAX_TITLE)
    if (title.isEmpty()) return error("title is required: what to remember")
    val ui = who()
    val owner = when (val o = resolve(args.opt("for") as? String, ui, defaultMe = false)) {
      is Owner.Ask -> return o.reply
      Owner.Household -> null
      is Owner.Person -> o.p
    }
    val due = when (val d = due(args)) {
      is Due.Bad -> return error(d.message)
      is Due.At -> d.ms
      Due.None -> null
    }
    val wantsPrivate = (args.opt("private") as? Boolean) ?: ReminderVisibility.defaultPrivate(owner)
    val private = wantsPrivate && ReminderVisibility.canBePrivate(owner)
    val draft = ReminderDraft(title, owner?.id, owner?.name.orEmpty(), due, private)
    return when (val r = add(draft)) {
      is ReminderResult.Refused -> error(if (r.code == "too_many_reminders") "too many reminders; finish some first" else "couldn't add it")
      is ReminderResult.Ok -> {
        val whom = owner?.let { "for ${it.name}" } ?: "for the household"
        val whenText = due?.let { " " + spokenWhen(it) }.orEmpty()
        val privacy = if (private) " It's private: it shows only on ${owner!!.name}'s Home while they're alone." else ""
        JSONObject().put("ok", true).put("message", "Added $whom$whenText: $title.$privacy")
      }
    }
  }

  private fun list(args: JSONObject): JSONObject {
    val ui = who()
    val s = state()
    val owner = when (val o = resolve(args.opt("for") as? String, ui, defaultMe = me(ui) != null)) {
      is Owner.Ask -> return o.reply
      Owner.Household -> null
      is Owner.Person -> o.p
    }
    val open = s.live.filter { it.open }
    val scoped = if (owner == null) open.filter { it.household }
        else open.filter { ReminderVisibility.belongsTo(it, owner, ui.people) }
    val (priv, public) = scoped.partition { it.private }
    val items = JSONArray(public.sortedWith(ReminderVisibility.order).take(MAX_LIST).map(::itemJson))
    val out = JSONObject().put("ok", true).put("for", owner?.name ?: "household").put("reminders", items)
    if (priv.isNotEmpty()) out.put("private_count", priv.size).put("private_note",
        "${priv.size} private reminder${if (priv.size == 1) "" else "s"} not read out; they're on ${owner?.name ?: "their"}'s Home.")
    return out
  }

  private fun complete(args: JSONObject): JSONObject {
    val ui = who()
    val s = state()
    val id = (args.opt("id") as? String)?.trim()
    val words = args.optString("title").trim().take(MAX_TITLE)
    val owner = me(ui)
    // The owner alone on their own Home may tick off their private ones by saying them.
    val privateOk = owner != null && !ui.hidePrivate
    val candidates = s.live.filter { r -> r.open && (!r.private || (privateOk && ReminderVisibility.belongsTo(r, owner!!, ui.people))) }
    val target = when {
      !id.isNullOrEmpty() -> candidates.firstOrNull { it.id == id && !it.private }
      words.isNotEmpty() -> best(candidates, words, owner, ui)
      else -> return error("say which reminder: give its title or id")
    } ?: return JSONObject().put("ok", false).put("message", "I couldn't find that reminder.")
    return when (setDone(target.id)) {
      is ReminderResult.Ok -> JSONObject().put("ok", true).put("message",
          if (target.private) "Done. Marked your private reminder complete." else "Done: ${target.title}.")
      is ReminderResult.Refused -> error("couldn't complete it")
    }
  }

  /** The candidate whose words best cover what was said; the person's own first on a tie. */
  private fun best(candidates: List<Reminder>, said: String, owner: WhosTherePerson?, ui: WhosThereUi): Reminder? {
    val want = words(said)
    if (want.isEmpty()) return null
    return candidates.map { r ->
      val have = words(r.title)
      val overlap = want.count { it in have }.toDouble() / maxOf(1, minOf(want.size, have.size))
      val mine = owner != null && ReminderVisibility.belongsTo(r, owner, ui.people)
      Triple(r, overlap, mine)
    }.filter { it.second >= MATCH }
        .sortedWith(compareByDescending<Triple<Reminder, Double, Boolean>> { it.second }.thenByDescending { it.third })
        .firstOrNull()?.first
  }

  private fun words(text: String): Set<String> =
      text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }.toSet()

  private sealed interface Due {
    data object None : Due
    data class At(val ms: Long) : Due
    data class Bad(val message: String) : Due
  }

  /** `time` "18:00" / "6 pm", optional `date` "YYYY-MM-DD"; a time alone is its next occurrence. */
  private fun due(args: JSONObject): Due {
    val timeRaw = (args.opt("time") as? String)?.trim().orEmpty()
    val dateRaw = (args.opt("date") as? String)?.trim().orEmpty()
    val dayRaw = (args.opt("day") as? String)?.trim()?.lowercase().orEmpty()
    if (timeRaw.isEmpty() && dateRaw.isEmpty() && dayRaw.isEmpty()) return Due.None
    val clock = if (timeRaw.isEmpty()) null else ReminderTimeText.parse(timeRaw) ?: return Due.Bad("time must look like 18:00")
    val nowMs = now()
    // The model's own "today" can be a UTC date; relative days are resolved here, on the Portal's clock.
    if (dayRaw.isNotEmpty()) {
      val offset = dayOffset(dayRaw, nowMs) ?: return Due.Bad("day must be today, tomorrow or a weekday")
      return ReminderDue.dueFrom(offset, clock, nowMs, zone())?.let { Due.At(it) } ?: Due.Bad("that time has already passed")
    }
    if (dateRaw.isEmpty()) return Due.At(ReminderDue.nextAt(clock!!.hour, clock.minute, nowMs, zone()))
    val m = DATE.matchEntire(dateRaw) ?: return Due.Bad("date must look like 2026-11-20")
    val ms = ReminderDue.at(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
        clock?.hour ?: DEFAULT_HOUR, clock?.minute ?: 0, zone()) ?: return Due.Bad("that date doesn't exist")
    return if (ms <= nowMs) Due.Bad("that time has already passed") else Due.At(ms)
  }

  /** today 0, tomorrow 1, a weekday its next occurrence (1..7 days ahead); null for anything else. */
  private fun dayOffset(day: String, nowMs: Long): Int? = when (day) {
    "today", "tonight" -> 0
    "tomorrow" -> 1
    else -> WEEKDAYS.indexOf(day).takeIf { it >= 0 }?.let { target ->
      val today = java.util.Calendar.getInstance(zone()).apply { timeInMillis = nowMs }.get(java.util.Calendar.DAY_OF_WEEK) - 1
      ((target - today + 7) % 7).let { if (it == 0) 7 else it }
    }
  }

  private fun spokenWhen(ms: Long): String {
    val tag = ReminderDue.tag(ms, now(), zone(), use24())
    val clock = ReminderDue.clock(ms, zone(), use24())
    return when {
      tag == clock -> "at $clock today"
      tag == "Tomorrow" -> "tomorrow at $clock"
      else -> "on $tag at $clock"
    }
  }

  private fun itemJson(r: Reminder): JSONObject = JSONObject().put("id", r.id).put("title", r.title).also { o ->
    r.dueMs?.let { o.put("due", spokenWhen(it)) }
  }

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.reminders"
    private const val MAX_TITLE = 120
    private const val MAX_NAME = 40
    private const val MAX_LIST = 10
    private const val MATCH = 0.6
    private const val DEFAULT_HOUR = 9
    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val ME_WORDS = setOf("me", "myself", "i", "mine", "my")
    private val HOUSEHOLD_WORDS = setOf("household", "home", "everyone", "everybody", "family", "us", "all", "the household")
    private val STOP = setOf("the", "to", "my", "a", "an", "and", "of", "for", "at", "on", "in", "up", "remind", "reminder", "me")
  }
}
