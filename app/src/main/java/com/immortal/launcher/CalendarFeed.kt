/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Read upcoming events from a public iCalendar (.ics) feed URL — no account or API
 * key, the calendar equivalent of [RemoteAlbum]'s public share links. Both Google
 * Calendar and Apple iCloud expose a calendar this way:
 *
 *  - **Google Calendar** → Settings → *Integrate calendar* → "Secret address in
 *    iCal format" (a private `…/basic.ics` URL — keep it private; anyone with it can
 *    read the calendar).
 *  - **Apple iCloud** → Calendar app → share a calendar → *Public Calendar* → a
 *    `webcal://…/.ics` link.
 *
 * This is a deliberately small RFC 5545 reader: it handles line-unfolding, the
 * common `DTSTART`/`DTEND` forms (UTC `Z`, `TZID=`, floating, and all-day `VALUE=DATE`),
 * `SUMMARY`/`LOCATION` with text escapes, and the recurrence rules people actually
 * use (`RRULE` with `FREQ`/`INTERVAL`/`COUNT`/`UNTIL`, weekly `BYDAY`, plus `EXDATE`
 * exclusions). Events explicitly marked `STATUS:CANCELLED` are omitted. Anything it
 * can't make sense of is skipped rather than crashing, and a
 * malformed recurrence falls back to showing the event once — the frame degrades
 * gracefully, like the rest of the screensaver.
 *
 * On a failed refresh [fetch] retains usable events from a bounded last-success cache.
 * [read] also reports the failure so widgets can offer recovery without claiming an empty calendar.
 */
object CalendarFeed {
  private val feedReader = CalendarFeedReader(::httpGet)
  private val preparedFeed = CalendarPreparedFeed(::prepareDocument, ::evaluateDocument,
      retainPrepared = { it.events.size <= 2048 })


  /** A single calendar occurrence, already resolved to absolute wall-clock millis. */
  data class Event(
      val startMillis: Long,
      val endMillis: Long,
      val title: String,
      val allDay: Boolean,
      val location: String? = null,
  )

  /** A failed refresh can retain events from the last usable feed. */
  data class Result(val events: List<Event>, val unavailable: Boolean = false)

  // Display windows for the screensaver widget. Stored verbatim in ScreensaverConfig.
  const val RANGE_DAY = "day" // today only
  const val RANGE_3DAY = "3day" // today + next 2 days
  const val RANGE_WEEK = "week" // today + next 6 days
  const val RANGE_AGENDA = "agenda" // the next N events, however far out ("only events")

  /** Clamp a stored range to a known value (defaults to a single day). */
  fun clampRange(v: String?): String =
      when (v) {
        RANGE_DAY, RANGE_3DAY, RANGE_WEEK, RANGE_AGENDA -> v
        else -> RANGE_DAY
      }

  /** Greatest number of rows we ever render, to keep the widget clean. */
  const val MAX_EVENTS = 8

  // --- URL handling -----------------------------------------------------------

  /** True for a structurally valid link with a supported calendar endpoint. */
  fun isSupported(url: String): Boolean {
    val uri = calendarUri(url) ?: return false
    return uri.scheme.equals("webcal", ignoreCase = true) ||
        uri.path.orEmpty().contains(".ics", ignoreCase = true) ||
        uri.query.orEmpty().contains(".ics", ignoreCase = true) ||
        isGoogle(uri) || isApple(uri)
  }

  private fun calendarUri(url: String): URI? = runCatching {
    val uri = URI(url.trim()).parseServerAuthority()
    if (uri.isOpaque || uri.host.isNullOrBlank() ||
        uri.scheme?.lowercase(Locale.US) !in setOf("http", "https", "webcal") ||
        (uri.port != -1 && uri.port !in 1..65535)) return@runCatching null
    uri
  }.getOrNull()

  private fun URI.hasHost(domain: String): Boolean {
    val hostname = host.lowercase(Locale.US).trimEnd('.')
    return hostname == domain || hostname.endsWith(".$domain")
  }

  private fun isGoogle(uri: URI): Boolean =
      uri.hasHost("calendar.google.com") ||
          (uri.hasHost("google.com") &&
              (uri.path == "/calendar" || uri.path.orEmpty().startsWith("/calendar/")))

  private fun isApple(uri: URI): Boolean =
      uri.hasHost("icloud.com") || uri.hasHost("apple.com")

  /** Human label based on the calendar host, never on path or query text. */
  fun providerName(url: String): String {
    val uri = calendarUri(url) ?: return "Calendar feed"
    return when {
      isGoogle(uri) -> "Google Calendar"
      isApple(uri) -> "Apple iCloud"
      else -> "Calendar feed"
    }
  }

  /** `webcal://` is just `https://` for a calendar; normalise so we can fetch it. */
  fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
      u.startsWith("webcal://", ignoreCase = true) -> "https://" + u.substring("webcal://".length)
      else -> u
    }
  }

  // --- fetch ------------------------------------------------------------------

  /**
   * Fetch and parse the feed, returning every occurrence between [nowMillis] and
   * [nowMillis] + [horizonDays] (already filtered, sorted, and de-duplicated).
   * Retains usable saved events on failure; returns empty when none remain.
   */
  fun fetch(
      url: String,
      nowMillis: Long = System.currentTimeMillis(),
      horizonDays: Int = 45,
  ): List<Event> = read(url, nowMillis, horizonDays).events

  internal fun read(
      url: String,
      nowMillis: Long = System.currentTimeMillis(),
      horizonDays: Int = 45,
      forceRefresh: Boolean = false,
  ): Result = feedReader.read(url, nowMillis, horizonDays, forceRefresh)

  // --- parsing (pure; unit-tested) --------------------------------------------

  /**
   * Pure parse of an iCalendar document. [nowMillis] is the reference "now" (passed
   * in rather than read from the clock so tests are deterministic); occurrences that
   * have already ended, or that start beyond the horizon, are dropped.
   */
  internal fun parse(ics: String, nowMillis: Long, horizonDays: Int = 45): List<Event> =
      preparedFeed.parse(ics, nowMillis, horizonDays)

  private data class PreparedEvent(
      val start: Long,
      val zone: TimeZone,
      val duration: Long,
      val allDayDays: Int?,
      val title: String,
      val allDay: Boolean,
      val location: String?,
      val rrule: String?,
      val exdates: List<Long>,
  )
  private data class PreparedDocument(
      val events: List<PreparedEvent>,
      val zone: TimeZone,
      val locale: Locale,
  )

  private fun prepareDocument(ics: String, zone: TimeZone, locale: Locale): PreparedDocument {
    val lines = unfold(ics)
    val events = ArrayList<PreparedEvent>()
    var i = 0
    while (i < lines.size) {
      if (lines[i].trim().equals("BEGIN:VEVENT", ignoreCase = true)) {
        var end = i + 1
        while (end < lines.size && !lines[end].trim().equals("END:VEVENT", ignoreCase = true)) end++
        prepareEvent(lines.subList(i + 1, minOf(end, lines.size)), zone, locale)?.let(events::add)
        i = end + 1
      } else i++
    }
    return PreparedDocument(events.toList(), zone, locale)
  }

  private fun prepareEvent(body: List<String>, zone: TimeZone, locale: Locale): PreparedEvent? {
    var start: Long? = null
    var startZone = zone
    var startAllDay = false
    var end: Long? = null
    var endAllDay = false
    var title = ""
    var location: String? = null
    var rrule: String? = null
    val exdates = ArrayList<Long>()
    for (raw in body) {
      val p = parseLine(raw) ?: continue
      when (p.name.uppercase(Locale.US)) {
        "STATUS" -> if (p.value.equals("CANCELLED", ignoreCase = true)) return null
        "DTSTART" -> parseDt(p, zone, locale)?.let { (m, allDay, parsedZone) ->
          start = m; startAllDay = allDay; startZone = parsedZone
        }
        "DTEND" -> parseDt(p, zone, locale)?.let { (m, allDay) -> end = m; endAllDay = allDay }
        "SUMMARY" -> title = unescapeText(p.value)
        "LOCATION" -> location = unescapeText(p.value).ifBlank { null }
        "RRULE" -> rrule = p.value
        "EXDATE" ->
            p.value.split(',').forEach { value ->
              parseDt(p.copy(value = value), zone, locale)?.let { (m, _) -> exdates.add(m) }
            }
      }
    }
    val s = start ?: return null
    val e = end
    if (title.isBlank()) title = "(busy)"
    val duration = (e ?: s) - s
    val allDayDays = if (startAllDay && (e == null || endAllDay)) {
      // Local date boundaries count calendar days, independent of their UTC offsets.
      if (e == null) 1 else
          ((e + zone.getOffset(e).toLong() - s - zone.getOffset(s).toLong()) /
              (24L * 60 * 60 * 1000)).toInt()
    } else null
    return PreparedEvent(s, startZone, duration, allDayDays, title, startAllDay, location, rrule, exdates.toList())
  }

  private fun evaluateDocument(document: PreparedDocument, nowMillis: Long, horizonDays: Int): List<Event> {
    val horizonEnd = nowMillis + horizonDays * 24L * 60 * 60 * 1000
    val out = CalendarOccurrenceSelection<Event>(500)
    for (event in document.events) {
      val span = event.allDayDays?.toLong()?.times(24L * 60 * 60 * 1000)
          ?: event.duration.coerceAtLeast(0L)
      val earliestStart = runCatching { Math.subtractExact(nowMillis, span) }.getOrDefault(Long.MIN_VALUE)
      val starts = if (event.rrule.isNullOrBlank()) listOf(event.start)
          else expandRecurrence(event.start, event.rrule, event.exdates, horizonEnd, earliestStart, event.zone)
      for (start in starts) {
        val end = if (event.allDayDays != null) {
          Calendar.getInstance(document.zone, document.locale).apply {
            timeInMillis = start
            add(Calendar.DAY_OF_MONTH, event.allDayDays)
          }.timeInMillis
        } else start + event.duration
        if (end < nowMillis || start > horizonEnd) continue
        out.offer(start) { Event(start, end, event.title, event.allDay, event.location) }
      }
    }
    return out.result()
  }

  /**
   * Unfold RFC 5545 continuation lines: a CRLF (or LF) followed by a space or tab is
   * a fold of the previous logical line.
   */
  internal fun unfold(ics: String): List<String> {
    val rawLines = ics.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val out = ArrayList<String>(rawLines.size)
    for (line in rawLines) {
      if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
        out[out.size - 1] = out[out.size - 1] + line.substring(1)
      } else {
        out.add(line)
      }
    }
    return out
  }

  private data class Prop(val name: String, val params: Map<String, String>, val value: String)

  /** Split a content line into name, parameters, and value (best-effort). */
  private fun parseLine(line: String): Prop? {
    if (line.isBlank()) return null
    val colon = line.indexOf(':')
    if (colon < 0) return null
    val left = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = left.split(';')
    val name = parts[0]
    val params = HashMap<String, String>()
    for (k in 1 until parts.size) {
      val eq = parts[k].indexOf('=')
      if (eq > 0) {
        params[parts[k].substring(0, eq).uppercase(Locale.US)] =
            parts[k].substring(eq + 1).trim('"')
      }
    }
    return Prop(name, params, value)
  }

  /**
   * Parse a date/date-time property to absolute millis. Returns the instant and
   * whether it's an all-day (date-only) value and its recurrence timezone. Handles:
   *  - `VALUE=DATE` or bare `yyyyMMdd` → local midnight, all-day
   *  - `yyyyMMdd'T'HHmmss'Z'` → UTC
   *  - `;TZID=Zone:yyyyMMdd'T'HHmmss` → that zone
   *  - `yyyyMMdd'T'HHmmss` → device-local (floating)
   *
   * EXDATE lists are split by the caller so every value retains the property's parameters.
   */
  private data class ParsedDateTime(val millis: Long, val allDay: Boolean, val zone: TimeZone)

  private fun parseDt(p: Prop, defaultZone: TimeZone, locale: Locale): ParsedDateTime? {
    val v = p.value.substringBefore(',').trim()
    if (v.isEmpty()) return null
    val dateOnly = p.params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))
    return runCatching {
          if (dateOnly) {
            if (v.length != 8 || v.any { it !in '0'..'9' }) return null
            // Validate the civil date separately from local midnight, which may have a DST gap.
            SimpleDateFormat("yyyyMMdd", Locale.US).apply {
              isLenient = false
              timeZone = TimeZone.getTimeZone("UTC")
            }.parse(v) ?: return null
            val cal = Calendar.getInstance(defaultZone, locale)
            cal.clear()
            cal.set(
                v.substring(0, 4).toInt(),
                v.substring(4, 6).toInt() - 1,
                v.substring(6, 8).toInt(),
                0,
                0,
                0)
            ParsedDateTime(cal.timeInMillis, true, defaultZone)
          } else {
            val utc = v.endsWith("Z")
            val basic = v.removeSuffix("Z")
            val zone =
                when {
                  utc -> TimeZone.getTimeZone("UTC")
                  p.params["TZID"] != null -> TimeZone.getTimeZone(p.params["TZID"])
                  else -> defaultZone
                }
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            fmt.timeZone = zone
            ParsedDateTime(fmt.parse(basic)?.time ?: return null, false, zone)
          }
        }
        .getOrNull()
  }

  /** Resolve `\n`, `\,`, `\;`, `\\` text escapes used in SUMMARY/LOCATION. */
  internal fun unescapeText(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (s[i + 1]) {
          'n', 'N' -> sb.append(' ')
          ',', ';', '\\' -> sb.append(s[i + 1])
          else -> sb.append(s[i + 1])
        }
        i += 2
      } else {
        sb.append(c)
        i++
      }
    }
    return sb.toString().trim()
  }

  /**
   * Expand an RRULE into occurrence start-times up to [horizonEnd]. Supports the
   * common shapes (FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, and
   * weekly BYDAY); on anything unexpected it falls back to a single occurrence so the
   * event still shows once.
   */
  internal fun expandRecurrence(
      start: Long,
      rrule: String,
      exdates: List<Long>,
      horizonEnd: Long,
      notBefore: Long? = null,
      zone: TimeZone = TimeZone.getDefault(),
  ): List<Long> =
      runCatching {
            val rules =
                rrule
                    .split(';')
                    .mapNotNull {
                      val eq = it.indexOf('=')
                      if (eq > 0) it.substring(0, eq).uppercase(Locale.US) to it.substring(eq + 1)
                      else null
                    }
                    .toMap()
            val freq = rules["FREQ"]?.uppercase(Locale.US) ?: return@runCatching listOf(start)
            val interval = rules["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val count = rules["COUNT"]?.toIntOrNull()
            val until = rules["UNTIL"]?.let { parseUntil(it, zone) }
            val byDay =
                rules["BYDAY"]?.split(',')?.mapNotNull { weekdayOf(it.trim()) }?.takeIf {
                  it.isNotEmpty()
                }

            val cap = 366
            val out = ArrayList<Long>()
            val exSet = exdates.toHashSet()
            val cal = Calendar.getInstance(zone)
            cal.timeInMillis = start
            // RFC 5545: COUNT counts generated occurrences; EXDATE then removes some
            // from that set. So we track generation separately from what we keep.
            var generated = 0L

            // Emit one occurrence (subject to EXDATE). Returns false once we've run
            // past UNTIL / the horizon, so the caller can stop generating.
            fun emit(t: Long): Boolean {
              if (until != null && t > until) return false
              if (t > horizonEnd) return false
              if (t !in exSet) out.add(t)
              return true
            }

            if (freq == "WEEKLY" && byDay != null) {
              // Walk week by week (INTERVAL weeks apart); within each week emit every
              // listed weekday, preserving the original time-of-day.
              val hour = cal.get(Calendar.HOUR_OF_DAY)
              val min = cal.get(Calendar.MINUTE)
              val sec = cal.get(Calendar.SECOND)
              // Move to the Sunday that starts this event's week.
              val weekStart = Calendar.getInstance(zone)
              weekStart.timeInMillis = start
              weekStart.set(Calendar.HOUR_OF_DAY, hour)
              weekStart.set(Calendar.MINUTE, min)
              weekStart.set(Calendar.SECOND, sec)
              weekStart.set(Calendar.MILLISECOND, 0)
              weekStart.add(Calendar.DAY_OF_MONTH, -(weekStart.get(Calendar.DAY_OF_WEEK) - 1))
              val skipped = recurrencePeriodsBefore(weekStart, notBefore, "WEEKLY", interval)
              if (skipped > 0) {
                val firstWeek = byDay.count { dow ->
                  (weekStart.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, dow - 1)
                  }.timeInMillis >= start
                }
                generated = firstWeek.toLong() + (skipped - 1) * byDay.size
                advanceRecurrence(weekStart, "WEEKLY", interval, skipped)
              }
              var weeks = 0
              var attemptedWeeks = 0
              while (weeks < cap && attemptedWeeks++ < cap + 4) {
                var anyInHorizon = false
                for (dow in byDay.sorted()) {
                  val occ = weekStart.clone() as Calendar
                  occ.add(Calendar.DAY_OF_MONTH, dow - 1) // dow: 1=Sun..7=Sat
                  val t = occ.timeInMillis
                  if (t < start) continue // skip days before the series actually begins
                  anyInHorizon = anyInHorizon || t <= horizonEnd
                  if (count != null && generated >= count.toLong()) return@runCatching out
                  generated++
                  if (!emit(t) && until != null) return@runCatching out
                }
                if (!anyInHorizon && weekStart.timeInMillis > horizonEnd) break
                if (notBefore == null || weekStart.timeInMillis >= notBefore) weeks++
                advanceCalendar(weekStart, Calendar.DAY_OF_MONTH, 7L * interval)
              }
              return@runCatching out
            }

            val skipped = recurrencePeriodsBefore(cal, notBefore, freq, interval)
            generated = skipped
            if (count != null && generated >= count.toLong()) return@runCatching out
            advanceRecurrence(cal, freq, interval, skipped)
            var iterations = 0
            var attemptedPeriods = 0
            while (iterations < cap && attemptedPeriods++ < cap + 4) {
              if (count != null && generated >= count.toLong()) break
              val t = cal.timeInMillis
              generated++
              if (!emit(t)) {
                if (t > horizonEnd && (until == null || t > until)) break
              }
              when (freq) {
                "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, interval)
                "WEEKLY" -> advanceCalendar(cal, Calendar.DAY_OF_MONTH, 7L * interval)
                "MONTHLY" -> cal.add(Calendar.MONTH, interval)
                "YEARLY" -> cal.add(Calendar.YEAR, interval)
                else -> return@runCatching listOf(start)
              }
              if (notBefore == null || t >= notBefore) iterations++
            }
            out
          }
          .getOrDefault(listOf(start))

  /** Leave two periods before the overlap boundary for offset changes and partial weeks. */
  private fun recurrencePeriodsBefore(
      start: Calendar, notBefore: Long?, freq: String, interval: Int,
  ): Long {
    if (notBefore == null || notBefore <= start.timeInMillis) return 0L
    val target = (start.clone() as Calendar).apply { timeInMillis = notBefore }
    val distance = when (freq) {
      "DAILY", "WEEKLY" ->
          Math.floorDiv(notBefore, 86_400_000L) - Math.floorDiv(start.timeInMillis, 86_400_000L)
      "MONTHLY" ->
          (target.get(Calendar.YEAR).toLong() - start.get(Calendar.YEAR)) * 12 +
              target.get(Calendar.MONTH) - start.get(Calendar.MONTH)
      "YEARLY" -> target.get(Calendar.YEAR).toLong() - start.get(Calendar.YEAR)
      else -> return 0L
    }
    val step = interval.toLong() * if (freq == "WEEKLY") 7 else 1
    return (distance / step - 2).coerceAtLeast(0L)
  }

  /** Calendar.add takes an Int; split a large seek without overflowing that argument. */
  private fun advanceCalendar(calendar: Calendar, field: Int, amount: Long) {
    var remaining = amount
    while (remaining > 0) {
      val step = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      calendar.add(field, step)
      remaining -= step
    }
  }

  private fun advanceRecurrence(calendar: Calendar, freq: String, interval: Int, periods: Long) {
    if (periods == 0L) return
    val field = when (freq) {
      "DAILY", "WEEKLY" -> Calendar.DAY_OF_MONTH
      "MONTHLY" -> Calendar.MONTH
      else -> Calendar.YEAR
    }
    val step = interval.toLong() * if (freq == "WEEKLY") 7 else 1
    // Retain normalization across every period the original expansion could visit,
    // including local times inside a daylight-saving gap, before seeking farther.
    val prefix = periods.coerceAtMost(366L).toInt()
    repeat(prefix) { advanceCalendar(calendar, field, step) }
    var remaining = periods - prefix
    if (remaining == 0L) return
    if (freq == "DAILY" || freq == "WEEKLY") {
      advanceCalendar(calendar, Calendar.DAY_OF_MONTH, remaining * step)
      return
    }
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val cycle = if (freq == "MONTHLY") 4800 else 400
    val phaseLength = cycle / gcd(interval, cycle)
    val minimumDay = if (freq == "YEARLY") {
      if (calendar.get(Calendar.MONTH) == Calendar.FEBRUARY) 28 else 31
    } else {
      val month = calendar.get(Calendar.MONTH)
      val monthStride = interval % 12
      (0 until 12 / gcd(monthStride, 12)).minOf { offset ->
        when ((month + offset * monthStride) % 12) {
          Calendar.FEBRUARY -> 28
          Calendar.APRIL, Calendar.JUNE, Calendar.SEPTEMBER, Calendar.NOVEMBER -> 30
          else -> 31
        }
      }
    }
    // Preserve sequential Calendar.add's existing day clamping before jumping.
    // Once the reachable minimum or a full Gregorian phase is visited, it cannot clamp further.
    var examined = 0
    while (remaining > 0 && examined < phaseLength &&
        calendar.get(Calendar.DAY_OF_MONTH) > minimumDay) {
      calendar.add(field, interval)
      remaining--
      examined++
    }
    advanceCalendar(calendar, field, remaining * interval)
  }

  /** RRULE UNTIL is a date or UTC date-time; resolve to millis. */
  private fun parseUntil(v: String, zone: TimeZone): Long? =
      runCatching {
            val s = v.trim()
            if (s.length == 8 && !s.contains('T')) {
              val cal = Calendar.getInstance(zone)
              cal.clear()
              cal.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(), 23, 59, 59)
              cal.timeInMillis
            } else {
              val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
              fmt.timeZone = if (s.endsWith("Z")) TimeZone.getTimeZone("UTC") else zone
              fmt.parse(s.removeSuffix("Z"))?.time
            }
          }
          .getOrNull()

  /** "MO","TU",… (optionally prefixed like "2MO") → Calendar.DAY_OF_WEEK. */
  private fun weekdayOf(token: String): Int? {
    val day = token.takeLast(2).uppercase(Locale.US)
    return when (day) {
      "SU" -> Calendar.SUNDAY
      "MO" -> Calendar.MONDAY
      "TU" -> Calendar.TUESDAY
      "WE" -> Calendar.WEDNESDAY
      "TH" -> Calendar.THURSDAY
      "FR" -> Calendar.FRIDAY
      "SA" -> Calendar.SATURDAY
      else -> null
    }
  }

  // --- windowing (pure; unit-tested) ------------------------------------------

  /**
   * Pick the events to show for a display [range], relative to [nowMillis]. For the
   * day/3-day/week ranges this is everything that overlaps the window [now → end of
   * the Nth day]; for the agenda range it's simply the next [MAX_EVENTS] events,
   * however far out. Already-finished events are dropped either way.
   */
  fun window(events: List<Event>, range: String, nowMillis: Long): List<Event> {
    val live = events.filter { it.endMillis > nowMillis }.sortedBy { it.startMillis }
    if (range == RANGE_AGENDA) return live.take(MAX_EVENTS)
    val days =
        when (range) {
          RANGE_WEEK -> 7
          RANGE_3DAY -> 3
          else -> 1
        }
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMillis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_MONTH, days)
    val windowEnd = cal.timeInMillis // exclusive: start of the day after the window
    return live.filter { it.startMillis < windowEnd }.take(MAX_EVENTS)
  }

  // --- net --------------------------------------------------------------------

  private fun httpGet(spec: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    return CalendarFeedHttp.fetch(c)
  }
}
