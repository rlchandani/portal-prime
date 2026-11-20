/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What Jarvis asked for in `hearthpane.home_control`: the user's words, untrusted (from the model). */
internal data class CcHomeRequest(val text: String)

/** What a household request does. */
internal enum class CcHomeVerb { ON, OFF, LOCK, UNLOCK, OPEN, CLOSE, STOP, BRIGHTNESS, TEMPERATURE, RUN, ARM, DISARM }

/** Which kind of thing it acts on. */
internal enum class CcHomeWhat(val noun: String, val one: String) {
  LIGHTS("lights", "light"), SWITCHES("switches", "switch"), FANS("fans", "fan"), LOCKS("locks", "lock"),
  GARAGE("garage doors", "garage door"), BLINDS("blinds", "blind"), VALVE("water valves", "water valve"),
  CLIMATE("thermostats", "thermostat"), MEDIA("TVs and speakers", "TV"), SCENE("scenes", "scene"), ALARM("alarms", "alarm"),
}

/** One entity and the Portal's own call for it, with the room it sits in (for the highlight). */
internal data class CcHomeItem(val entity: CcEntity, val call: CcCall, val roomId: String?)

/** A request resolved against this Portal's Control Center registry, or why it can't be. */
internal sealed interface CcHomeIntent {
  /**
   * [items] to act on (empty when everything is [already] in the asked state); [place] is how the
   * scope reads in a sentence ("the kitchen", "upstairs", "the house"), null for one named device.
   */
  data class Act(val verb: CcHomeVerb, val what: CcHomeWhat, val items: List<CcHomeItem>, val already: Int, val place: String?,
                 val device: String?, val roomIds: List<String>, val floorId: String?, val value: Double?) : CcHomeIntent

  data class Unclear(val reply: String) : CcHomeIntent
}

/**
 * Turns "turn off the kitchen lights" into the Portal's own calls (pure, unit tested). Rooms,
 * areas and floors come from the Control Center registry and plan, devices from the entities
 * the Control Center watches; calls are built only with [CcCalls], exactly as the Portal's own
 * controls build them, and policy is applied afterwards ([CcHomeRoute]). Nothing outside that
 * vocabulary can be reached: no service names, no entity ids from the model, and never the car.
 */
internal object CcHomeCommand {
  const val MAX_TEXT = 200
  /** A request touching more than this many devices is refused rather than half done. */
  const val MAX_ITEMS = 40
  private const val DIM_PCT = 30
  private const val FULL_PCT = 100

  fun resolve(s: CcUiState, r: CcHomeRequest): CcHomeIntent {
    val text = normalize(r.text.take(MAX_TEXT))
    if (text.isBlank()) return CcHomeIntent.Unclear("Say what to do, for example \"turn off the kitchen lights\".")
    val verb = verb(text) ?: return CcHomeIntent.Unclear("I can turn things on or off, lock, open, close, dim, set the heating or run a scene.")
    val value = value(text, verb)
    val explicit = what(text)
    val candidates = s.entities.values.filter { it.id !in s.registry?.tesla?.all.orEmpty() && it.domain in CONTROLLABLE }
    val device = bestDevice(text, candidates, explicit, verb)
    val rooms = rooms(s, text)
    val roomLen = rooms.maxOfOrNull { it.matched.length } ?: 0
    if (device != null && device.second.length > roomLen) {
      val e = device.first
      val what = explicit ?: kindOf(e) ?: return CcHomeIntent.Unclear("I can't control ${e.name} by voice.")
      return act(s, text, verb, what, listOf(e), null, e.name, value, emptyList(), null)
    }
    val what = explicit ?: defaultWhat(verb, text) ?: return CcHomeIntent.Unclear("Which device? Name it, like \"the floor lamp\".")
    if (what == CcHomeWhat.SCENE) return CcHomeIntent.Unclear("Which scene? Say its name.")
    val floor = floor(s, text)
    val whole = WHOLE.containsMatchIn(text)
    val scope: Scope = when {
      rooms.isNotEmpty() -> Scope(rooms.map { it.id }.distinct(), placeOf(rooms.map { it.name }), null)
      floor != null -> Scope(roomsOn(s, floor.first), floor.second.lowercase(), floor.first)
      // "Close the garage" needs no room, but only when the words named the kind: an unknown name
      // ("unlock the boat") must never fall through to every lock in the house.
      whole || what in HOUSE_WIDE && (explicit != null || verb == CcHomeVerb.ARM || verb == CcHomeVerb.DISARM) ->
        Scope(null, "the house", null)
      else -> return CcHomeIntent.Unclear("Which room? I know ${knownRooms(s)}.")
    }
    val inScope = candidates.filter { e -> fits(e, what) && (scope.roomIds == null || roomOf(s, e.id) in scope.roomIds) }
    if (inScope.isEmpty()) return CcHomeIntent.Unclear("I don't see any ${what.noun} in ${scope.place}.")
    return act(s, text, verb, what, inScope, scope.place, null, value, scope.roomIds.orEmpty(), scope.floorId)
  }

  private class Scope(val roomIds: List<String>?, val place: String, val floorId: String?)

  private class RoomMatch(val id: String, val name: String, val matched: String)

  private fun act(s: CcUiState, text: String, verb: CcHomeVerb, what: CcHomeWhat, targets: List<CcEntity>, place: String?, device: String?,
                  value: Double?, roomIds: List<String>, floorId: String?): CcHomeIntent {
    if (verb == CcHomeVerb.TEMPERATURE) {
      val t = value ?: return CcHomeIntent.Unclear("To what temperature?")
      targets.firstOrNull { t < it.minTemp || t > it.maxTemp }?.let {
        return CcHomeIntent.Unclear("${it.name} goes from ${fmt(it.minTemp)}° to ${fmt(it.maxTemp)}°.")
      }
    }
    var already = 0
    val items = ArrayList<CcHomeItem>()
    for (e in targets.filter { !it.unavailable }) {
      if (isAlready(e, verb, value)) { already++; continue }
      val call = call(e, verb, value, text) ?: continue
      items += CcHomeItem(e, call, roomOf(s, e.id))
    }
    if (items.isEmpty() && already == 0) {
      return CcHomeIntent.Unclear("I can't ${verbWord(verb)} ${device ?: "the ${what.noun} in $place"}.")
    }
    if (items.size > MAX_ITEMS) return CcHomeIntent.Unclear("That's ${items.size} devices at once. Try one room or floor.")
    val rooms = roomIds.ifEmpty { items.mapNotNull { it.roomId }.distinct() }
    return CcHomeIntent.Act(verb, what, items, already, place, device, rooms, floorId ?: floorOfRooms(s, rooms), value)
  }

  /** The Portal's own call for [verb] on [e], or null when [e] can't do it. */
  private fun call(e: CcEntity, verb: CcHomeVerb, value: Double?, text: String): CcCall? = when (verb) {
    CcHomeVerb.ON, CcHomeVerb.OFF -> {
      val on = verb == CcHomeVerb.ON
      when (e.domain) {
        "light" -> CcCalls.lightPower(e, on)
        "switch", "fan", "input_boolean" -> if (kindOf(e) == CcHomeWhat.VALVE) valve(e, on) else CcCalls.power(e, on)
        "media_player" -> CcCalls.mediaPower(e, on)
        "valve" -> valve(e, on)
        // bestDevice lets a scene be named under ON ("turn on movie time"): that means run it.
        "scene", "script" -> if (on) CcCalls.run(e) else null
        else -> null
      }
    }
    CcHomeVerb.LOCK -> if (e.domain == "lock") CcCalls.lock(e) else null
    CcHomeVerb.UNLOCK -> if (e.domain == "lock") CcCalls.unlock(e) else null
    CcHomeVerb.OPEN -> when (e.domain) { "cover" -> CcCalls.coverOpen(e); "valve" -> CcCalls.valveOpen(e); else -> null }
    CcHomeVerb.CLOSE -> when (e.domain) { "cover" -> CcCalls.coverClose(e); "valve" -> CcCalls.valveClose(e); else -> null }
    CcHomeVerb.STOP -> if (e.domain == "cover") CcCalls.coverStop(e) else null
    CcHomeVerb.BRIGHTNESS -> value?.toInt()?.takeIf { e.domain == "light" && it in 0..FULL_PCT }?.let {
      if (it == 0) CcCalls.lightPower(e, false) else CcCalls.brightness(e, it)
    }
    CcHomeVerb.TEMPERATURE -> value?.takeIf { e.domain == "climate" && it >= e.minTemp && it <= e.maxTemp }?.let { CcCalls.temperature(e, it) }
    CcHomeVerb.RUN -> if (e.domain == "scene" || e.domain == "script") CcCalls.run(e) else null
    CcHomeVerb.ARM -> if (e.domain == "alarm_control_panel") armMode(e, text)?.let { CcCalls.alarm(e, it, null) } else null
    CcHomeVerb.DISARM -> if (e.domain == "alarm_control_panel") CcCalls.alarm(e, CcAlarmMode.DISARM, null) else null
  }

  private fun valve(e: CcEntity, on: Boolean): CcCall? = when (e.domain) {
    "valve" -> if (on) CcCalls.valveOpen(e) else CcCalls.valveClose(e)
    "switch" -> CcCalls.power(e, on)
    else -> null
  }

  /** The arm mode the words asked for (night / away / home), if the panel offers it. */
  private fun armMode(e: CcEntity, text: String): CcAlarmMode? {
    val want = when {
      "night" in text -> CcAlarmMode.NIGHT
      "away" in text || "leaving" in text -> CcAlarmMode.AWAY
      else -> CcAlarmMode.HOME
    }
    return want.takeIf { it in CcAlarmMode.offered(e) }
  }

  private fun isAlready(e: CcEntity, verb: CcHomeVerb, value: Double?): Boolean = when (verb) {
    CcHomeVerb.ON -> e.domain in POWERED && e.isOn
    CcHomeVerb.OFF -> e.domain in POWERED && !e.isOn
    CcHomeVerb.LOCK -> e.domain == "lock" && e.state == "locked"
    CcHomeVerb.UNLOCK -> e.domain == "lock" && e.state == "unlocked"
    CcHomeVerb.OPEN -> (e.domain == "cover" || e.domain == "valve") && e.state == "open"
    CcHomeVerb.CLOSE -> (e.domain == "cover" || e.domain == "valve") && e.state == "closed"
    CcHomeVerb.BRIGHTNESS -> value?.toInt() == 0 && e.domain == "light" && !e.isOn
    else -> false
  }

  // --- words -------------------------------------------------------------------------------

  /** Lowercase words: apostrophes dropped ("mia's" → "mias"), everything else but %, . and digits a space. */
  fun normalize(text: String): String =
      text.lowercase().replace("'", "").replace("’", "").replace(NON_WORD, " ").replace(SPACES, " ").trim()

  fun verb(text: String): CcHomeVerb? {
    return when {
      Regex("\\bdisarm\\b").containsMatchIn(text) -> CcHomeVerb.DISARM
      Regex("\\barm\\b").containsMatchIn(text) -> CcHomeVerb.ARM
      Regex("\\bunlock\\b").containsMatchIn(text) -> CcHomeVerb.UNLOCK
      Regex("\\block\\b").containsMatchIn(text) -> CcHomeVerb.LOCK
      Regex("\\b(\\d{1,3})\\s*(%|percent)").containsMatchIn(text) || Regex("\\b(dim|brighten|brightness)\\b").containsMatchIn(text) ->
        CcHomeVerb.BRIGHTNESS
      Regex("\\b(\\d{2,3}(\\.\\d)?)\\s*(degrees|deg)\\b").containsMatchIn(text) ||
          Regex("\\b(thermostat|heating|heat|temperature)\\b.*\\bto \\d").containsMatchIn(text) -> CcHomeVerb.TEMPERATURE
      Regex("\\b(turn|switch|shut|power)\\s+(\\w+\\s+){0,4}?off\\b|\\boff\\b").containsMatchIn(text) -> CcHomeVerb.OFF
      Regex("\\b(turn|switch|power)\\s+(\\w+\\s+){0,4}?on\\b|\\bon\\b").containsMatchIn(text) -> CcHomeVerb.ON
      Regex("\\b(open|raise)\\b").containsMatchIn(text) -> CcHomeVerb.OPEN
      Regex("\\b(close|shut|lower)\\b").containsMatchIn(text) -> CcHomeVerb.CLOSE
      Regex("\\bstop\\b").containsMatchIn(text) -> CcHomeVerb.STOP
      Regex("\\b(run|start|activate|good ?night|movie time)\\b").containsMatchIn(text) -> CcHomeVerb.RUN
      else -> null
    }
  }

  private fun value(text: String, verb: CcHomeVerb): Double? = when (verb) {
    CcHomeVerb.BRIGHTNESS -> Regex("\\b(\\d{1,3})\\s*(%|percent)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: if ("brighten" in text) FULL_PCT.toDouble() else DIM_PCT.toDouble()
    CcHomeVerb.TEMPERATURE -> Regex("\\b(\\d{2,3}(\\.\\d)?)\\b").findAll(text).map { it.groupValues[1] }.lastOrNull()?.toDoubleOrNull()
    else -> null
  }

  /** The kind the words name; "switch" as the verb ("switch the hall off") is not the noun. */
  fun what(text: String): CcHomeWhat? {
    val nouns = text.replace(SWITCH_VERB, " ")
    return WHAT_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(nouns) }?.second
  }

  private fun defaultWhat(verb: CcHomeVerb, text: String): CcHomeWhat? = when (verb) {
    CcHomeVerb.LOCK, CcHomeVerb.UNLOCK -> CcHomeWhat.LOCKS
    CcHomeVerb.ARM, CcHomeVerb.DISARM -> CcHomeWhat.ALARM
    CcHomeVerb.TEMPERATURE -> CcHomeWhat.CLIMATE
    CcHomeVerb.BRIGHTNESS, CcHomeVerb.ON, CcHomeVerb.OFF -> CcHomeWhat.LIGHTS
    CcHomeVerb.OPEN, CcHomeVerb.CLOSE, CcHomeVerb.STOP -> if ("garage" in text) CcHomeWhat.GARAGE else CcHomeWhat.BLINDS
    CcHomeVerb.RUN -> CcHomeWhat.SCENE
  }

  /** Which [CcHomeWhat] an entity is, or null for things voice doesn't drive (sensors, cameras). */
  fun kindOf(e: CcEntity): CcHomeWhat? = when (CcKind.of(e)) {
    CcKind.LIGHT -> CcHomeWhat.LIGHTS
    CcKind.SWITCH -> if (VALVE_HINTS.any { it in e.id }) CcHomeWhat.VALVE else if (LIGHTISH.containsMatchIn(normalize(e.name))) CcHomeWhat.LIGHTS else CcHomeWhat.SWITCHES
    CcKind.FAN -> CcHomeWhat.FANS
    CcKind.LOCK -> CcHomeWhat.LOCKS
    CcKind.GARAGE -> CcHomeWhat.GARAGE
    CcKind.COVER -> CcHomeWhat.BLINDS
    CcKind.VALVE -> CcHomeWhat.VALVE
    CcKind.CLIMATE -> CcHomeWhat.CLIMATE
    CcKind.MEDIA -> CcHomeWhat.MEDIA
    CcKind.SCENE, CcKind.SCRIPT -> CcHomeWhat.SCENE
    CcKind.ALARM -> CcHomeWhat.ALARM
    else -> null
  }

  private fun fits(e: CcEntity, what: CcHomeWhat): Boolean = kindOf(e) == what

  /** The entity whose name the words say, longest name first (so "garage door" beats "garage"). */
  private fun bestDevice(text: String, entities: List<CcEntity>, explicit: CcHomeWhat?, verb: CcHomeVerb): Pair<CcEntity, String>? =
      entities.asSequence().mapNotNull { e ->
        val kind = kindOf(e) ?: return@mapNotNull null
        if (explicit != null && kind != explicit) return@mapNotNull null
        if (kind == CcHomeWhat.SCENE && verb != CcHomeVerb.RUN && verb != CcHomeVerb.ON) return@mapNotNull null
        val name = normalize(e.name)
        if (name.length < MIN_NAME || !contains(text, name)) return@mapNotNull null
        e to name
      }.maxByOrNull { it.second.length }

  private fun rooms(s: CcUiState, text: String): List<RoomMatch> {
    val named = LinkedHashMap<String, String>()
    s.plan?.floors?.forEach { f -> f.rooms.forEach { r -> named.putIfAbsent(r.id, r.name) } }
    s.registry?.areas?.forEach { a -> named.putIfAbsent(a.id, a.name) }
    return named.mapNotNull { (id, name) ->
      aliases(name).filter { contains(text, it) }.maxByOrNull { it.length }?.let { RoomMatch(id, name, it) }
    }.let { all ->
      // "living room" also contains "room": keep only matches no longer match covers.
      all.filter { m -> all.none { o -> o !== m && o.matched.length > m.matched.length && o.matched.contains(m.matched) } }
    }
  }

  /** A room's spoken names: "Mia's room" → "mias room", "mias"; "Living room" → "living room", "living". */
  fun aliases(name: String): List<String> {
    val n = normalize(name)
    val short = n.removeSuffix(" room").removePrefix("the ").trim()
    return listOf(n, short).filter { it.length >= MIN_NAME && it !in GENERIC }.distinct()
  }

  private fun floor(s: CcUiState, text: String): Pair<String, String>? {
    val floors = s.plan?.floors.orEmpty()
    floors.firstOrNull { f -> aliases(f.name).any { contains(text, it) } }?.let { return it.id to it.name }
    val up = Regex("\\b(upstairs|upper floor|top floor)\\b").containsMatchIn(text)
    val down = Regex("\\b(downstairs|ground floor|main floor)\\b").containsMatchIn(text)
    if (!up && !down) return null
    val hints = if (up) UP_HINTS else DOWN_HINTS
    val f = floors.firstOrNull { f -> hints.any { it in normalize(f.name) } } ?: return null
    return f.id to if (up) "upstairs" else "downstairs"
  }

  private fun roomsOn(s: CcUiState, floorId: String): List<String> = s.plan?.floors?.firstOrNull { it.id == floorId }?.rooms?.map { it.id }.orEmpty()

  /** The room (HA area) an entity is in: its plan room first, else the registry's area. */
  fun roomOf(s: CcUiState, entityId: String): String? = s.plan?.roomOf(entityId)?.id ?: s.registry?.areaOf(entityId)

  private fun floorOfRooms(s: CcUiState, rooms: List<String>): String? =
      rooms.firstNotNullOfOrNull { id -> s.plan?.floors?.firstOrNull { f -> f.rooms.any { it.id == id } }?.id }

  private fun knownRooms(s: CcUiState): String {
    val names = s.plan?.floors?.flatMap { f -> f.rooms.filter { !it.outdoor }.map { it.name } }.orEmpty().ifEmpty {
      s.registry?.areas?.map { it.name }.orEmpty()
    }
    return if (names.isEmpty()) "no rooms yet" else names.take(KNOWN_ROOMS).joinToString(", ")
  }

  private fun placeOf(names: List<String>): String {
    val lower = names.map { it.lowercase() }
    return "the " + if (lower.size == 1) lower[0] else lower.dropLast(1).joinToString(", ") + " and " + lower.last()
  }

  private fun contains(text: String, phrase: String): Boolean = Regex("\\b${Regex.escape(phrase)}\\b").containsMatchIn(text)

  fun verbWord(v: CcHomeVerb): String = when (v) {
    CcHomeVerb.ON -> "turn on"; CcHomeVerb.OFF -> "turn off"; CcHomeVerb.LOCK -> "lock"; CcHomeVerb.UNLOCK -> "unlock"
    CcHomeVerb.OPEN -> "open"; CcHomeVerb.CLOSE -> "close"; CcHomeVerb.STOP -> "stop"; CcHomeVerb.BRIGHTNESS -> "dim"
    CcHomeVerb.TEMPERATURE -> "set"; CcHomeVerb.RUN -> "run"; CcHomeVerb.ARM -> "arm"; CcHomeVerb.DISARM -> "disarm"
  }

  fun fmt(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(java.util.Locale.US, t)

  private val NON_WORD = Regex("[^a-z0-9%. ]")
  private val SPACES = Regex("\\s+")
  private const val MIN_NAME = 2
  private const val KNOWN_ROOMS = 8
  private val GENERIC = setOf("room", "the", "home", "house", "floor")
  private val WHOLE = Regex("\\b(all|every|everything|whole house|entire house|the house)\\b")
  private val UP_HINTS = listOf("up", "first", "second", "top", "1", "2")
  private val DOWN_HINTS = listOf("down", "ground", "main", "lower", "0")
  private val SWITCH_VERB = Regex("\\bswitch\\b(?=(\\s+\\w+){0,4}?\\s+(on|off)\\b)|\\bswitch\\s+(on|off)\\b")
  private val LIGHTISH = Regex("\\b(light|lights|lamp|lamps)\\b")
  private val VALVE_HINTS = listOf("valve", "water_main", "main_water")
  /** Things that are one per house: "close the garage" needs no room. */
  private val HOUSE_WIDE = setOf(CcHomeWhat.GARAGE, CcHomeWhat.VALVE, CcHomeWhat.ALARM, CcHomeWhat.LOCKS)
  private val POWERED = setOf("light", "switch", "fan", "input_boolean", "media_player")
  private val CONTROLLABLE = setOf("light", "switch", "fan", "input_boolean", "lock", "cover", "valve", "climate", "media_player",
      "scene", "script", "alarm_control_panel")
  private val WHAT_WORDS: List<Pair<Regex, CcHomeWhat>> = listOf(
      Regex("\\bgarage\\b") to CcHomeWhat.GARAGE,
      Regex("\\b(water|valve)\\b") to CcHomeWhat.VALVE,
      Regex("\\b(lights?|lamps?|bulbs?|lighting)\\b") to CcHomeWhat.LIGHTS,
      Regex("\\bfans?\\b") to CcHomeWhat.FANS,
      Regex("\\b(blinds?|shades?|curtains?|shutters?)\\b") to CcHomeWhat.BLINDS,
      Regex("\\b(thermostats?|heating|heat|temperature|ac|air conditioning)\\b") to CcHomeWhat.CLIMATE,
      Regex("\\b(tv|television|speakers?)\\b") to CcHomeWhat.MEDIA,
      Regex("\\b(alarm|security)\\b") to CcHomeWhat.ALARM,
      Regex("\\b(locks?|doors?)\\b") to CcHomeWhat.LOCKS,
      Regex("\\b(plugs?|outlets?|switch|switches)\\b") to CcHomeWhat.SWITCHES,
      Regex("\\bscenes?\\b") to CcHomeWhat.SCENE,
  )
}
