/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What a personal Home can honestly show from data Hearthpane already has.
 *
 * Calendar: a person's own ICS feed when one is set; otherwise the household feed, keeping
 * events that name them ("Maya: dentist", "dentist #maya", "[Maya] dentist") plus family events
 * that name nobody. Events naming only someone else are left out. A title starting "Private",
 * or tagged #private, is a private event: its title hides when others are near.
 *
 * Reminders live in their own store now ([ReminderStore], rules in [ReminderVisibility]).
 */
internal enum class EventOwner { MINE, FAMILY }

internal data class PersonalEvent(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val allDay: Boolean,
    val location: String?,
    val owner: EventOwner,
    val private: Boolean,
)

internal object WhosThereContent {
  private val PRIVATE_PREFIX = Regex("^\\s*(?:private\\b|🔒)[:\\-–\\s]*", RegexOption.IGNORE_CASE)
  private val PRIVATE_TAG = Regex("(^|\\s)#private\\b", RegexOption.IGNORE_CASE)

  /** True when [text] names [name] as a word, a #tag, an @mention or a [bracket] prefix. */
  fun mentions(text: String, name: String): Boolean {
    val n = name.trim()
    if (n.isEmpty()) return false
    val pattern = Regex("(^|[^\\p{L}\\p{N}])[#@]?" + Regex.escape(n) + "($|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    return pattern.containsMatchIn(text)
  }

  fun isPrivate(title: String): Boolean = PRIVATE_PREFIX.containsMatchIn(title) || PRIVATE_TAG.containsMatchIn(title)

  /** Strip a leading "Name:" / "[Name]" / "#name" tag and the private marker for display. */
  fun displayTitle(title: String, name: String): String {
    var t = title.replace(PRIVATE_TAG, " ")
    t = t.replace(PRIVATE_PREFIX, "")
    val n = Regex.escape(name.trim())
    t = t.replace(Regex("^\\s*\\[$n]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*@?$n\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(^|\\s)[#@]$n\\b", RegexOption.IGNORE_CASE), " ")
    return t.trim().replace(Regex("\\s+"), " ").ifEmpty { title.trim() }
  }

  /** Events for [person]'s Home from the shared household feed. */
  fun fromShared(events: List<CalendarFeed.Event>, person: WhosTherePerson, everyone: List<WhosTherePerson>): List<PersonalEvent> {
    val others = everyone.filter { it.id != person.id }.map { it.name }
    return events.mapNotNull { e ->
      val mine = mentions(e.title, person.name)
      val namesOthers = others.any { mentions(e.title, it) }
      when {
        mine -> e.toPersonal(person, EventOwner.MINE)
        !namesOthers -> e.toPersonal(person, EventOwner.FAMILY)
        else -> null
      }
    }
  }

  /** Every event in a person's own feed is theirs. */
  fun fromOwnFeed(events: List<CalendarFeed.Event>, person: WhosTherePerson): List<PersonalEvent> =
      events.map { it.toPersonal(person, EventOwner.MINE) }

  /** Family view: family events in full, each person's own events as "Busy" if private. */
  fun forFamily(events: List<CalendarFeed.Event>, people: List<WhosTherePerson>): List<PersonalEvent> =
      events.map { e ->
        val owner = people.firstOrNull { mentions(e.title, it.name) }
        if (owner == null) e.toPersonal(null, EventOwner.FAMILY) else e.toPersonal(owner, EventOwner.MINE)
      }

  private fun CalendarFeed.Event.toPersonal(person: WhosTherePerson?, owner: EventOwner) = PersonalEvent(
      startMillis, endMillis, if (person == null) title.trim() else displayTitle(title, person.name), allDay, location,
      owner, private = isPrivate(title))

  /** Today's remaining events (all-day first), at most [limit]. */
  fun upcoming(events: List<PersonalEvent>, nowMs: Long, dayEndMs: Long, limit: Int = 6): List<PersonalEvent> =
      events.filter { it.endMillis > nowMs && it.startMillis < dayEndMs }
          .sortedWith(compareByDescending<PersonalEvent> { it.allDay }.thenBy { it.startMillis })
          .take(limit)

  /** The redacted text shown in place of a private title. */
  const val PRIVATE_LABEL = "Private"
}
