/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * The guest card's non-secret values: the network and the house basics, stored as choices
 * ([GuestBasics]). The Wi-Fi password and door code are NOT here (see [GuestPasswordStore],
 * [GuestDoorCodeStore]). Edited only through [com.immortal.launcher.settings.GuestSettings.domain],
 * never directly from UI or routes.
 */
internal object GuestConfig {
  private const val PREFS = "immortal_guest"
  private const val KEY_SCHEMA = "schema"
  /** 2: choice-based basics; the free-text lines of schema 1 are folded in by [GuestLegacy]. */
  private const val SCHEMA = 2
  /** Longest single-line value; keeps the card readable at large sizes. */
  const val MAX_TEXT = 120

  data class Settings(
      val ssid: String = "",
      val security: String = GuestWifi.Security.WPA.wire,
      val hiddenNetwork: Boolean = false,
      /** The password stays behind a "Show password" tap until a guest asks. */
      val hidePassword: Boolean = true,
      val welcome: String = GuestBasics.DEFAULT_WELCOME,
      /** "" (no check-out) or 24-hour "HH:mm". */
      val checkout: String = "",
      /** Comma-joined [GuestBasics.PRESETS] ids. */
      val goodToKnow: String = "",
      /** "Add your own…" notes, `|`-joined ([GuestBasics.encodeNotes]). */
      val customNotes: String = "",
      val contact: String = GuestBasics.CONTACT_NONE,
      /** A first name picked on the phone; shown as "Questions? Ask <name>." */
      val contactName: String = "",
  ) {
    val wifiSecurity: GuestWifi.Security get() = GuestWifi.Security.fromWire(security)
    val presets: List<GuestBasics.Preset> get() = GuestBasics.decodePresets(goodToKnow).mapNotNull(GuestBasics::preset)
    val notes: List<String> get() = GuestBasics.decodeNotes(customNotes)
    val contactLine: String? get() = GuestBasics.contactLine(contact, contactName)
  }

  /** One-line, bounded, no control characters. Blank is allowed (it clears the line). */
  fun isValidText(value: String): Boolean = value.length <= MAX_TEXT && value.none { it.isISOControl() }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings {
    val p = prefs(c)
    if (p.getInt(KEY_SCHEMA, 1) < SCHEMA) migrate(p)
    return read(p)
  }

  private fun read(p: SharedPreferences) = Settings(
      ssid = p.getString("ssid", "").orEmpty(),
      security = GuestWifi.Security.fromWire(p.getString("security", null)).wire,
      hiddenNetwork = p.getBoolean("hiddenNetwork", false),
      hidePassword = p.getBoolean("hidePassword", true),
      welcome = p.getString("welcome", GuestBasics.DEFAULT_WELCOME).orEmpty(),
      checkout = p.getString("checkout", "").orEmpty().takeIf(GuestBasics::isValidCheckout).orEmpty(),
      goodToKnow = GuestBasics.encodePresets(p.getString("goodToKnow", "").orEmpty().split(',')),
      customNotes = p.getString("customNotes", "").orEmpty().takeIf(GuestBasics::isValidNotes).orEmpty(),
      contact = p.getString("contact", null).takeIf { it != null && GuestBasics.isValidContact(it) } ?: GuestBasics.CONTACT_NONE,
      contactName = p.getString("contactName", "").orEmpty(),
  )

  /** Schema 1 → 2. The old door code stays put until [GuestSecretsMigration] moves it. */
  @Synchronized private fun migrate(p: SharedPreferences) {
    if (p.getInt(KEY_SCHEMA, 1) >= SCHEMA) return
    val legacy = GuestLegacy.V1(p.getString("checkout", "").orEmpty(), p.getString("houseNote", "").orEmpty(),
        p.getString("emergency", "").orEmpty())
    val folded = GuestLegacy.fold(read(p).copy(checkout = ""), legacy)
    p.edit().putString("checkout", folded.checkout).putString("customNotes", folded.customNotes)
        .remove("houseNote").remove("emergency").putInt(KEY_SCHEMA, SCHEMA).apply()
  }

  fun setText(c: Context, key: String, value: String) = prefs(c).edit().putString(key, value.trim()).apply()
  fun setSecurity(c: Context, value: String) =
      prefs(c).edit().putString("security", GuestWifi.Security.fromWire(value).wire).apply()
  fun setHiddenNetwork(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hiddenNetwork", on).apply()
  fun setHidePassword(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hidePassword", on).apply()
  fun setPresets(c: Context, value: String) = setText(c, "goodToKnow", GuestBasics.encodePresets(value.split(',')))
  fun setNotes(c: Context, value: String) = setText(c, "customNotes", GuestBasics.encodeNotes(GuestBasics.decodeNotes(value)))

  /** The schema-1 door code, if it hasn't moved to [GuestDoorCodeStore] yet. */
  fun legacyDoorCode(c: Context): String? = prefs(c).getString("doorCode", null)
  fun dropLegacyDoorCode(c: Context): Boolean = prefs(c).edit().remove("doorCode").commit()
}
