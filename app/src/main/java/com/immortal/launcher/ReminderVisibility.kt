/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Who may see which reminder, everywhere it is shown. Pure; the one place the privacy rules live:
 * - a private reminder shows only on its owner's own Home, and its title only while
 *   [WhosThereUi.hidePrivate] is false (the owner alone, confirmed, not in a call);
 * - shared surfaces (Tools, the phone, Jarvis) never carry a private title, only a count;
 * - kids (parent-managed) never have private reminders.
 */
internal object ReminderVisibility {
  const val HOME_ROWS = 5

  /** One row on a Home card; [redacted] rows are drawn as soft bars. */
  data class Row(val reminder: Reminder, val redacted: Boolean, val mine: Boolean)

  data class HomeCard(val rows: List<Row>, val privateHidden: Int, val hasPrivate: Boolean, val total: Int)

  /** Public (non-private) view for shared surfaces, plus how many private ones each person has. */
  data class Shared(val reminders: List<Reminder>, val privateCounts: Map<String, Int>)

  /** Does [r] belong to [person] here? By id, or by name when this Portal doesn't know that id. */
  fun belongsTo(r: Reminder, person: WhosTherePerson, people: List<WhosTherePerson>): Boolean {
    val id = r.person ?: return false
    if (id == person.id) return true
    if (people.any { it.id == id }) return false
    return r.personName.isNotBlank() && r.personName.equals(person.name, ignoreCase = true)
  }

  /** The local person [r] is for, if any. */
  fun ownerOf(r: Reminder, people: List<WhosTherePerson>): WhosTherePerson? = people.firstOrNull { belongsTo(r, it, people) }

  /** Open first, then by due time (none last), then oldest first. */
  val order: Comparator<Reminder> = compareBy<Reminder>({ it.done }, { it.dueMs ?: Long.MAX_VALUE }, { it.createdMs }, { it.id })

  /** The personal Home's card: their open reminders, then the household's, at most [limit] rows. */
  fun homeCard(s: ReminderState, person: WhosTherePerson, people: List<WhosTherePerson>, hidePrivate: Boolean,
               limit: Int = HOME_ROWS): HomeCard {
    val open = s.live.filter { it.open }
    val mine = open.filter { belongsTo(it, person, people) }.map { it.copy(private = it.private && !person.role.parentManaged) }
        .sortedWith(order)
    val household = open.filter { it.household }.sortedWith(order)
    val rows = (mine.map { Row(it, it.private && hidePrivate, true) } + household.map { Row(it, false, false) }).take(limit)
    return HomeCard(rows, rows.count { it.redacted }, mine.any { it.private }, mine.size + household.size)
  }

  /**
   * The personal briefing's reminders: the person's own open ones, soonest first. The briefing hides
   * (and never speaks) all of them unless the person is alone, as the mock says.
   */
  fun briefingTitles(s: ReminderState, person: WhosTherePerson, people: List<WhosTherePerson>): List<String> =
      s.live.filter { it.open && belongsTo(it, person, people) }.sortedWith(order).map { it.title }

  /** What Tools, the phone and Jarvis may show: never a private reminder, only a count per person. */
  fun shared(s: ReminderState, people: List<WhosTherePerson>): Shared {
    val live = s.live
    val (priv, public) = live.partition { it.private }
    val counts = priv.filter { it.open }.groupingBy { ownerOf(it, people)?.name ?: it.personName }.eachCount()
    return Shared(public.sortedWith(order), counts)
  }

  /** Who a reminder is for, as shown: the household or the person's (local) name. */
  fun ownerLabel(r: Reminder, people: List<WhosTherePerson>): String =
      if (r.household) "Home" else ownerOf(r, people)?.name ?: r.personName.ifBlank { "Someone" }

  /** Private is on by default for adults and teens; kids and the household never have private items. */
  fun defaultPrivate(person: WhosTherePerson?): Boolean = person != null && !person.role.parentManaged

  /** Whether a draft for [person] may be private at all. */
  fun canBePrivate(person: WhosTherePerson?): Boolean = defaultPrivate(person)
}
