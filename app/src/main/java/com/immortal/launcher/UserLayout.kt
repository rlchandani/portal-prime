/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * User-created folder assignments, persisted across restarts.
 *
 * Maps a package id to the folder the user dropped it into (by dragging one app
 * onto another in Manage mode). This overlays the static [Curation.folders]
 * defaults: an entry here wins, so users can both create new folders and move
 * apps out of the curated Settings folder.
 */
object UserLayout {

  private const val PREFS = "immortal_layout"
  private const val KEY = "assignments"
  private const val KEY_ORDER = "home_order"
  private const val KEY_GRID_ORDER = "grid_order"
  private const val KEY_GRID_SLOTS = "grid_slots"

  private val assignmentSnapshot =
      ParsedLayoutSnapshot<Map<String, String>>(emptyMap(), ::deserialize)
  private val homeOrderSnapshot =
      ParsedLayoutSnapshot<List<String>>(emptyList(), ::deserializeOrder)
  private val gridOrderSnapshot =
      ParsedLayoutSnapshot<List<String>>(emptyList(), ::deserializeOrder)
  private val gridSlotsSnapshot =
      ParsedLayoutSnapshot<List<String?>>(emptyList(), ::deserializeSlots)

  /** package id -> folder name (user override). */
  fun load(context: Context): Map<String, String> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    return assignmentSnapshot.read(raw)
  }

  fun save(context: Context, assignments: Map<String, String>) =
      synchronized(RemoteCompanionStore.mutationLock) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, serialize(assignments))
        .apply()
  }

  /** Reject stale whole-map edits without replacing an acknowledged change on another screen. */
  fun saveAssignmentsFromSnapshot(
      context: Context, expected: Map<String, String>, updated: Map<String, String>,
  ): Boolean = synchronized(RemoteCompanionStore.mutationLock) {
    if (load(context) != expected) return@synchronized false
    save(context, updated)
    true
  }

  /** User-defined order of ungrouped home-screen app package IDs. */
  fun loadOrder(context: Context): List<String> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORDER, null)
    return homeOrderSnapshot.read(raw)
  }

  fun saveOrder(context: Context, order: List<String>) =
      synchronized(RemoteCompanionStore.mutationLock) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ORDER, serializeOrder(order))
        .apply()
  }

  fun saveOrderFromSnapshot(
      context: Context, expected: List<String>, updated: List<String>,
  ): Boolean = synchronized(RemoteCompanionStore.mutationLock) {
    if (loadOrder(context) != expected) return@synchronized false
    saveOrder(context, updated)
    true
  }

  /**
   * Unified top-level home-grid order: stable keys for every movable tile — built-ins
   * ("builtin:calls" etc.), widgets ("widget-tile:…"), folders ("folder:…"), and ungrouped apps
   * ("app:…"). Lets the user drag ANY tile, not just apps. Reuses the tolerant order codec.
   */
  fun loadGridOrder(context: Context): List<String> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GRID_ORDER, null)
    return gridOrderSnapshot.read(raw)
  }

  fun saveGridOrder(context: Context, order: List<String>) =
      synchronized(RemoteCompanionStore.mutationLock) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_GRID_ORDER, serializeOrder(order))
        .apply()
  }

  /**
   * Free-placement home grid: a flat list of slots where each entry is a tile key or `null` (a
   * blank cell the user left). Stored as a JSON array with "" standing in for a blank.
   */
  fun loadGridSlots(context: Context): List<String?> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GRID_SLOTS, null)
    return gridSlotsSnapshot.read(raw)
  }

  fun saveGridSlots(context: Context, slots: List<String?>) =
      synchronized(RemoteCompanionStore.mutationLock) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_GRID_SLOTS, serializeSlots(slots))
        .apply()
  }

  internal fun serializeSlots(slots: List<String?>): String =
      org.json.JSONArray(slots.map { it ?: "" }).toString()

  internal fun deserializeSlots(raw: String): List<String?> =
      runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
              for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                add(if (s.isNullOrBlank()) null else s)
              }
            }
          }
          .getOrDefault(emptyList())

  /** JSON encode of the assignment map (extracted for testing). */
  internal fun serialize(assignments: Map<String, String>): String {
    val obj = JSONObject()
    assignments.forEach { (k, v) -> obj.put(k, v) }
    return obj.toString()
  }

  /** Tolerant decode — malformed/garbage input yields an empty map, never throws. */
  internal fun deserialize(raw: String): Map<String, String> =
      runCatching {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
          }
          .getOrDefault(emptyMap())

  internal fun serializeOrder(order: List<String>): String =
      org.json.JSONArray(order.distinct()).toString()

  internal fun deserializeOrder(raw: String): List<String> =
      runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                  for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank() && id !in this) add(id)
                  }
                }
          }
          .getOrDefault(emptyList())

  fun <T> applyOrder(items: List<T>, order: List<String>, idOf: (T) -> String): List<T> {
    if (order.isEmpty()) return items
    val byId = items.associateBy(idOf)
    val ordered = order.mapNotNull { byId[it] }
    val orderedIds = order.toSet()
    val remaining = items.filter { idOf(it) !in orderedIds }
    return ordered + remaining
  }

  fun moveOrder(order: List<String>, source: String, target: String): List<String> {
    if (source == target) return order
    val mutable = order.toMutableList()
    val from = mutable.indexOf(source)
    val to = mutable.indexOf(target)
    if (from < 0 || to < 0) return order
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
  }

  /** Folder identity uses the same exact-case names as the app grid. */
  internal fun folderRenameError(
      assignments: Map<String, String>, current: String, draft: String,
      existingNames: Collection<String>,
  ): String? {
    val name = draft.trim()
    if (name.isBlank()) return "Enter a folder name."
    if (name == current) return null
    return if (name in existingNames || name in assignments.values) {
      "A folder named “$name” already exists. Choose a different name."
    } else null
  }

  /** Rename the whole saved membership; missing installed apps retain their folder on return. */
  internal fun renamedFolderAssignments(
      assignments: Map<String, String>, current: String, draft: String,
      memberPackages: Collection<String>, existingNames: Collection<String>,
  ): Map<String, String> {
    val error = folderRenameError(assignments, current, draft, existingNames)
    require(error == null) { error ?: "Invalid folder name" }
    val name = draft.trim()
    if (name == current) return assignments
    return assignments.mapValues { (_, folder) -> if (folder == current) name else folder } +
        memberPackages.associateWith { name }
  }

  /** A folder name not already used by either the user map or the curated set. */
  fun nextFolderName(existing: Set<String>): String {
    if ("Folder" !in existing) return "Folder"
    var i = 2
    while ("Folder $i" in existing) i++
    return "Folder $i"
  }
}
