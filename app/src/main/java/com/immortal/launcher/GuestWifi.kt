/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The guest Wi-Fi network and its standard join payload (`WIFI:T:WPA;S:<ssid>;P:<password>;;`),
 * which phone cameras read as "Join network". Pure JVM; [lanSetupQr] renders the result.
 */
internal object GuestWifi {
  enum class Security(val wire: String, val qr: String, val label: String) {
    WPA("wpa", "WPA", "WPA / WPA2 / WPA3"),
    WEP("wep", "WEP", "WEP (older)"),
    NONE("none", "nopass", "No password");

    companion object {
      fun fromWire(value: String?): Security = entries.firstOrNull { it.wire == value } ?: WPA
    }
  }

  /** A credential-bearing value: [toString] never includes the password. */
  class Network(
      val ssid: String,
      val password: String,
      val security: Security = Security.WPA,
      val hidden: Boolean = false,
  ) {
    override fun toString(): String = "Network(security=${security.wire}, hidden=$hidden)"
  }

  private val RESERVED = setOf('\\', ';', ',', ':', '"')

  /** Backslash-escapes the characters the WIFI: grammar reserves. */
  fun escape(value: String): String = buildString(value.length + 4) {
    value.forEach { ch -> if (ch in RESERVED) append('\\'); append(ch) }
  }

  /** The join payload, or null when the network can't be joined from it (no name, or no password). */
  fun payload(network: Network): String? {
    if (network.ssid.isBlank()) return null
    val secured = network.security != Security.NONE
    if (secured && network.password.isEmpty()) return null
    return buildString {
      append("WIFI:T:").append(network.security.qr).append(";S:").append(escape(network.ssid)).append(';')
      if (secured) append("P:").append(escape(network.password)).append(';')
      if (network.hidden) append("H:true;")
      append(';')
    }
  }
}
