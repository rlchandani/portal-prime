/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.BriefingCopy
import com.immortal.launcher.CalendarFeed
import com.immortal.launcher.PersonalEvent
import com.immortal.launcher.WhosThereContent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.calendar` for Jarvis: "what's on my calendar tomorrow", "what's on Friday", "what do we have this
 * week / this weekend" → the household calendar (the one Home and the photo frame show) for that day or span, as
 * text for Jarvis to say. Relative days are resolved here on the Portal's clock and zone (the model's own "today" can
 * be a UTC date); a weekday name means its next occurrence, today's own name being today. Guest mode hides it, as it
 * hides the calendar on Home. "What's next on my calendar" (`day` next) is the soonest event still to start. When
 * Hearthpane recognises who is there, "my calendar" is that person's own ([mine]: their feed, else the household's
 * filtered to them, as their personal Home shows it), with private titles kept back while others may hear. Arguments
 * are untrusted (model output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneCalendar(
    /** The household calendar's events from now on; null when no calendar is added. */
    private val feed: () -> CalendarFeed.Result?,
    /** Guest mode hides the calendar. */
    private val hidden: () -> Boolean,
    private val now: () -> Long,
    private val zone: () -> TimeZone,
    private val locale: () -> Locale,
    private val use24: () -> Boolean,
    /** The recognised person's calendar; null when nobody is recognised (then "my calendar" is the household's). */
    private val mine: () -> Mine? = { null },
) {
  /** [result] null: the person has no calendar of their own and the household has none either. */
  data class Mine(val name: String, val result: CalendarFeed.Result?)

  private data class Source(val result: CalendarFeed.Result?, val whose: String?, val note: String?)

  private data class Span(val start: Long, val end: Long, val label: String, val perEventDay: Boolean)

  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { answer(args) }.getOrElse { error("calendar unavailable") }
  }

  private fun answer(args: JSONObject): JSONObject {
    val nowMs = now()
    val span = when (val s = span(args, nowMs)) {
      is SpanOrReply.Ok -> s.span
      is SpanOrReply.Reply -> return s.json
    }
    if (hidden()) return reply("The calendar is hidden while guests are here.")
    val source = source((args.opt("for") as? String)?.trim()?.lowercase(Locale.ROOT).orEmpty())
    val result = source.result ?: return reply("No calendar is added to this Portal yet. Add one in Settings, under Calendar.")
    if (span.label == NEXT) return next(result, nowMs, source)
    val all = result.events.filter { it.endMillis > maxOf(span.start, nowMs) && it.startMillis < span.end }
        .sortedWith(compareBy<CalendarFeed.Event>({ dayStart(it.startMillis.coerceAtLeast(span.start)) }, { !it.allDay },
            { it.startMillis }))
    if (all.isEmpty() && result.unavailable) return reply("I couldn't reach the calendar just now.")
    val shown = all.take(MAX_EVENTS)
    val out = JSONObject().put("ok", true).put("when", span.label).put("events", JSONArray(shown.map { json(it, span) }))
    if (all.size > shown.size) out.put("more", all.size - shown.size)
    return out.put("message", message(span, shown, all.size)).tagged(source)
  }

  /** "me" (or nothing said) is the recognised person's calendar; "household" or nobody recognised is the family's. */
  private fun source(whose: String): Source {
    val household = whose in HOUSEHOLD
    val person = if (household) null else mine()
    if (person != null) return Source(person.result, person.name, null)
    val note = if (whose in ME) "I can't tell who's asking, so this is the household calendar." else null
    return Source(feed(), null, note)
  }

  private fun JSONObject.tagged(source: Source): JSONObject = apply {
    source.whose?.let { put("whose", it) }
    source.note?.let { put("note", it) }
  }

  /** The soonest event still to start, however far out within the feed's horizon. */
  private fun next(result: CalendarFeed.Result, nowMs: Long, source: Source): JSONObject {
    val e = result.events.filter { it.startMillis >= nowMs }.minByOrNull { it.startMillis }
    if (e == null) {
      if (result.unavailable) return reply("I couldn't reach the calendar just now.")
      return JSONObject().put("ok", true).put("when", NEXT).put("events", JSONArray())
          .put("message", "Nothing coming up on the calendar in the next $HORIZON_DAYS days.").tagged(source)
    }
    val day = nextDay(e.startMillis)
    val event = JSONObject().put("title", title(e)).put("day", day)
        .put("time", if (e.allDay) "all day" else BriefingCopy.clock(e.startMillis, zone(), locale(), use24()))
        .put("all_day", e.allDay)
        .apply { e.location?.trim()?.takeIf { it.isNotEmpty() }?.let { put("location", it.take(MAX_TITLE)) } }
    return JSONObject().put("ok", true).put("when", NEXT).put("events", JSONArray().put(event))
        .put("message", "Next on the calendar: ${title(e)} $day ${time(e)}.").tagged(source)
  }

  /** today, tomorrow, "on Friday" this week, else "on Friday, December 4". */
  private fun nextDay(ms: Long): String {
    val offset = daysBetween(dayStart(now()), dayStart(ms))
    return when (offset) {
      0 -> "today"
      1 -> "tomorrow"
      in 2 until DAYS_IN_WEEK -> "on ${format("EEEE", ms)}"
      else -> "on ${format("EEEE, MMMM d", ms)}"
    }
  }

  private fun message(span: Span, shown: List<CalendarFeed.Event>, total: Int): String {
    if (shown.isEmpty()) return "Nothing on the calendar ${span.label}."
    val lead = "${span.label.replaceFirstChar { it.uppercase(locale()) }} you have ${BriefingCopy.plural(total, "thing")}: "
    val parts = shown.map { e -> if (span.perEventDay) "${dayName(e, span)}, ${spoken(e)}" else spoken(e) }
    val body = if (span.perEventDay) parts.joinToString("; ") else BriefingCopy.joinNames(parts)
    val more = if (total > shown.size) ", and ${total - shown.size} more" else ""
    return "$lead$body$more."
  }

  private fun spoken(e: CalendarFeed.Event): String = "${title(e)} ${time(e)}"

  private fun time(e: CalendarFeed.Event): String =
      if (e.allDay) "all day" else "at ${BriefingCopy.clock(e.startMillis, zone(), locale(), use24())}"

  private fun json(e: CalendarFeed.Event, span: Span): JSONObject = JSONObject().put("title", title(e))
      .put("time", if (e.allDay) "all day" else BriefingCopy.clock(e.startMillis, zone(), locale(), use24()))
      .put("all_day", e.allDay)
      .apply { if (span.perEventDay) put("day", dayName(e, span)) }
      .apply { e.location?.trim()?.takeIf { it.isNotEmpty() }?.let { put("location", it.take(MAX_TITLE)) } }

  private fun title(e: CalendarFeed.Event): String = e.title.trim().ifEmpty { "An event" }.take(MAX_TITLE)

  /** today, tomorrow, else the weekday's name, for the day the event is on within the span. */
  private fun dayName(e: CalendarFeed.Event, span: Span): String {
    val start = dayStart(maxOf(e.startMillis, span.start, now()))
    return when (daysBetween(dayStart(now()), start)) {
      0 -> "today"
      1 -> "tomorrow"
      else -> format("EEEE", start)
    }
  }

  private sealed interface SpanOrReply {
    data class Ok(val span: Span) : SpanOrReply
    data class Reply(val json: JSONObject) : SpanOrReply
  }

  private fun span(args: JSONObject, nowMs: Long): SpanOrReply {
    val today = dayStart(nowMs)
    val date = (args.opt("date") as? String)?.trim().orEmpty()
    if (date.isNotEmpty()) return dateSpan(date, today)
    val day = (args.opt("day") as? String)?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val span = when (day) {
      in NEXT_WORDS -> Span(nowMs, addDays(today, HORIZON_DAYS + 1), NEXT, perEventDay = true)
      "", "today", "tonight", "this evening" -> oneDay(today, 0)
      "tomorrow" -> oneDay(today, 1)
      in WEEK -> Span(today, addDays(today, DAYS_IN_WEEK), "this week", perEventDay = true)
      in WEEKEND -> weekend(today)
      else -> WEEKDAYS.indexOf(day.removePrefix("on ").removePrefix("this ").removeSuffix("s")).takeIf { it >= 0 }
          ?.let { oneDay(today, (it - weekday(today) + DAYS_IN_WEEK) % DAYS_IN_WEEK) }
    } ?: return SpanOrReply.Reply(error("day must be next, today, tomorrow, a weekday, week or weekend; or give date"))
    return SpanOrReply.Ok(span)
  }

  private fun dateSpan(date: String, today: Long): SpanOrReply {
    val parsed = runCatching {
      SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone(); isLenient = false }.parse(date)
    }.getOrNull()?.takeIf { DATE.matches(date) }
        ?: return SpanOrReply.Reply(error("date must be YYYY-MM-DD"))
    val start = dayStart(parsed.time)
    val offset = daysBetween(today, start)
    return when {
      offset < 0 -> SpanOrReply.Reply(reply("I can only look ahead on the calendar, not back."))
      offset > HORIZON_DAYS -> SpanOrReply.Reply(reply("I can only see the next $HORIZON_DAYS days of the calendar."))
      else -> SpanOrReply.Ok(oneDay(today, offset))
    }
  }

  private fun oneDay(today: Long, offset: Int): Span {
    val start = addDays(today, offset)
    val label = when (offset) {
      0 -> "today"
      1 -> "tomorrow"
      in 2 until DAYS_IN_WEEK -> "on ${format("EEEE", start)}"
      else -> "on ${format("EEEE, MMMM d", start)}"
    }
    return Span(start, addDays(start, 1), label, perEventDay = false)
  }

  /** Saturday and Sunday: this one if it's already the weekend, else the coming one. */
  private fun weekend(today: Long): Span {
    val wd = weekday(today)
    val start = when (wd) {
      SATURDAY, SUNDAY -> today
      else -> addDays(today, SATURDAY - wd)
    }
    val end = addDays(start, if (wd == SUNDAY) 1 else 2)
    return Span(start, end, "this weekend", perEventDay = true)
  }

  private fun cal(ms: Long): Calendar = Calendar.getInstance(zone(), locale()).apply { timeInMillis = ms }

  private fun dayStart(ms: Long): Long = cal(ms).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  private fun addDays(dayStartMs: Long, days: Int): Long = cal(dayStartMs).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

  /** Whole local days between two local midnights (DST-safe: counts calendar days, not 24-hour blocks). */
  private fun daysBetween(from: Long, to: Long): Int {
    var n = 0
    var c = from
    val step = if (to >= from) 1 else -1
    while ((step > 0 && c < to) || (step < 0 && c > to)) {
      c = addDays(c, step); n += step
      if (n > HORIZON_DAYS + 1 || n < -1) return n
    }
    return n
  }

  /** 0 = Sunday … 6 = Saturday. */
  private fun weekday(ms: Long): Int = cal(ms).get(Calendar.DAY_OF_WEEK) - 1

  private fun format(pattern: String, ms: Long): String =
      SimpleDateFormat(pattern, locale()).apply { timeZone = zone() }.format(Date(ms))

  private fun reply(message: String) = JSONObject().put("ok", false).put("message", message)

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.calendar"
    const val MAX_EVENTS = 8
    const val MAX_TITLE = 80
    /** The calendar feed's own look-ahead ([CalendarFeed.read]'s horizon). */
    const val HORIZON_DAYS = 45
    private const val DAYS_IN_WEEK = 7
    private const val SATURDAY = 6
    private const val SUNDAY = 0
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val WEEK = setOf("week", "this week", "the week", "next 7 days", "next seven days", "coming week")
    private val WEEKEND = setOf("weekend", "this weekend", "the weekend")
    private const val NEXT = "next"
    private val NEXT_WORDS = setOf("next", "next up", "upcoming", "coming up", "soonest")
    private val ME = setOf("me", "my", "mine", "myself")
    private val HOUSEHOLD = setOf("household", "family", "everyone", "us", "we", "home")

    /**
     * A person's events ([WhosThereContent]: tags stripped, private ones marked) as calendar events for Jarvis to
     * say; a private title is "Private" whenever Who's there hides private items (others may be near).
     */
    fun personal(events: List<PersonalEvent>, hidePrivate: Boolean): List<CalendarFeed.Event> = events.map { e ->
      val title = if (e.private && hidePrivate) WhosThereContent.PRIVATE_LABEL else e.title
      CalendarFeed.Event(e.startMillis, e.endMillis, title, e.allDay, e.location.takeUnless { e.private && hidePrivate })
    }
  }
}
