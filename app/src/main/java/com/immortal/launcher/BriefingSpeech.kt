/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The spoken morning summary: short sentences, only what is known, no symbols the voice would
 * read oddly. Pure; [BriefingBuilder] decides whether it is spoken at all (setting, quiet hours).
 */
internal object BriefingSpeech {
  fun summary(
      inputs: BriefingInputs,
      today: List<CalendarFeed.Event>?,
      routine: BriefingRoutine?,
      time: String,
  ): String = listOfNotNull(
      "${BriefingCopy.greeting(inputs.greeting, inputs.name)}. It's $time.",
      weather(inputs),
      calendar(inputs, today),
      list(inputs.listItems),
      timers(inputs.timers),
      routine?.let { "${it.name} starts at ${BriefingCopy.clockOfMinute(it.startMinute, inputs.use24Hour)}." },
  ).joinToString(" ")

  private fun weather(inputs: BriefingInputs): String? {
    val w = (inputs.weather as? BriefingPart.Ready)?.value ?: return null
    val unit = if (inputs.fahrenheit) "degrees Fahrenheit" else "degrees"
    val now = "It's ${w.temp} $unit and ${BriefingCopy.condition(w.code).lowercase()}"
    val range = if (w.high != null && w.low != null) ", with a high of ${w.high} and a low of ${w.low}" else ""
    val rain = w.rainChance?.let { " Chance of rain: $it percent." } ?: ""
    return "$now$range.$rain"
  }

  private fun calendar(inputs: BriefingInputs, today: List<CalendarFeed.Event>?): String? {
    if ((inputs.events as? BriefingPart.Ready)?.value == null) return null
    if (today.isNullOrEmpty()) return "Nothing on the calendar today."
    val first = today.first()
    val firstText = if (first.allDay) first.title
        else "${first.title} at ${BriefingCopy.clock(first.startMillis, inputs.zone, inputs.locale, inputs.use24Hour)}"
    val count = if (today.size == 1) "One thing on the calendar today" else "${today.size} things on the calendar today"
    return "$count, starting with $firstText."
  }

  private fun list(items: List<String>): String? {
    if (items.isEmpty()) return null
    val names = BriefingCopy.joinNames(items.take(BriefingBuilder.MAX_LIST_NAMES))
    val more = if (items.size > BriefingBuilder.MAX_LIST_NAMES) ", and more" else ""
    return "The shared list has ${BriefingCopy.plural(items.size, "item")}: $names$more."
  }

  private fun timers(timers: List<BriefingTimer>): String? {
    if (timers.isEmpty()) return null
    val parts = timers.sortedBy { it.remainingMs }.map { t ->
      val state = if (t.paused) "paused with ${BriefingCopy.spokenRemaining(t.remainingMs)} left"
          else "${BriefingCopy.spokenRemaining(t.remainingMs)} left"
      "${t.name}, $state"
    }
    val lead = if (timers.size == 1) "One timer is set" else "${timers.size} timers are set"
    return "$lead: ${parts.joinToString("; ")}."
  }
}
