/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The personal briefing ([BriefingPersonalPolicy]): weather, *your* day, *your* reminders, timers and the
 * first routine. Private calendar titles read "Private" and reminders become a count while
 * [BriefingPersonalInputs.hidePrivate]; neither is spoken then. Pure, like [BriefingBuilder].
 */
internal object BriefingPersonalBuilder {
  fun build(inputs: BriefingInputs, p: BriefingPersonalInputs): Briefing {
    val today = (p.events as? BriefingPart.Ready)?.value?.let { todays(it, inputs) }
    val routine = BriefingBuilder.firstMorningRoutine(inputs)
    val time = BriefingCopy.clock(inputs.nowMs, inputs.zone, inputs.locale, inputs.use24Hour)
    return Briefing(
        title = BriefingCopy.greeting(inputs.greeting, p.name),
        subtitle = "${BriefingCopy.PERSONAL_SUBTITLE} · $time · ${BriefingCopy.dayLine(inputs.nowMs, inputs.zone, inputs.locale)}",
        sections = listOfNotNull(
            BriefingBuilder.weatherSection(inputs),
            calendarSection(inputs, p, today),
            remindersSection(p),
            interestsSection(p),
            BriefingBuilder.timersSection(inputs.timers),
            routine?.let { BriefingBuilder.routineSection(it, inputs.use24Hour) },
        ),
        spoken = if (inputs.speakOn && !inputs.quiet) BriefingSpeech.personal(inputs, p, today, routine, time) else null,
        complete = inputs.weather is BriefingPart.Ready && p.events is BriefingPart.Ready,
        note = BriefingCopy.personalNote(p.name),
    )
  }

  /** Their events still to come or under way today, all-day first, at most [BriefingBuilder.MAX_EVENTS]. */
  fun todays(events: List<PersonalEvent>, inputs: BriefingInputs): List<PersonalEvent> {
    val end = BriefingBuilder.endOfDay(inputs)
    return events.filter { it.endMillis > inputs.nowMs && it.startMillis < end }
        .sortedWith(compareBy<PersonalEvent> { !it.allDay }.thenBy { it.startMillis })
        .take(BriefingBuilder.MAX_EVENTS)
  }

  /** What an event is called on screen and aloud: a private one is just "Private" while hidden. */
  fun title(e: PersonalEvent, hidePrivate: Boolean): String =
      if (e.private && hidePrivate) WhosThereContent.PRIVATE_LABEL else e.title

  private fun calendarSection(inputs: BriefingInputs, p: BriefingPersonalInputs, today: List<PersonalEvent>?): BriefingSection {
    val heading = BriefingCopy.PERSONAL_DAY
    val part = p.events
    if (part !is BriefingPart.Ready) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("Checking your calendar…"), muted = true)
    if (part.value == null) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("No calendar added. Add one in Settings › Widgets & Calendar."), muted = true)
    if (today.isNullOrEmpty()) return BriefingSection(BriefingSectionKind.CALENDAR, heading,
        listOf("Nothing on your calendar today"), muted = true)
    return BriefingSection(BriefingSectionKind.CALENDAR, heading, today.map { e ->
      val time = if (e.allDay) "All day" else BriefingCopy.clock(e.startMillis, inputs.zone, inputs.locale, inputs.use24Hour)
      "$time  ${title(e, p.hidePrivate)}"
    })
  }

  /** The followed teams and markets, only when there is something to say. */
  private fun interestsSection(p: BriefingPersonalInputs): BriefingSection? =
      p.interests.takeIf { it.isNotEmpty() }?.let {
        BriefingSection(BriefingSectionKind.INTERESTS, com.immortal.launcher.memory.BriefingInterests.HEADING, it)
      }

  private fun remindersSection(p: BriefingPersonalInputs): BriefingSection {
    val heading = BriefingCopy.PERSONAL_REMINDERS
    val items = p.reminders
    if (items.isEmpty()) return BriefingSection(BriefingSectionKind.REMINDERS, heading, listOf("Nothing on your list"), muted = true)
    val count = BriefingCopy.plural(items.size, "reminder")
    if (p.hidePrivate) return BriefingSection(BriefingSectionKind.REMINDERS, heading,
        listOf("$count, private", BriefingCopy.PRIVATE_HIDDEN), muted = true)
    val shown = items.take(BriefingBuilder.MAX_LIST_NAMES)
    val more = items.size - shown.size
    return BriefingSection(BriefingSectionKind.REMINDERS, heading, buildList {
      add(count)
      addAll(shown)
      if (more > 0) add("and $more more")
    })
  }
}
