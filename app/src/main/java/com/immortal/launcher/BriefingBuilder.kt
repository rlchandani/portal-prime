/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar

/**
 * Pure briefing builder: [BriefingInputs] in, the card's sections and the spoken summary out.
 * No Android, no clock reads (time comes from [BriefingInputs.nowMs]); covered by JVM tests.
 */
internal object BriefingBuilder {
  const val MAX_EVENTS = 3
  const val MAX_LIST_NAMES = 3

  fun build(inputs: BriefingInputs): Briefing {
    inputs.personal?.let { return BriefingPersonalBuilder.build(inputs, it) }
    val todayEvents = (inputs.events as? BriefingPart.Ready)?.value?.let { todaysEvents(it, inputs) }
    val routine = firstMorningRoutine(inputs)
    val sections = listOfNotNull(
        weatherSection(inputs),
        calendarSection(inputs, todayEvents),
        listSection(inputs.listItems),
        timersSection(inputs.timers),
        routine?.let { routineSection(it, inputs.use24Hour) },
    )
    val complete = inputs.weather is BriefingPart.Ready && inputs.events is BriefingPart.Ready
    val time = BriefingCopy.clock(inputs.nowMs, inputs.zone, inputs.locale, inputs.use24Hour)
    return Briefing(
        title = BriefingCopy.greeting(inputs.greeting, inputs.name),
        subtitle = "$time · ${BriefingCopy.dayLine(inputs.nowMs, inputs.zone, inputs.locale)}",
        sections = sections,
        spoken = if (inputs.speakOn && !inputs.quiet) BriefingSpeech.summary(inputs, todayEvents, routine, time) else null,
        complete = complete,
    )
  }

  /** Events still to come or under way today, soonest first, at most [MAX_EVENTS]. */
  fun todaysEvents(events: List<CalendarFeed.Event>, inputs: BriefingInputs): List<CalendarFeed.Event> {
    val endOfDay = endOfDay(inputs)
    return events.filter { it.endMillis > inputs.nowMs && it.startMillis < endOfDay }
        .sortedWith(compareBy<CalendarFeed.Event> { !it.allDay }.thenBy { it.startMillis })
        .take(MAX_EVENTS)
  }

  /** Local midnight at the end of [BriefingInputs.nowMs]'s day. */
  fun endOfDay(inputs: BriefingInputs): Long = Calendar.getInstance(inputs.zone, inputs.locale).apply {
    timeInMillis = inputs.nowMs
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, 1)
  }.timeInMillis

  /** The earliest routine of today that starts before noon. */
  fun firstMorningRoutine(inputs: BriefingInputs): BriefingRoutine? =
      inputs.routines.filter { it.startMinute < BriefingCopy.NOON_MINUTE }.minByOrNull { it.startMinute }

  internal fun weatherSection(inputs: BriefingInputs): BriefingSection {
    val heading = "Weather"
    val part = inputs.weather
    if (part !is BriefingPart.Ready) return BriefingSection(BriefingSectionKind.WEATHER, heading,
        listOf("Checking the weather…"), muted = true)
    val w = part.value ?: return BriefingSection(BriefingSectionKind.WEATHER, heading,
        listOf("Weather isn't available right now"), muted = true)
    val lines = buildList {
      add("${w.temp}° now · ${BriefingCopy.condition(w.code)}")
      if (w.high != null && w.low != null) add("High ${w.high}° · Low ${w.low}°")
      w.rainChance?.let { add("$it% chance of rain") }
    }
    return BriefingSection(BriefingSectionKind.WEATHER, heading, lines)
  }

  private fun calendarSection(inputs: BriefingInputs, today: List<CalendarFeed.Event>?): BriefingSection {
    val heading = "Today"
    val part = inputs.events
    if (part !is BriefingPart.Ready) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("Checking your calendar…"), muted = true)
    if (part.value == null) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("No calendar added. Add one in Settings › Widgets & Calendar."), muted = true)
    if (today.isNullOrEmpty()) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("Nothing on the calendar today"), muted = true)
    val lines = today.map { e ->
      val time = if (e.allDay) "All day" else BriefingCopy.clock(e.startMillis, inputs.zone, inputs.locale, inputs.use24Hour)
      "$time  ${e.title}"
    }
    return BriefingSection(BriefingSectionKind.CALENDAR, heading, lines)
  }

  private fun listSection(items: List<String>): BriefingSection {
    val heading = "Shared list"
    if (items.isEmpty()) return BriefingSection(BriefingSectionKind.LIST, heading, listOf("The list is empty"), muted = true)
    val shown = items.take(MAX_LIST_NAMES)
    val more = items.size - shown.size
    val lines = buildList {
      add(BriefingCopy.plural(items.size, "item") + " to get")
      addAll(shown)
      if (more > 0) add("and $more more")
    }
    return BriefingSection(BriefingSectionKind.LIST, heading, lines)
  }

  internal fun timersSection(timers: List<BriefingTimer>): BriefingSection? {
    if (timers.isEmpty()) return null
    val lines = timers.sortedBy { it.remainingMs }.map { t ->
      val left = if (t.paused) "Paused, " + BriefingCopy.remaining(t.remainingMs).replaceFirstChar { it.lowercase() }
          else BriefingCopy.remaining(t.remainingMs)
      listOf(t.name, left, t.room).filter { it.isNotBlank() }.joinToString(" · ")
    }
    return BriefingSection(BriefingSectionKind.TIMERS, if (timers.size == 1) "Timer" else "Timers", lines)
  }

  internal fun routineSection(r: BriefingRoutine, use24Hour: Boolean): BriefingSection =
      BriefingSection(BriefingSectionKind.ROUTINE, "First routine",
          listOf("${r.name} at ${BriefingCopy.clockOfMinute(r.startMinute, use24Hour)}"))
}
