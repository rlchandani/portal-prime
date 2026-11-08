/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** Card validation and version-1 folding for the phone route; the registry's guards run again on apply. */
internal object GuestRequests {
  /** What a card value may be on the wire. `checkout` accepts any line: older phones send free text. */
  private val TEXT_KEYS: Map<String, (String) -> Boolean> = mapOf(
      "ssid" to GuestConfig::isValidText,
      "welcome" to GuestConfig::isValidText,
      "checkout" to GuestConfig::isValidText,
      "goodToKnow" to GuestConfig::isValidText,
      "customNotes" to GuestBasics::isValidNotes,
      "contactName" to GuestBasics::isValidContactName,
      "contact" to GuestBasics::isValidContact,
      "security" to { v -> GuestWifi.Security.entries.any { it.wire == v } },
  ) + GuestLegacy.KEYS.associateWith { GuestConfig::isValidText }
  private val BOOL_KEYS = setOf("hiddenNetwork", "hidePassword")

  /** Registry values to apply, plus a version-1 door code to move into the secret store. */
  class Normalized(val values: JSONObject, val legacyDoorCode: String?) {
    override fun toString() = "Normalized(keys=${values.keys().asSequence().toList()}, legacyDoorCode=${legacyDoorCode != null})"
  }

  /** Null when every key is known and well-typed; otherwise the error code. */
  fun cardError(card: JSONObject): String? {
    for (key in card.keys()) {
      val v = card.opt(key)
      val ok = when (key) {
        in TEXT_KEYS -> v is String && TEXT_KEYS.getValue(key)(v)
        in BOOL_KEYS -> v is Boolean
        else -> return "unknown_field"
      }
      if (!ok) return "invalid_$key"
    }
    return null
  }

  /**
   * Folds an already-validated card into registry values. Version-1 lines are only additive: a
   * blank `houseNote` / `emergency` / `doorCode` (what an older phone sends back for keys it can no
   * longer read) changes nothing, so an old phone can't wipe the door code or notes.
   */
  fun normalize(card: JSONObject, current: JSONObject): Normalized {
    val values = JSONObject()
    card.keys().forEach { key -> if (key !in GuestLegacy.KEYS && key != "checkout") values.put(key, card.get(key)) }
    val checkout = card.optString("checkout", "")
    val legacy = GuestLegacy.V1(checkout, card.optString("houseNote", ""), card.optString("emergency", ""))
    if (card.has("checkout")) GuestBasics.parseCheckout(checkout)?.let { values.put("checkout", it) }
    val notes = GuestLegacy.notes(legacy)
    if (notes.isNotEmpty()) {
      val base = if (values.has("customNotes")) values.getString("customNotes") else current.optString("customNotes", "")
      values.put("customNotes", GuestLegacy.withNotes(base, notes))
    }
    return Normalized(values, card.optString("doorCode", "").trim().ifEmpty { null })
  }
}
