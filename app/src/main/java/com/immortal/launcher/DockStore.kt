/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray

/**
 * Editable bottom dock package list, persisted across restarts.
 *
 * Users can add/remove apps from the dock (up to MAX items). The list is stored
 * as a JSON array in SharedPreferences and defaults to the system's preferred
 * packages if not yet customized.
 */
object DockStore {

  const val MAX = 5
  private const val PREFS = "immortal_dock"
  private const val KEY = "dock_apps"

  val DEFAULT: List<String> = listOf(
      "com.portal.calendar",
      "com.portal.assistant",
      "org.chromium.chrome",
      "com.facebook.alohaapps.launcher",
      "com.immortal.launcher.debug",
  )

  /** Load saved dock package list, or DEFAULT if unset. */
  fun load(context: Context): List<String> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return DEFAULT
    return deserialize(raw)
  }

  /** Save dock package list to SharedPreferences. */
  fun save(context: Context, pkgs: List<String>) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, serialize(pkgs))
        .apply()
  }

  /** JSON encode of the package list (extracted for testing). */
  internal fun serialize(pkgs: List<String>): String =
      JSONArray(pkgs).toString()

  /** Tolerant decode — malformed/garbage input yields an empty list, never throws. */
  internal fun deserialize(raw: String): List<String> =
      runCatching {
            val arr = JSONArray(raw)
            buildList {
              for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                if (pkg.isNotBlank()) add(pkg)
              }
            }
          }
          .getOrDefault(emptyList())

  /** Append pkg to current list if not present and there is room (< max). */
  internal fun addToDock(current: List<String>, pkg: String, max: Int): List<String> {
    if (pkg in current) return current
    if (current.size >= max) return current
    return current + pkg
  }

  /** Remove pkg from current list. */
  internal fun removeFromDock(current: List<String>, pkg: String): List<String> =
      current.filterNot { it == pkg }
}
