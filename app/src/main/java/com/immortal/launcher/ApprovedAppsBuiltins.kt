/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Built-in tiles in the Apps grid (pure, unit tested). Our own Activities never come from the
 * package query — Home's inventory injects them and Apps drops every own-package entry — so each
 * built-in must be listed here or it silently disappears (the missing "Home Control" tile).
 * Optional built-ins appear only when Home prepared their artwork (`shortcutKeys`).
 */
internal object ApprovedAppsBuiltins {
  const val CONTROL = "control"
  const val DASHBOARD = "dashboard"

  private val ALWAYS = listOf("store" to "App Store", "music" to "Music", "calls" to "Calls", "settings" to "Settings")
  private val OPTIONAL = listOf(CONTROL to "Home Control", DASHBOARD to "Home dashboard")

  fun tiles(shortcutKeys: Set<String>, query: String = ""): List<Pair<String, String>> =
      (ALWAYS + OPTIONAL.filter { it.first in shortcutKeys })
          .filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
}
