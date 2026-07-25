/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified grid + dock layout model with a strict one-place invariant: a package
 * is in exactly one container (grid or dock), never both. Persists order across
 * restarts. All mutation functions are pure (no Android deps → JVM-unit-testable).
 */
object HomeLayoutStore {

  private const val PREFS = "immortal_home_layout"
  private const val KEY = "layout"

  data class HomeLayout(
      val grid: List<String>,
      val dock: List<String>
  )

  /** Load saved layout, or empty if unset. */
  fun load(context: Context): HomeLayout {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return HomeLayout(emptyList(), emptyList())
    return deserialize(raw)
  }

  /** Save layout to SharedPreferences. */
  fun save(context: Context, layout: HomeLayout) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, serialize(layout))
        .apply()
  }

  /**
   * Reorder within grid: move item at [from] index to [to] index. Clamps to valid
   * range; no-op if indices out of range.
   */
  fun moveWithinGrid(layout: HomeLayout, from: Int, to: Int): HomeLayout {
    if (from < 0 || to < 0 || from >= layout.grid.size || to >= layout.grid.size) {
      return layout
    }
    if (from == to) return layout

    val mutable = layout.grid.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return layout.copy(grid = mutable)
  }

  /**
   * Reorder within dock: move item at [from] index to [to] index. Clamps to valid
   * range; no-op if indices out of range.
   */
  fun moveWithinDock(layout: HomeLayout, from: Int, to: Int): HomeLayout {
    if (from < 0 || to < 0 || from >= layout.dock.size || to >= layout.dock.size) {
      return layout
    }
    if (from == to) return layout

    val mutable = layout.dock.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return layout.copy(dock = mutable)
  }

  /**
   * Move package to dock at [dockIndex], enforcing one-place invariant:
   * - Removes [pkg] from grid if present
   * - If [pkg] already in dock, reorders to [dockIndex]
   * - If dock at [maxDock] capacity AND pkg not already in dock, returns layout UNCHANGED
   * - Otherwise inserts into dock at [dockIndex] (clamped to 0..dock.size)
   */
  fun moveToDock(
      layout: HomeLayout,
      pkg: String,
      dockIndex: Int,
      maxDock: Int
  ): HomeLayout {
    val alreadyInDock = pkg in layout.dock

    // If already in dock, treat as reorder
    if (alreadyInDock) {
      val currentIndex = layout.dock.indexOf(pkg)
      val clampedTo = dockIndex.coerceIn(0, layout.dock.size - 1)
      return moveWithinDock(layout, currentIndex, clampedTo)
    }

    // Check capacity before adding
    if (layout.dock.size >= maxDock) {
      return layout // UNCHANGED: dock full
    }

    // Remove from grid, add to dock
    val newGrid = layout.grid.filterNot { it == pkg }
    val mutable = layout.dock.toMutableList()
    val clampedIndex = dockIndex.coerceIn(0, mutable.size)
    mutable.add(clampedIndex, pkg)

    return HomeLayout(newGrid, mutable)
  }

  /**
   * Move package to grid at [gridIndex], enforcing one-place invariant:
   * - Removes [pkg] from dock if present
   * - If [pkg] already in grid, reorders to [gridIndex]
   * - Otherwise inserts into grid at [gridIndex] (clamped to 0..grid.size)
   */
  fun moveToGrid(layout: HomeLayout, pkg: String, gridIndex: Int): HomeLayout {
    val alreadyInGrid = pkg in layout.grid

    // If already in grid, treat as reorder
    if (alreadyInGrid) {
      val currentIndex = layout.grid.indexOf(pkg)
      val clampedTo = gridIndex.coerceIn(0, layout.grid.size - 1)
      return moveWithinGrid(layout, currentIndex, clampedTo)
    }

    // Remove from dock, add to grid
    val newDock = layout.dock.filterNot { it == pkg }
    val mutable = layout.grid.toMutableList()
    val clampedIndex = gridIndex.coerceIn(0, mutable.size)
    mutable.add(clampedIndex, pkg)

    return HomeLayout(mutable, newDock)
  }

  /**
   * Enforce invariants (safety net for external mutations or deserialization):
   * 1. Dedupe: if a pkg is in both containers, keep in dock and drop from grid
   * 2. Drop packages not in [allInstalled]
   * 3. APPEND any installed package missing from both to grid (newly-installed apps)
   * 4. Preserve existing relative order
   */
  fun normalize(layout: HomeLayout, allInstalled: List<String>): HomeLayout {
    val installedSet = allInstalled.toSet()
    val dockSet = layout.dock.toSet()

    // Dedupe: dock wins; drop uninstalled
    val cleanGrid = layout.grid
        .filter { it in installedSet && it !in dockSet }
        .distinct()
    val cleanDock = layout.dock
        .filter { it in installedSet }
        .distinct()

    // Append newly-installed packages to grid
    val present = cleanGrid.toSet() + cleanDock.toSet()
    val newInstalls = allInstalled.filter { it !in present }

    return HomeLayout(cleanGrid + newInstalls, cleanDock)
  }

  /** JSON encode of the layout (extracted for testing). */
  internal fun serialize(layout: HomeLayout): String {
    val obj = JSONObject()
    obj.put("grid", JSONArray(layout.grid))
    obj.put("dock", JSONArray(layout.dock))
    return obj.toString()
  }

  /** Tolerant decode — malformed/garbage input yields empty layout, never throws. */
  internal fun deserialize(raw: String): HomeLayout =
      runCatching {
            val obj = JSONObject(raw)
            val grid = obj.optJSONArray("grid")?.let { arr ->
              buildList {
                for (i in 0 until arr.length()) {
                  val pkg = arr.optString(i)
                  if (pkg.isNotBlank()) add(pkg)
                }
              }
            } ?: emptyList()

            val dock = obj.optJSONArray("dock")?.let { arr ->
              buildList {
                for (i in 0 until arr.length()) {
                  val pkg = arr.optString(i)
                  if (pkg.isNotBlank()) add(pkg)
                }
              }
            } ?: emptyList()

            HomeLayout(grid, dock)
          }
          .getOrDefault(HomeLayout(emptyList(), emptyList()))
}
