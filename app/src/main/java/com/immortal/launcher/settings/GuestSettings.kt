/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.GuestConfig
import com.immortal.launcher.GuestWifi

/**
 * The guest card's non-secret settings. The Wi-Fi password is deliberately absent: it is a
 * credential kept in [com.immortal.launcher.GuestPasswordStore], outside the registry, so it can't
 * reach settings snapshots, the remote schema or fleet backups.
 */
internal object GuestSettings {
  private fun text(key: String, title: String, get: (GuestConfig.Settings) -> String, help: String? = null) =
      StringSpec<GuestConfig.Settings>(
          key = key,
          title = title,
          get = get,
          set = { c, v -> GuestConfig.setText(c, key, v) },
          applyWhen = GuestConfig::isValidText,
          help = help,
      )

  val domain: SettingsDomain<GuestConfig.Settings> =
      SettingsDomain(
          id = "guests",
          title = "Guest card",
          load = GuestConfig::load,
          specs =
              listOf(
                  text("ssid", "Wi-Fi network name", { it.ssid }, "Exactly as it appears when joining."),
                  EnumSpec(
                      "security", "Wi-Fi security",
                      get = { it.security },
                      set = GuestConfig::setSecurity,
                      options = GuestWifi.Security.entries.map { it.wire to it.label },
                      coerce = { v -> GuestWifi.Security.entries.firstOrNull { it.wire == v }?.wire },
                  ),
                  BoolSpec(
                      "hiddenNetwork", "Network is hidden",
                      get = { it.hiddenNetwork },
                      set = GuestConfig::setHiddenNetwork,
                      help = "Turn on if the network doesn't show up in the Wi-Fi list."),
                  BoolSpec(
                      "hidePassword", "Hide password until tapped",
                      get = { it.hidePassword },
                      set = GuestConfig::setHidePassword,
                      help = "Guests tap Show password. The QR code always works."),
                  text("welcome", "Welcome line", { it.welcome }, "For example: Make yourselves at home!"),
                  text("doorCode", "Door code", { it.doorCode }),
                  text("checkout", "Checkout time", { it.checkout }, "For example: 11:00 on Sunday"),
                  text("houseNote", "Good to know", { it.houseNote }, "For example: Bins go out Tuesday"),
                  text("emergency", "Emergency contact", { it.emergency }),
              ),
          sections = mapOf("ssid" to "Wi-Fi", "welcome" to "House basics"),
          defaults = { GuestConfig.Settings() },
      )
}
