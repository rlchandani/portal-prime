/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A local checklist / to-do list for the home-screen widget — the shared "what do we need?" list
 * on the kitchen Portal. Purely local (no network, no permissions): items live as a JSON array in
 * SharedPreferences, same shape as [HomeWidgetStore]/[CameraConfig].
 *
 * Distinct from [NotesConfig] on purpose: that's a single free-text fridge note left *for someone*;
 * this is a structured, checkable list. Keeping them separate avoids cramming two UX models into one.
 *
 * All mutators return via re-save and never edit a list in place — the pure [withAdded]/[withToggled]/
 * [without] helpers build new lists (immutable style), and the `Context` variants persist the result.
 */
object TodoStore {

  private const val PREFS = "immortal_todos"
  private const val KEY = "items"

  /** One checklist entry. [done] toggles the checkbox; [createdMs] preserves insertion order. */
  data class TodoItem(
      val id: String = UUID.randomUUID().toString(),
      val text: String = "",
      val done: Boolean = false,
      val createdMs: Long = 0L,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Items in insertion order (oldest first). */
  fun load(context: Context): List<TodoItem> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return deserialize(raw)
  }

  fun save(context: Context, items: List<TodoItem>) {
    prefs(context).edit().putString(KEY, serialize(items)).apply()
  }

  /** Add a new unchecked item with the given text, stamped [nowMs] for ordering. Blank text is a no-op. */
  fun add(context: Context, text: String, nowMs: Long = System.currentTimeMillis()) {
    val clean = text.trim()
    if (clean.isBlank()) return
    save(context, withAdded(load(context), clean, nowMs))
  }

  /** Flip an item's done state. */
  fun toggle(context: Context, id: String) = save(context, withToggled(load(context), id))

  /** Delete an item. */
  fun remove(context: Context, id: String) = save(context, without(load(context), id))

  /** Drop every checked item (a one-tap "clear completed"). */
  fun clearCompleted(context: Context) = save(context, load(context).filterNot { it.done })

  // ---- Pure list transforms (immutable; unit-testable without a Context) ----

  fun withAdded(items: List<TodoItem>, text: String, nowMs: Long): List<TodoItem> =
      items + TodoItem(text = text.trim(), done = false, createdMs = nowMs)

  fun withToggled(items: List<TodoItem>, id: String): List<TodoItem> =
      items.map { if (it.id == id) it.copy(done = !it.done) else it }

  fun without(items: List<TodoItem>, id: String): List<TodoItem> = items.filterNot { it.id == id }

  internal fun serialize(items: List<TodoItem>): String {
    val arr = JSONArray()
    items.forEach { t ->
      arr.put(
          JSONObject()
              .put("id", t.id)
              .put("text", t.text)
              .put("done", t.done)
              .put("createdMs", t.createdMs))
    }
    return arr.toString()
  }

  /** A missing key is empty; unreadable saved collections must never become editable defaults. */
  internal fun deserialize(raw: String): List<TodoItem> {
    val input = JSONTokener(raw)
    val arr = JSONArray(input)
    require(input.nextClean() == '\u0000') { "Unreadable saved to-do list" }
    val rows = (0 until arr.length()).mapNotNull { index ->
      arr.optJSONObject(index)?.takeIf { it.optString("text", "").isNotBlank() }
          ?.let { index to it }
    }
    // Reserve every valid saved ID, including one that occurs after a repaired row.
    val reserved = rows.mapNotNull { (_, row) ->
      (row.opt("id") as? String)?.takeIf { it.isNotBlank() }
    }.toHashSet()
    val used = hashSetOf<String>()
    return buildList {
      for ((index, o) in rows) {
        val text = o.optString("text", "")
        val createdMs = o.optLong("createdMs", 0L)
        val savedId = (o.opt("id") as? String)?.takeIf { it.isNotBlank() }
        val id = if (savedId != null && used.add(savedId)) savedId else {
          // Stable until a normal mutation saves these IDs into the existing JSON.
          var attempt = 0
          var repaired: String
          do {
            repaired = UUID.nameUUIDFromBytes(
                "immortal-todo:$index:$text:$createdMs:${attempt++}"
                    .toByteArray(Charsets.UTF_8)).toString()
          } while (repaired in reserved || !used.add(repaired))
          repaired
        }
        add(TodoItem(id = id, text = text, done = o.optBoolean("done", false),
            createdMs = createdMs))
      }
    }
  }
}
