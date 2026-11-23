/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone

/** Weather for the briefing: now, and today's high/low and rain chance (percent, if known). */
internal data class BriefingWeather(
    val temp: Int,
    val code: Int,
    val high: Int? = null,
    val low: Int? = null,
    val rainChance: Int? = null,
)

/** A live shared timer, already reduced to what the briefing shows. */
internal data class BriefingTimer(val name: String, val room: String, val remainingMs: Long, val paused: Boolean)

/** A routine that runs today; [startMinute] is minutes after midnight. */
internal data class BriefingRoutine(val name: String, val startMinute: Int)

/** A source that may still be loading ([Loading]), or has answered ([Ready], value may be empty). */
internal sealed interface BriefingPart<out T> {
  object Loading : BriefingPart<Nothing>
  data class Ready<T>(val value: T) : BriefingPart<T>
}

/**
 * Everything the briefing builder needs, gathered off the main thread by [BriefingLoader]. Slow
 * sources (weather, calendar) start as [BriefingPart.Loading] so the first frame never waits.
 */
internal data class BriefingInputs(
    val nowMs: Long,
    val zone: TimeZone = TimeZone.getDefault(),
    val locale: Locale = Locale.getDefault(),
    val use24Hour: Boolean = false,
    val fahrenheit: Boolean = false,
    /** The owner's morning greeting ("Good morning") and optional name, from Welcome Greeting. */
    val greeting: String = BriefingCopy.DEFAULT_GREETING,
    val name: String = "",
    /** Ready(null): no location or the weather service did not answer. */
    val weather: BriefingPart<BriefingWeather?> = BriefingPart.Loading,
    /** Ready(null): no calendar added. */
    val events: BriefingPart<List<CalendarFeed.Event>?> = BriefingPart.Loading,
    /** Open (unticked) shared-list item names, in list order. */
    val listItems: List<String> = emptyList(),
    val timers: List<BriefingTimer> = emptyList(),
    /** Today's routines (any time); the builder picks the first one of the morning. */
    val routines: List<BriefingRoutine> = emptyList(),
    /** "Read it aloud" is on. */
    val speakOn: Boolean = false,
    /** The chime's quiet hours are on and it's inside the window now. */
    val quiet: Boolean = false,
    /** Who's there? recognised an adult: the briefing is theirs ([BriefingPersonalPolicy]). */
    val personal: BriefingPersonalInputs? = null,
)

internal enum class BriefingSectionKind(val icon: String) {
  WEATHER("weather"),
  CALENDAR("calendar"),
  LIST("todo"),
  TIMERS("timer"),
  ROUTINE("tools"),
  REMINDERS("todo"),
  /** Followed teams and markets (Jarvis personalization, personal briefing only). */
  INTERESTS("interest_ball"),
}

/** One block of the card. [muted] lines are status copy (loading, empty, unavailable). */
internal data class BriefingSection(
    val kind: BriefingSectionKind,
    val heading: String,
    val lines: List<String>,
    val muted: Boolean = false,
)

/** What the card shows and, when allowed, what is read aloud ([spoken] null = stay silent). */
internal data class Briefing(
    val title: String,
    val subtitle: String,
    val sections: List<BriefingSection>,
    val spoken: String?,
    /** Weather and calendar have both answered (the spoken summary waits for this). */
    val complete: Boolean,
    /** A quiet line under the sections (the personal briefing's privacy promise). */
    val note: String? = null,
)
