/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk form of [CcLastKnown]'s retained entities (pure, unit tested). The in-memory cache
 * ([CcEngine.lastKnown]) survives an activity finish but not process death, so a cold open (the
 * first after a reboot or a low-memory kill) would still draw blank and redraw. This codec lets a
 * sibling file ([CcLastKnownStore]) carry the last-known states across the process, so even a cold
 * open draws at once.
 *
 * Restored entities are marked unconfirmed and reconciled by Home Assistant's first full answer
 * exactly as the in-memory ones are, so no action is ever decided from a persisted state (a
 * safety-critical one shows the in-flight look and refuses actions until HA confirms). A cache older
 * than [MAX_AGE_MS] is ignored: an overnight reboot still opens drawn, but days-old states are not
 * painted as if current. [decode] never throws; any malformed or stale payload yields no entities.
 */
internal object CcLastKnownJson {
  /** The schema version; a payload written by another version is ignored (treated as no cache). */
  const val VERSION = 1

  /** Covers an overnight reboot while not painting days-old states as current. */
  const val MAX_AGE_MS = 12L * 60L * 60L * 1_000L

  private const val KEY_VERSION = "v"
  private const val KEY_SAVED_AT = "savedAt"
  private const val KEY_ENTITIES = "entities"
  private const val KEY_ID = "id"
  private const val KEY_STATE = "state"
  private const val KEY_LAST_CHANGED = "lastChanged"
  private const val KEY_USER_ID = "userId"
  private const val KEY_ATTRS = "attrs"

  fun encode(entities: Map<String, CcEntity>, savedAt: Long): JSONObject {
    val array = JSONArray()
    entities.values.forEach { array.put(encodeEntity(it)) }
    return JSONObject().put(KEY_VERSION, VERSION).put(KEY_SAVED_AT, savedAt).put(KEY_ENTITIES, array)
  }

  /** The retained entities if the payload is this version and no older than [MAX_AGE_MS]; else none. */
  fun decode(text: String, now: Long): Map<String, CcEntity> = runCatching {
    val root = JSONObject(text)
    if (root.optInt(KEY_VERSION, -1) != VERSION) return emptyMap()
    if (now - root.optLong(KEY_SAVED_AT, 0L) > MAX_AGE_MS) return emptyMap()
    val array = root.optJSONArray(KEY_ENTITIES) ?: return emptyMap()
    val out = LinkedHashMap<String, CcEntity>(array.length())
    for (i in 0 until array.length()) {
      val entity = array.optJSONObject(i)?.let(::decodeEntity) ?: continue
      out[entity.id] = entity
    }
    out
  }.getOrDefault(emptyMap())

  private fun encodeEntity(e: CcEntity): JSONObject = JSONObject()
      .put(KEY_ID, e.id)
      .put(KEY_STATE, e.state)
      .put(KEY_LAST_CHANGED, e.lastChanged)
      .also { o -> e.userId?.let { o.put(KEY_USER_ID, it) } }
      .put(KEY_ATTRS, encodeAttrs(e.attrs))

  private fun decodeEntity(o: JSONObject): CcEntity? {
    val id = o.optString(KEY_ID).takeIf { it.contains('.') } ?: return null
    return CcEntity(
        id = id,
        state = o.optString(KEY_STATE),
        // Decode attributes through the same normaliser the live parser uses, so a restored
        // entity is indistinguishable from a freshly parsed one.
        attrs = CcEntityParser.toMap(o.optJSONObject(KEY_ATTRS)),
        lastChanged = o.optLong(KEY_LAST_CHANGED, 0L),
        userId = o.optString(KEY_USER_ID).takeIf { it.isNotBlank() })
  }

  private fun encodeAttrs(attrs: Map<String, Any?>): JSONObject {
    val o = JSONObject()
    attrs.forEach { (k, v) -> o.put(k, toJson(v)) }
    return o
  }

  /** Attribute values originate from JSON ([CcEntityParser.plain]): null, nested maps, lists, primitives. */
  private fun toJson(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, value) -> if (k is String) o.put(k, toJson(value)) } }
    is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
    else -> v
  }
}
