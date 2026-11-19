/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Everything biometric on this Portal: the people and their face templates (numbers only). */
internal data class WhosThereHousehold(
    val people: List<WhosTherePerson> = emptyList(),
    val templates: List<FaceTemplates> = emptyList(),
) {
  fun person(id: String): WhosTherePerson? = people.firstOrNull { it.id == id }
  fun templatesOf(id: String): FaceTemplates? = templates.firstOrNull { it.personId == id }
  val enrolled: List<WhosTherePerson> get() = people.filter { p -> templatesOf(p.id)?.looks?.isNotEmpty() == true }

  fun upsert(person: WhosTherePerson): WhosThereHousehold =
      copy(people = people.filterNot { it.id == person.id } + person)

  fun withTemplates(t: FaceTemplates): WhosThereHousehold =
      copy(templates = templates.filterNot { it.personId == t.personId } + t.copy(looks = t.looks.take(WhosThereLimits.MAX_LOOKS)))

  /** Delete-person: the person and every template of theirs. */
  fun without(id: String): WhosThereHousehold =
      copy(people = people.filterNot { it.id == id }, templates = templates.filterNot { it.personId == id })
}

/**
 * JSON layout of the encrypted household file. Strict on read: a malformed person or template
 * is dropped (logged by the caller), never half-loaded.
 */
internal object WhosThereCodec {
  const val VERSION = 1
  private val ID = Regex("[a-z0-9]{6,32}")

  fun encode(h: WhosThereHousehold): String = JSONObject()
      .put("v", VERSION)
      .put("people", JSONArray().apply { h.people.forEach { put(person(it)) } })
      .put("templates", JSONArray().apply { h.templates.forEach { put(templates(it)) } })
      .toString()

  fun decode(text: String): WhosThereHousehold {
    val root = JSONObject(text)
    require(root.optInt("v") == VERSION) { "unsupported household version" }
    val people = root.optJSONArray("people").objects().mapNotNull(::readPerson).distinctBy { it.id }
        .take(WhosThereLimits.MAX_PEOPLE)
    val ids = people.map { it.id }.toSet()
    val templates = root.optJSONArray("templates").objects().mapNotNull(::readTemplates)
        .filter { it.personId in ids }.distinctBy { it.personId }
    return WhosThereHousehold(people, templates)
  }

  fun validId(id: String): Boolean = ID.matches(id)

  private fun person(p: WhosTherePerson) = JSONObject()
      .put("id", p.id).put("name", p.name).put("role", p.role.name).put("color", p.colorIndex)
      .put("created", p.createdMs).put("paused", p.pausedUntilMs)
      .apply { p.calendarUrl?.let { put("calendar", it) } }

  private fun readPerson(o: JSONObject): WhosTherePerson? {
    val id = o.optString("id")
    val name = WhosThereLimits.cleanName(o.optString("name"))
    if (!validId(id) || name.isEmpty()) return null
    return WhosTherePerson(id, name, WhosThereRole.parse(o.optString("role")), o.optInt("color").coerceIn(0, 63),
        o.optLong("created"), o.optLong("paused").coerceAtLeast(0L),
        o.optString("calendar").takeIf { it.startsWith("https://") || it.startsWith("webcal://") })
  }

  private fun templates(t: FaceTemplates) = JSONObject()
      .put("person", t.personId)
      .put("looks", JSONArray().apply {
        t.looks.forEach { e -> put(JSONArray().apply { for (i in 0 until e.size) put(round(e[i])) }) }
      })

  private fun readTemplates(o: JSONObject): FaceTemplates? {
    val id = o.optString("person")
    if (!validId(id)) return null
    val looks = o.optJSONArray("looks") ?: return null
    val vectors = (0 until looks.length()).mapNotNull { i ->
      val arr = looks.optJSONArray(i) ?: return@mapNotNull null
      if (arr.length() != WhosThereLimits.EMBEDDING_SIZE) return@mapNotNull null
      Embedding.of(FloatArray(arr.length()) { arr.optDouble(it, Double.NaN).toFloat() })
    }.take(WhosThereLimits.MAX_LOOKS)
    return FaceTemplates(id, vectors)
  }

  private fun round(v: Float): Double = Math.round(v * 1e6) / 1e6

  private fun JSONArray?.objects(): List<JSONObject> =
      if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
