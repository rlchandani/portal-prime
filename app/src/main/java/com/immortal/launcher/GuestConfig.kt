/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * The guest card's non-secret text: network name and the house basics the household writes.
 * The Wi-Fi password is NOT here (see [GuestPasswordStore]). Edited only through
 * [com.immortal.launcher.settings.GuestSettings.domain], never directly from UI or routes.
 */
internal object GuestConfig {
  private const val PREFS = "immortal_guest"
  /** Longest single-line value; keeps the card readable at large sizes. */
  const val MAX_TEXT = 120

  data class Settings(
      val ssid: String = "",
      val security: String = GuestWifi.Security.WPA.wire,
      val hiddenNetwork: Boolean = false,
      /** The password stays behind a "Show password" tap until a guest asks. */
      val hidePassword: Boolean = true,
      val welcome: String = "",
      val doorCode: String = "",
      val checkout: String = "",
      val houseNote: String = "",
      val emergency: String = "",
  ) {
    val wifiSecurity: GuestWifi.Security get() = GuestWifi.Security.fromWire(security)

    /** The house basics in display order, blanks dropped. */
    fun basics(): List<Pair<String, String>> =
        listOf("Door code" to doorCode, "Checkout" to checkout, "Good to know" to houseNote,
            "Emergency contact" to emergency).filter { it.second.isNotBlank() }
  }

  /** One-line, bounded, no control characters. Blank is allowed (it clears the line). */
  fun isValidText(value: String): Boolean = value.length <= MAX_TEXT && value.none { it.isISOControl() }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings = prefs(c).let { p ->
    Settings(
        ssid = p.getString("ssid", "").orEmpty(),
        security = GuestWifi.Security.fromWire(p.getString("security", null)).wire,
        hiddenNetwork = p.getBoolean("hiddenNetwork", false),
        hidePassword = p.getBoolean("hidePassword", true),
        welcome = p.getString("welcome", "").orEmpty(),
        doorCode = p.getString("doorCode", "").orEmpty(),
        checkout = p.getString("checkout", "").orEmpty(),
        houseNote = p.getString("houseNote", "").orEmpty(),
        emergency = p.getString("emergency", "").orEmpty(),
    )
  }

  fun setText(c: Context, key: String, value: String) = prefs(c).edit().putString(key, value.trim()).apply()
  fun setSecurity(c: Context, value: String) =
      prefs(c).edit().putString("security", GuestWifi.Security.fromWire(value).wire).apply()
  fun setHiddenNetwork(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hiddenNetwork", on).apply()
  fun setHidePassword(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hidePassword", on).apply()
}
