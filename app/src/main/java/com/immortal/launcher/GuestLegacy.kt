/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Folds the first guest card's free-text basics into the choice-based model, losing nothing: a
 * written time becomes the check-out choice; anything else (and the old "Good to know" and
 * "Emergency contact" lines) becomes a custom "Good to know" note. Used by the saved-settings
 * migration ([GuestConfig.load]) and for older Portal Toolkit saves ([GuestRequests]). The old
 * door code is a secret and moves separately ([GuestSecretsMigration]).
 */
internal object GuestLegacy {
  data class V1(val checkout: String = "", val houseNote: String = "", val emergency: String = "")

  /** Keys only the first card had; [GuestRequests] still accepts them from older phones. */
  val KEYS = setOf("houseNote", "emergency", "doorCode")

  fun fold(settings: GuestConfig.Settings, legacy: V1): GuestConfig.Settings {
    val parsed = GuestBasics.parseCheckout(legacy.checkout)
    return settings.copy(
        checkout = if (parsed.isNullOrEmpty()) settings.checkout else parsed,
        customNotes = withNotes(settings.customNotes, notes(legacy)),
    )
  }

  /** The custom notes [legacy] adds: unparseable check-out text, the old note and contact lines. */
  fun notes(legacy: V1): List<String> = listOfNotNull(
      legacy.checkout.trim().takeIf { it.isNotEmpty() && GuestBasics.parseCheckout(it) == null }?.let { "Check-out: $it" },
      legacy.houseNote.trim().ifEmpty { null },
      legacy.emergency.trim().ifEmpty { null }?.let { "Emergency contact: $it" },
  )

  fun withNotes(stored: String, extra: List<String>): String =
      if (extra.isEmpty()) stored else GuestBasics.encodeNotes(GuestBasics.decodeNotes(stored) + extra)
}
