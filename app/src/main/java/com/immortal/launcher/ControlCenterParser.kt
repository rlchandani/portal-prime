/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure parsers for Home Assistant's websocket payloads (unit tested): full state objects
 * (`get_states`, `state_changed`), the compressed `subscribe_entities` events (`a` adds, `c` diffs
 * with `+`/`-`, `r` removals) and the four registries. Malformed pieces are skipped, never thrown.
 */
internal object CcEntityParser {
  private const val MS_PER_S = 1000.0

  /** A full HA state object (`entity_id`, `state`, `attributes`, `last_changed`, `context`). */
  fun fromFull(o: JSONObject): CcEntity? {
    val id = o.optString("entity_id").takeIf { it.contains('.') } ?: return null
    val ctx = o.optJSONObject("context")
    return CcEntity(id = id, state = o.optString("state"), attrs = toMap(o.optJSONObject("attributes")),
        lastChanged = isoMs(o.optString("last_changed")), userId = ctx?.optString("user_id")?.takeIf(::present))
  }

  /** One compressed state (`s`, `a`, `c`, `lc`, `lu`) as sent under `a` by `subscribe_entities`. */
  fun fromCompressed(id: String, o: JSONObject): CcEntity =
      CcEntity(id = id, state = o.optString("s"), attrs = toMap(o.optJSONObject("a")),
          lastChanged = secondsMs(o.opt("lc") ?: o.opt("lu")), userId = contextUser(o.opt("c")))

  /** Applies one compressed diff (`{"+": {...}, "-": {"a": [keys]}}`) to [old]. */
  fun applyDiff(old: CcEntity, diff: JSONObject): CcEntity {
    val plus = diff.optJSONObject("+")
    val minus = diff.optJSONObject("-")
    var attrs = old.attrs
    minus?.optJSONArray("a")?.let { removed -> attrs = attrs - (0 until removed.length()).map { removed.optString(it) }.toSet() }
    plus?.optJSONObject("a")?.let { added -> attrs = attrs + toMap(added) }
    return old.copy(
        state = if (plus?.has("s") == true) plus.optString("s") else old.state,
        attrs = attrs,
        lastChanged = plus?.opt("lc")?.let(::secondsMs) ?: old.lastChanged,
        userId = if (plus?.has("c") == true) contextUser(plus.opt("c")) else old.userId)
  }

  /** Folds one `subscribe_entities` event into [current]; returns the ids it touched. */
  fun applyEvent(current: Map<String, CcEntity>, event: JSONObject): Pair<Map<String, CcEntity>, Set<String>> {
    val next = HashMap(current)
    val touched = HashSet<String>()
    event.optJSONObject("a")?.let { adds -> adds.keys().forEach { id -> adds.optJSONObject(id)?.let { next[id] = fromCompressed(id, it); touched += id } } }
    event.optJSONObject("c")?.let { diffs ->
      diffs.keys().forEach { id -> val old = next[id]; val d = diffs.optJSONObject(id); if (old != null && d != null) { next[id] = applyDiff(old, d); touched += id } }
    }
    event.optJSONArray("r")?.let { r -> (0 until r.length()).forEach { i -> r.optString(i).let { id -> if (next.remove(id) != null) touched += id } } }
    return next to touched
  }

  fun toMap(o: JSONObject?): Map<String, Any?> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    o.keys().forEach { k -> out[k] = plain(o.opt(k)) }
    return out
  }

  private fun plain(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> toMap(v)
    is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
    else -> v
  }

  private fun contextUser(c: Any?): String? = (c as? JSONObject)?.optString("user_id")?.takeIf(::present)

  private fun present(s: String?): Boolean = !s.isNullOrBlank() && s != "null"

  fun secondsMs(v: Any?): Long = ((v as? Number)?.toDouble()?.times(MS_PER_S))?.toLong() ?: 0L

  /** HA ISO timestamps ("2026-11-17T19:25:03.123456+00:00") to epoch ms; 0 when unparseable. */
  fun isoMs(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    val trimmed = s.replace(Regex("\\.(\\d{3})\\d*"), ".$1")
    val normalized = trimmed.replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2").replace(Regex("Z$"), "+0000")
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")
    for (p in patterns) {
      val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
      runCatching { return f.parse(normalized)?.time ?: 0L }
    }
    return 0L
  }
}

/** Pure registry parsing (the `config/..._registry/list` results). */
internal object CcRegistryParser {
  private val USABLE_CATEGORIES = setOf("", "null")

  fun floors(result: JSONArray?): List<CcHaFloor> = objects(result).mapNotNull { o ->
    val id = o.optString("floor_id").ifBlank { return@mapNotNull null }
    CcHaFloor(id, o.optString("name").ifBlank { id }, if (o.isNull("level")) null else o.optInt("level"))
  }.sortedWith(compareBy({ it.level ?: Int.MAX_VALUE }, { it.name }))

  fun areas(result: JSONArray?): List<CcHaArea> = objects(result).mapNotNull { o ->
    val id = o.optString("area_id").ifBlank { return@mapNotNull null }
    CcHaArea(id, o.optString("name").ifBlank { id }, o.optString("floor_id").takeIf { it.isNotBlank() && it != "null" })
  }

  /** device id → area id. */
  fun deviceAreas(result: JSONArray?): Map<String, String> = objects(result).mapNotNull { o ->
    val area = o.optString("area_id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
    o.optString("id").takeIf { it.isNotBlank() }?.let { it to area }
  }.toMap()

  /** Builds the registry; entities inherit their device's area when they have none. */
  fun registry(floors: List<CcHaFloor>, areas: List<CcHaArea>, deviceAreas: Map<String, String>, entities: JSONArray?): CcRegistry {
    val entityArea = HashMap<String, String>()
    val entityClass = HashMap<String, String>()
    val usable = HashSet<String>()
    objects(entities).forEach { o ->
      val id = o.optString("entity_id").takeIf { it.contains('.') } ?: return@forEach
      val own = o.optString("area_id").takeIf { it.isNotBlank() && it != "null" }
      val device = o.optString("device_id").takeIf { it.isNotBlank() && it != "null" }
      (own ?: device?.let(deviceAreas::get))?.let { entityArea[id] = it }
      val cls = o.optString("device_class").takeIf { it.isNotBlank() && it != "null" }
          ?: o.optString("original_device_class").takeIf { it.isNotBlank() && it != "null" }
      cls?.let { entityClass[id] = it }
      val disabled = !o.isNull("disabled_by") && o.optString("disabled_by").isNotBlank()
      val hidden = !o.isNull("hidden_by") && o.optString("hidden_by").isNotBlank()
      val category = if (o.isNull("entity_category")) "" else o.optString("entity_category")
      if (!disabled && !hidden && category in USABLE_CATEGORIES) usable += id
    }
    return CcRegistry(floors, areas, entityArea, entityClass, usable)
  }

  private fun objects(a: JSONArray?): List<JSONObject> =
      if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
