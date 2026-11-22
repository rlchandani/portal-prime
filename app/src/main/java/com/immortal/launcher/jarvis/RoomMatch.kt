/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

/**
 * Matches a room the user named ("the living area", "Kitchen Portal") to one of the Portal rooms Hearthpane knows.
 * Pure. Case, punctuation, a leading "the" and a trailing "portal"/"room" don't matter; an exact name wins over a
 * name that merely starts with what was said, and an ambiguous prefix matches nothing.
 */
internal object RoomMatch {
  private val ALL_WORDS = setOf("", "all", "everyone", "everybody", "everywhere", "all rooms", "every room", "house",
      "the house", "whole house", "the whole house", "home", "all portals", "every portal", "household")

  fun norm(raw: String): String {
    var s = raw.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    if (s.startsWith("the ")) s = s.removePrefix("the ").trim()
    for (tail in listOf(" portal", " room")) if (s.endsWith(tail) && s.length > tail.length) s = s.removeSuffix(tail).trim()
    return s
  }

  /** True when the user meant every room (or named none). */
  fun meansAll(raw: String?): Boolean = raw == null || raw.lowercase().trim().removePrefix("to ").trim() in ALL_WORDS

  /** The one name in [names] the user meant, or null. */
  fun find(said: String, names: List<String>): String? {
    val want = norm(said)
    if (want.isEmpty()) return null
    names.firstOrNull { norm(it) == want }?.let { return it }
    return names.filter { norm(it).startsWith(want) || want.startsWith(norm(it)) && norm(it).isNotEmpty() }.singleOrNull()
  }
}
