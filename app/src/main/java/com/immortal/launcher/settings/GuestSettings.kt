/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.GuestBasics
import com.immortal.launcher.GuestConfig
import com.immortal.launcher.GuestWifi

/**
 * The guest card's non-secret settings. The Wi-Fi password and door code are deliberately absent:
 * they are credentials kept in [com.immortal.launcher.GuestSecret] stores, outside the registry,
 * so they can't reach settings snapshots, the remote schema or fleet backups. On the Portal these
 * are edited by the guest setup flow ([com.immortal.launcher.GuestSetupContent]).
 */
internal object GuestSettings {
  /** Longest stored preset list (every id once, with commas). */
  private val MAX_PRESETS = GuestBasics.PRESETS.sumOf { it.id.length + 1 }

  private fun text(
      key: String, title: String, get: (GuestConfig.Settings) -> String, help: String? = null,
      valid: (String) -> Boolean = GuestConfig::isValidText,
      set: (Context, String) -> Unit = { c, v -> GuestConfig.setText(c, key, v) },
  ) = StringSpec(key = key, title = title, get = get, set = set, applyWhen = valid, help = help)

  private val wifi: List<SettingSpec<GuestConfig.Settings>> = listOf(
      text("ssid", "Wi-Fi network name", { it.ssid }, "Exactly as it appears when joining."),
      EnumSpec(
          "security", "Wi-Fi security",
          get = { it.security },
          set = GuestConfig::setSecurity,
          options = GuestWifi.Security.entries.map { it.wire to it.label },
          coerce = { v -> GuestWifi.Security.entries.firstOrNull { it.wire == v }?.wire },
      ),
      BoolSpec("hiddenNetwork", "Network is hidden", get = { it.hiddenNetwork }, set = GuestConfig::setHiddenNetwork,
          help = "Turn on if the network doesn't show up in the Wi-Fi list."),
      BoolSpec("hidePassword", "Hide password on the card until tapped", get = { it.hidePassword },
          set = GuestConfig::setHidePassword, help = "Guests tap Show password. The QR code always works."),
  )

  private val basics: List<SettingSpec<GuestConfig.Settings>> = listOf(
      text("welcome", "Welcome message", { it.welcome }, "For example: ${GuestBasics.DEFAULT_WELCOME}"),
      text("checkout", "Check-out", { it.checkout }, "Blank for no check-out, otherwise 24-hour HH:mm.",
          valid = GuestBasics::isValidCheckout),
      text("goodToKnow", "Good to know", { it.goodToKnow }, "Comma-separated choices: " +
          GuestBasics.PRESETS.joinToString(", ") { it.id },
          valid = { v -> v.length <= MAX_PRESETS && GuestConfig.isValidText(v) }, set = GuestConfig::setPresets),
      text("customNotes", "Your own notes", { it.customNotes }, "Up to ${GuestBasics.MAX_NOTES}, separated by |.",
          valid = GuestBasics::isValidNotes, set = GuestConfig::setNotes),
      EnumSpec(
          "contact", "Contact",
          get = { it.contact },
          set = { c, v -> GuestConfig.setText(c, "contact", v) },
          options = GuestBasics.CONTACTS.map { it.id to it.label },
          coerce = { v -> v.takeIf(GuestBasics::isValidContact) },
      ),
      text("contactName", "Contact name", { it.contactName }, "Shown as \"Questions? Ask <name>.\" Name only.",
          valid = GuestBasics::isValidContactName),
  )

  val domain: SettingsDomain<GuestConfig.Settings> =
      SettingsDomain(
          id = "guests",
          title = "Guest card",
          load = GuestConfig::load,
          specs = wifi + basics,
          sections = mapOf("ssid" to "Wi-Fi", "welcome" to "House basics"),
          defaults = { GuestConfig.Settings() },
      )
}
