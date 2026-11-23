/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.security.MessageDigest
import java.util.Locale

/**
 * The household-stable person id (plan §3.8's prerequisite). Who's there ids are local to each Portal, so a profile
 * kept under one can't follow the person to another Portal. Like reminders, Portals already agree that the same name
 * is the same person, and Who's there refuses a second person with the same name on one Portal, so the id is derived
 * from the name alone: `h` + 20 hex of SHA-256 over the normalized name. Every Portal derives the same id with no
 * roster to exchange, no face data leaves a Portal, and the id never spells the name (it names a sealed file).
 * Pure.
 */
internal object HouseholdPersonId {
  private const val PREFIX = "h"
  private const val HEX_CHARS = 20
  private const val DOMAIN = "hearthpane.person.v1|"

  /** The id for [name] (case, spacing and control characters ignored), or null for no name. */
  fun of(name: String): String? {
    val normal = name.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    if (normal.isEmpty()) return null
    val hash = MessageDigest.getInstance("SHA-256").digest((DOMAIN + normal).toByteArray(Charsets.UTF_8))
    return PREFIX + hash.joinToString("") { "%02x".format(it) }.take(HEX_CHARS)
  }

  /**
   * The id for local person [localId] among this Portal's [people] (local id to name), or null when they are unknown
   * or share their name with someone else here (then nobody's profile is used: never the wrong person's).
   */
  fun resolve(localId: String, people: List<Pair<String, String>>): String? {
    val name = people.firstOrNull { it.first == localId }?.second ?: return null
    val id = of(name) ?: return null
    return id.takeIf { people.count { of(it.second) == id } == 1 }
  }
}
