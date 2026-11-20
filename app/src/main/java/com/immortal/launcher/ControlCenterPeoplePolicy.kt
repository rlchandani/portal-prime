/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Who may appear where on the Control Center map, in one place (pure, JVM-tested). The plan shows
 * an anonymous "someone" pulse by default; a person's chip only when every one of these holds:
 *
 * 1. The household setting "Show who's where on the map" is [ControlCenterConfig.MAP_NAMES]
 *    (household scope; the default is [ControlCenterConfig.MAP_SOMEONE], so no names).
 * 2. On the Portal that recognised them ([shareable]): recognition is on there and not paused for
 *    the household, the person isn't paused, hasn't set "Never recognise me on this Portal", and has
 *    turned on "Show my room on the home map" (off by default, for kids too; an adult sets a kid's).
 * 3. On the Portal drawing the map ([accept]): the sighting is under [CcOccupancyFusion.SIGHTING_MS]
 *    old and, if the same name is enrolled here, that person isn't paused, opted out here, or off the map.
 *
 * Off hides every pulse that comes from people (motion sensors still pulse as before).
 */
internal object CcPeoplePolicy {
  enum class Mode { OFF, SOMEONE, NAMES }

  fun mode(settings: ControlCenterConfig.Settings): Mode = when (settings.mapPeople) {
    ControlCenterConfig.MAP_OFF -> Mode.OFF
    ControlCenterConfig.MAP_NAMES -> Mode.NAMES
    else -> Mode.SOMEONE
  }

  fun anonymous(settings: ControlCenterConfig.Settings): Boolean = mode(settings) != Mode.OFF
  fun named(settings: ControlCenterConfig.Settings): Boolean = mode(settings) == Mode.NAMES

  /** May the Portal that recognised [person] tell anyone (its own map, other Portals, phones) which room they're in? */
  fun shareable(person: WhosTherePerson, recognition: WhosThereSettings, mapMode: Mode, nowMs: Long): Boolean =
      mapMode == Mode.NAMES && recognition.enabled && recognition.pausedUntilMs <= nowMs &&
          person.recognisableAt(nowMs) && person.onMap

  /**
   * May this Portal draw a sighting of [name] that another Portal sent? Strictest wins: anyone with that
   * name enrolled here who is paused, has opted out of recognition here, or is off the map is dropped.
   */
  fun accept(name: String, ageMs: Long, local: List<WhosTherePerson>, mapMode: Mode, nowMs: Long): Boolean {
    if (mapMode != Mode.NAMES || ageMs !in 0 until CcOccupancyFusion.SIGHTING_MS) return false
    val same = local.filter { it.name.equals(name, ignoreCase = true) }
    return same.all { it.recognisableAt(nowMs) && it.onMap }
  }

  /**
   * The Who's home line for an HA person: their HA zone line, plus the room Who's there last saw
   * them in when names are allowed ("Home · Kitchen · 3 min ago"). Matched by first name.
   */
  fun whoLine(base: String, personName: String, home: Boolean, occupancy: CcOccupancy, named: Boolean, now: Long): String {
    if (!named || !home) return base
    val seen = occupancy.lastSeen[personName.trim().lowercase().substringBefore(' ')] ?: return base
    val head = base.substringBefore(" · ")
    return "$head · ${seen.roomName} · ${ago(now - seen.seenMs)}"
  }

  /**
   * What the recognising Portal does now. [share]: people in view it may show (their sighting refreshed);
   * [send]: those of them due a `room_seen` (first sight, or [resendMs] since the last); [forget]: people it
   * told other Portals about who may no longer be shown (a revocation goes out); [drop]: its own sightings
   * to remove at once (anyone no longer shareable, sent or not). Someone still at "Is this you?" isn't in view.
   */
  data class Step(val share: List<WhosTherePerson>, val send: List<WhosTherePerson>, val forget: Set<String>, val drop: Set<String>)

  fun step(view: WhosThereView, people: List<WhosTherePerson>, recognition: WhosThereSettings, mapMode: Mode,
           sent: Map<String, Long>, sighted: Set<String>, nowMs: Long, resendMs: Long): Step {
    val byId = people.associateBy { it.id }
    fun ok(id: String) = byId[id]?.let { shareable(it, recognition, mapMode, nowMs) } == true
    val unsure = (view as? WhosThereView.Personal)?.confirm == true
    val inView = if (unsure) emptyList() else WhosThereViewPolicy.people(view).distinct()
    val share = inView.filter(::ok).map(byId::getValue)
    val send = share.filter { p -> sent[p.id]?.let { nowMs - it >= resendMs } ?: true }
    return Step(share, send, sent.keys.filterNot(::ok).toSet(), sighted.filterNot(::ok).toSet())
  }

  fun ago(ms: Long): String {
    val min = (ms.coerceAtLeast(0L) / MS_PER_MIN).toInt()
    return if (min < 1) "now" else "$min min ago"
  }

  private const val MS_PER_MIN = 60_000L
}
