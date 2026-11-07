/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** One picture card: a bundled [RoutinePictures] id and a short label. */
internal data class RoutineCard(val id: String, val pic: String, val label: String)

/** A child a routine can be for; [avatar] is a bundled avatar id. */
internal data class RoutineKid(val id: String, val name: String, val avatar: String)

/**
 * A picture routine. [days] is Monday-first; [start]/[end] are minutes of the day ("Ready by" is
 * [end]); [skips] are epoch days the parent ticked in Toolkit. An empty or single [kids] list shows
 * one set of cards; two or more give each kid a tab.
 */
internal data class Routine(
    val id: String,
    val name: String,
    val kids: List<String>,
    val cards: List<RoutineCard>,
    val days: List<Boolean>,
    val start: Int,
    val end: Int,
    val enabled: Boolean,
    val skips: List<Int>,
)

/** Everything a Portal stores about routines; [rev] increases with every accepted change. */
internal data class RoutineDoc(val rev: Long, val routines: List<Routine>, val kids: List<RoutineKid>)

internal sealed interface Parsed<out T> {
  data class Ok<T>(val value: T) : Parsed<T>
  data class Bad(val code: String) : Parsed<Nothing>
}

/** JSON wire/storage form, validating every field: phone payloads and stored prefs are untrusted. */
internal object RoutineCodec {
  const val NAME_MAX = 24
  const val LABEL_MAX = 14
  const val KID_NAME_MAX = 16
  const val MAX_CARDS = 8
  const val MAX_KIDS = 4
  const val MAX_SKIPS = 31
  const val MIN_LEN = 5
  const val MAX_LEN = 180
  private val ID = Regex("^[a-z0-9]{1,24}$")

  private class Reject(val code: String) : Exception(code)
  private fun need(ok: Boolean, code: String) { if (!ok) throw Reject(code) }

  fun parseRoutine(json: JSONObject?): Parsed<Routine> = try {
    Parsed.Ok(routine(json ?: throw Reject("invalid_routine")))
  } catch (e: Reject) { Parsed.Bad(e.code) }

  private fun routine(j: JSONObject): Routine {
    val id = j.optString("id"); need(ID.matches(id), "invalid_id")
    val name = text(j, "name", NAME_MAX, "invalid_name")
    val kids = stringList(j.optJSONArray("kids") ?: JSONArray(), MAX_KIDS, "invalid_kids")
    need(kids.all(ID::matches) && kids.distinct().size == kids.size, "invalid_kids")
    val cardsJson = j.optJSONArray("cards") ?: throw Reject("invalid_cards")
    need(cardsJson.length() in 1..MAX_CARDS, "invalid_cards")
    val cards = (0 until cardsJson.length()).map { card(cardsJson.optJSONObject(it) ?: throw Reject("invalid_cards")) }
    need(cards.map { it.id }.distinct().size == cards.size, "invalid_cards")
    val daysJson = j.optJSONArray("days") ?: throw Reject("invalid_days")
    need(daysJson.length() == 7, "invalid_days")
    val days = (0 until 7).map { i -> when (daysJson.opt(i)) { 1, true -> true; 0, false -> false; else -> throw Reject("invalid_days") } }
    need(days.any { it }, "invalid_days")
    val start = int(j, "start"); val end = int(j, "end")
    need(start in 0 until RoutineDates.DAY_MIN && end in 1..RoutineDates.DAY_MIN && end > start, "invalid_time")
    need(end - start >= MIN_LEN, "too_short")
    need(end - start <= MAX_LEN, "too_long")
    val enabled = j.opt("enabled") as? Boolean ?: throw Reject("invalid_routine")
    val skipText = stringList(j.optJSONArray("skips") ?: JSONArray(), MAX_SKIPS, "invalid_skip")
    val skips = skipText.map { RoutineDates.epochDayOf(it) ?: throw Reject("invalid_skip") }.distinct().sorted()
    return Routine(id, name, kids, cards, days, start, end, enabled, skips)
  }

  private fun card(j: JSONObject): RoutineCard {
    val id = j.optString("id"); need(ID.matches(id), "invalid_cards")
    val pic = j.optString("pic"); need(RoutinePictures.isValid(pic), "invalid_picture")
    return RoutineCard(id, pic, text(j, "label", LABEL_MAX, "invalid_label"))
  }

  private fun text(j: JSONObject, key: String, max: Int, code: String): String {
    val raw = j.opt(key) as? String ?: throw Reject(code)
    val t = raw.trim()
    need(t.isNotEmpty() && t.length <= max && t.none { it.isISOControl() }, code)
    return t
  }

  private fun int(j: JSONObject, key: String): Int {
    val v = j.opt(key)
    need(v is Int || v is Long, "invalid_time")
    return (v as Number).toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw Reject("invalid_time") }
  }

  private fun stringList(a: JSONArray, max: Int, code: String): List<String> {
    need(a.length() <= max, code)
    return (0 until a.length()).map { a.opt(it) as? String ?: throw Reject(code) }
  }

  fun parseKids(a: JSONArray?): Parsed<List<RoutineKid>> = try {
    val arr = a ?: throw Reject("invalid_kids")
    need(arr.length() <= MAX_KIDS, "invalid_kids")
    val kids = (0 until arr.length()).map { i ->
      val k = arr.optJSONObject(i) ?: throw Reject("invalid_kids")
      val id = k.optString("id"); need(ID.matches(id), "invalid_kids")
      val avatar = k.optString("avatar"); need(avatar in RoutinePictures.avatars, "invalid_kids")
      RoutineKid(id, text(k, "name", KID_NAME_MAX, "invalid_kids"), avatar)
    }
    need(kids.map { it.id }.distinct().size == kids.size, "invalid_kids")
    Parsed.Ok(kids)
  } catch (e: Reject) { Parsed.Bad(e.code) }

  fun routineJson(r: Routine): JSONObject = JSONObject().put("id", r.id).put("name", r.name)
      .put("kids", JSONArray(r.kids))
      .put("cards", JSONArray().apply { r.cards.forEach { put(JSONObject().put("id", it.id).put("pic", it.pic).put("label", it.label)) } })
      .put("days", JSONArray(r.days.map { if (it) 1 else 0 })).put("start", r.start).put("end", r.end)
      .put("enabled", r.enabled).put("skips", JSONArray(r.skips.map(RoutineDates::isoOf)))

  fun kidsJson(kids: List<RoutineKid>): JSONArray = JSONArray().apply {
    kids.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) }
  }

  fun docJson(doc: RoutineDoc): JSONObject = JSONObject().put("rev", doc.rev)
      .put("routines", JSONArray().apply { doc.routines.forEach { put(routineJson(it)) } })
      .put("kids", kidsJson(doc.kids))

  /** The stored document, or null when it is unreadable (the caller then refuses to overwrite blindly). */
  fun parseDoc(j: JSONObject): RoutineDoc? {
    val rev = j.opt("rev") as? Number ?: return null
    val arr = j.optJSONArray("routines") ?: return null
    val routines = (0 until arr.length()).map { (parseRoutine(arr.optJSONObject(it)) as? Parsed.Ok)?.value ?: return null }
    val kids = (parseKids(j.optJSONArray("kids") ?: JSONArray()) as? Parsed.Ok)?.value ?: return null
    return RoutineDoc(rev.toLong(), routines, kids)
  }
}

/** Cross-routine rules the per-field codec can't see. */
internal object RoutineRules {
  const val MAX_ROUTINES = 12

  /** Null when [r] fits beside the other routines in [doc]; otherwise an error code. */
  fun check(r: Routine, doc: RoutineDoc): String? {
    val kidIds = doc.kids.map { it.id }.toSet()
    if (!kidIds.containsAll(r.kids)) return "invalid_kids"
    if (clash(r, doc) != null) return "overlap"
    if (doc.routines.none { it.id == r.id } && doc.routines.size >= MAX_ROUTINES) return "too_many_routines"
    return null
  }

  fun clash(r: Routine, doc: RoutineDoc): Routine? = doc.routines.firstOrNull { o ->
    o.id != r.id && o.days.indices.any { o.days[it] && r.days[it] } && r.start < o.end && o.start < r.end
  }

  /** The prototype's copy for an error code, used in logs and the phone's error line. */
  fun message(code: String, r: Routine, doc: RoutineDoc): String = when (code) {
    "overlap" -> clash(r, doc)?.let { "Overlaps ${it.name} (${RoutineDates.time12(it.start)} to ${RoutineDates.time12(it.end)})." } ?: "Overlaps another routine."
    "invalid_kids" -> "Pick kids from this Portal's list."
    "too_many_routines" -> "This Portal already has $MAX_ROUTINES routines."
    else -> "Check the routine and try again."
  }
}
