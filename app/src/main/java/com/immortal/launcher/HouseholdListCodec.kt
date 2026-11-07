/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * JSON for the list: the full replica (disk + Portal↔Portal exchange, tombstones included) and the
 * phone view (/remote/list, live items only). Decoding treats every payload as untrusted: any
 * malformed item rejects the whole replica rather than half-merging it.
 */
internal object HouseholdListCodec {
  const val VERSION = 1
  /** A replica stamped further ahead than this is from a broken clock; refuse it. */
  const val MAX_FUTURE_MS = 24L * 60 * 60 * 1000

  // ---- replica ----

  fun encodeState(s: HouseholdListState): JSONObject {
    val arr = JSONArray()
    s.items.values.sortedBy { it.id }.forEach { arr.put(encodeItem(it)) }
    return JSONObject().put("v", VERSION).put("items", arr)
  }

  private fun encodeItem(i: HouseholdItem): JSONObject = JSONObject()
      .put("id", i.id)
      .put("t", JSONArray().put(i.text.value).put(i.text.stamp.at).put(i.text.stamp.by).put(i.text.baseAt))
      .put("d", JSONArray().put(i.done.value).put(i.done.stamp.at).put(i.done.stamp.by))
      .put("o", JSONArray().put(i.order.value).put(i.order.stamp.at).put(i.order.stamp.by))
      .put("a", JSONArray().put(i.added.at).put(i.added.by))
      .apply {
        i.removal?.let { put("r", JSONArray().put(it.removed).put(it.stamp.at).put(it.stamp.by).put(it.seen)) }
        i.alt?.let { put("x", JSONArray().put(it.value).put(it.by).put(it.at)) }
        if (i.migratedFrom.isNotEmpty()) put("m", JSONArray(i.migratedFrom.sorted()))
      }

  /** The replica, or null when [json] is not a well-formed, plausible list. */
  fun decodeState(json: JSONObject?, nowMs: Long): HouseholdListState? {
    if (json == null || json.optInt("v", -1) != VERSION) return null
    val arr = json.optJSONArray("items") ?: return null
    if (arr.length() > HouseholdListOps.MAX_ITEMS * 2) return null // merges of two full replicas still decode
    val items = HashMap<String, HouseholdItem>(arr.length())
    for (k in 0 until arr.length()) {
      val item = runCatching { decodeItem(arr.getJSONObject(k), nowMs + MAX_FUTURE_MS) }.getOrNull() ?: return null
      if (items.put(item.id, item) != null) return null
    }
    return HouseholdListState(items)
  }

  private fun decodeItem(o: JSONObject, maxAt: Long): HouseholdItem? {
    val id = o.getString("id").takeIf(HouseholdListText::isValidId) ?: return null
    val t = o.getJSONArray("t")
    val text = TextReg(cleanText(t.getString(0)) ?: return null, stamp(t, 1, maxAt) ?: return null, time(t.getLong(3), maxAt) ?: return null)
    val d = o.getJSONArray("d")
    val done = Reg(d.get(0) as? Boolean ?: return null, stamp(d, 1, maxAt) ?: return null)
    val ord = o.getJSONArray("o")
    val orderValue = ord.getDouble(0).takeIf { it.isFinite() } ?: return null
    val order = Reg(orderValue, stamp(ord, 1, maxAt) ?: return null)
    val added = stamp(o.getJSONArray("a"), 0, maxAt) ?: return null
    val removal = o.optJSONArray("r")?.let { r ->
      Removal(r.get(0) as? Boolean ?: return null, stamp(r, 1, maxAt) ?: return null, time(r.getLong(3), maxAt) ?: return null)
    }
    val alt = o.optJSONArray("x")?.let { x ->
      AltText(cleanText(x.getString(0)) ?: return null, HouseholdListText.cleanName(x.getString(1)) ?: return null,
          time(x.getLong(2), maxAt) ?: return null)
    }
    val migrated = o.optJSONArray("m")?.let { m ->
      if (m.length() > 8) return null
      (0 until m.length()).map { HouseholdListText.cleanName(m.getString(it)) ?: return null }.toSet()
    } ?: emptySet()
    return HouseholdItem(id, text, done, order, added, removal, alt, migrated)
  }

  private fun cleanText(raw: String): String? = HouseholdListText.clean(raw)?.takeIf { it == raw }

  private fun time(at: Long, maxAt: Long): Long? = at.takeIf { it in 0..maxAt }

  private fun stamp(a: JSONArray, from: Int, maxAt: Long): Stamp? {
    val at = time(a.getLong(from), maxAt) ?: return null
    val by = HouseholdListText.cleanName(a.getString(from + 1)) ?: return null
    return Stamp(at, by)
  }

  /** 12 hex of SHA-256 over a canonical, platform-independent rendering of every register. */
  fun digest(s: HouseholdListState): String {
    val canon = StringBuilder()
    s.items.values.sortedBy { it.id }.forEach { i ->
      canon.append(i.id).append('\u0001')
          .append(i.text.value).append('\u0001').append(i.text.stamp.at).append('\u0001').append(i.text.stamp.by)
          .append('\u0001').append(i.text.baseAt).append('\u0001')
          .append(i.done.value).append('\u0001').append(i.done.stamp.at).append('\u0001').append(i.done.stamp.by).append('\u0001')
          .append(i.order.value.toString()).append('\u0001').append(i.order.stamp.at).append('\u0001').append(i.order.stamp.by).append('\u0001')
          .append(i.added.at).append('\u0001').append(i.added.by).append('\u0001')
          .append(i.removal?.let { "${it.removed}\u0001${it.stamp.at}\u0001${it.stamp.by}\u0001${it.seen}" } ?: "-").append('\u0001')
          .append(i.alt?.let { "${it.value}\u0001${it.by}\u0001${it.at}" } ?: "-").append('\u0001')
          .append(i.migratedFrom.sorted().joinToString("\u0002")).append('\u0003')
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(canon.toString().toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(12)
  }

  // ---- phone view ----

  fun publicItem(i: HouseholdItem): JSONObject {
    val note = HouseholdListNotes.note(i)
    val renamed = i.text.stamp.at > i.added.at
    return JSONObject()
        .put("id", i.id).put("text", i.text.value).put("done", i.done.value).put("order", i.order.value)
        .put("addedBy", i.added.by).put("addedAt", i.added.at)
        .put("updatedAt", maxOf(i.contentAt, i.order.stamp.at))
        .put("doneBy", if (i.done.value) i.done.stamp.by else JSONObject.NULL)
        .put("doneAt", if (i.done.value) i.done.stamp.at else JSONObject.NULL)
        .put("renamedBy", if (renamed) i.text.stamp.by else JSONObject.NULL)
        .put("renamedAt", if (renamed) i.text.stamp.at else JSONObject.NULL)
        .put("note", note?.let {
          JSONObject().put("kind", it.kind.wire).put("text", it.text)
              .put("alt", it.alt ?: JSONObject.NULL).put("altBy", it.altBy ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
  }

  fun publicItems(s: HouseholdListState): JSONArray = JSONArray().apply {
    (s.open() + s.done()).forEach { put(publicItem(it)) }
  }
}
